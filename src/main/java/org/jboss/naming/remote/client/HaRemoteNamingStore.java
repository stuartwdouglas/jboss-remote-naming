package org.jboss.naming.remote.client;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NameClassPair;
import javax.naming.NamingException;
import javax.security.auth.callback.CallbackHandler;

import org.jboss.logging.Logger;
import org.jboss.naming.remote.protocol.IoFutureHelper;
import org.jboss.naming.remote.protocol.NamingIOException;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.Connection;
import org.jboss.remoting3.Endpoint;
import org.xnio.IoFuture;
import org.xnio.OptionMap;

/**
 * Remote naming store that can handle connecting to multiple hosts.
 *
 * @author Stuart Douglas
 */
public class HaRemoteNamingStore implements RemoteNamingStore {

    private static final Logger logger = Logger.getLogger(HaRemoteNamingStore.class);


    private final Endpoint clientEndpoint;
    private final List<URI> connectionURIs;
    private final OptionMap connectOptions;
    private final CallbackHandler callbackHandler;
    private final long connectionTimeout;
    private final OptionMap channelCreationOptions;
    private final long channelCreationTimeoutInMillis;

    private volatile boolean closed = false;
    /**
     * The index of the next server to attempt to connect to
     */
    private volatile int nextServer;
    private volatile RemoteNamingStore currentNamingStore;
    /**
     * The list of client contexts that are using this naming stores connection.
     *
     */
    private final Set<CurrentEjbClientConnection> currentEjbClientContexts = new HashSet<CurrentEjbClientConnection>();
    //should only be accessed under lock
    private Connection connection;

    public HaRemoteNamingStore(final long channelCreationTimeoutInMillis, final OptionMap channelCreationOptions, final long connectionTimeout, final CallbackHandler callbackHandler, final OptionMap connectOptions, final List<URI> connectionURIs, final Endpoint clientEndpoint, final boolean connectInOrder) {
        if (connectionURIs.isEmpty()) {
            throw new IllegalArgumentException("Cannot create a HA remote naming store without any servers to connect to");
        }
        this.channelCreationTimeoutInMillis = channelCreationTimeoutInMillis;
        this.channelCreationOptions = channelCreationOptions;
        this.connectionTimeout = connectionTimeout;
        this.callbackHandler = callbackHandler;
        this.connectOptions = connectOptions;
        this.connectionURIs = connectionURIs;
        this.clientEndpoint = clientEndpoint;
        if (connectInOrder) {
            nextServer = 0;
        } else {
            nextServer = new Random().nextInt(connectionURIs.size());
        }
    }


    /**
     * Perfoms a remoting naming operation, retrying when a server cannot be found.
     *
     * @param operation The operation
     * @param <T>       The return type of the operation
     * @return The result of the operation
     */
    private <T> T namingOperation(Operation<T> operation) throws NamingException {
        if (closed) {
            throw new NamingException("NamingStore has been closed");
        }
        RemoteNamingStore namingStore = namingStore();
        try {
            return operation.operation(namingStore);
        } catch (NamingIOException e) {
            synchronized (this) {
                namingStore = failOverSequence(namingStore);
            }
            return operation.operation(namingStore);
        }
    }

    /**
     * @return The current naming store
     */
    private RemoteNamingStore namingStore() throws NamingException {
        final RemoteNamingStore namingStore = currentNamingStore;
        if (namingStore == null) {
            synchronized (this) {
                if (currentNamingStore == null) {
                    return failOverSequence(null);
                }
                return currentNamingStore;
            }
        }
        return namingStore;
    }

    /**
     * Fail over to a new RemoteNamingStore
     *
     * @param attempted The remote naming store that caused the failover attempt. If it does not match the current naming
     *                  store the fail over will be aborted
     * @return The new remote naming store
     */
    private RemoteNamingStore failOverSequence(RemoteNamingStore attempted) throws NamingException {
        assert Thread.holdsLock(this);
        final RemoteNamingStore currentNamingStore = this.currentNamingStore;
        if (attempted != null && attempted != currentNamingStore) {
            //a different thread has already caused a failover
            return currentNamingStore;
        }

        if (currentNamingStore != null) {
            try {
                //even though this probably won't work we try and close it anyway
                currentNamingStore.close();
                connection.close();
            } catch (Exception e) {
                //this is not unexpected, as if the naming store was in a reasonable state
                //we should not be failing over.
                logger.debug("Failed to close existing naming store on failover", e);
            }
        }

        final int startingNext = nextServer();
        int currentServer = startingNext;
        RemoteNamingStore store = null;

        //we loop through and attempt to connect to ever server, one at a time
        do {
            final URI connectionUri = connectionURIs.get(currentServer);
            Connection connection = null;
            try {
                final IoFuture<Connection> futureConnection = clientEndpoint.connect(connectionUri, connectOptions, callbackHandler);
                connection = IoFutureHelper.get(futureConnection, connectionTimeout, TimeUnit.MILLISECONDS);
                // open a channel
                final IoFuture<Channel> futureChannel = connection.openChannel("naming", channelCreationOptions);
                final Channel channel = IoFutureHelper.get(futureChannel, channelCreationTimeoutInMillis, TimeUnit.MILLISECONDS);
                store = RemoteContextFactory.createVersionedStore(channel);
                this.connection = connection;
                break;
            } catch (Exception e) {
                logger.debug("Failed to connect to server " + connectionUri, e);
                currentServer = nextServer();
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (IOException e1) {
                        logger.debug("Failed to close connection " + connectionUri, e);
                    }
                }
            }
        } while (currentServer != startingNext);
        if (store == null) {
            throw new NamingException("Failed to connect to any server. Servers tried: " + connectionURIs);
        }
        this.currentNamingStore = store;
        for(final CurrentEjbClientConnection currentEjbClientContext : currentEjbClientContexts) {
            currentEjbClientContext.setConnection(connection);
        }
        return store;
    }

    private int nextServer() {
        assert Thread.holdsLock(this);
        final int next = nextServer;
        final int newValue = next + 1;
        if (newValue == connectionURIs.size()) {
            nextServer = 0;
        } else {
            nextServer = newValue;
        }
        return next;
    }

    @Override
    public Object lookup(final Name name) throws NamingException {
        return namingOperation(
                new Operation<Object>() {
                    @Override
                    public Object operation(final RemoteNamingStore store) throws NamingException {
                        return store.lookup(name);
                    }
                }
        );
    }

    @Override
    public void bind(final Name name, final Object object) throws NamingException {
        namingOperation(
                new Operation<Void>() {
                    @Override
                    public Void operation(final RemoteNamingStore store) throws NamingException {
                        store.bind(name, object);
                        return null;

                    }
                }
        );
    }

    @Override
    public void rebind(final Name name, final Object object) throws NamingException {
        namingOperation(
                new Operation<Void>() {
                    @Override
                    public Void operation(final RemoteNamingStore store) throws NamingException {
                        store.rebind(name, object);
                        return null;

                    }
                }
        );
    }

    @Override
    public void rename(final Name name, final Name object) throws NamingException {
        namingOperation(
                new Operation<Void>() {
                    @Override
                    public Void operation(final RemoteNamingStore store) throws NamingException {
                        store.rename(name, object);
                        return null;

                    }
                }
        );
    }

    @Override
    public List<NameClassPair> list(final Name name) throws NamingException {
        return namingOperation(
                new Operation<List<NameClassPair>>() {
                    @Override
                    public List<NameClassPair> operation(final RemoteNamingStore store) throws NamingException {
                        return store.list(name);
                    }
                }
        );
    }

    @Override
    public List<Binding> listBindings(final Name name) throws NamingException {
        return namingOperation(
                new Operation<List<Binding>>() {
                    @Override
                    public List<Binding> operation(final RemoteNamingStore store) throws NamingException {
                        return store.listBindings(name);
                    }
                }
        );
    }

    @Override
    public void unbind(final Name name) throws NamingException {
        namingOperation(
                new Operation<Void>() {
                    @Override
                    public Void operation(final RemoteNamingStore store) throws NamingException {
                        store.unbind(name);
                        return null;
                    }
                }
        );
    }

    @Override
    public Context createSubcontext(final Name name) throws NamingException {
        return namingOperation(
                new Operation<Context>() {
                    @Override
                    public Context operation(final RemoteNamingStore store) throws NamingException {
                        return store.createSubcontext(name);
                    }
                }
        );
    }

    @Override
    public void destroySubcontext(final Name name) throws NamingException {
        namingOperation(
                new Operation<Void>() {
                    @Override
                    public Void operation(final RemoteNamingStore store) throws NamingException {
                        store.destroySubcontext(name);
                        return null;
                    }
                }
        );
    }

    @Override
    public Object lookupLink(final Name name) throws NamingException {
        return namingOperation(
                new Operation<Object>() {
                    @Override
                    public Object operation(final RemoteNamingStore store) throws NamingException {
                        return store.lookupLink(name);
                    }
                }
        );
    }

    @Override
    public synchronized void close() throws NamingException {
        closed = true;
        try {
            if(connection != null) {
                connection.close();
            }
        } catch (IOException e) {
            NamingException exception = new NamingException("Failed to close connection");
            exception.initCause(e);
            throw exception;
        }
    }

    @Override
    public synchronized void addEjbContext(final CurrentEjbClientConnection connection) {
        if(this.connection != null) {
            connection.setConnection(this.connection);
        }
        this.currentEjbClientContexts.add(connection);
    }

    @Override
    public synchronized void removeEjbContext(final CurrentEjbClientConnection connection) {
        this.currentEjbClientContexts.remove(connection);
    }

    /**
     * Simple interface used to encapsulate a naming operation.
     *
     * @param <T> The return type of the operation
     */
    private static interface Operation<T> {
        T operation(final RemoteNamingStore store) throws NamingException;
    }

}
