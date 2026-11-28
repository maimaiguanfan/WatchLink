package io.github.watchlink.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.annotation.RequiresApi;
import io.github.watchlink.alarm.probe.ProbeLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Collects live watch connection state, display name, battery percentage (0..100), and charging
 * state inside Huawei Health :PhoneService using:
 * 1) HealthCooperativeReadiness.readWatchDeviceInfo() (connected state + DeviceInfo.getDeviceName)
 * 2) Dynamically resolved DeviceBatteryInfo (Lncd) static read/write methods
 * 3) Unobfuscated com.huawei.bone.action.BATTERY_LEVEL & CONNECTION_STATE_CHANGED broadcasts
 * 4) Unobfuscated HwDeviceStatusPushManager static charge map + putChargeByIdentify hook.
 */
@RequiresApi(34)
final class HealthWatchStatusCollector {
    private static final String ACTION_BATTERY_LEVEL = "com.huawei.bone.action.BATTERY_LEVEL";
    private static final String ACTION_CONNECTION_CHANGED = "com.huawei.bone.action.CONNECTION_STATE_CHANGED";
    private static final String CHARGE_MGR_CLASS =
            "com.huawei.hwdevice.phoneprocess.mgr.hwdevicestatusmgr.HwDeviceStatusPushManager";

    record WatchStatusSnapshot(
            boolean connected,
            String watchName,
            String rawMac,
            int batteryPercent,
            boolean charging,
            long updatedAtMs) {
        static final WatchStatusSnapshot DISCONNECTED =
                new WatchStatusSnapshot(false, "", "", -1, false, 0L);

        @Override public String toString() {
            return "WatchStatusSnapshot(connected=" + connected
                    + ", battery=" + batteryPercent + ", charging=" + charging + ")";
        }
    }

    private final Context app;
    private final Executor worker;
    private final HealthCooperativeReadiness readiness;

    private BroadcastReceiver statusReceiver;
    private Method batteryReadMethod;
    private Field chargeMapField;
    private volatile boolean installed;
    private volatile String lastKnownName = "";
    private volatile String lastKnownMac = "";
    private volatile int cachedBattery = -1;
    private volatile boolean cachedCharging;
    private volatile long lastUpdatedAtMs;
    private volatile Consumer<WatchStatusSnapshot> changeListener;

    HealthWatchStatusCollector(Context app, Executor worker, HealthCooperativeReadiness readiness) {
        this.app = app;
        this.worker = worker;
        this.readiness = readiness;
    }

    void setChangeListener(Consumer<WatchStatusSnapshot> listener) {
        this.changeListener = listener;
    }

    void install(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        boolean batteryReady = installBatteryHook(installer, symbols);
        boolean chargeReady = installChargeHook(installer);
        registerStatusReceiver();
        installed = true;
        worker.execute(this::refreshAndNotify);
        ProbeLog.info((batteryReady || chargeReady ? "HEALTH_WATCH_STATUS_READY" : "HEALTH_WATCH_STATUS_DEGRADED")
                + " source=HEALTH");
    }

    void close() {
        installed = false;
        changeListener = null;
        if (statusReceiver != null) {
            try { app.unregisterReceiver(statusReceiver); } catch (RuntimeException ignored) {}
            statusReceiver = null;
        }
    }

    /** Refreshes live watch connection/battery/charging state (call on worker thread). */
    WatchStatusSnapshot snapshot() {
        HealthCooperativeReadiness.WatchDeviceInfo info = readiness != null
                ? readiness.readWatchDeviceInfo()
                : HealthCooperativeReadiness.WatchDeviceInfo.DISCONNECTED;
        if (!info.connected()) {
            return new WatchStatusSnapshot(false, lastKnownName, lastKnownMac, cachedBattery, false, lastUpdatedAtMs);
        }
        String mac = info.rawMac();
        String name = info.deviceName();
        if (mac != null && !mac.isEmpty()) lastKnownMac = mac;
        if (name != null && !name.isEmpty()) lastKnownName = name;

        int battery = readBatteryFromCache(mac);
        if (battery >= 0 && battery <= 100) {
            cachedBattery = battery;
            if (lastUpdatedAtMs == 0L) lastUpdatedAtMs = System.currentTimeMillis();
        }
        Boolean charging = readChargingFromCache(mac);
        if (charging != null) {
            cachedCharging = charging;
        }
        return new WatchStatusSnapshot(
                true,
                name != null && !name.isEmpty() ? name : lastKnownName,
                mac != null ? mac : "",
                cachedBattery,
                cachedCharging,
                lastUpdatedAtMs);
    }

    private void refreshAndNotify() {
        if (!installed) return;
        WatchStatusSnapshot snap = snapshot();
        notifyListener(snap);
    }

    private void notifyListener(WatchStatusSnapshot snap) {
        Consumer<WatchStatusSnapshot> listener = changeListener;
        if (listener != null && snap != null) {
            try { listener.accept(snap); } catch (RuntimeException ignored) {}
        }
    }

    private boolean installBatteryHook(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        String className = symbols != null ? symbols.batteryInfoClass() : "";
        if (installer == null || className == null || className.isEmpty()) return false;
        try {
            Class<?> cls = installer.target(className);
            Method readM = null;
            Method writeM = null;
            for (Method m : cls.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (!Modifier.isStatic(mod) || m.isSynthetic()) continue;
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == int.class && p.length == 1 && p[0] == String.class) {
                    if (readM == null) readM = m;
                } else if (m.getReturnType() == void.class && p.length == 2
                        && p[0] == String.class && p[1] == int.class) {
                    if (writeM == null) writeM = m;
                }
            }
            if (readM != null) {
                readM.setAccessible(true);
                batteryReadMethod = readM;
            }
            if (writeM != null) {
                installer.observeAround("health.watch.battery", writeM, chain -> {
                    if (installed && chain.getArg(0) instanceof String mac && chain.getArg(1) instanceof Integer level) {
                        if (level >= 0 && level <= 100) {
                            onBatteryObserved(mac, level);
                        }
                    }
                    return null;
                }, (chain, token, result) -> {});
            }
            return readM != null || writeM != null;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private boolean installChargeHook(AlarmObserverInstaller installer) {
        if (installer == null) return false;
        try {
            Class<?> cls = installer.target(CHARGE_MGR_CLASS);
            for (Field f : cls.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    chargeMapField = f;
                    break;
                }
            }
            Method putCharge = null;
            for (Method m : cls.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (!Modifier.isPublic(mod) || Modifier.isStatic(mod) || m.isSynthetic()) continue;
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == void.class && p.length == 2
                        && p[0] == String.class && p[1] == int.class) {
                    if (putCharge != null) {
                        putCharge = null;
                        break;
                    }
                    putCharge = m;
                }
            }
            if (putCharge != null) {
                installer.observeAround("health.watch.charge", putCharge, chain -> {
                    if (installed && chain.getArg(0) instanceof String mac && chain.getArg(1) instanceof Integer status) {
                        onChargeObserved(mac, status == 1);
                    }
                    return null;
                }, (chain, token, result) -> {});
                return true;
            }
            return chargeMapField != null;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private void registerStatusReceiver() {
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!installed || intent == null) return;
                    String action = intent.getAction();
                    if (ACTION_BATTERY_LEVEL.equals(action)) {
                        int level = intent.getIntExtra("BATTERY_LEVEL", -1);
                        String mac = intent.getStringExtra("DEVICE_MAC");
                        if (level >= 0 && level <= 100) {
                            onBatteryObserved(mac, level);
                        } else {
                            try { worker.execute(HealthWatchStatusCollector.this::refreshAndNotify); }
                            catch (RuntimeException ignored) {}
                        }
                    } else if (ACTION_CONNECTION_CHANGED.equals(action)) {
                        try { worker.execute(HealthWatchStatusCollector.this::refreshAndNotify); }
                        catch (RuntimeException ignored) {}
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_BATTERY_LEVEL);
            filter.addAction(ACTION_CONNECTION_CHANGED);
            app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED);
            statusReceiver = r;
        } catch (RuntimeException ignored) {}
    }

    private void onBatteryObserved(String mac, int level) {
        if (mac != null && !mac.isEmpty() && !lastKnownMac.isEmpty() && !mac.equalsIgnoreCase(lastKnownMac)) {
            // Re-verify primary device asynchronously before overwriting.
            try { worker.execute(this::refreshAndNotify); } catch (RuntimeException ignored) {}
            return;
        }
        boolean changed = cachedBattery != level;
        cachedBattery = level;
        lastUpdatedAtMs = System.currentTimeMillis();
        if (changed) {
            try { worker.execute(this::refreshAndNotify); } catch (RuntimeException ignored) {}
        }
    }

    private void onChargeObserved(String mac, boolean charging) {
        if (mac != null && !mac.isEmpty() && !lastKnownMac.isEmpty() && !mac.equalsIgnoreCase(lastKnownMac)) {
            try { worker.execute(this::refreshAndNotify); } catch (RuntimeException ignored) {}
            return;
        }
        boolean changed = cachedCharging != charging;
        cachedCharging = charging;
        lastUpdatedAtMs = System.currentTimeMillis();
        if (changed) {
            try { worker.execute(this::refreshAndNotify); } catch (RuntimeException ignored) {}
        }
    }

    private int readBatteryFromCache(String mac) {
        Method m = batteryReadMethod;
        if (m == null || mac == null || mac.isEmpty()) return -1;
        try {
            Object raw = m.invoke(null, mac);
            if (raw instanceof Integer level && level >= 0 && level <= 100) return level;
        } catch (Throwable ignored) {}
        return -1;
    }

    private Boolean readChargingFromCache(String mac) {
        Field f = chargeMapField;
        if (f == null || mac == null || mac.isEmpty()) return null;
        try {
            Object raw = f.get(null);
            if (raw instanceof Map<?, ?> map) {
                Object val = map.get(mac);
                if (val instanceof Integer status) return status == 1;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
