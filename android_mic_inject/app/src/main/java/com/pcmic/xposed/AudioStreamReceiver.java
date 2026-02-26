package com.pcmic.xposed;

import java.io.DataInputStream;
import java.io.IOException;
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

    private String host;
    private int port;

    private final byte[] ring = new byte[RING_SIZE];
    private int writePos = 0;
    private int available = 0;
    private final Object lock = new Object();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread recvThread;

    private AudioStreamReceiver() {}

    public static synchronized AudioStreamReceiver getInstance() {
        if (sInstance == null) sInstance = new AudioStreamReceiver();
        return sInstance;
    }

    public void configure(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void start() {
        if (running.getAndSet(true)) return;
        recvThread = new Thread(this::recvLoop, "PcMic-TCP");
        recvThread.setDaemon(true);
        recvThread.start();
        XposedBridge.log(TAG + ": receiver thread started");
    }

    public void stop() {
        running.set(false);
        if (recvThread != null) recvThread.interrupt();
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
        return running.get();
    }

    private void recvLoop() {
        while (running.get()) {
            try {
                XposedBridge.log(TAG + ": connecting " + host + ":" + port);
                Socket sock = new Socket(host, port);
                sock.setTcpNoDelay(true);
                sock.setReceiveBufferSize(65536);
                DataInputStream dis = new DataInputStream(sock.getInputStream());
                XposedBridge.log(TAG + ": connected");
                readFrames(dis);
                sock.close();
            } catch (IOException e) {
                XposedBridge.log(TAG + ": connection failed: " + e.getMessage());
            }
            if (running.get()) {
                try { Thread.sleep(RECONNECT_MS); } catch (InterruptedException ignored) {}
            }
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
            for (byte b : data) {
                ring[writePos] = b;
                writePos = (writePos + 1) % RING_SIZE;
            }
            available = Math.min(available + data.length, RING_SIZE);
        }
    }
}
