package io.github.watchlink.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.alarm.probe.ProbeLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Collects live watch connection state, display name, battery percentage (0..100), and charging
 * state inside Huawei Health :PhoneService using:
 * 1) HealthCooperativeReadiness.readWatchDeviceInfo() (connected state + DeviceInfo metadata)
 * 2) Direct Huawei Service 1 / Command 8 (getBattery) query via HealthExternalSender + SDK 01 08
 *    response TLV observation (since DeviceBatteryInfo Lncd lives in Health's main process, not
 *    :PhoneService)
 * 3) SharedPreferences cache inside :PhoneService so hot reloads retain last-known battery
 * 4) Passive com.huawei.bone.action.BATTERY_LEVEL & CONNECTION_STATE_CHANGED broadcasts +
 *    HwDeviceStatusPushManager static charge map + putChargeByIdentify hook.
 */
@RequiresApi(34)
final class HealthWatchStatusCollector {
    private static final String ACTION_BATTERY_LEVEL = "com.huawei.bone.action.BATTERY_LEVEL";
    private static final String ACTION_CONNECTION_CHANGED = "com.huawei.bone.action.CONNECTION_STATE_CHANGED";
    private static final String CHARGE_MGR_CLASS =
            "com.huawei.hwdevice.phoneprocess.mgr.hwdevicestatusmgr.HwDeviceStatusPushManager";
    private static final String CACHE_PREFS = "watchlink_watch_status_cache";
    private static final String KEY_MAC = "mac";
    private static final String KEY_NAME = "name";
    private static final String KEY_BATTERY = "battery";
    private static final String KEY_CHARGING = "charging";
    private static final String KEY_UPDATED_AT = "updated_at";

    static final int BATTERY_SERVICE_ID = 1;
    static final int BATTERY_COMMAND_ID = 8;
    /** Exact 1/8 getBattery TLV payload verified from Health Lnak.b (tag 0x01, len 0x00). */
    static final byte[] BATTERY_QUERY_PAYLOAD = new byte[]{0x01, 0x00};

    private static final long BATTERY_STALE_MS = 60_000L;
    private static final long BATTERY_QUERY_COOLDOWN_MS = 2_500L;
    private static final long BATTERY_QUERY_WAIT_MS = 1_200L;

    record WatchStatusSnapshot(
            boolean connected,
            String watchName,
            String rawMac,
            int batteryPercent,
            boolean charging,
            long updatedAtMs,
            int productType,
            String hiLinkDeviceId,
            String deviceModel,
            String deviceSubModelId) {
        static final WatchStatusSnapshot DISCONNECTED =
                new WatchStatusSnapshot(false, "", "", -1, false, 0L, -1, "", "", "");

        @Override public String toString() {
            return "WatchStatusSnapshot(connected=" + connected
                    + ", battery=" + batteryPercent + ", charging=" + charging + ")";
        }
    }

    private final Context app;
    private final Executor worker;
    private final HealthCooperativeReadiness readiness;
    private final HealthExternalSender sender;

    private BroadcastReceiver statusReceiver;
    private Method batteryReadMethod;
    private Field chargeMapField;
    private volatile boolean installed;
    private volatile String lastKnownName = "";
    private volatile String lastKnownMac = "";
    private volatile int cachedBattery = -1;
    private volatile boolean cachedCharging;
    private volatile long lastUpdatedAtMs;
    private volatile long lastQueryElapsedMs;
    private volatile CountDownLatch pendingBatteryLatch;
    private volatile Consumer<WatchStatusSnapshot> changeListener;

    HealthWatchStatusCollector(Context app, Executor worker,
                               HealthCooperativeReadiness readiness,
                               HealthExternalSender sender) {
        this.app = app;
        this.worker = worker;
        this.readiness = readiness;
        this.sender = sender;
        loadPersistedCache();
    }

    void setChangeListener(Consumer<WatchStatusSnapshot> listener) {
        this.changeListener = listener;
    }

    void install(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        boolean batteryReady = installBatteryHook(installer, symbols);
        boolean chargeReady = installChargeHook(installer);
        boolean directQueryReady = sender != null && sender.directFallbackReady();
        registerStatusReceiver();
        installed = true;
        worker.execute(() -> refreshAndNotify(true));
        ProbeLog.info((batteryReady || chargeReady || directQueryReady
                ? "HEALTH_WATCH_STATUS_READY" : "HEALTH_WATCH_STATUS_DEGRADED")
                + " source=HEALTH direct_query=" + directQueryReady);
    }

    void close() {
        installed = false;
        changeListener = null;
        CountDownLatch latch = pendingBatteryLatch;
        pendingBatteryLatch = null;
        if (latch != null) latch.countDown();
        if (statusReceiver != null) {
            try { app.unregisterReceiver(statusReceiver); } catch (RuntimeException ignored) {}
            statusReceiver = null;
        }
    }

    /** Fast check for Huawei Service 1 / Command 8 (getBattery) inbound frames. */
    static boolean isBatteryResponseFrame(byte[] frame) {
        return frame != null && frame.length >= 5 && frame[0] == BATTERY_SERVICE_ID && frame[1] == BATTERY_COMMAND_ID;
    }

    /**
     * Parses watch battery percentage (0..100) from a Huawei Service 1 / Command 8 response frame.
     * Matches Health Lnaf.c / Lnaf.b: returns -1 on timeout/error (frame[2] == 0x7F) or invalid data.
     */
    static int parseBatteryResponseFrame(byte[] frame) {
        if (!isBatteryResponseFrame(frame)) return -1;
        // 0x7F is Huawei error-code TLV (e.g., 7F 04 ... timeout/error).
        if ((frame[2] & 0xFF) == 0x7F) return -1;
        int pos = 2;
        while (pos < frame.length) {
            int tag = frame[pos++] & 0xFF;
            if (pos >= frame.length) break;
            int lenByte = frame[pos++] & 0xFF;
            int len;
            if ((lenByte & 0x80) == 0) {
                len = lenByte;
            } else {
                if (pos >= frame.length) break;
                int nextByte = frame[pos++] & 0xFF;
                len = ((lenByte & 0x7F) << 7) | (nextByte & 0x7F);
            }
            if (len < 0 || pos + len > frame.length) break;
            if (tag == 0x01 && len >= 1) {
                int raw = frame[pos] & 0xFF;
                if (raw == 0xFF) return -1;
                return Math.min(raw, 100);
            }
            pos += len;
        }
        return -1;
    }

    /** Called from WatchIngressProbe when an inbound 01 08 battery frame arrives on SDK receive. */
    void observeSdkFrame(String mac, byte[] frame) {
        if (!installed || !isBatteryResponseFrame(frame)) return;
        int level = parseBatteryResponseFrame(frame);
        if (level >= 0 && level <= 100) {
            onBatteryObserved(mac, level);
        }
    }

    /** Refreshes watch status, actively querying 1/8 if battery is missing or stale. */
    WatchStatusSnapshot snapshot() {
        return snapshot(true, false);
    }

    /** Refreshes watch status (call on worker thread). */
    WatchStatusSnapshot snapshot(boolean allowActiveQuery, boolean forceQuery) {
        HealthCooperativeReadiness.WatchDeviceInfo info = readiness != null
                ? readiness.readWatchDeviceInfo()
                : HealthCooperativeReadiness.WatchDeviceInfo.DISCONNECTED;
        if (!info.connected()) {
            return new WatchStatusSnapshot(false, lastKnownName, lastKnownMac, cachedBattery, false, lastUpdatedAtMs,
                    -1, "", "", "");
        }
        String mac = info.rawMac();
        String name = info.deviceName();
        boolean macChanged = mac != null && !mac.isEmpty()
                && !lastKnownMac.isEmpty() && !mac.equalsIgnoreCase(lastKnownMac);
        if (macChanged) {
            cachedBattery = -1;
            cachedCharging = false;
            lastUpdatedAtMs = 0L;
        }
        if (mac != null && !mac.isEmpty()) lastKnownMac = mac;
        if (name != null && !name.isEmpty()) lastKnownName = name;

        int battery = readBatteryFromCache(mac);
        if (battery >= 0 && battery <= 100) {
            cachedBattery = battery;
            if (lastUpdatedAtMs == 0L) lastUpdatedAtMs = System.currentTimeMillis();
            persistCache();
        }
        Boolean charging = readChargingFromCache(mac);
        if (charging != null) {
            cachedCharging = charging;
        }

        if (allowActiveQuery && mac != null && !mac.isEmpty()) {
            long nowWall = System.currentTimeMillis();
            boolean stale = cachedBattery < 0
                    || lastUpdatedAtMs <= 0L
                    || (nowWall - lastUpdatedAtMs) >= BATTERY_STALE_MS;
            if (forceQuery || stale) {
                queryBatteryDirectAndAwait(mac, forceQuery);
            }
        }

        return new WatchStatusSnapshot(
                true,
                name != null && !name.isEmpty() ? name : lastKnownName,
                mac != null ? mac : "",
                cachedBattery,
                cachedCharging,
                lastUpdatedAtMs,
                info.productType(),
                info.hiLinkDeviceId(),
                info.deviceModel(),
                info.deviceSubModelId());
    }

    private void queryBatteryDirectAndAwait(String mac, boolean force) {
        if (sender == null || !sender.directFallbackReady()) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        long minGap = cachedBattery < 0 ? 1_500L : BATTERY_QUERY_COOLDOWN_MS;
        if (!force && lastQueryElapsedMs > 0L && (nowElapsed - lastQueryElapsedMs) < minGap) {
            return;
        }
        if (force && lastQueryElapsedMs > 0L && (nowElapsed - lastQueryElapsedMs) < 1_000L) {
            return;
        }
        lastQueryElapsedMs = nowElapsed;
        CountDownLatch latch = new CountDownLatch(1);
        pendingBatteryLatch = latch;
        try {
            boolean sent = sender.sendDeviceCommand(mac, BATTERY_SERVICE_ID, BATTERY_COMMAND_ID, BATTERY_QUERY_PAYLOAD);
            if (!sent) return;
            ProbeLog.info("HEALTH_WATCH_BATTERY_QUERY_SENT source=HEALTH");
            latch.await(BATTERY_QUERY_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (pendingBatteryLatch == latch) {
                pendingBatteryLatch = null;
            }
        }
    }

    private void refreshAndNotify(boolean forceQuery) {
        if (!installed) return;
        WatchStatusSnapshot snap = snapshot(true, forceQuery);
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
                            try { worker.execute(() -> refreshAndNotify(false)); }
                            catch (RuntimeException ignored) {}
                        }
                    } else if (ACTION_CONNECTION_CHANGED.equals(action)) {
                        try { worker.execute(() -> refreshAndNotify(true)); }
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
            try { worker.execute(() -> refreshAndNotify(false)); } catch (RuntimeException ignored) {}
            return;
        }
        if (mac != null && !mac.isEmpty() && lastKnownMac.isEmpty()) {
            lastKnownMac = mac;
        }
        boolean changed = cachedBattery != level;
        cachedBattery = level;
        lastUpdatedAtMs = System.currentTimeMillis();
        CountDownLatch latch = pendingBatteryLatch;
        if (latch != null) latch.countDown();
        persistCache();
        if (changed) {
            ProbeLog.info("HEALTH_WATCH_BATTERY_UPDATED battery=" + level + " charging=" + cachedCharging);
            try { worker.execute(() -> refreshAndNotify(false)); } catch (RuntimeException ignored) {}
        }
    }

    private void onChargeObserved(String mac, boolean charging) {
        if (mac != null && !mac.isEmpty() && !lastKnownMac.isEmpty() && !mac.equalsIgnoreCase(lastKnownMac)) {
            try { worker.execute(() -> refreshAndNotify(false)); } catch (RuntimeException ignored) {}
            return;
        }
        boolean changed = cachedCharging != charging;
        cachedCharging = charging;
        lastUpdatedAtMs = System.currentTimeMillis();
        persistCache();
        if (changed) {
            try { worker.execute(() -> refreshAndNotify(false)); } catch (RuntimeException ignored) {}
        }
    }

    private void loadPersistedCache() {
        try {
            SharedPreferences sp = app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
            String mac = sp.getString(KEY_MAC, "");
            String name = sp.getString(KEY_NAME, "");
            int bat = sp.getInt(KEY_BATTERY, -1);
            boolean chg = sp.getBoolean(KEY_CHARGING, false);
            long at = sp.getLong(KEY_UPDATED_AT, 0L);
            if (mac != null) lastKnownMac = mac;
            if (name != null) lastKnownName = name;
            if (bat >= 0 && bat <= 100) {
                cachedBattery = bat;
                cachedCharging = chg;
                lastUpdatedAtMs = Math.max(0L, at);
            }
        } catch (RuntimeException ignored) {}
    }

    private void persistCache() {
        try {
            SharedPreferences sp = app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
            sp.edit()
                    .putString(KEY_MAC, lastKnownMac)
                    .putString(KEY_NAME, lastKnownName)
                    .putInt(KEY_BATTERY, cachedBattery)
                    .putBoolean(KEY_CHARGING, cachedCharging)
                    .putLong(KEY_UPDATED_AT, lastUpdatedAtMs)
                    .apply();
        } catch (RuntimeException ignored) {}
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
