package com.pcmic.xposed;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;

/**
 * TCP client: connect to PC, receive 48kHz/stereo/24bit PCM stream into ring buffer.
 * Protocol: [4-byte length uint32 LE] + [PCM data]
 *   length=0 -> heartbeat; length=0xFFFFFFFF -> close
 */
public class AudioStreamReceiver {

    private static final String TAG = "PcMic-Recv";
    // 48kHz * 2ch * 3bytes * 2sec = 576KB ring buffer
    private static final int RING_SIZE = 576 * 1024;
    private static final long RECONNECT_MS = 2000;
    // Max frame: 20ms @ 48kHz stereo 24bit = 5760, allow some headroom
    private static final int MAX_FRAME = 16384;

    // Source format constants
    public static final int SRC_RATE = 48000;
    public static final int SRC_CH = 2;
    public static final int SRC_BYTES_PER_SAMPLE = 3; // 24bit

    private static AudioStreamReceiver sInstance;

    private volatile String host = "";
    private volatile int port = 9876;

    private final byte[] ring = new byte[RING_SIZE];
    private int writePos = 0;
    private int available = 0;
    private final Object lock = new Object();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean connected;
    private volatile Socket activeSocket;
    private Thread recvThread;

    private AudioStreamReceiver() {}

    public static synchronized AudioStreamReceiver getInstance() {
        if (sInstance == null) sInstance = new AudioStreamReceiver();
        return sInstance;
    }

    public synchronized void configure(String host, int port) {
        String newHost = host == null ? "" : host.trim();
        boolean changed = !newHost.equals(this.host) || port != this.port;
        this.host = newHost;
        this.port = port;
        if (changed) {
            closeActiveSocket();
        }
    }

    public synchronized void start() {
        if (running.get() && recvThread != null && recvThread.isAlive()) return;
        running.set(true);
        recvThread = new Thread(this::recvLoop, "PcMic-TCP");
        recvThread.setDaemon(true);
        recvThread.start();
        XposedBridge.log(TAG + ": receiver thread started");
    }

    public synchronized void stop() {
        running.set(false);
        connected = false;
        if (recvThread != null) recvThread.interrupt();
        closeActiveSocket();
        clearRing();
    }

    /** Read raw 48kHz/stereo/24bit PCM from ring buffer, pad with silence if insufficient */
    public int read(byte[] buf, int offset, int size) {
        synchronized (lock) {
            int toRead = Math.min(size, available);
            int readPos = (writePos - available + RING_SIZE) % RING_SIZE;
            for (int i = 0; i < toRead; i++) {
                buf[offset + i] = ring[(readPos + i) % RING_SIZE];
            }
            for (int i = toRead; i < size; i++) {
                buf[offset + i] = 0;
            }
            available -= toRead;
            return size;
        }
    }

    public boolean isConnected() {
        return connected;
    }

    private void recvLoop() {
        while (running.get()) {
            Socket sock = null;
            try {
                if (host.isEmpty()) {
                    connected = false;
                    clearRing();
                    sleepReconnect();
                    continue;
                }

                XposedBridge.log(TAG + ": connecting " + host + ":" + port);
                sock = new Socket();
                activeSocket = sock;
                sock.connect(new InetSocketAddress(host, port), 3000);
                sock.setTcpNoDelay(true);
                sock.setReceiveBufferSize(65536);
                DataInputStream dis = new DataInputStream(sock.getInputStream());
                connected = true;
                XposedBridge.log(TAG + ": connected");
                readFrames(dis);
            } catch (Exception e) {
                XposedBridge.log(TAG + ": connection failed: " + e.getMessage());
            } finally {
                connected = false;
                closeSocketQuietly(sock);
                activeSocket = null;
                clearRing();
            }
            sleepReconnect();
        }
    }

    private void sleepReconnect() {
        if (!running.get()) return;
        try {
            Thread.sleep(RECONNECT_MS);
        } catch (InterruptedException ignored) {
        }
    }

    private void readFrames(DataInputStream dis) throws IOException {
        byte[] hdr = new byte[4];
        while (running.get()) {
            dis.readFully(hdr);
            long len = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            if (len == 0) continue;
            if (len == 0xFFFFFFFFL) break;
            if (len > MAX_FRAME) {
                XposedBridge.log(TAG + ": frame too large " + len);
                break;
            }
            byte[] pcm = new byte[(int) len];
            dis.readFully(pcm);
            writeToRing(pcm);
        }
    }

    private void writeToRing(byte[] data) {
        synchronized (lock) {
            int len = data.length;
            int firstPart = Math.min(len, RING_SIZE - writePos);
            System.arraycopy(data, 0, ring, writePos, firstPart);
            if (firstPart < len) {
                System.arraycopy(data, firstPart, ring, 0, len - firstPart);
            }
            writePos = (writePos + len) % RING_SIZE;
            available = Math.min(available + len, RING_SIZE);
        }
    }

    private void clearRing() {
        synchronized (lock) {
            writePos = 0;
            available = 0;
        }
    }

    private void closeActiveSocket() {
        closeSocketQuietly(activeSocket);
        activeSocket = null;
    }

    private void closeSocketQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
