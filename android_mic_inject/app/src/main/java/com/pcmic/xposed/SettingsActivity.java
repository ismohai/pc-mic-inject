package com.pcmic.xposed;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    private EditText etIp, etPort;
    private Switch swEnable;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_settings);

        etIp = findViewById(R.id.et_ip);
        etPort = findViewById(R.id.et_port);
        swEnable = findViewById(R.id.sw_enable);
        tvStatus = findViewById(R.id.tv_status);
        Button btnSave = findViewById(R.id.btn_save);

        // 读取已保存的配置
        SharedPreferences sp = getPrefs();
        etIp.setText(sp.getString("pc_ip", ""));
        etPort.setText(String.valueOf(sp.getInt("pc_port", 9876)));
        swEnable.setChecked(sp.getBoolean("enabled", false));

        btnSave.setOnClickListener(v -> save());
    }

    private void save() {
        String ip = etIp.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            port = 9876;
        }
        boolean enabled = swEnable.isChecked();

        SharedPreferences sp = getPrefs();
        sp.edit()
            .putString("pc_ip", ip)
            .putInt("pc_port", port)
            .putBoolean("enabled", enabled)
            .apply();

        tvStatus.setText("状态: 已保存 (" + ip + ":" + port + ")");
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences getPrefs() {
        return getSharedPreferences("pcmic_config", Context.MODE_WORLD_READABLE);
    }
}
