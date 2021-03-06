/*
* Copyright 2009, Mahmood Ali.
* All rights reserved.
*
* Redistribution and use in source and binary forms, with or without
* modification, are permitted provided that the following conditions are
* met:
*
*   * Redistributions of source code must retain the above copyright
*     notice, this list of conditions and the following disclaimer.
*   * Redistributions in binary form must reproduce the above
*     copyright notice, this list of conditions and the following disclaimer
*     in the documentation and/or other materials provided with the
*     distribution.
*   * Neither the name of Mahmood Ali. nor the names of its
*     contributors may be used to endorse or promote products derived from
*     this software without specific prior written permission.
*
* THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
* "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
* LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
* A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
* OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
* SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
* LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
* DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
* THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
* (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
* OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package com.notnoop.apns.internal;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

import com.notnoop.apns.ApnsDelegate;
import com.notnoop.apns.StartSendingApnsDelegate;
import com.notnoop.apns.ApnsNotification;
import com.notnoop.apns.DeliveryError;
import com.notnoop.apns.EnhancedApnsNotification;
import com.notnoop.apns.ReconnectPolicy;
import com.notnoop.exceptions.ApnsDeliveryErrorException;
import com.notnoop.exceptions.NetworkIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApnsConnectionImpl implements ApnsConnection {

    private static final Logger logger = LoggerFactory.getLogger(ApnsConnectionImpl.class);

    private final SocketFactory factory;
    private final String host;
    private final int port;
    private final int readTimeout;
    private final int connectTimeout;
    private final Proxy proxy;
    private final String proxyUsername;
    private final String proxyPassword;
    private final ReconnectPolicy reconnectPolicy;
    private final ApnsDelegate delegate;
    private int cacheLength;
    private final boolean errorDetection;
    private final ThreadFactory threadFactory;
    private final boolean autoAdjustCacheLength;
    private final ConcurrentLinkedQueue<ApnsNotification> cachedNotifications, notificationsBuffer;
    private Socket socket;
    private final AtomicInteger threadId = new AtomicInteger(0);
    private ExecutorService executors = Executors.newSingleThreadExecutor();

    private int sendMessageTimeout = 30;

    public ApnsConnectionImpl(SocketFactory factory, String host, int port) {
        this(factory, host, port, new ReconnectPolicies.Never(), ApnsDelegate.EMPTY);
    }

    private ApnsConnectionImpl(SocketFactory factory, String host, int port, ReconnectPolicy reconnectPolicy, ApnsDelegate delegate) {
        this(factory, host, port, null, null, null, reconnectPolicy, delegate);
    }

    private ApnsConnectionImpl(SocketFactory factory, String host, int port, Proxy proxy, String proxyUsername, String proxyPassword,
                               ReconnectPolicy reconnectPolicy, ApnsDelegate delegate) {
        this(factory, host, port, proxy, proxyUsername, proxyPassword, reconnectPolicy, delegate, false, null,
                ApnsConnection.DEFAULT_CACHE_LENGTH, true, 0, 0);
    }

    public ApnsConnectionImpl(SocketFactory factory, String host, int port, Proxy proxy, String proxyUsername, String proxyPassword,
                              ReconnectPolicy reconnectPolicy, ApnsDelegate delegate, boolean errorDetection, ThreadFactory tf, int cacheLength,
                              boolean autoAdjustCacheLength, int readTimeout, int connectTimeout) {
        this.factory = factory;
        this.host = host;
        this.port = port;
        this.reconnectPolicy = reconnectPolicy;
        this.delegate = delegate == null ? ApnsDelegate.EMPTY : delegate;
        this.proxy = proxy;
        this.errorDetection = errorDetection;
        this.threadFactory = tf == null ? defaultThreadFactory() : tf;
        this.cacheLength = cacheLength;
        this.autoAdjustCacheLength = autoAdjustCacheLength;
        this.readTimeout = readTimeout;
        this.connectTimeout = connectTimeout;
        this.proxyUsername = proxyUsername;
        this.proxyPassword = proxyPassword;
        cachedNotifications = new ConcurrentLinkedQueue<ApnsNotification>();
        notificationsBuffer = new ConcurrentLinkedQueue<ApnsNotification>();
    }

    private ThreadFactory defaultThreadFactory() {
        return new ThreadFactory() {
            ThreadFactory wrapped = Executors.defaultThreadFactory();

            @Override
            public Thread newThread(Runnable r) {
                Thread result = wrapped.newThread(r);
                result.setName("MonitoringThread-" + threadId.incrementAndGet());
                result.setDaemon(true);
                return result;
            }
        };
    }

    public synchronized void close() {
        executors.shutdown();
        try {
            executors.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.warn("pool termination interrupted", e);
        }
        Utilities.close(socket);
    }

    private void monitorSocket(final Socket socket) {
        logger.debug("Launching Monitoring Thread for socket {}", socket);

//        避免在finally块中重复关闭已经正常开启的Socket和executors
        final Socket monitoredSocket = socket;

        Thread t = threadFactory.newThread(new Runnable() {
            final static int EXPECTED_SIZE = 6;

            @SuppressWarnings("InfiniteLoopStatement")
            @Override
            public void run() {
                logger.debug("Started monitoring thread");

                try {
                    InputStream in;
                    try {
                        logger.debug("CC MMMMMMMMMMM-1 ready getInputStream monitorSocket socket = {}", monitoredSocket);
                        in = monitoredSocket.getInputStream();
                        logger.debug("CC MMMMMMMMMMM-1-1 getInputStream end monitorSocket socket = {}", monitoredSocket);
                    } catch (IOException ioe) {
                        logger.debug("CC MMMMMMMMMMM-2 IOException = {}, monitorSocket socket = {}", ioe, monitoredSocket);
                        in = null;
                    }

                    byte[] bytes = new byte[EXPECTED_SIZE];
                    while (in != null && readPacket(in, bytes)) {

                        logger.debug("CC MMMMMMMMMMM-3, readpacket monitorSocket socket = {}", monitoredSocket);

                        logger.debug("Error-response packet {}", Utilities.encodeHex(bytes));
                        // Quickly close socket, so we won't ever try to send push notifications
                        // using the defective socket.
                        Utilities.close(monitoredSocket);

                        int command = bytes[0] & 0xFF;
                        if (command != 8) {
                            throw new IOException("Unexpected command byte " + command);
                        }
                        int statusCode = bytes[1] & 0xFF;
                        DeliveryError e = DeliveryError.ofCode(statusCode);

                        int id = Utilities.parseBytes(bytes[2], bytes[3], bytes[4], bytes[5]);

                        logger.debug("Closed connection cause={}; id={}", e, id);
                        delegate.connectionClosed(e, id);

                        Queue<ApnsNotification> tempCache = new LinkedList<ApnsNotification>();
                        ApnsNotification notification = null;
                        boolean foundNotification = false;

                        while (!cachedNotifications.isEmpty()) {
                            notification = cachedNotifications.poll();
                            logger.debug("Candidate for removal, message id {}", notification.getIdentifier());

                            if (notification.getIdentifier() == id) {
                                logger.debug("Bad message found {}", notification.getIdentifier());
                                foundNotification = true;
                                break;
                            }
                            tempCache.add(notification);
                        }

                        if (foundNotification) {
                            logger.debug("delegate.messageSendFailed, message id {}", notification.getIdentifier());
                            delegate.messageSendFailed(notification, new ApnsDeliveryErrorException(e));
                        } else {
                            cachedNotifications.addAll(tempCache);
                            int resendSize = tempCache.size();
                            logger.warn("Received error for message that wasn't in the cache...");
                            if (autoAdjustCacheLength) {
                                cacheLength = cacheLength + (resendSize / 2);
                                delegate.cacheLengthExceeded(cacheLength);
                            }
                            logger.debug("delegate.messageSendFailed, unknown id");
                            delegate.messageSendFailed(null, new ApnsDeliveryErrorException(e));
                        }

                        int resendSize = 0;

                        while (!cachedNotifications.isEmpty()) {

                            resendSize++;
                            final ApnsNotification resendNotification = cachedNotifications.poll();
                            logger.debug("Queuing for resend {}", resendNotification.getIdentifier());
                            notificationsBuffer.add(resendNotification);
                        }
                        logger.debug("resending {} notifications", resendSize);
                        delegate.notificationsResent(resendSize);
                    }
                    logger.debug("Monitoring input stream closed by EOF");
                } catch (IOException e) {
                    // An exception when reading the error code is non-critical, it will cause another retry
                    // sending the message. Other than providing a more stable network connection to the APNS
                    // server we can't do much about it - so let's not spam the application's error log.
                    logger.info("Exception while waiting for error code", e);
                    delegate.connectionClosed(DeliveryError.UNKNOWN, -1);
                } finally {
//                    close();
                    Utilities.close(monitoredSocket);
                    drainBuffer();
                }
            }

            /**
             * Read a packet like in.readFully(bytes) does - but do not throw an exception and return false if nothing
             * could be read at all.
             * @param in the input stream
             * @param bytes the array to be filled with data
             * @return true if a packet as been read, false if the stream was at EOF right at the beginning.
             * @throws IOException When a problem occurs, especially EOFException when there's an EOF in the middle of the packet.
             */
            private boolean readPacket(final InputStream in, final byte[] bytes) throws IOException {
                final int len = bytes.length;
                int n = 0;
                while (n < len) {
                    logger.debug("CC RRRRRRR-1 len = {}", len);
                    try {
                        int count = in.read(bytes, n, len - n);
                        logger.debug("CC RRRRRRR-2 n = {}, count = {}", n, count);
                        if (count < 0) {
                            throw new EOFException("EOF after reading " + n + " bytes of new packet.");
                        }
                        n += count;
                    } catch (IOException ioe) {
                        if (n == 0) {
//                            logger.debug("CC RRRRRRRRRRRR IOException=", ioe);
                            logger.debug("CC RRRRRRRRRRRR received apple response = {}, readPacket = false", Utilities.encodeHex(bytes));

                            return false;
                        }
                        throw new IOException("Error after reading " + n + " bytes of packet", ioe);
                    }
                }
                logger.debug("CC RRRRRRRRRRRR received apple response = {}, readPacket = true", Utilities.encodeHex(bytes));
                return true;
            }
        });
        t.start();
    }

    private synchronized Socket getOrCreateSocket(boolean resend) throws NetworkIOException {
        if (reconnectPolicy.shouldReconnect()) {
            logger.debug("Reconnecting due to reconnectPolicy dictating it");
            Utilities.close(socket);
            socket = null;
        }

        if (socket == null || socket.isClosed()) {
            try {
                if (proxy == null) {
                    socket = factory.createSocket(host, port);
                    logger.debug("Connected new socket {}", socket);
                } else if (proxy.type() == Proxy.Type.HTTP) {
                    TlsTunnelBuilder tunnelBuilder = new TlsTunnelBuilder();
                    socket = tunnelBuilder.build((SSLSocketFactory) factory, proxy, proxyUsername, proxyPassword, host, port);
                    logger.debug("Connected new socket through http tunnel {}", socket);
                } else {
                    boolean success = false;
                    Socket proxySocket = null;
                    try {
                        proxySocket = new Socket(proxy);
                        proxySocket.connect(new InetSocketAddress(host, port), connectTimeout);
                        socket = ((SSLSocketFactory) factory).createSocket(proxySocket, host, port, false);
                        success = true;
                    } finally {
                        if (!success) {
                            Utilities.close(proxySocket);
                        }
                    }
                    logger.debug("Connected new socket through socks tunnel {}", socket);
                }

                socket.setSoTimeout(readTimeout);
                socket.setKeepAlive(true);
                socket.setSendBufferSize(1024 * 32);
//                socket.setReceiveBufferSize();

                if (errorDetection) {
                    monitorSocket(socket);
                }

                reconnectPolicy.reconnected();
                logger.debug("Made a new connection to APNS");
            } catch (IOException e) {
                logger.error("Couldn't connect to APNS server", e);
                // indicate to clients whether this is a resend or initial send
                throw new NetworkIOException(e, resend);
            }
        }
        return socket;
    }

    int DELAY_IN_MS = 1000;
    private static final int RETRIES = 3;

    public synchronized void sendMessage(ApnsNotification m) throws NetworkIOException {
        logger.debug("CC enter impl sendMessage, ApnsNotification = {}", m);
        sendMessage(m, false);
        drainBuffer();
    }

    private synchronized void sendMessage(final ApnsNotification m, final boolean fromBuffer) throws NetworkIOException {
        logger.debug("sendMessage {} fromBuffer: {}", m, fromBuffer);
        logger.debug("CC SSSSSSSSSSS enter sendMessage {} fromBuffer: {} socket is {}", m, fromBuffer, (socket == null || socket.isClosed()) ? null : socket);

        if (delegate instanceof StartSendingApnsDelegate) {
            ((StartSendingApnsDelegate) delegate).startSending(m, fromBuffer);
        }

        int attempts = 0;

        while (true) {

            try {
                attempts++;

                logger.debug("CC SSSSSSSSSSS-0 ready");
                final Socket socket = getOrCreateSocket(fromBuffer);
                logger.debug("CC SSSSSSSSSSS-1 ready socket = {}, isConnected = {}, isInputShutdown = {}, isOutputShutdown = {}", socket, socket.isConnected(), socket.isInputShutdown(), socket.isOutputShutdown());
                logger.debug("CC SSSSSSSSSSS-2 ready write nitifacation = {}", m);
                if (executors == null || executors.isShutdown() || executors.isTerminated()) {
                    logger.debug("CC SSSSSSSSSSS-2-1 executors is shutdown, XXXXXXXXXXXXXXXXXX setup a new one. nitifacation = {}", m);
                    executors = Executors.newSingleThreadExecutor();
                }
                Future<Void> future = executors.submit(new Callable<Void>() {
                    public Void call() throws Exception {
                        logger.debug("CC SSSSSSSSSSS-3-2 enter thread write and flush nitifacation = {}", m);
                        socket.getOutputStream().write(m.marshall());
                        socket.getOutputStream().flush();
                        logger.debug("CC SSSSSSSSSSS-3-3 enter thread done nitifacation = {}", m);
                        return null;
                    }
                });
                try {
                    logger.debug("CC SSSSSSSSSSS-3-1 future.get() start====== nitifacation = {}", m);
                    future.get(sendMessageTimeout, TimeUnit.SECONDS);
                    logger.debug("CC SSSSSSSSSSS-3-4 future.get() end====== nitifacation = {}", m);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ee) {
                    throw new IOException(ee.getCause());
                } catch (TimeoutException te) {
                    logger.debug("CC SSSSSSSSSSS-3-5 timeout for sendMessage HAHAHAHAHAHAHAHAHAHA nitifacation = {}", m);
                    throw new IOException(te.getCause());
                }

                logger.debug("CC SSSSSSSSSSS-4 ready cache nitifacation = {}", m);
                cacheNotification(m);

                delegate.messageSent(m, fromBuffer);

                //logger.debug("Message \"{}\" sent", m);
                attempts = 0;
                break;
            } catch (IOException e) {
                logger.debug("CC SSSSSSSSSSS-5 catch exception nitifacation = {}, IOException = {}, attempts = {}, close socket = {}", m, e, attempts, socket);
                Utilities.close(socket);
                if (attempts >= RETRIES) {
                    logger.error("Couldn't send message after " + RETRIES + " retries." + m, e);
                    delegate.messageSendFailed(m, e);
                    Utilities.wrapAndThrowAsRuntimeException(e);
                }
                // The first failure might be due to closed connection (which in turn might be caused by
                // a message containing a bad token), so don't delay for the first retry.
                //
                // Additionally we don't want to spam the log file in this case, only after the second retry
                // which uses the delay.

                if (attempts != 1) {
                    logger.debug("CC SSSSSSSSSSS-6 retry send nitifacation = {}, attempts = {}", m, attempts);
                    logger.info("Failed to send message " + m + "... trying again after delay", e);
                    Utilities.sleep(DELAY_IN_MS);
                }
            }
        }
    }

    private synchronized void drainBuffer() {
        logger.debug("draining buffer");
        while (!notificationsBuffer.isEmpty()) {
            final ApnsNotification notification = notificationsBuffer.poll();
            try {
                sendMessage(notification, true);
            } catch (NetworkIOException ex) {
                // at this point we are retrying the submission of messages but failing to connect to APNS, therefore
                // notify the client of this
                delegate.messageSendFailed(notification, ex);
            }
        }
    }

    private void cacheNotification(ApnsNotification notification) {
        cachedNotifications.add(notification);
        while (cachedNotifications.size() > cacheLength) {
            cachedNotifications.poll();
            logger.debug("Removing notification from cache " + notification);
        }
    }

    public ApnsConnectionImpl copy() {
        return new ApnsConnectionImpl(factory, host, port, proxy, proxyUsername, proxyPassword, reconnectPolicy.copy(), delegate,
                errorDetection, threadFactory, cacheLength, autoAdjustCacheLength, readTimeout, connectTimeout);
    }

    public void testConnection() throws NetworkIOException {
        ApnsConnectionImpl testConnection = null;
        try {
            testConnection =
                    new ApnsConnectionImpl(factory, host, port, proxy, proxyUsername, proxyPassword, reconnectPolicy.copy(), delegate);
            final ApnsNotification notification = new EnhancedApnsNotification(0, 0, new byte[]{0}, new byte[]{0});
            testConnection.sendMessage(notification);
        } finally {
            if (testConnection != null) {
                testConnection.close();
            }
        }
    }

    public void setCacheLength(int cacheLength) {
        this.cacheLength = cacheLength;
    }

    public int getCacheLength() {
        return cacheLength;
    }
}
