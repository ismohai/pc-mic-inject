package com.pcmic.xposed;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

import org.json.JSONObject;

/**
 * UDP discovery client — listens for PC broadcast on port 9877,
 * maintains a live list of discovered PCs.
 */
public class DiscoveryClient {

    public static final int DISCOVERY_PORT = 9877;
    private static final long STALE_MS = 6000;

    private final Context appContext;
    private final CopyOnWriteArrayList<PcInfo> pcList = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private Thread listenThread;
    private Listener listener;
    private WifiManager.MulticastLock multicastLock;

    public DiscoveryClient(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public interface Listener {
        void onPcListUpdated(CopyOnWriteArrayList<PcInfo> list);
    }

    public static class PcInfo {
        public String name;
        public String ip;
        public int port;
        public long lastSeen;

        public PcInfo(String name, String ip, int port) {
            this.name = name;
            this.ip = ip;
            this.port = port;
            this.lastSeen = System.currentTimeMillis();
        }
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public CopyOnWriteArrayList<PcInfo> getPcList() {
        return pcList;
    }

    public void start() {
        if (running) return;
        running = true;
        acquireMulticastLock();
        listenThread = new Thread(this::listenLoop, "PcMic-Discovery");
        listenThread.setDaemon(true);
        listenThread.start();
    }

    public void stop() {
        running = false;
        if (listenThread != null) listenThread.interrupt();
        releaseMulticastLock();
    }

    private void listenLoop() {
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(DISCOVERY_PORT));
            sock.setBroadcast(true);
            sock.setSoTimeout(2000);
            byte[] buf = new byte[1024];

            while (running) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    sock.receive(pkt);
                    String json = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    JSONObject obj = new JSONObject(json);
                    String name = obj.optString("name", "PC");
                    String ip = obj.optString("ip", "");
                    if (ip.isEmpty() && pkt.getAddress() != null) {
                        ip = pkt.getAddress().getHostAddress();
                    }
                    int port = obj.optInt("port", 9876);
                    if (!ip.isEmpty()) {
                        updatePc(name, ip, port);
                    }
                } catch (SocketTimeoutException ignored) {
                } catch (Exception ignored) {
                }
                pruneStale();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (sock != null) sock.close();
        }
    }

    private void updatePc(String name, String ip, int port) {
        boolean found = false;
        for (PcInfo pc : pcList) {
            if (pc.ip.equals(ip) && pc.port == port) {
                pc.name = name;
                pc.lastSeen = System.currentTimeMillis();
                found = true;
                break;
            }
        }
        if (!found) {
            pcList.add(new PcInfo(name, ip, port));
        }
        notifyListener();
    }

    private void pruneStale() {
        long now = System.currentTimeMillis();
        boolean changed = pcList.removeIf(pc -> now - pc.lastSeen > STALE_MS);
        if (changed) notifyListener();
    }

    private void notifyListener() {
        Listener l = listener;
        if (l != null) l.onPcListUpdated(new CopyOnWriteArrayList<>(pcList));
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) return;
            multicastLock = wifiManager.createMulticastLock("PcMic-Discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception ignored) {
        }
    }
}
