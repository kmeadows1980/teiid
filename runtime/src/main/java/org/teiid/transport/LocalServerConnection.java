/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.transport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.security.auth.Subject;

import org.teiid.client.DQP;
import org.teiid.client.security.ILogon;
import org.teiid.client.security.LogonException;
import org.teiid.client.security.LogonResult;
import org.teiid.client.util.ExceptionUtil;
import org.teiid.client.util.ResultsFuture;
import org.teiid.core.TeiidComponentException;
import org.teiid.core.TeiidRuntimeException;
import org.teiid.core.util.PropertiesUtils;
import org.teiid.deployers.VDBLifeCycleListener;
import org.teiid.deployers.VDBRepository;
import org.teiid.dqp.internal.process.DQPWorkContext;
import org.teiid.jdbc.LocalProfile;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.net.CommunicationException;
import org.teiid.net.ConnectionException;
import org.teiid.net.ServerConnection;
import org.teiid.net.TeiidURL;
import org.teiid.net.socket.Handshake;
import org.teiid.runtime.RuntimePlugin;
import org.teiid.vdb.runtime.VDBKey;


public class LocalServerConnection implements ServerConnection {
	private static final String TEIID_RUNTIME_CONTEXT = "teiid/queryengine"; //$NON-NLS-1$
	
	private LogonResult result;
	private boolean shutdown;
	private ClientServiceRegistry csr;
    private DQPWorkContext workContext = new DQPWorkContext();
    private Properties connectionProperties;
    private boolean passthrough;
    private boolean derived;
    private static String serverVersion = new Handshake().getVersion();
    
    private Method cancelMethod;
    
    public static String jndiNameForRuntime(String embeddedTransportName) {
    	return TEIID_RUNTIME_CONTEXT+"/"+embeddedTransportName; //$NON-NLS-1$
    }
    
	public LocalServerConnection(Properties connectionProperties, boolean useCallingThread) throws CommunicationException, ConnectionException{
		this.connectionProperties = connectionProperties;
		this.csr = getClientServiceRegistry(connectionProperties.getProperty(LocalProfile.TRANSPORT_NAME, "local")); //$NON-NLS-1$
		
		DQPWorkContext context = (DQPWorkContext)connectionProperties.get(LocalProfile.DQP_WORK_CONTEXT);
		if (context == null) {
			String vdbVersion = connectionProperties.getProperty(TeiidURL.JDBC.VDB_VERSION);
			String vdbName = connectionProperties.getProperty(TeiidURL.JDBC.VDB_NAME);
			VDBKey key = new VDBKey(vdbName, vdbVersion);
			
			if (!key.isAtMost()) {
				int waitForLoad = PropertiesUtils.getIntProperty(connectionProperties, TeiidURL.CONNECTION.LOGIN_TIMEOUT, -1);
				if (waitForLoad == -1) {
					waitForLoad = PropertiesUtils.getIntProperty(connectionProperties, LocalProfile.WAIT_FOR_LOAD, -1);
				} else {
					waitForLoad *= 1000; //seconds to milliseconds
				}
				if (waitForLoad != 0) {
					this.csr.waitForFinished(key, waitForLoad);
				}
			}
			
			workContext.setSecurityHelper(csr.getSecurityHelper());
			workContext.setUseCallingThread(useCallingThread);
			workContext.setSecurityContext(csr.getSecurityHelper().getSecurityContext());
			authenticate();
			passthrough = Boolean.valueOf(connectionProperties.getProperty(TeiidURL.CONNECTION.PASSTHROUGH_AUTHENTICATION, "false")); //$NON-NLS-1$
		} else {
			derived = true;
			workContext = context;
			this.result = new LogonResult(context.getSessionToken(), context.getVdbName(), null);
			passthrough = true;
		}
		
		try {
			cancelMethod = DQP.class.getMethod("cancelRequest", new Class[] {long.class}); //$NON-NLS-1$
		} catch (SecurityException e) {
			throw new TeiidRuntimeException(e);
		} catch (NoSuchMethodException e) {
			throw new TeiidRuntimeException(e);
		}
	}

	protected ClientServiceRegistry getClientServiceRegistry(String transport) {
		try {
			InitialContext ic = new InitialContext();
			return (ClientServiceRegistry)ic.lookup(jndiNameForRuntime(transport));
		} catch (NamingException e) {
			 throw new TeiidRuntimeException(RuntimePlugin.Event.TEIID40067, e);
		}
	}
	
	public synchronized void authenticate() throws ConnectionException, CommunicationException {
		Object previousSecurityContext = workContext.getSecurityHelper().associateSecurityContext(workContext.getSession().getSecurityContext());
		try {
			logoff(); 
		} finally {
			workContext.getSecurityHelper().associateSecurityContext(previousSecurityContext);
		}
		workContext.setSecurityContext(previousSecurityContext);
        try {
        	this.result = this.getService(ILogon.class).logon(this.connectionProperties);
        } catch (LogonException e) {
            // Propagate the original message as it contains the message we want
            // to give to the user
             throw new ConnectionException(e);
        } catch (TeiidComponentException e) {
        	if (e.getCause() instanceof CommunicationException) {
        		throw (CommunicationException)e.getCause();
        	}
             throw new CommunicationException(RuntimePlugin.Event.TEIID40069, e);
        } 	
	}	
	
	public <T> T getService(final Class<T> iface) {
		return iface.cast(Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class[] {iface}, new InvocationHandler() {

			boolean logon = iface.equals(ILogon.class);
			
			public Object invoke(Object arg0, final Method arg1, final Object[] arg2) throws Throwable {
				if (shutdown) {
					throw ExceptionUtil.convertException(arg1, new TeiidComponentException(RuntimePlugin.Util.gs(RuntimePlugin.Event.TEIID40074)));
				}
				try {
					// check to make sure the current security context same as logged one
					if (passthrough && !logon 
							&& !arg1.equals(cancelMethod) // -- it's ok to use another thread to cancel
							&& !workContext.getSession().isClosed()
							//if configured without a security domain the context will be null
							&& workContext.getSession().getSecurityDomain() != null
							&& !sameSubject(workContext)) {
						//TODO: this is an implicit changeUser - we may want to make this explicit, but that would require pools to explicitly use changeUser
						LogManager.logInfo(LogConstants.CTX_SECURITY, RuntimePlugin.Util.gs(RuntimePlugin.Event.TEIID40115, workContext.getSession().getSessionId()));
						authenticate();
					}
					
					final T service = csr.getClientService(iface);
					return workContext.runInContext(new Callable<Object>() {
						public Object call() throws Exception {
							return arg1.invoke(service, arg2);						
						}
					});
				} catch (InvocationTargetException e) {
					throw e.getTargetException();
				} catch (Throwable e) {
					throw ExceptionUtil.convertException(arg1, e);
				}
			}
		}));
	}
	
	public static boolean sameSubject(DQPWorkContext workContext) {
		Object currentContext = workContext.getSecurityHelper().getSecurityContext();
		
		if (currentContext != null) {
			Subject currentUser = workContext.getSecurityHelper().getSubjectInContext(workContext.getSecurityDomain());
			if (workContext.getSubject() != null && currentUser != null && workContext.getSubject().equals(currentUser)) {
				return true;
			}
			if (currentUser == null && workContext.getSubject() == null) {
				return true; //unauthenticated
			}
		}
		
		return false;
	}
	
	@Override
	public boolean isOpen(long msToTest) {
		if (shutdown) {
			return false;
		}
		try {
			ResultsFuture<?> result = this.getService(ILogon.class).ping();
			result.get(msToTest, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			return false;
		}
		return true;
	}

	public void close() {
		shutdown(true);
	}
	
	private void shutdown(boolean logoff) {
		if (shutdown) {
			return;
		}
		
		if (logoff) {
			logoff();
		}
		this.shutdown = true;
	}

	private void logoff() {
		if (derived) {
			return; //not the right place to kill the session
		}
		try {
			//make a best effort to send the logoff
			Future<?> writeFuture = this.getService(ILogon.class).logoff();
			if (writeFuture != null) {
				writeFuture.get(5000, TimeUnit.MILLISECONDS);
			}
		} catch (Exception e) {
			//ignore
		}
	}

	public LogonResult getLogonResult() {
		return result;
	}

	@Override
	public boolean isSameInstance(ServerConnection conn) throws CommunicationException {
		return (conn instanceof LocalServerConnection);
	}
	
	@Override
	public void cleanUp() {
		
	}
	
	@Override
	public boolean supportsContinuous() {
		return true;
	}
	
	public DQPWorkContext getWorkContext() {
		return workContext;
	}
	
	@Override
	public boolean isLocal() {
		return true;
	}
	
	public void addListener(VDBLifeCycleListener listener) {
		VDBRepository repo = csr.getVDBRepository();
		if (repo != null) {
			repo.addListener(listener);
		}
	}
	
	public void removeListener(VDBLifeCycleListener listener) {
		VDBRepository repo = csr.getVDBRepository();
		if (repo != null) {
			repo.removeListener(listener);
		}
	}
	
	@Override
	public String getServerVersion() {
		return serverVersion;
	}
}
