package com.pcmic.xposed;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;

/**
 * TCP 客户端：连接 PC，接收 PCM 音频流，写入环形缓冲区。
 * 协议：[4字节长度 uint32 LE] + [PCM数据]
 *   长度=0 → 心跳；长度=0xFFFFFFFF → 关闭
 */
public class AudioStreamReceiver {

    private static final String TAG = "PcMic-Recv";
    private static final int RING_SIZE = 64 * 1024; // 64KB 环形缓冲区
    private static final long RECONNECT_MS = 2000;

    private static AudioStreamReceiver sInstance;

    private String host;
    private int port;

    // 环形缓冲区
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

    /** 启动接收线程（幂等） */
    public void start() {
        if (running.getAndSet(true)) return;
        recvThread = new Thread(this::recvLoop, "PcMic-TCP");
        recvThread.setDaemon(true);
        recvThread.start();
        XposedBridge.log(TAG + ": 接收线程已启动");
    }

    /** 停止接收 */
    public void stop() {
        running.set(false);
        if (recvThread != null) recvThread.interrupt();
    }

    /** 从环形缓冲区读取 PCM 数据，不足部分填零（静音） */
    public int read(byte[] buf, int offset, int size) {
        synchronized (lock) {
            int toRead = Math.min(size, available);
            int readPos = (writePos - available + RING_SIZE) % RING_SIZE;
            for (int i = 0; i < toRead; i++) {
                buf[offset + i] = ring[(readPos + i) % RING_SIZE];
            }
            // 不足部分填静音
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

    // ---- 内部 ----

    private void recvLoop() {
        while (running.get()) {
            try {
                XposedBridge.log(TAG + ": 连接 " + host + ":" + port);
                Socket sock = new Socket(host, port);
                sock.setTcpNoDelay(true);
                DataInputStream dis = new DataInputStream(sock.getInputStream());
                XposedBridge.log(TAG + ": 已连接");

                readFrames(dis);

                sock.close();
            } catch (IOException e) {
                XposedBridge.log(TAG + ": 连接失败: " + e.getMessage());
            }
            // 重连等待
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
            if (len == 0) continue;                    // 心跳
            if (len == 0xFFFFFFFFL) break;             // 关闭信号
            if (len > 8192) {                          // 安全上限
                XposedBridge.log(TAG + ": 帧过大 " + len);
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
