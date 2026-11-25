package io.github.watchlink.xposed;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.validation.DailyAlarmWire;
import io.github.watchlink.wifi.HuaweiWifiSyncCodec;
import io.github.watchlink.wifi.WifiNetworkRecord;
import io.github.watchlink.wifi.WifiSyncWire;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Health :PhoneService Wi-Fi sync coordinator (ServiceID=1 / CommandID=47).
 * Shared by Route 1A (WatchLink UI bulk sync) and Route 1B (WirelessSettings automatic zero-Root sync).
 * Stores only SHA-256 sync fingerprints in host-local prefs; never logs or persists plaintext SSIDs/passwords.
 */
@RequiresApi(34)
final class HealthWifiBridge {
    private static final String PREFS_HISTORY = BuildConfig.APPLICATION_ID + ".wifi_sync_history_v1";
    private static final String KEY_FINGERPRINTS = "fingerprints_v1";
    private static final String ACTION_WATCH_STATE_CHANGED = "com.huawei.bone.action.CONNECTION_STATE_CHANGED";
    private static final int MAX_HISTORY = 256;
    private static final long ACK_WAIT_MS = 3_500L;
    private static final long AUTO_DEBOUNCE_MS = 2_000L;
    private static final long QUERY_UNANSWERED_MS = 4_500L;

    record StatusSnapshot(
            String watchSupport,
            boolean ackReady,
            int syncedCount,
            String lastResult,
            String lastSource,
            long lastAt) {}

    private record PendingQuery(String requestId, long createdElapsed, boolean force, ScheduledFuture<?> timeout) {}

    private final Context app;
    private final int ownerUid;
    private final HostSettings settings;
    private final HealthExternalSender sender;
    private final HealthCooperativeReadiness readiness;
    private final boolean sdkObserverReady;
    private final SharedPreferences historyPrefs;
    private final LinkedHashSet<String> syncedFingerprints = new LinkedHashSet<>();
    private final ScheduledThreadPoolExecutor wifiWorker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "WatchLink-health-wifi");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, PendingQuery> pendingQueries = new ConcurrentHashMap<>();

    private volatile boolean installed;
    private volatile boolean wifiAckHookReady;
    private volatile CompletableFuture<HuaweiWifiSyncCodec.Ack> pendingAck;
    private volatile boolean busy;

    private volatile String lastResult = "NONE";
    private volatile String lastSource = "NONE";
    private volatile long lastAt;

    private BroadcastReceiver syncOneReceiver;
    private BroadcastReceiver currentReplyReceiver;
    private BroadcastReceiver watchConnectReceiver;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private ScheduledFuture<?> debouncedAutoTrigger;

    HealthWifiBridge(Context app, int ownerUid, HostSettings settings,
                     HealthExternalSender sender, HealthCooperativeReadiness readiness,
                     boolean sdkObserverReady) {
        this.app = app;
        this.ownerUid = ownerUid;
        this.settings = settings;
        this.sender = sender;
        this.readiness = readiness;
        this.sdkObserverReady = sdkObserverReady;
        this.historyPrefs = app.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE);
        wifiWorker.setRemoveOnCancelPolicy(true);
        loadHistory();
    }

    void install(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        wifiAckHookReady = installAckHook(installer, symbols);
        ProbeLog.info((wifiAckHookReady ? "HEALTH_WIFI_ACK_HOOK_READY" : "HEALTH_WIFI_ACK_HOOK_DEGRADED"));

        syncOneReceiver = WifiSyncWire.registerHealthSyncOne(app, ownerUid, wifiWorker, this::handleUiSyncOne);
        registerCurrentReplyReceiver();
        registerAutoTriggers();

        settings.onWifiAutoSyncChanged(enabled -> {
            if (Boolean.TRUE.equals(enabled) && installed) {
                scheduleAutoQuery("SETTING_ENABLED", false, 500L);
            }
        });

        installed = true;
        if (settings.isWifiAutoSyncEnabled()) {
            scheduleAutoQuery("STARTUP", false, 2_500L);
        }
    }

    private boolean installAckHook(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        if (symbols == null || symbols.wifiSyncMgrClass() == null || symbols.wifiSyncMgrClass().isEmpty()) {
            return false;
        }
        try {
            Class<?> mgrType = installer.target(symbols.wifiSyncMgrClass());
            Class<?> deviceInfo = installer.target("com.huawei.health.devicemgr.business.entity.DeviceInfo");
            Method getResult = installer.method(mgrType, "getResult", void.class, false, deviceInfo, byte[].class);
            installer.observeAround("health.wifi.ack", getResult, chain -> {
                byte[] frame = (byte[]) chain.getArg(1);
                onWifiAckFrame(frame);
                return null;
            }, (chain, token, result) -> {});
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError degraded) {
            return false;
        }
    }

    /** Secondary ACK source from WatchIngressProbe when 1/47 response arrives on raw SDK receive. */
    void observeSdkFrame(boolean received, byte[] frame) {
        if (!received || frame == null || frame.length < 4) return;
        if ((frame[0] & 0xFF) == HuaweiWifiSyncCodec.SERVICE_ID
                && (frame[1] & 0xFF) == HuaweiWifiSyncCodec.COMMAND_ID) {
            onWifiAckFrame(frame);
        }
    }

    private void onWifiAckFrame(byte[] frame) {
        CompletableFuture<HuaweiWifiSyncCodec.Ack> waiter = pendingAck;
        if (waiter == null || frame == null) return;
        HuaweiWifiSyncCodec.Ack ack = HuaweiWifiSyncCodec.decodeAck(frame);
        if (ack.valid()) {
            waiter.complete(ack);
        }
    }

    boolean isAwaitingAck() {
        return pendingAck != null;
    }

    boolean isBusy() {
        return busy;
    }

    boolean ackObserverReady() {
        return wifiAckHookReady || sdkObserverReady;
    }

    void triggerCurrentWifiSync(String trigger, boolean force) {
        if (!installed) return;
        scheduleAutoQuery(trigger, force, 0L);
    }

    StatusSnapshot statusSnapshot() {
        String support = inspectWatchSupport();
        int count;
        synchronized (syncedFingerprints) {
            count = syncedFingerprints.size();
        }
        return new StatusSnapshot(support, ackObserverReady(), count, lastResult, lastSource, lastAt);
    }

    private String inspectWatchSupport() {
        if (readiness == null) return "UNKNOWN";
        HealthCooperativeReadiness.WifiDeviceSnapshot snap = readiness.readWifiSnapshot();
        if (!snap.connected()) return "WATCH_DISCONNECTED";
        Boolean supportSyncWifi = sender.querySupportSyncWifi(snap.rawDevice());
        if (Boolean.FALSE.equals(supportSyncWifi)) return "UNSUPPORTED";
        if (Boolean.TRUE.equals(snap.capability241())) return "SUPPORTED_NEW";
        if (Boolean.FALSE.equals(snap.capability241())) return "SUPPORTED_LEGACY";
        return Boolean.TRUE.equals(supportSyncWifi) ? "SUPPORTED_UNKNOWN_CAP" : "UNKNOWN";
    }

    private WifiSyncWire.SyncResult handleUiSyncOne(WifiNetworkRecord record, boolean force) {
        return syncNetworkLocked(record, force, "BULK_1A");
    }

    private WifiSyncWire.SyncResult syncNetworkLocked(WifiNetworkRecord record, boolean force, String source) {
        if (!installed) return new WifiSyncWire.SyncResult(true, "FAILED", -1);
        if (record == null || !record.isSupportedByWatch()) {
            recordOutcome("UNSUPPORTED_NETWORK", source);
            return new WifiSyncWire.SyncResult(true, "UNSUPPORTED_NETWORK", -1);
        }
        if (!sender.directFallbackReady()) {
            recordOutcome("DIRECT_UNAVAILABLE", source);
            ProbeLog.info("WIFI_SYNC_RESULT source=HEALTH result=UNAVAILABLE");
            return new WifiSyncWire.SyncResult(true, "DIRECT_UNAVAILABLE", -1);
        }
        if (readiness == null) {
            recordOutcome("WATCH_DISCONNECTED", source);
            return new WifiSyncWire.SyncResult(true, "WATCH_DISCONNECTED", -1);
        }
        HealthCooperativeReadiness.WifiDeviceSnapshot snap = readiness.readWifiSnapshot();
        if (!snap.connected() || snap.rawDevice().isEmpty()) {
            recordOutcome("WATCH_DISCONNECTED", source);
            ProbeLog.info("WIFI_SYNC_RESULT source=HEALTH result=WATCH_UNAVAILABLE");
            return new WifiSyncWire.SyncResult(true, "WATCH_DISCONNECTED", -1);
        }
        Boolean supportSyncWifi = sender.querySupportSyncWifi(snap.rawDevice());
        if (Boolean.FALSE.equals(supportSyncWifi)) {
            recordOutcome("UNSUPPORTED_WATCH", source);
            return new WifiSyncWire.SyncResult(true, "UNSUPPORTED_WATCH", -1);
        }
        String fingerprint = record.syncFingerprint(snap.deviceToken());
        if (!force && isAlreadySynced(fingerprint)) {
            recordOutcome("SKIPPED_DUP", source);
            return new WifiSyncWire.SyncResult(true, "SKIPPED_DUP", HuaweiWifiSyncCodec.ACK_SUCCESS);
        }

        boolean includeTag4 = Boolean.TRUE.equals(snap.capability241());
        byte[] payload;
        try {
            payload = HuaweiWifiSyncCodec.encodePayload(record, includeTag4);
        } catch (IllegalArgumentException invalid) {
            recordOutcome("UNSUPPORTED_NETWORK", source);
            return new WifiSyncWire.SyncResult(true, "UNSUPPORTED_NETWORK", -1);
        }

        busy = true;
        CompletableFuture<HuaweiWifiSyncCodec.Ack> ackWaiter = new CompletableFuture<>();
        pendingAck = ackWaiter;
        try {
            boolean sent = sender.sendDeviceCommand(
                    snap.rawDevice(), HuaweiWifiSyncCodec.SERVICE_ID, HuaweiWifiSyncCodec.COMMAND_ID, payload);
            if (!sent) {
                recordOutcome("FAILED", source);
                ProbeLog.info("WIFI_SYNC_RESULT source=HEALTH result=FAILED");
                return new WifiSyncWire.SyncResult(true, "FAILED", -1);
            }
            if (!ackObserverReady()) {
                markSynced(fingerprint);
                recordOutcome("SENT_NO_ACK_HOOK", source);
                ProbeLog.info("WIFI_SYNC_RESULT source=HEALTH result=SENT");
                return new WifiSyncWire.SyncResult(true, "SENT_NO_ACK_HOOK", -1);
            }
            try {
                HuaweiWifiSyncCodec.Ack ack = ackWaiter.get(ACK_WAIT_MS, TimeUnit.MILLISECONDS);
                if (ack.success()) {
                    markSynced(fingerprint);
                    recordOutcome("ACK_OK", source);
                    ProbeLog.info("WIFI_SYNC_RESULT source=HEALTH result=VERIFIED");
                    return new WifiSyncWire.SyncResult(true, "ACK_OK", ack.code());
                }
                recordOutcome("ACK_FAIL", source);
                ProbeLog.info("WIFI_SYNC_RESULT source=HEALTH result=REJECTED");
                return new WifiSyncWire.SyncResult(true, "ACK_FAIL", ack.code());
            } catch (TimeoutException timeout) {
                recordOutcome("ACK_TIMEOUT", source);
                ProbeLog.info("WIFI_SYNC_RESULT source=HEALTH result=UNCERTAIN");
                return new WifiSyncWire.SyncResult(true, "ACK_TIMEOUT", -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                recordOutcome("FAILED", source);
                return new WifiSyncWire.SyncResult(true, "FAILED", -1);
            } catch (Exception error) {
                recordOutcome("FAILED", source);
                return new WifiSyncWire.SyncResult(true, "FAILED", -1);
            }
        } finally {
            pendingAck = null;
            busy = false;
        }
    }

    private void registerCurrentReplyReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!installed || intent == null || !WifiSyncWire.ACTION_CURRENT_REPLY.equals(intent.getAction())) {
                    return;
                }
                int uid = getSentFromUid();
                String pkg = getSentFromPackage();
                if (uid < 0 || !TargetProfile.WIRELESS_SETTINGS.packageName.equals(pkg)
                        || intent.getComponent() != null
                        || !TargetProfile.HEALTH.packageName.equals(intent.getPackage())
                        || (intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
                    return;
                }
                PendingResult pending = goAsync();
                try {
                    wifiWorker.execute(() -> {
                        try {
                            if (installed && SenderVerifier.verifyReminderSender(
                                    app, TargetProfile.WIRELESS_SETTINGS, uid, pkg)) {
                                handleCurrentWifiReply(intent);
                            }
                        } catch (RuntimeException error) {
                            ProbeLog.failure("WIFI_1B_REPLY_FAILED", error);
                        } finally {
                            pending.finish();
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
                    pending.finish();
                }
            }
        };
        app.registerReceiver(receiver, new IntentFilter(WifiSyncWire.ACTION_CURRENT_REPLY), Context.RECEIVER_EXPORTED);
        currentReplyReceiver = receiver;
    }

    private void handleCurrentWifiReply(Intent intent) {
        String requestId = intent.getStringExtra("request");
        long wall = intent.getLongExtra("at", 0L);
        long elapsed = intent.getLongExtra("elapsed", -1L);
        String status = intent.getStringExtra("status");
        if (!DailyAlarmWire.isRun(requestId) || !WifiSyncWire.validQueryStatus(status)) return;
        if (!DailyAlarmWire.inWindow(wall, elapsed, WifiSyncWire.QUERY_WINDOW_MS)) return;

        PendingQuery pending = pendingQueries.remove(requestId);
        if (pending == null) return;
        if (pending.timeout() != null) pending.timeout().cancel(false);

        if (!"OK".equals(status)) {
            if (pending.force() || "UNSUPPORTED_EAP".equals(status)) {
                String mapped = switch (status) {
                    case "DISABLED" -> "DISABLED";
                    case "NOT_CONNECTED" -> "NOT_CONNECTED";
                    case "UNSUPPORTED_EAP" -> "UNSUPPORTED_NETWORK";
                    default -> "FAILED";
                };
                recordOutcome(mapped, "AUTO_1B");
            }
            return;
        }
        if (!pending.force() && !settings.isWifiAutoSyncEnabled()) {
            recordOutcome("DISABLED", "AUTO_1B");
            return;
        }
        String ssid = intent.getStringExtra("ssid");
        String password = intent.getStringExtra("password");
        int encodeType = intent.getIntExtra("encodeType", WifiNetworkRecord.ENCODE_OPEN);
        String capabilities = intent.getStringExtra("capabilities");
        boolean hidden = intent.getBooleanExtra("hidden", false);
        if (ssid == null || ssid.isEmpty()) {
            recordOutcome("FAILED", "AUTO_1B");
            return;
        }
        WifiNetworkRecord record = new WifiNetworkRecord(ssid, password, encodeType, capabilities, hidden, wall);
        syncNetworkLocked(record, pending.force(), "AUTO_1B");
    }

    private void registerAutoTriggers() {
        try {
            ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
            if (cm != null) {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build();
                ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) {
                        if (installed && settings.isWifiAutoSyncEnabled()) {
                            scheduleAutoQuery("WIFI_CONNECTED", false, AUTO_DEBOUNCE_MS);
                        }
                    }
                };
                cm.registerNetworkCallback(request, cb);
                connectivityManager = cm;
                wifiCallback = cb;
            }
        } catch (RuntimeException unavailable) {
            ProbeLog.failure("WIFI_1B_CALLBACK_UNAVAILABLE", unavailable);
        }

        try {
            BroadcastReceiver watchReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (installed && settings.isWifiAutoSyncEnabled()) {
                        scheduleAutoQuery("WATCH_CONNECTED", false, AUTO_DEBOUNCE_MS);
                    }
                }
            };
            app.registerReceiver(watchReceiver, new IntentFilter(ACTION_WATCH_STATE_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED);
            watchConnectReceiver = watchReceiver;
        } catch (RuntimeException ignored) {
            // Optional secondary watch reconnect hook.
        }
    }

    private void scheduleAutoQuery(String trigger, boolean force, long delayMs) {
        try {
            wifiWorker.execute(() -> {
                if (!installed) return;
                if (!force && !settings.isWifiAutoSyncEnabled()) return;
                if (debouncedAutoTrigger != null && !force) {
                    debouncedAutoTrigger.cancel(false);
                }
                Runnable task = () -> sendWirelessQuery(trigger, force);
                if (delayMs <= 0L) {
                    task.run();
                } else {
                    debouncedAutoTrigger = wifiWorker.schedule(task, delayMs, TimeUnit.MILLISECONDS);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private void sendWirelessQuery(String trigger, boolean force) {
        if (!installed) return;
        if (!force && !settings.isWifiAutoSyncEnabled()) return;
        if (!SenderVerifier.trustedReminderTarget(app, TargetProfile.WIRELESS_SETTINGS)) {
            if (force) recordOutcome("WIRELESS_UNANSWERED", "AUTO_1B");
            return;
        }
        if (readiness != null && !readiness.readWifiSnapshot().connected()) {
            if (force) recordOutcome("WATCH_DISCONNECTED", "AUTO_1B");
            return;
        }
        pruneExpiredQueries();
        String requestId = UUID.randomUUID().toString();
        long wall = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        if (force) recordOutcome("SENDING", "AUTO_1B");

        ScheduledFuture<?> timeout = wifiWorker.schedule(() -> {
            PendingQuery expired = pendingQueries.remove(requestId);
            if (expired != null && (expired.force() || "SENDING".equals(lastResult))) {
                recordOutcome("WIRELESS_UNANSWERED", "AUTO_1B");
                ProbeLog.info("WIFI_1B_QUERY_RESULT result=UNAVAILABLE");
            }
        }, QUERY_UNANSWERED_MS, TimeUnit.MILLISECONDS);
        pendingQueries.put(requestId, new PendingQuery(requestId, elapsed, force, timeout));

        try {
            Intent query = WifiSyncWire.queryCurrentIntent(requestId, wall, elapsed, trigger);
            app.sendBroadcast(query, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
            ProbeLog.info("WIFI_1B_QUERY_SENT");
        } catch (RuntimeException failed) {
            PendingQuery removed = pendingQueries.remove(requestId);
            if (removed != null && removed.timeout() != null) removed.timeout().cancel(false);
            if (force) recordOutcome("FAILED", "AUTO_1B");
            ProbeLog.failure("WIFI_1B_QUERY_FAILED", failed);
        }
    }

    private void pruneExpiredQueries() {
        long now = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, PendingQuery>> it = pendingQueries.entrySet().iterator();
        while (it.hasNext()) {
            PendingQuery q = it.next().getValue();
            if (now - q.createdElapsed() > WifiSyncWire.QUERY_WINDOW_MS) {
                if (q.timeout() != null) q.timeout().cancel(false);
                it.remove();
            }
        }
    }

    private void recordOutcome(String result, String source) {
        lastResult = result;
        lastSource = source;
        lastAt = System.currentTimeMillis();
    }

    private boolean isAlreadySynced(String fingerprint) {
        synchronized (syncedFingerprints) {
            return syncedFingerprints.contains(fingerprint);
        }
    }

    private void markSynced(String fingerprint) {
        String serialized;
        synchronized (syncedFingerprints) {
            syncedFingerprints.remove(fingerprint);
            syncedFingerprints.add(fingerprint);
            while (syncedFingerprints.size() > MAX_HISTORY) {
                Iterator<String> oldest = syncedFingerprints.iterator();
                if (!oldest.hasNext()) break;
                oldest.next();
                oldest.remove();
            }
            serialized = String.join("\n", syncedFingerprints);
        }
        try {
            historyPrefs.edit().putString(KEY_FINGERPRINTS, serialized).apply();
        } catch (RuntimeException ignored) {}
    }

    private void loadHistory() {
        try {
            String raw = historyPrefs.getString(KEY_FINGERPRINTS, "");
            if (raw == null || raw.isEmpty()) return;
            synchronized (syncedFingerprints) {
                for (String line : raw.split("\n")) {
                    if (line.length() == 64 && line.matches("[0-9a-f]{64}")) {
                        syncedFingerprints.add(line);
                        if (syncedFingerprints.size() >= MAX_HISTORY) break;
                    }
                }
            }
        } catch (RuntimeException ignored) {}
    }

    void close() {
        installed = false;
        settings.onWifiAutoSyncChanged(null);
        CompletableFuture<HuaweiWifiSyncCodec.Ack> waiter = pendingAck;
        pendingAck = null;
        if (waiter != null) waiter.cancel(true);
        for (PendingQuery q : pendingQueries.values()) {
            if (q.timeout() != null) q.timeout().cancel(false);
        }
        pendingQueries.clear();
        if (wifiCallback != null && connectivityManager != null) {
            try { connectivityManager.unregisterNetworkCallback(wifiCallback); } catch (RuntimeException ignored) {}
            wifiCallback = null;
            connectivityManager = null;
        }
        if (watchConnectReceiver != null) {
            try { app.unregisterReceiver(watchConnectReceiver); } catch (RuntimeException ignored) {}
            watchConnectReceiver = null;
        }
        if (currentReplyReceiver != null) {
            try { app.unregisterReceiver(currentReplyReceiver); } catch (RuntimeException ignored) {}
            currentReplyReceiver = null;
        }
        if (syncOneReceiver != null) {
            try { app.unregisterReceiver(syncOneReceiver); } catch (RuntimeException ignored) {}
            syncOneReceiver = null;
        }
        wifiWorker.shutdownNow();
    }
}
