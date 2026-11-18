package com.gathin.tvhome;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Window;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * Fork: the Arc 5's remote reaches Android over Bluetooth and IR at once, so one arrow press arrives 2-3 times from
 * different input devices. Every activity's window drops those copies here, before any screen sees them.
 */
public class LauncherApp extends Application {
    static final long ECHO_MS = 180;
    private static int lastCode, lastDevice = -1;
    private static long lastAt;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle saved) { filterKeys(a.getWindow()); }
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle out) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    private static void filterKeys(Window w) {
        Window.Callback real = w.getCallback();
        w.setCallback((Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                new Class<?>[] {Window.Callback.class}, (proxy, m, args) -> {
                    if (m.getName().equals("dispatchKeyEvent")) {
                        KeyEvent e = (KeyEvent) args[0];
                        if (e.getAction() == KeyEvent.ACTION_DOWN && isArrow(e.getKeyCode())
                                && echo(e.getKeyCode(), e.getDeviceId(), e.getEventTime())) return true;
                    }
                    try {
                        return m.invoke(real, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                }));
    }

    private static boolean isArrow(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /** True when this key-down is a copy of the last one: same key, another input device, within ECHO_MS. */
    static boolean echo(int code, int device, long at) {
        if (code == lastCode && device != lastDevice && at - lastAt < ECHO_MS) return true;
        lastCode = code;
        lastDevice = device;
        lastAt = at;
        return false;
    }
}
