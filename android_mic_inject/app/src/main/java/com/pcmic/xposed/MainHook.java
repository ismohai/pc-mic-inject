package com.pcmic.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PcMic";
    private static final String MODULE_PACKAGE = "com.pcmic.xposed";
    private static final String PREFS_NAME = "pcmic_config";
    private static XSharedPreferences sPrefs;

    private static void reloadPrefs() {
        if (sPrefs != null) sPrefs.reload();
    }

    /**
     * Check if mic service is enabled. Reloads prefs each call
     * so the toggle takes effect without restarting hooked apps.
     */
    public static boolean isMicServiceEnabled() {
        if (sPrefs == null) return false;
        reloadPrefs();
        return sPrefs.getBoolean("mic_service_enabled", false)
                && sPrefs.getBoolean("enabled", false);
    }

    public static String getPcIp() {
        if (sPrefs == null) return "";
        reloadPrefs();
        return sPrefs.getString("pc_ip", "");
    }

    public static int getPcPort() {
        if (sPrefs == null) return 9876;
        reloadPrefs();
        return sPrefs.getInt("pc_port", 9876);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (MODULE_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        sPrefs = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
        sPrefs.makeWorldReadable();
        reloadPrefs();

        String pcIp = getPcIp();
        int pcPort = getPcPort();

        XposedBridge.log(TAG + ": hooking " + lpparam.packageName
                + " -> " + (pcIp.isEmpty() ? "<not-configured>" : pcIp + ":" + pcPort));

        AudioStreamReceiver receiver = AudioStreamReceiver.getInstance();
        receiver.configure(pcIp, pcPort);

        AudioRecordHook.install(receiver);
        ToastNotifier.install(lpparam);
    }
}
