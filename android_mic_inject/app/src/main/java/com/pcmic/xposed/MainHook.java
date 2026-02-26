package com.pcmic.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PcMic";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 读取模块配置
        XSharedPreferences prefs = new XSharedPreferences("com.pcmic.xposed", "pcmic_config");
        prefs.makeWorldReadable();
        prefs.reload();

        boolean enabled = prefs.getBoolean("enabled", false);
        if (!enabled) {
            XposedBridge.log(TAG + ": 模块未启用，跳过 " + lpparam.packageName);
            return;
        }

        String pcIp = prefs.getString("pc_ip", "");
        int pcPort = prefs.getInt("pc_port", 9876);

        if (pcIp.isEmpty()) {
            XposedBridge.log(TAG + ": PC IP 未配置，跳过");
            return;
        }

        XposedBridge.log(TAG + ": hooking " + lpparam.packageName
                + " -> " + pcIp + ":" + pcPort);

        // 初始化音频接收器（单例）
        AudioStreamReceiver receiver = AudioStreamReceiver.getInstance();
        receiver.configure(pcIp, pcPort);

        // 安装 AudioRecord hook
        AudioRecordHook.install(receiver);
    }
}
