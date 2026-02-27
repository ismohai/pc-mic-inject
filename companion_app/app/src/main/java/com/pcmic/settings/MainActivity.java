package com.pcmic.settings;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final String CONFIG_PATH = "/data/adb/pcmic/config.properties";
    private static final String PID_FILE = "/data/adb/pcmic/daemon.pid";

    private Switch swEnabled;
    private EditText etPort;
    private TextView tvStatus, tvDaemonStatus, tvPhoneIp;
    private Button btnSave, btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
        loadConfig();
        refreshStatus();
    }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F5F5F5"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(24));

        // Title
        TextView title = new TextView(this);
        title.setText("PcMic Virtual Mic");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Use PC audio as phone microphone input");
        sub.setTextSize(13);
        sub.setTextColor(Color.parseColor("#888"));
        sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);

        // Status card
        LinearLayout statusCard = makeCard();
        addBoldLabel(statusCard, "Service Status");
        tvDaemonStatus = new TextView(this);
        tvDaemonStatus.setText("Checking...");
        tvDaemonStatus.setTextSize(13);
        tvDaemonStatus.setTextColor(Color.parseColor("#FF9800"));
        tvDaemonStatus.setPadding(0, dp(6), 0, 0);
        statusCard.addView(tvDaemonStatus);

        tvPhoneIp = new TextView(this);
        tvPhoneIp.setText("Phone IP: ...");
        tvPhoneIp.setTextSize(13);
        tvPhoneIp.setTextColor(Color.parseColor("#333"));
        tvPhoneIp.setPadding(0, dp(6), 0, 0);
        statusCard.addView(tvPhoneIp);
        root.addView(statusCard);

        // Enable switch card
        LinearLayout enCard = makeCard();
        LinearLayout enRow = new LinearLayout(this);
        enRow.setOrientation(LinearLayout.HORIZONTAL);
        enRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView enLabel = new TextView(this);
        enLabel.setText("Enable Virtual Mic");
        enLabel.setTextSize(15);
        enLabel.setTextColor(Color.parseColor("#333"));
        enLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        enRow.addView(enLabel);
        swEnabled = new Switch(this);
        swEnabled.setChecked(true);
        enRow.addView(swEnabled);
        enCard.addView(enRow);

        TextView enHint = new TextView(this);
        enHint.setText("Note: Toggling requires app restart to take effect");
        enHint.setTextSize(11);
        enHint.setTextColor(Color.parseColor("#999"));
        enHint.setPadding(0, dp(4), 0, 0);
        enCard.addView(enHint);
        root.addView(enCard);

        // Port card
        LinearLayout portCard = makeCard();
        addBoldLabel(portCard, "Listen Port");
        TextView portHint = new TextView(this);
        portHint.setText("PC connects to this port to send audio");
        portHint.setTextSize(12);
        portHint.setTextColor(Color.parseColor("#999"));
        portHint.setPadding(0, dp(2), 0, dp(8));
        portCard.addView(portHint);
        etPort = new EditText(this);
        etPort.setText("9876");
        etPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etPort.setTextSize(16);
        etPort.setBackgroundColor(Color.parseColor("#EEE"));
        etPort.setPadding(dp(12), dp(10), dp(12), dp(10));
        portCard.addView(etPort);
        root.addView(portCard);

        // Buttons
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(12), 0, 0);

        btnSave = makeBtn("Save Config", "#2196F3");
        btnSave.setOnClickListener(v -> saveConfig());
        row1.addView(btnSave, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, 0);

        btnStart = makeBtn("Start Service", "#4CAF50");
        btnStart.setOnClickListener(v -> startDaemon());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp1.setMargins(0, 0, dp(4), 0);
        row2.addView(btnStart, lp1);

        btnStop = makeBtn("Stop Service", "#F44336");
        btnStop.setOnClickListener(v -> stopDaemon());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp2.setMargins(dp(4), 0, 0, 0);
        row2.addView(btnStop, lp2);

        root.addView(row2);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(Color.parseColor("#4CAF50"));
        tvStatus.setPadding(0, dp(12), 0, 0);
        tvStatus.setGravity(Gravity.CENTER);
        root.addView(tvStatus);

        // Help
        LinearLayout helpCard = makeCard();
        addBoldLabel(helpCard, "Instructions");
        TextView help = new TextView(this);
        help.setText(
            "1. Both phone and PC must be on the same WiFi\n" +
            "2. Note this phone's IP address shown above\n" +
            "3. On PC, run start_streamer.bat\n" +
            "4. Enter this phone's IP in the PC app\n" +
            "5. Click 'Connect & Stream' on PC\n" +
            "6. All apps' mic will use PC audio\n" +
            "7. Disconnect PC to restore real mic\n\n" +
            "Config: /data/adb/pcmic/config.properties"
        );
        help.setTextSize(13);
        help.setTextColor(Color.parseColor("#666"));
        help.setPadding(0, dp(8), 0, 0);
        help.setLineSpacing(dp(3), 1);
        helpCard.addView(help);
        root.addView(helpCard);

        scroll.addView(root);
        setContentView(scroll);
    }

    private Button makeBtn(String text, String color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setBackgroundColor(Color.parseColor(color));
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        return b;
    }

    private void addBoldLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(Color.parseColor("#333"));
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        parent.addView(tv);
    }

    private LinearLayout makeCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(Color.WHITE);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        c.setLayoutParams(lp);
        c.setElevation(dp(2));
        return c;
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density);
    }

    private String execRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) { return ""; }
    }

    private void loadConfig() {
        new Thread(() -> {
            String content = execRoot("cat " + CONFIG_PATH + " 2>/dev/null");
            String ip = getWifiIp();
            handler.post(() -> {
                tvPhoneIp.setText("Phone IP: " + ip);
                if (content.isEmpty()) {
                    tvStatus.setText("Config not found. Install KSU module first.");
                    tvStatus.setTextColor(Color.parseColor("#F44336"));
                    return;
                }
                for (String l : content.split("\n")) {
                    l = l.trim();
                    if (l.startsWith("enabled="))
                        swEnabled.setChecked(l.substring(8).trim().equals("true"));
                    else if (l.startsWith("port="))
                        etPort.setText(l.substring(5).trim());
                }
                tvStatus.setText("Config loaded");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
            });
        }).start();
    }

    private String getWifiIp() {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress())
                        return a.getHostAddress();
                }
            }
        } catch (Exception e) {}
        return "unknown";
    }

    private void saveConfig() {
        boolean enabled = swEnabled.isChecked();
        String port = etPort.getText().toString().trim();
        if (port.isEmpty()) port = "9876";
        String cfg = "enabled=" + enabled + "\nport=" + port + "\nsample_rate=48000\nchannels=2\n";
        new Thread(() -> {
            execRoot("mkdir -p /data/adb/pcmic");
            // Use printf to avoid echo interpretation issues
            execRoot("printf '%s' '" + cfg.replace("'", "'\\''") + "' > " + CONFIG_PATH);
            handler.post(() -> {
                tvStatus.setText("Config saved");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
            });
        }).start();
    }

    private void stopDaemon() {
        tvStatus.setText("Stopping...");
        tvStatus.setTextColor(Color.parseColor("#FF9800"));
        new Thread(() -> {
            // Read PID file and kill specifically
            String pid = execRoot("cat " + PID_FILE + " 2>/dev/null").trim();
            if (!pid.isEmpty()) {
                execRoot("kill " + pid + " 2>/dev/null");
            }
            execRoot("killall pcmic-daemon 2>/dev/null");
            try { Thread.sleep(500); } catch (Exception e) {}
            execRoot("rm -f " + PID_FILE);
            handler.post(() -> {
                tvStatus.setText("Service stopped");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                refreshStatus();
            });
        }).start();
    }

    private void startDaemon() {
        tvStatus.setText("Starting...");
        tvStatus.setTextColor(Color.parseColor("#FF9800"));
        new Thread(() -> {
            // Stop existing first
            execRoot("killall pcmic-daemon 2>/dev/null");
            try { Thread.sleep(300); } catch (Exception e) {}
            String port = etPort.getText().toString().trim();
            if (port.isEmpty()) port = "9876";
            String modDir = execRoot("ls -d /data/adb/modules/pcmic 2>/dev/null").trim();
            if (!modDir.isEmpty()) {
                execRoot(modDir + "/pcmic-daemon " + port + " &");
            } else {
                // Try common paths
                execRoot("/data/adb/modules/pcmic/pcmic-daemon " + port + " &");
            }
            try { Thread.sleep(1000); } catch (Exception e) {}
            handler.post(() -> {
                tvStatus.setText("Service started");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                refreshStatus();
            });
        }).start();
    }

    private void refreshStatus() {
        new Thread(() -> {
            String pid = execRoot("cat " + PID_FILE + " 2>/dev/null").trim();
            String running = execRoot("pidof pcmic-daemon 2>/dev/null").trim();
            String ip = getWifiIp();
            handler.post(() -> {
                tvPhoneIp.setText("Phone IP: " + ip);
                if (!running.isEmpty()) {
                    tvDaemonStatus.setText("Running (PID: " + running + ")");
                    tvDaemonStatus.setTextColor(Color.parseColor("#4CAF50"));
                } else {
                    tvDaemonStatus.setText("Not running");
                    tvDaemonStatus.setTextColor(Color.parseColor("#F44336"));
                }
            });
        }).start();
    }
}
