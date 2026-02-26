package com.pcmic.xposed;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

public class SettingsActivity extends Activity implements DiscoveryClient.Listener {

    private enum State { SCANNING, CONNECTED }

    private Switch swMicService;
    private TextView tvStatus;
    private ProgressBar pbScanning;
    private ListView lvDevices;
    private Button btnDisconnect;

    private DiscoveryClient discovery;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ArrayAdapter<String> adapter;
    private final ArrayList<String> deviceLabels = new ArrayList<>();
    private CopyOnWriteArrayList<DiscoveryClient.PcInfo> currentPcList = new CopyOnWriteArrayList<>();
    private State state = State.SCANNING;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_settings);

        swMicService = findViewById(R.id.sw_mic_service);
        tvStatus = findViewById(R.id.tv_status);
        pbScanning = findViewById(R.id.pb_scanning);
        lvDevices = findViewById(R.id.lv_devices);
        btnDisconnect = findViewById(R.id.btn_disconnect);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceLabels);
        lvDevices.setAdapter(adapter);

        // Load saved mic_service_enabled state
        SharedPreferences sp = getPrefs();
        swMicService.setChecked(sp.getBoolean("mic_service_enabled", false));

        // If already connected, restore connected state
        if (sp.getBoolean("enabled", false)) {
            String ip = sp.getString("pc_ip", "");
            int port = sp.getInt("pc_port", 9876);
            if (!ip.isEmpty()) {
                enterConnected(ip, port, ip);
            }
        }

        swMicService.setOnCheckedChangeListener((v, checked) -> {
            getPrefs().edit().putBoolean("mic_service_enabled", checked).apply();
        });

        lvDevices.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos < currentPcList.size()) {
                DiscoveryClient.PcInfo pc = currentPcList.get(pos);
                connect(pc);
            }
        });

        btnDisconnect.setOnClickListener(v -> disconnect());

        discovery = new DiscoveryClient();
        discovery.setListener(this);
        discovery.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (discovery != null) discovery.stop();
    }

    @Override
    public void onPcListUpdated(CopyOnWriteArrayList<DiscoveryClient.PcInfo> list) {
        handler.post(() -> {
            if (state != State.SCANNING) return;
            currentPcList = list;
            deviceLabels.clear();
            for (DiscoveryClient.PcInfo pc : list) {
                deviceLabels.add(pc.name + " (" + pc.ip + ":" + pc.port + ")");
            }
            adapter.notifyDataSetChanged();
            if (list.isEmpty()) {
                tvStatus.setText("正在扫描局域网...");
            } else {
                tvStatus.setText("发现 " + list.size() + " 台 PC，点击连接");
            }
        });
    }

    private void connect(DiscoveryClient.PcInfo pc) {
        SharedPreferences.Editor ed = getPrefs().edit();
        ed.putString("pc_ip", pc.ip);
        ed.putInt("pc_port", pc.port);
        ed.putBoolean("enabled", true);
        ed.putBoolean("mic_service_enabled", true);
        ed.apply();
        enterConnected(pc.ip, pc.port, pc.name);
    }

    private void enterConnected(String ip, int port, String name) {
        state = State.CONNECTED;
        tvStatus.setText("已连接: " + name + " (" + ip + ":" + port + ")");
        pbScanning.setVisibility(View.GONE);
        lvDevices.setVisibility(View.GONE);
        btnDisconnect.setVisibility(View.VISIBLE);
        swMicService.setChecked(true);
        swMicService.setEnabled(false);
    }

    private void disconnect() {
        SharedPreferences.Editor ed = getPrefs().edit();
        ed.putBoolean("enabled", false);
        ed.apply();

        state = State.SCANNING;
        tvStatus.setText("正在扫描局域网...");
        pbScanning.setVisibility(View.VISIBLE);
        lvDevices.setVisibility(View.VISIBLE);
        btnDisconnect.setVisibility(View.GONE);
        swMicService.setEnabled(true);
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences getPrefs() {
        return getSharedPreferences("pcmic_config", Context.MODE_WORLD_READABLE);
    }
}
