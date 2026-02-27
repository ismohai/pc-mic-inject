package com.pcmic.settings;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;

/**
 * PcMic 设置界面
 * 读写 /data/adb/pcmic/config.properties
 * 需要 root 权限
 */
public class MainActivity extends Activity {
    private static final String CONFIG_PATH = "/data/adb/pcmic/config.properties";

    private Switch swEnabled;
    private EditText etPort;
    private TextView tvStatus;
    private TextView tvDaemonStatus;
    private Button btnSave;
    private Button btnRestart;

    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
        loadConfig();
        checkDaemonStatus();
    }

    private void buildUI() {
        // 根布局
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F5F5F5"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(24));

        // 标题
        TextView title = new TextView(this);
        title.setText("PcMic 虚拟麦克风");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("使用电脑作为手机的麦克风输入");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.parseColor("#888888"));
        subtitle.setPadding(0, dp(4), 0, dp(20));
        root.addView(subtitle);

        // ---- 服务状态卡片 ----
        LinearLayout statusCard = makeCard();

        TextView statusTitle = new TextView(this);
        statusTitle.setText("服务状态");
        statusTitle.setTextSize(15);
        statusTitle.setTextColor(Color.parseColor("#333333"));
        statusTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        statusCard.addView(statusTitle);

        tvDaemonStatus = new TextView(this);
        tvDaemonStatus.setText("检查中...");
        tvDaemonStatus.setTextSize(13);
        tvDaemonStatus.setTextColor(Color.parseColor("#FF9800"));
        tvDaemonStatus.setPadding(0, dp(8), 0, 0);
        statusCard.addView(tvDaemonStatus);

        root.addView(statusCard);

        // ---- 启用开关卡片 ----
        LinearLayout enableCard = makeCard();

        LinearLayout enableRow = new LinearLayout(this);
        enableRow.setOrientation(LinearLayout.HORIZONTAL);
        enableRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView enableLabel = new TextView(this);
        enableLabel.setText("启用虚拟麦克风");
        enableLabel.setTextSize(15);
        enableLabel.setTextColor(Color.parseColor("#333333"));
        enableLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        enableRow.addView(enableLabel);

        swEnabled = new Switch(this);
        swEnabled.setChecked(true);
        enableRow.addView(swEnabled);

        enableCard.addView(enableRow);
        root.addView(enableCard);

        // ---- 端口设置卡片 ----
        LinearLayout portCard = makeCard();

        TextView portLabel = new TextView(this);
        portLabel.setText("监听端口");
        portLabel.setTextSize(15);
        portLabel.setTextColor(Color.parseColor("#333333"));
        portLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        portCard.addView(portLabel);

        TextView portHint = new TextView(this);
        portHint.setText("PC端连接到此端口发送音频");
        portHint.setTextSize(12);
        portHint.setTextColor(Color.parseColor("#999999"));
        portHint.setPadding(0, dp(2), 0, dp(8));
        portCard.addView(portHint);

        etPort = new EditText(this);
        etPort.setText("9876");
        etPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etPort.setTextSize(16);
        etPort.setBackgroundColor(Color.parseColor("#EEEEEE"));
        etPort.setPadding(dp(12), dp(10), dp(12), dp(10));
        portCard.addView(etPort);

        root.addView(portCard);

        // ---- 按钮区 ----
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(16), 0, 0);
        btnRow.setGravity(Gravity.CENTER);

        btnSave = new Button(this);
        btnSave.setText("保存配置");
        btnSave.setTextSize(14);
        btnSave.setBackgroundColor(Color.parseColor("#2196F3"));
        btnSave.setTextColor(Color.WHITE);
        btnSave.setPadding(dp(24), dp(12), dp(24), dp(12));
        btnSave.setOnClickListener(v -> saveConfig());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        btnLp.setMargins(0, 0, dp(8), 0);
        btnSave.setLayoutParams(btnLp);
        btnRow.addView(btnSave);

        btnRestart = new Button(this);
        btnRestart.setText("重启服务");
        btnRestart.setTextSize(14);
        btnRestart.setBackgroundColor(Color.parseColor("#FF9800"));
        btnRestart.setTextColor(Color.WHITE);
        btnRestart.setPadding(dp(24), dp(12), dp(24), dp(12));
        btnRestart.setOnClickListener(v -> restartDaemon());
        LinearLayout.LayoutParams btn2Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        btn2Lp.setMargins(dp(8), 0, 0, 0);
        btnRestart.setLayoutParams(btn2Lp);
        btnRow.addView(btnRestart);

        root.addView(btnRow);

        // ---- 状态提示 ----
        tvStatus = new TextView(this);
        tvStatus.setText("");
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(Color.parseColor("#4CAF50"));
        tvStatus.setPadding(0, dp(12), 0, 0);
        tvStatus.setGravity(Gravity.CENTER);
        root.addView(tvStatus);

        // ---- 使用说明卡片 ----
        LinearLayout helpCard = makeCard();
        TextView helpTitle = new TextView(this);
        helpTitle.setText("使用说明");
        helpTitle.setTextSize(15);
        helpTitle.setTextColor(Color.parseColor("#333333"));
        helpTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        helpCard.addView(helpTitle);

        TextView helpText = new TextView(this);
        helpText.setText(
            "1. 确保手机和电脑在同一WiFi网络\n" +
            "2. 在电脑端运行 start_streamer.bat\n" +
            "3. 在电脑端输入手机IP并开始推流\n" +
            "4. 手机上所有应用的麦克风将自动替换\n" +
            "5. PC断开连接后自动恢复手机麦克风\n\n" +
            "配置文件: /data/adb/pcmic/config.properties"
        );
        helpText.setTextSize(13);
        helpText.setTextColor(Color.parseColor("#666666"));
        helpText.setPadding(0, dp(8), 0, 0);
        helpText.setLineSpacing(dp(3), 1);
        helpCard.addView(helpText);

        root.addView(helpCard);

        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(lp);
        card.setElevation(dp(2));
        return card;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---- Root命令执行 ----
    private String execRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ---- 配置读写 ----
    private void loadConfig() {
        new Thread(() -> {
            String content = execRoot("cat " + CONFIG_PATH + " 2>/dev/null");
            handler.post(() -> {
                if (content.isEmpty()) {
                    tvStatus.setText("未找到配置文件，请先安装KernelSU模块");
                    tvStatus.setTextColor(Color.parseColor("#F44336"));
                    return;
                }
                for (String line : content.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("enabled=")) {
                        swEnabled.setChecked(line.substring(8).trim().equals("true"));
                    } else if (line.startsWith("port=")) {
                        etPort.setText(line.substring(5).trim());
                    }
                }
                tvStatus.setText("配置已加载");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
            });
        }).start();
    }

    private void saveConfig() {
        boolean enabled = swEnabled.isChecked();
        String port = etPort.getText().toString().trim();
        if (port.isEmpty()) port = "9876";

        String config = "enabled=" + enabled + "\n" +
                        "port=" + port + "\n" +
                        "sample_rate=48000\n" +
                        "channels=2\n";

        String finalPort = port;
        new Thread(() -> {
            execRoot("mkdir -p /data/adb/pcmic");
            execRoot("echo '" + config + "' > " + CONFIG_PATH);
            handler.post(() -> {
                tvStatus.setText("✓ 配置已保存");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
            });
        }).start();
    }

    private void restartDaemon() {
        tvStatus.setText("正在重启服务...");
        tvStatus.setTextColor(Color.parseColor("#FF9800"));
        new Thread(() -> {
            execRoot("killall pcmic-daemon 2>/dev/null");
            try { Thread.sleep(500); } catch (Exception e) {}
            String port = etPort.getText().toString().trim();
            if (port.isEmpty()) port = "9876";
            // Find module path and start daemon
            execRoot("sh /data/adb/modules/pcmic/service.sh &");
            try { Thread.sleep(1500); } catch (Exception e) {}
            handler.post(() -> {
                tvStatus.setText("✓ 服务已重启");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                checkDaemonStatus();
            });
        }).start();
    }

    private void checkDaemonStatus() {
        new Thread(() -> {
            String result = execRoot("pidof pcmic-daemon 2>/dev/null");
            handler.post(() -> {
                if (result != null && !result.isEmpty()) {
                    tvDaemonStatus.setText("● 运行中 (PID: " + result.trim() + ")");
                    tvDaemonStatus.setTextColor(Color.parseColor("#4CAF50"));
                } else {
                    tvDaemonStatus.setText("● 未运行");
                    tvDaemonStatus.setTextColor(Color.parseColor("#F44336"));
                }
            });
        }).start();
    }
}
