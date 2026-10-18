package com.winlator.cmod.xconnector;

import com.winlator.cmod.xserver.XServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.ArrayDeque;
import android.os.ParcelFileDescriptor;

public class XOutputStream {
    private static final byte[] ZERO = new byte[64];
    public ByteBuffer buffer;
    public final ClientSocket clientSocket;
    private final ReentrantLock lock = new ReentrantLock();
    private int ancillaryFd = -1;
    private final ArrayDeque<Packet> pending = new ArrayDeque<>();
    private boolean asynchronous;
    private volatile boolean closed;
    private ParcelFileDescriptor writerFd;
    private ClientSocket writerSocket;
    private int pendingBytes;
    // A native-resolution GetImage reply on the foldable already exceeds 16 MiB.
    private static final int MAX_PENDING_BYTES = 64 * 1024 * 1024;

    private static final class Packet {
        final ByteBuffer bytes;
        final ParcelFileDescriptor fd;
        Packet(ByteBuffer bytes, ParcelFileDescriptor fd) { this.bytes = bytes; this.fd = fd; }
        void close() { if (fd != null) try { fd.close(); } catch (IOException ignored) {} }
    }

    /** Replies and events share this FIFO, so no socket I/O occurs under X-server or UI locks. */
    public void enableAsync() {
        if (clientSocket == null || asynchronous) return;
        try {
            // Keep the socket identity alive until the writer exits, even if the connector closes its original fd.
            writerFd = ParcelFileDescriptor.fromFd(clientSocket.fd);
            writerSocket = new ClientSocket(writerFd.getFd());
        } catch (IOException e) { throw new IllegalStateException("Cannot duplicate X11 output socket", e); }
        asynchronous = true;
        Thread writer = new Thread(this::writePending, "X11-output-" + clientSocket.fd);
        writer.setDaemon(true);
        writer.start();
    }

    private void writePending() {
        try {
            while (true) {
                Packet packet;
                synchronized (pending) {
                    while (pending.isEmpty() && !closed) pending.wait();
                    if (closed) return;
                    packet = pending.removeFirst();
                }
                boolean ok;
                try { ok = writerSocket.sendPacket(packet.bytes, packet.bytes.limit(), packet.fd != null ? packet.fd.getFd() : -1); }
                finally { packet.close(); }
                synchronized (pending) { pendingBytes -= packet.bytes.limit(); }
                if (!ok) return;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally {
            close();
            try { writerFd.close(); } catch (IOException ignored) {}
        }
    }

    public void close() {
        synchronized (pending) {
            if (closed) return;
            closed = true;
            for (Packet packet : pending) packet.close();
            pending.clear();
            pending.notifyAll();
            if (writerSocket != null) writerSocket.abort();
        }
    }

    public XOutputStream(int initialCapacity) {
        this(null, initialCapacity);
    }

    public XOutputStream(ClientSocket clientSocket, int initialCapacity) {
        this.clientSocket = clientSocket;
        buffer = ByteBuffer.allocateDirect(initialCapacity);
    }

    public void setByteOrder(ByteOrder byteOrder) {
        buffer.order(byteOrder);
    }

    public void setAncillaryFd(int ancillaryFd) {
        this.ancillaryFd = ancillaryFd;
    }

    public void writeByte(byte value) {
        ensureSpaceIsAvailable(1);
        buffer.put(value);
    }

    public void writeShort(short value) {
        ensureSpaceIsAvailable(2);
        buffer.putShort(value);
    }

    public void writeInt(int value) {
        ensureSpaceIsAvailable(4);
        buffer.putInt(value);
    }

    public void writeIntPair(int first, int second) {
        writeInt(first);
        writeInt(second);
    }

    public void writeLong(long value) {
        ensureSpaceIsAvailable(8);
        buffer.putLong(value);
    }

    public void writeFP3232(double value) {
        int integral = (int) Math.floor(value);
        int frac = (int) (long) ((value - integral) * 0x100000000L);
        writeInt(integral);
        writeInt(frac);
    }

    public void writeString8(String str) {
        byte[] bytes = str.getBytes(XServer.LATIN1_CHARSET);
        int length = -str.length() & 3;
        ensureSpaceIsAvailable(bytes.length + length);
        buffer.put(bytes);
        if (length > 0) writePad(length);
    }

    public void write(byte[] data) {
        write(data, 0, data.length);
    }

    public void write(byte[] data, int offset, int length) {
        ensureSpaceIsAvailable(length);
        buffer.put(data, offset, length);
    }

    public void write(ByteBuffer data) {
        ensureSpaceIsAvailable(data.remaining());
        buffer.put(data);
    }

    public void writePad(int length) {
        write(ZERO, 0, length);
    }

    private void flush() throws IOException {
        if (clientSocket == null) return; // Memory stream, also used by protocol probes.
        if (buffer.position() != 0) {
            buffer.flip();
            if (com.winlator.cmod.xserver.XClientRequestHandler.trace) traceOutput();
            if (asynchronous) {
                synchronized (pending) {
                    if (closed || pendingBytes + buffer.remaining() > MAX_PENDING_BYTES) {
                        close();
                        buffer.clear();
                        ancillaryFd = -1;
                        throw new IOException("X11 client stopped consuming output");
                    }
                    ByteBuffer copy = ByteBuffer.allocateDirect(buffer.remaining());
                    copy.put(buffer).flip();
                    ParcelFileDescriptor fd = ancillaryFd == -1 ? null : ParcelFileDescriptor.fromFd(ancillaryFd);
                    pending.addLast(new Packet(copy, fd));
                    pendingBytes += copy.remaining();
                    ancillaryFd = -1;
                    pending.notifyAll();
                }
            } else if (ancillaryFd != -1) {
                clientSocket.sendAncillaryMsg(buffer, ancillaryFd);
                ancillaryFd = -1;
            } else {
                clientSocket.write(buffer);
            }
            buffer.clear();
        }
    }

    /** Logs what is about to be sent: the total size and the first bytes of each 32-byte reply, event or error. */
    private void traceOutput() {
        StringBuilder text = new StringBuilder("XOut ").append(buffer.remaining()).append("B:");
        int start = buffer.position();
        int shown = 0;
        for (int offset = 0; offset + 8 <= buffer.remaining() && shown < 12; shown++) {
            int type = buffer.get(start + offset) & 0xff;
            int detail = buffer.get(start + offset + 1) & 0xff;
            int seq = (buffer.get(start + offset + 2) & 0xff) | ((buffer.get(start + offset + 3) & 0xff) << 8);
            text.append(" [type=").append(type).append(" d=").append(detail).append(" seq=").append(seq);
            int size = 32;
            if (type == 1 || type == 35) {
                long extra = (buffer.get(start + offset + 4) & 0xffL) | ((buffer.get(start + offset + 5) & 0xffL) << 8)
                        | ((buffer.get(start + offset + 6) & 0xffL) << 16) | ((buffer.get(start + offset + 7) & 0xffL) << 24);
                text.append(" extra=").append(extra);
                StringBuilder hex = new StringBuilder(" hex=");
                for (int i = 0; i < 48 && offset + i < buffer.remaining(); i++) hex.append(String.format("%02x", buffer.get(start + offset + i) & 0xff));
                text.append(hex);
                size = (int)Math.min(Integer.MAX_VALUE / 2, 32 + extra * 4);
            }
            text.append(']');
            offset += size;
        }
        android.util.Log.d("XTrace", text.toString());
    }

    public XStreamLock lock() {
        return new OutputStreamLock();
    }

    private void ensureSpaceIsAvailable(int length) {
        int position = buffer.position();
        if ((buffer.capacity() - position) >= length) return;
        ByteBuffer newBuffer = ByteBuffer.allocateDirect(buffer.capacity() + length).order(buffer.order());
        buffer.rewind();
        newBuffer.put(buffer).position(position);
        buffer = newBuffer;
    }

    private class OutputStreamLock implements XStreamLock {
        public OutputStreamLock() {
            lock.lock();
        }

        @Override
        public void close() throws IOException {
            try {
                flush();
            } finally {
                lock.unlock();
            }
        }
    }

    public void writeSuccessReply(int sequenceNumber, int replyLength) throws IOException {
        try (XStreamLock lock = lock()) {
            writeByte((byte) 1);
            writeByte((byte) 0);
            writeShort((short) sequenceNumber);
            writeInt(replyLength);
            writePad(24);
        }
    }
}
