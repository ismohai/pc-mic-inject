package com.pcmic.xposed;

import android.app.Application;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook Application.onCreate() to show a toast when a hooked app starts.
 * Fires once per process (each process has its own classloader).
 */
public class ToastNotifier {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(
            Application.class, "onCreate",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.thisObject;
                    Toast.makeText(app, "麦克风准备就绪", Toast.LENGTH_SHORT).show();
                }
            }
        );
    }
}
