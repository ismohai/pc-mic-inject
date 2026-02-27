package com.pcmic.xposed;

import android.app.Application;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook Application.onCreate() to show a toast when a hooked app starts.
 * Fires once per process (each process has its own classloader).
 */
public class ToastNotifier {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals(lpparam.processName)) return;

        XposedHelpers.findAndHookMethod(
            Application.class, "onCreate",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!MainHook.isMicServiceEnabled()) return;
                    Application app = (Application) param.thisObject;
                    Toast.makeText(app, "PC Mic Inject 已接管麦克风", Toast.LENGTH_SHORT).show();
                    XposedBridge.log("PcMic-Toast: " + lpparam.packageName + " notified");
                }
            }
        );
    }
}
