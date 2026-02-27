package com.pcmic.xposed;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

public class SettingsActivity extends Activity implements DiscoveryClient.Listener {

    private static final String PREF_NAME = "pcmic_config";
    private static final String KEY_PC_IP = "pc_ip";
    private static final String KEY_PC_PORT = "pc_port";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MIC_SERVICE_ENABLED = "mic_service_enabled";
    private static final int DEFAULT_PORT = 9876;

    private enum State { SCANNING, CONNECTED }

    private Switch swMicService;
    private TextView tvStatus;
    private ProgressBar pbScanning;
    private ListView lvDevices;
    private Button btnDisconnect;

    private DiscoveryClient discovery;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> deviceLabels = new ArrayList<>();
    private CopyOnWriteArrayList<DiscoveryClient.PcInfo> currentPcList = new CopyOnWriteArrayList<>();
    private ArrayAdapter<String> adapter;
    private State state = State.SCANNING;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        swMicService = findViewById(R.id.sw_mic_service);
        tvStatus = findViewById(R.id.tv_status);
        pbScanning = findViewById(R.id.pb_scanning);
        lvDevices = findViewById(R.id.lv_devices);
        btnDisconnect = findViewById(R.id.btn_disconnect);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceLabels);
        lvDevices.setAdapter(adapter);

        SharedPreferences prefs = getPrefs();
        ensurePrefsReadable();

        swMicService.setChecked(prefs.getBoolean(KEY_MIC_SERVICE_ENABLED, false));

        if (prefs.getBoolean(KEY_ENABLED, false)) {
            String ip = prefs.getString(KEY_PC_IP, "");
            int port = prefs.getInt(KEY_PC_PORT, DEFAULT_PORT);
            if (!ip.isEmpty()) {
                enterConnected(ip, port, ip);
            }
        } else {
            enterScanning();
        }

        swMicService.setOnCheckedChangeListener((v, checked) -> {
            getPrefs().edit().putBoolean(KEY_MIC_SERVICE_ENABLED, checked).apply();
            ensurePrefsReadable();
        });

        lvDevices.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos < currentPcList.size()) {
                connect(currentPcList.get(pos));
            }
        });

        btnDisconnect.setOnClickListener(v -> disconnect());

        discovery = new DiscoveryClient(this);
        discovery.setListener(this);
        discovery.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (discovery != null) {
            discovery.stop();
        }
    }

    @Override
    public void onPcListUpdated(CopyOnWriteArrayList<DiscoveryClient.PcInfo> list) {
        handler.post(() -> {
            if (state != State.SCANNING) {
                return;
            }

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
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.putString(KEY_PC_IP, pc.ip);
        editor.putInt(KEY_PC_PORT, pc.port);
        editor.putBoolean(KEY_ENABLED, true);
        editor.putBoolean(KEY_MIC_SERVICE_ENABLED, true);
        editor.apply();
        ensurePrefsReadable();

        enterConnected(pc.ip, pc.port, pc.name);
    }

    private void disconnect() {
        getPrefs().edit().putBoolean(KEY_ENABLED, false).apply();
        ensurePrefsReadable();
        enterScanning();
    }

    private void enterConnected(String ip, int port, String name) {
        state = State.CONNECTED;
        tvStatus.setText("已连接: " + name + " (" + ip + ":" + port + ")");
        pbScanning.setVisibility(View.GONE);
        lvDevices.setVisibility(View.GONE);
        btnDisconnect.setVisibility(View.VISIBLE);
        swMicService.setChecked(getPrefs().getBoolean(KEY_MIC_SERVICE_ENABLED, true));
    }

    private void enterScanning() {
        state = State.SCANNING;
        tvStatus.setText("正在扫描局域网...");
        pbScanning.setVisibility(View.VISIBLE);
        lvDevices.setVisibility(View.VISIBLE);
        btnDisconnect.setVisibility(View.GONE);
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private void ensurePrefsReadable() {
        try {
            File dataDir = new File(getApplicationInfo().dataDir);
            File sharedPrefsDir = new File(dataDir, "shared_prefs");
            File prefsFile = new File(sharedPrefsDir, PREF_NAME + ".xml");

            if (dataDir.exists()) {
                dataDir.setExecutable(true, false);
                dataDir.setReadable(true, false);
            }
            if (sharedPrefsDir.exists()) {
                sharedPrefsDir.setExecutable(true, false);
                sharedPrefsDir.setReadable(true, false);
            }
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
            }
        } catch (Exception ignored) {
        }
    }
}
