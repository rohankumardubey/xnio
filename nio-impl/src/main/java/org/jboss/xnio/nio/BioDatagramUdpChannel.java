/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.xnio.nio;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.jboss.xnio.Option;
import org.jboss.xnio.ChannelListener;
import org.jboss.xnio.IoUtils;
import org.jboss.xnio.Options;
import org.jboss.xnio.channels.Configurable;
import org.jboss.xnio.channels.MultipointReadResult;
import org.jboss.xnio.channels.UdpChannel;
import org.jboss.xnio.channels.UnsupportedOptionException;
import org.jboss.xnio.log.Logger;

/**
 *
 */
class BioDatagramUdpChannel implements UdpChannel {
    private static final Logger log = Logger.getLogger("org.jboss.xnio.nio.udp.bio-server.channel");

    private final DatagramSocket datagramSocket;
    private final DatagramPacket receivePacket;
    private final ByteBuffer receiveBuffer;
    private final DatagramPacket sendPacket;
    private final ByteBuffer sendBuffer;
    private final Executor handlerExecutor;
    private final Runnable readHandlerTask = new ReadHandlerTask();
    private final Runnable writeHandlerTask = new WriteHandlerTask();
    private final ReaderTask readerTask = new ReaderTask();
    private final WriterTask writerTask = new WriterTask();

    private volatile ChannelListener<? super UdpChannel> readListener = null;
    private volatile ChannelListener<? super UdpChannel> writeListener = null;
    private volatile ChannelListener<? super UdpChannel> closeListener = null;

    private static final AtomicReferenceFieldUpdater<BioDatagramUdpChannel, ChannelListener> readListenerUpdater = AtomicReferenceFieldUpdater.newUpdater(BioDatagramUdpChannel.class, ChannelListener.class, "readListener");
    private static final AtomicReferenceFieldUpdater<BioDatagramUdpChannel, ChannelListener> writeListenerUpdater = AtomicReferenceFieldUpdater.newUpdater(BioDatagramUdpChannel.class, ChannelListener.class, "writeListener");
    private static final AtomicReferenceFieldUpdater<BioDatagramUdpChannel, ChannelListener> closeListenerUpdater = AtomicReferenceFieldUpdater.newUpdater(BioDatagramUdpChannel.class, ChannelListener.class, "closeListener");

    private final ChannelListener.Setter<UdpChannel> readSetter = IoUtils.getSetter(this, readListenerUpdater);
    private final ChannelListener.Setter<UdpChannel> writeSetter = IoUtils.getSetter(this, writeListenerUpdater);
    private final ChannelListener.Setter<UdpChannel> closeSetter = IoUtils.getSetter(this, closeListenerUpdater);

    private final Object readLock = new Object();
    private final Object writeLock = new Object();

    // @protectedby {@link #readLock}
    private boolean enableRead;
    // @protectedby {@link #writeLock}
    private boolean enableWrite;
    // @protectedby {@link #readLock}
    private boolean readable;
    // @protectedby {@link #writeLock}
    private boolean writable;
    // @protectedby {@link #readLock}
    private IOException readException;

    private final AtomicBoolean closeCalled = new AtomicBoolean(false);
    private final AtomicLong globalBytesRead;
    private final AtomicLong globalBytesWritten;
    private final AtomicLong globalMessagesRead;
    private final AtomicLong globalMessagesWritten;
    final AtomicLong bytesRead = new AtomicLong();
    final AtomicLong bytesWritten = new AtomicLong();
    final AtomicLong messagesRead = new AtomicLong();
    final AtomicLong messagesWritten = new AtomicLong();

    BioDatagramUdpChannel(int sendBufSize, int recvBufSize, final Executor handlerExecutor, final DatagramSocket datagramSocket, final AtomicLong globalBytesRead, final AtomicLong globalBytesWritten, final AtomicLong globalMessagesRead, final AtomicLong globalMessagesWritten) {
        this.datagramSocket = datagramSocket;
        this.handlerExecutor = handlerExecutor;
        this.globalBytesRead = globalBytesRead;
        this.globalBytesWritten = globalBytesWritten;
        this.globalMessagesRead = globalMessagesRead;
        this.globalMessagesWritten = globalMessagesWritten;
        if (sendBufSize == -1) {
            sendBufSize = 4096;
        } else if (sendBufSize < 0) {
            throw new IllegalArgumentException("sendBufSize is less than 0");
        }
        if (recvBufSize == -1) {
            recvBufSize = 4096;
        } else if (recvBufSize < 0) {
            throw new IllegalArgumentException("recvBufSize is less than 0");
        }
        final byte[] sendBufferBytes = new byte[sendBufSize];
        sendBuffer = ByteBuffer.wrap(sendBufferBytes);
        final byte[] recvBufferBytes = new byte[recvBufSize];
        receiveBuffer = ByteBuffer.wrap(recvBufferBytes);
        sendPacket = new DatagramPacket(sendBufferBytes, sendBufSize);
        receivePacket = new DatagramPacket(recvBufferBytes, recvBufSize);
        log.trace("Constructed a new channel (%s); send buffer size %d, receive buffer size %d", this, Integer.valueOf(sendBufSize), Integer.valueOf(recvBufSize));
    }

    protected void open() {
        final ThreadFactory threadFactory = Executors.defaultThreadFactory();
        final Thread readThread = threadFactory.newThread(readerTask);
        boolean ok = false;
        try {
            final Thread writeThread = threadFactory.newThread(writerTask);
            try {
                readThread.start();
                writeThread.start();
                ok = true;
            } finally {
                if (! ok) {
                    writerTask.cancel();
                }
            }
        } finally {
            if (! ok) {
                readerTask.cancel();
            }
        }
        log.trace("Channel %s opened", this);
    }

    public ChannelListener.Setter<UdpChannel> getReadSetter() {
        return readSetter;
    }

    public ChannelListener.Setter<UdpChannel> getWriteSetter() {
        return writeSetter;
    }

    public ChannelListener.Setter<UdpChannel> getCloseSetter() {
        return closeSetter;
    }

    public boolean flush() throws IOException {
        return true;
    }

    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) datagramSocket.getLocalSocketAddress();
    }

    public MultipointReadResult<InetSocketAddress> receive(final ByteBuffer buffer) throws IOException {
        synchronized (readLock) {
            if (!readable) {
                return null;
            }
            readable = false;
            if (readException != null) {
                try {
                    readException.setStackTrace(new Throwable().getStackTrace());
                    throw readException;
                } finally {
                    readException = null;
                }
            }
            final int size = Math.min(buffer.remaining(), receiveBuffer.remaining());
            receiveBuffer.limit(size);
            buffer.put(receiveBuffer);
            readLock.notify();
            final InetSocketAddress socketAddress = (InetSocketAddress) receivePacket.getSocketAddress();
            bytesRead.addAndGet(size);
            globalBytesRead.addAndGet(size);
            messagesRead.incrementAndGet();
            globalMessagesRead.incrementAndGet();
            return new MultipointReadResult<InetSocketAddress>() {
                public InetSocketAddress getSourceAddress() {
                    return socketAddress;
                }

                public InetSocketAddress getDestinationAddress() {
                    return null;
                }
            };
        }
    }

    public boolean isOpen() {
        return ! datagramSocket.isClosed();
    }

    public void close() throws IOException {
        if (! closeCalled.getAndSet(true)) {
            synchronized (writeLock) {
                enableWrite = false;
            }
            synchronized (readLock) {
                enableRead = false;
            }
            try {
                readerTask.cancel();
            } catch (Throwable t) {
                log.trace(t, "Reader task cancel failed");
            }
            try {
                writerTask.cancel();
            } catch (Throwable t) {
                log.trace(t, "Writer task cancel failed");
            }
            synchronized (writeLock) {
                writable = false;
            }
            synchronized (readLock) {
                readable = false;
            }
            datagramSocket.close();
            IoUtils.<UdpChannel>invokeChannelListener(this, closeListener);
            log.trace("Closing channel %s", this);
        }
    }

    public boolean send(final InetSocketAddress target, final ByteBuffer buffer) throws IOException {
        synchronized (writeLock) {
            if (! writable) {
                return false;
            }
            sendBuffer.clear();
            if (sendBuffer.remaining() < buffer.remaining()) {
                throw new IOException("Insufficient room in send buffer (send will never succeed); send buffer is " + sendBuffer.remaining() + " bytes, but transmitted datagram is " + buffer.remaining() + " bytes");
            }
            final int cnt = buffer.remaining();
            bytesWritten.addAndGet(cnt);
            globalBytesWritten.addAndGet(cnt);
            messagesWritten.incrementAndGet();
            globalMessagesWritten.incrementAndGet();
            sendBuffer.put(buffer);
            sendPacket.setSocketAddress(target);
            sendPacket.setData(sendBuffer.array(), sendBuffer.arrayOffset(), sendBuffer.position());
            writeLock.notifyAll();
            writable = false;
            return true;
        }
    }

    public boolean send(final InetSocketAddress target, final ByteBuffer[] dsts) throws IOException {
        return send(target, dsts, 0, dsts.length);
    }

    public boolean send(final InetSocketAddress target, final ByteBuffer[] dsts, final int offset, final int length) throws IOException {
        synchronized (writeLock) {
            if (! writable) {
                return false;
            }
            sendBuffer.clear();
            long t = 0;
            for (int i = 0; i < length; i ++) {
                t += dsts[i + offset].remaining();
            }
            if (sendBuffer.remaining() < t) {
                throw new IOException("Insufficient room in send buffer (send will never succeed); send buffer is " + sendBuffer.remaining() + " bytes, but transmitted datagram is " + t + " bytes");
            }
            for (int i = 0; i < length; i ++) {
                sendBuffer.put(dsts[i + offset]);
            }
            sendPacket.setSocketAddress(target);
            sendPacket.setData(sendBuffer.array(), sendBuffer.arrayOffset(), sendBuffer.position());
            writeLock.notifyAll();
            writable = false;
            return true;
        }
    }

    public void suspendReads() {
        synchronized (readLock) {
            enableRead = false;
        }
    }

    public void suspendWrites() {
        synchronized (readLock) {
            enableWrite = false;
        }
    }

    public void resumeReads() {
        synchronized (readLock) {
            enableRead = true;
            if (readable) {
                handlerExecutor.execute(readHandlerTask);
            }
        }
    }

    public void resumeWrites() {
        synchronized (writeLock) {
            enableWrite = true;
            if (writable) {
                handlerExecutor.execute(writeHandlerTask);
            }
        }
    }

    public void shutdownReads() throws IOException {
        throw new UnsupportedOperationException("Shutdown reads");
    }

    public boolean shutdownWrites() throws IOException {
        throw new UnsupportedOperationException("Shutdown writes");
    }

    public void awaitReadable() throws IOException {
        try {
            synchronized (readLock) {
                if (! isOpen()) {
                    return;
                }
                while (! readable) {
                    readLock.wait();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void awaitReadable(final long time, final TimeUnit timeUnit) throws IOException {
        try {
            synchronized (readLock) {
                if (! isOpen()) {
                    return;
                }
                if (! readable) {
                    timeUnit.timedWait(readLock, time);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void awaitWritable() throws IOException {
        try {
            synchronized (writeLock) {
                if (! isOpen()) {
                    return;
                }
                while (! writable) {
                    writeLock.wait();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void awaitWritable(final long time, final TimeUnit timeUnit) throws IOException {
        try {
            synchronized (writeLock) {
                if (! isOpen()) {
                    return;
                }
                if (! writable) {
                    timeUnit.timedWait(writeLock, time);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final Set<Option<?>> OPTIONS = Option.setBuilder()
            .add(Options.BROADCAST)
            .add(Options.IP_TRAFFIC_CLASS)
            .create();

    public boolean supportsOption(final Option<?> option) {
        return OPTIONS.contains(option);
    }

    @SuppressWarnings({"unchecked"})
    public <T> T getOption(final Option<T> option) throws UnsupportedOptionException, IOException {
        if (Options.BROADCAST.equals(option)) {
            return (T) Boolean.valueOf(datagramSocket.getBroadcast());
        } else if (Options.IP_TRAFFIC_CLASS.equals(option)) {
            final int v = datagramSocket.getTrafficClass();
            return v == -1 ? null : (T) Integer.valueOf(v);
        } else {
            return null;
        }
    }

    public <T> Configurable setOption(final Option<T> option, final T value) throws IllegalArgumentException, IOException {
        if (Options.BROADCAST.equals(option)) {
            datagramSocket.setBroadcast(Options.BROADCAST.cast(value).booleanValue());
        } else if (Options.IP_TRAFFIC_CLASS.equals(option)) {
            datagramSocket.setTrafficClass(Options.IP_TRAFFIC_CLASS.cast(value).intValue());
        }
        return this;
    }

    public Key join(final InetAddress group, final NetworkInterface iface) throws IOException {
        throw new UnsupportedOptionException("Multicast not supported");
    }

    public Key join(final InetAddress group, final NetworkInterface iface, final InetAddress source) throws IOException {
        throw new UnsupportedOptionException("Multicast not supported");
    }

    private final class ReaderTask implements Runnable {
        private volatile Thread thread;

        public void run() {
            thread = Thread.currentThread();
            try {
                for (;;) {
                    synchronized (readLock) {
                        while (readable) {
                            try {
                                readLock.wait();
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }
                    try {
                        datagramSocket.receive(receivePacket);
                    } catch (IOException e) {
                        synchronized (readLock) {
                            // pass the exception on to the user
                            readException = e;
                            readable = true;
                            if (enableRead) {
                                handlerExecutor.execute(readHandlerTask);
                            }
                            continue;
                        }
                    }
                    synchronized (readLock) {
                        receiveBuffer.limit(receivePacket.getLength());
                        receiveBuffer.position(0);
                        readable = true;
                        if (enableRead) {
                            handlerExecutor.execute(readHandlerTask);
                        }
                    }
                }
            } finally {
                thread = null;
            }
        }

        public void cancel() {
            final Thread thread = this.thread;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    private final class WriterTask implements Runnable {
        private volatile Thread thread;

        public void run() {
            thread = Thread.currentThread();
            try {
                for (;;) {
                    synchronized (writeLock) {
                        writable = true;
                        while (writable) {
                            if (enableWrite) {
                                enableWrite = false;
                                handlerExecutor.execute(writeHandlerTask);
                            }
                            if (writable) try {
                                writeLock.wait();
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }
                    try {
                        datagramSocket.send(sendPacket);
                    } catch (IOException e) {
                        log.trace("Packet send failed: %s", e);
                    }
                }
            } finally {
                thread = null;
            }
        }

        public void cancel() {
            final Thread thread = this.thread;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    private final class ReadHandlerTask implements Runnable {
        public void run() {
            IoUtils.<UdpChannel>invokeChannelListener(BioDatagramUdpChannel.this, readListener);
        }
    }

    private final class WriteHandlerTask implements Runnable {
        public void run() {
            IoUtils.<UdpChannel>invokeChannelListener(BioDatagramUdpChannel.this, writeListener);
        }
    }
}
