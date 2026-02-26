package com.pcmic.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PcMic";
    private static XSharedPreferences sPrefs;

    /**
     * Check if mic service is enabled. Reloads prefs each call
     * so the toggle takes effect without restarting hooked apps.
     */
    public static boolean isMicServiceEnabled() {
        if (sPrefs == null) return false;
        sPrefs.reload();
        return sPrefs.getBoolean("mic_service_enabled", false)
            && sPrefs.getBoolean("enabled", false);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 读取模块配置
        sPrefs = new XSharedPreferences("com.pcmic.xposed", "pcmic_config");
        sPrefs.makeWorldReadable();
        sPrefs.reload();

        boolean enabled = sPrefs.getBoolean("enabled", false);
        if (!enabled) {
            XposedBridge.log(TAG + ": 模块未启用，跳过 " + lpparam.packageName);
            return;
        }

        String pcIp = sPrefs.getString("pc_ip", "");
        int pcPort = sPrefs.getInt("pc_port", 9876);

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

        // 安装启动 Toast 通知
        ToastNotifier.install(lpparam);
    }
}
