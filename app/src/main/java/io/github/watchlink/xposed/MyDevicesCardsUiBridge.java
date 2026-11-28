package io.github.watchlink.xposed;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.RequiresApi;
import io.github.libxposed.api.XposedInterface;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.mydevices.MyDevicesCardCodec;
import io.github.watchlink.setup.WatchLinkPrefs;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight UI-process bridge for com.heytap.mydevices:cards (which hosts DeviceCardHomeActivity
 * and HomeCardView). Intercepts taps on WatchLink's virtual watch card and opens either Huawei
 * Health or WatchLink according to RemotePreferences, with zero WatchLink process wake-up.
 */
@RequiresApi(34)
final class MyDevicesCardsUiBridge {
    private static final String VIRTUAL_PKG = BuildConfig.APPLICATION_ID;
    private static final String HOME_CARD_VIEW_CLASS =
            "com.oplus.mydevices.deviceui.devicecard.view.homecard.HomeCardView";

    private final XposedInterface module;
    private final ClassLoader loader;
    private final Context app;
    private final SharedPreferences remotePrefs;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();

    private volatile boolean active = true;
    private float downX;
    private float downY;
    private boolean trackingTap;
    private long lastTapLaunchMs;

    MyDevicesCardsUiBridge(XposedInterface module, ClassLoader loader, Context app, SharedPreferences remotePrefs) {
        this.module = module;
        this.loader = loader;
        this.app = app.getApplicationContext();
        this.remotePrefs = remotePrefs;
    }

    void install() {
        try {
            Class<?> homeCardCls = Class.forName(HOME_CARD_VIEW_CLASS, false, loader);
            Method dispatchTouch = homeCardCls.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            int touchSlop = ViewConfiguration.get(app).getScaledTouchSlop();
            XposedInterface.HookBuilder builder = module.hook(dispatchTouch);
            if (module.getApiVersion() >= XposedInterface.API_102) {
                builder.setId("mydevices.cards.tap");
            }
            XposedInterface.HookHandle h = builder.intercept(chain -> {
                Object view = chain.getThisObject();
                Object rawEv = chain.getArg(0);
                if (active && isCardEnabled() && view != null && rawEv instanceof MotionEvent ev
                        && isWatchLinkCardView(view)) {
                    int action = ev.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        downX = ev.getX();
                        downY = ev.getY();
                        trackingTap = true;
                    } else if (action == MotionEvent.ACTION_MOVE && trackingTap) {
                        if (Math.hypot(ev.getX() - downX, ev.getY() - downY) > touchSlop) {
                            trackingTap = false;
                        }
                    } else if (action == MotionEvent.ACTION_CANCEL) {
                        trackingTap = false;
                    } else if (action == MotionEvent.ACTION_UP && trackingTap) {
                        trackingTap = false;
                        long now = System.currentTimeMillis();
                        if (now - lastTapLaunchMs > 600L) {
                            lastTapLaunchMs = now;
                            launchConfiguredTapTarget();
                            return Boolean.TRUE;
                        }
                    }
                }
                return chain.proceed();
            });
            synchronized (handles) {
                handles.add(h);
            }
        } catch (Throwable ignored) {}
    }

    void close() {
        active = false;
        synchronized (handles) {
            for (XposedInterface.HookHandle h : handles) {
                try { h.unhook(); } catch (RuntimeException ignored) {}
            }
            handles.clear();
        }
    }

    private boolean isCardEnabled() {
        if (remotePrefs == null) return false;
        try {
            return remotePrefs.getBoolean(WatchLinkPrefs.KEY_MYDEVICES_CARD_ENABLED, false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String tapTarget() {
        if (remotePrefs == null) return WatchLinkPrefs.TAP_TARGET_HEALTH;
        try {
            return WatchLinkPrefs.normalizeTapTarget(
                    remotePrefs.getString(WatchLinkPrefs.KEY_MYDEVICES_TAP_TARGET, WatchLinkPrefs.TAP_TARGET_HEALTH));
        } catch (RuntimeException ignored) {
            return WatchLinkPrefs.TAP_TARGET_HEALTH;
        }
    }

    private void launchConfiguredTapTarget() {
        String pkg = WatchLinkPrefs.TAP_TARGET_WATCHLINK.equals(tapTarget())
                ? BuildConfig.APPLICATION_ID
                : TargetProfile.HEALTH.packageName;
        try {
            Intent launch = app.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(launch);
                ProbeLog.info("MYDEVICES_CARD_TAP source=MY_DEVICES");
            }
        } catch (RuntimeException ignored) {}
    }

    private static boolean isWatchLinkCardView(Object view) {
        for (Class<?> c = view.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(view);
                    if (v == null) continue;
                    if (v instanceof String s) {
                        if (s.startsWith(MyDevicesCardCodec.DEVICE_ID_PREFIX) || VIRTUAL_PKG.equals(s)) {
                            return true;
                        }
                    } else {
                        String typeName = v.getClass().getName();
                        if (typeName.contains("QuickCardDeviceData") || typeName.contains("DeviceInfo")
                                || typeName.contains("DeviceCard")) {
                            String text = String.valueOf(v);
                            if (text.contains(MyDevicesCardCodec.DEVICE_ID_PREFIX)
                                    || text.contains(VIRTUAL_PKG)) {
                                return true;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }
}
