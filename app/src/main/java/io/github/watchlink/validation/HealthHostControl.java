package io.github.watchlink.validation;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** On-demand settings UI query, fallback toggle sync, and standalone watch test on Health :PhoneService. */
public final class HealthHostControl {
    public static final String QUERY = "QUERY", ENABLE = "ENABLE", DISABLE = "DISABLE",
            ENABLE_CALENDAR = "ENABLE_CALENDAR", DISABLE_CALENDAR = "DISABLE_CALENDAR",
            ENABLE_WIFI_AUTO = "ENABLE_WIFI_AUTO", DISABLE_WIFI_AUTO = "DISABLE_WIFI_AUTO",
            SYNC_CURRENT_WIFI = "SYNC_CURRENT_WIFI",
            ENABLE_LIVE_WINDOW = "ENABLE_LIVE_WINDOW", DISABLE_LIVE_WINDOW = "DISABLE_LIVE_WINDOW",
            TEST_LIVE_WINDOW = "TEST_LIVE_WINDOW", CANCEL_LIVE_WINDOW = "CANCEL_LIVE_WINDOW",
            TEST = "TEST", CANCEL_TEST = "CANCEL_TEST";
    private static final String ACTION = BuildConfig.APPLICATION_ID + ".cooperative.HEALTH_CONTROL_V1";
    private static final int ACK = 1;
    private static final long TIMEOUT = 5_000L;

    private static final Set<String> SYMBOL_SOURCES = Set.of("CACHE", "FAST_PATH", "DEX_SCAN", "DEGRADED", "UNKNOWN");
    private static final Set<String> WATCH_STATES = Set.of(
            "READY_SUPPORTED_ON", "READY_SUPPORTED_UNSET", "READY_SUPPORTED_OFF",
            "READY_UNSUPPORTED", "READY_DEVICE_MISMATCH", "WATCH_DISCONNECTED", "DEGRADED", "UNKNOWN");
    private static final Set<String> DISPATCH_MODES = Set.of(
            "START_SERVICE", "DIRECT_SDK", "BG_RESTRICTED", "DENIED", "FAILED", "NONE");
    private static final Set<String> ACTION_SOURCES = Set.of(
            "SDK_AND_INTENT", "SDK_ONLY", "INTENT_ONLY", "NONE");
    private static final Set<String> TEST_STATES = Set.of(
            "IDLE", "SENDING", "SENT", "SENT_DIRECT", "SERVICE_RECEIVED", "OUTBOUND",
            "SNOOZE", "DISMISS", "CLOSED", "WATCH_UNAVAILABLE", "BUSY",
            "BACKGROUND_RESTRICTED", "DENIED", "FAILED");
    private static final Set<String> WIFI_WATCH_SUPPORTS = Set.of(
            "SUPPORTED_NEW", "SUPPORTED_LEGACY", "SUPPORTED_UNKNOWN_CAP",
            "UNSUPPORTED", "WATCH_DISCONNECTED", "UNKNOWN");
    private static final Set<String> WIFI_RESULTS = Set.of(
            "NONE", "SENDING", "ACK_OK", "SENT_NO_ACK_HOOK", "ACK_FAIL", "ACK_TIMEOUT", "SKIPPED_DUP",
            "WATCH_DISCONNECTED", "UNSUPPORTED_WATCH", "UNSUPPORTED_NETWORK",
            "WIRELESS_UNANSWERED", "NOT_CONNECTED", "DISABLED", "DIRECT_UNAVAILABLE", "FAILED");
    private static final Set<String> WIFI_SOURCES = Set.of("NONE", "BULK_1A", "AUTO_1B");
    private static final Set<String> LIVE_WATCH_SUPPORTS = Set.of(
            "SUPPORTED_LIVE_LEVEL", "SUPPORTED_BASIC", "UNSUPPORTED", "WATCH_DISCONNECTED", "UNKNOWN");
    private static final Set<String> LIVE_RESULTS = Set.of(
            "NONE", "OPENED", "UPDATED", "CLOSED", "WATCH_DISCONNECTED",
            "UNSUPPORTED_WATCH", "DISABLED", "BG_RESTRICTED", "FAILED");
    private static final Set<String> LIVE_SOURCES = Set.of("NONE", "UMS", "DAEMON", "TEST");
    private static final Set<String> LIVE_TEST_STATES = Set.of(
            "IDLE", "OPENED", "UPDATED", "CLOSED", "WATCH_UNAVAILABLE", "FAILED");

    public record Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                        boolean serviceWitness, boolean sdkObserver, String symbolSource,
                        String watchState, String lastDispatchMode, String lastActionSource,
                        String testState, String testDispatchMode, String testActionSource,
                        boolean wifiAutoSyncEnabled, String wifiWatchSupport, boolean wifiAckReady,
                        int wifiSyncedCount, String wifiLastResult, String wifiLastSource, long wifiLastAt,
                        boolean liveWindowEnabled, String liveWatchSupport, int liveActiveCount,
                        String liveLastResult, String liveLastSource, String liveDispatchMode, String liveTestState) {
        public Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource,
                     boolean wifiAutoSyncEnabled, String wifiWatchSupport, boolean wifiAckReady,
                     int wifiSyncedCount, String wifiLastResult, String wifiLastSource, long wifiLastAt) {
            this(reachable, enabled, calendarEnabled, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    wifiAutoSyncEnabled, wifiWatchSupport, wifiAckReady, wifiSyncedCount, wifiLastResult,
                    wifiLastSource, wifiLastAt,
                    false, "UNKNOWN", 0, "NONE", "NONE", "NONE", "IDLE");
        }

        public Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource) {
            this(reachable, enabled, calendarEnabled, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    false, "UNKNOWN", false, 0, "NONE", "NONE", 0L,
                    false, "UNKNOWN", 0, "NONE", "NONE", "NONE", "IDLE");
        }

        public Reply(boolean reachable, boolean enabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource) {
            this(reachable, enabled, false, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    false, "UNKNOWN", false, 0, "NONE", "NONE", 0L,
                    false, "UNKNOWN", 0, "NONE", "NONE", "NONE", "IDLE");
        }

        public Reply {
            enabled = reachable && enabled;
            calendarEnabled = reachable && calendarEnabled;
            remotePrefs = reachable && remotePrefs;
            serviceWitness = reachable && serviceWitness;
            sdkObserver = reachable && sdkObserver;
            symbolSource = pick(reachable, SYMBOL_SOURCES, symbolSource, "UNKNOWN");
            watchState = pick(reachable, WATCH_STATES, watchState, "UNKNOWN");
            lastDispatchMode = pick(reachable, DISPATCH_MODES, lastDispatchMode, "NONE");
            lastActionSource = pick(reachable, ACTION_SOURCES, lastActionSource, "NONE");
            testState = pick(reachable, TEST_STATES, testState, "IDLE");
            testDispatchMode = pick(reachable, DISPATCH_MODES, testDispatchMode, "NONE");
            testActionSource = pick(reachable, ACTION_SOURCES, testActionSource, "NONE");
            wifiAutoSyncEnabled = reachable && wifiAutoSyncEnabled;
            wifiWatchSupport = pick(reachable, WIFI_WATCH_SUPPORTS, wifiWatchSupport, "UNKNOWN");
            wifiAckReady = reachable && wifiAckReady;
            wifiSyncedCount = reachable ? Math.max(0, wifiSyncedCount) : 0;
            wifiLastResult = pick(reachable, WIFI_RESULTS, wifiLastResult, "NONE");
            wifiLastSource = pick(reachable, WIFI_SOURCES, wifiLastSource, "NONE");
            wifiLastAt = reachable ? Math.max(0L, wifiLastAt) : 0L;
            liveWindowEnabled = reachable && liveWindowEnabled;
            liveWatchSupport = pick(reachable, LIVE_WATCH_SUPPORTS, liveWatchSupport, "UNKNOWN");
            liveActiveCount = reachable ? Math.max(0, liveActiveCount) : 0;
            liveLastResult = pick(reachable, LIVE_RESULTS, liveLastResult, "NONE");
            liveLastSource = pick(reachable, LIVE_SOURCES, liveLastSource, "NONE");
            liveDispatchMode = pick(reachable, DISPATCH_MODES, liveDispatchMode, "NONE");
            liveTestState = pick(reachable, LIVE_TEST_STATES, liveTestState, "IDLE");
        }

        private static String pick(boolean reachable, Set<String> allowed, String value, String fallback) {
            return reachable && value != null && allowed.contains(value) ? value : fallback;
        }
    }

    public interface CommandHandler { Reply handle(String command); }

    private static boolean validCommand(String value) {
        return QUERY.equals(value) || ENABLE.equals(value) || DISABLE.equals(value)
                || ENABLE_CALENDAR.equals(value) || DISABLE_CALENDAR.equals(value)
                || ENABLE_WIFI_AUTO.equals(value) || DISABLE_WIFI_AUTO.equals(value)
                || SYNC_CURRENT_WIFI.equals(value)
                || ENABLE_LIVE_WINDOW.equals(value) || DISABLE_LIVE_WINDOW.equals(value)
                || TEST_LIVE_WINDOW.equals(value) || CANCEL_LIVE_WINDOW.equals(value)
                || TEST.equals(value) || CANCEL_TEST.equals(value);
    }

    public static Reply unavailable() {
        return new Reply(false, false, false, false, false, false, "UNKNOWN",
                "UNKNOWN", "NONE", "NONE", "IDLE", "NONE", "NONE",
                false, "UNKNOWN", false, 0, "NONE", "NONE", 0L,
                false, "UNKNOWN", 0, "NONE", "NONE", "NONE", "IDLE");
    }

    @RequiresApi(34)
    public static BroadcastReceiver register(Context host, int ownerUid, Executor worker, CommandHandler handler) {
        if (ownerUid < 0 || !TargetProfile.HEALTH.packageName.equals(host.getPackageName())) return null;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!isOrderedBroadcast() || getSentFromUid() != ownerUid
                        || !BuildConfig.APPLICATION_ID.equals(getSentFromPackage())
                        || !ACTION.equals(intent.getAction()) || intent.getComponent() != null
                        || !host.getPackageName().equals(intent.getPackage())
                        || (intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) return;
                String command = intent.getStringExtra("command");
                String id = intent.getStringExtra("request");
                long wall = intent.getLongExtra("at", 0), elapsed = intent.getLongExtra("elapsed", -1);
                if (!validCommand(command) || !DailyAlarmWire.isRun(id)) return;
                PendingResult pending = goAsync();
                try {
                    worker.execute(() -> {
                        try {
                            if (!DailyAlarmWire.inWindow(wall, elapsed, TIMEOUT)) return;
                            Reply reply = handler.handle(command);
                            if (reply == null || !reply.reachable()) return;
                            Bundle details = new Bundle();
                            details.putString("request", id);
                            details.putBoolean("enabled", reply.enabled());
                            details.putBoolean("calendarEnabled", reply.calendarEnabled());
                            details.putBoolean("remotePrefs", reply.remotePrefs());
                            details.putBoolean("serviceWitness", reply.serviceWitness());
                            details.putBoolean("sdkObserver", reply.sdkObserver());
                            details.putString("symbolSource", reply.symbolSource());
                            details.putString("watchState", reply.watchState());
                            details.putString("lastDispatchMode", reply.lastDispatchMode());
                            details.putString("lastActionSource", reply.lastActionSource());
                            details.putString("testState", reply.testState());
                            details.putString("testDispatchMode", reply.testDispatchMode());
                            details.putString("testActionSource", reply.testActionSource());
                            details.putBoolean("wifiAutoSyncEnabled", reply.wifiAutoSyncEnabled());
                            details.putString("wifiWatchSupport", reply.wifiWatchSupport());
                            details.putBoolean("wifiAckReady", reply.wifiAckReady());
                            details.putInt("wifiSyncedCount", reply.wifiSyncedCount());
                            details.putString("wifiLastResult", reply.wifiLastResult());
                            details.putString("wifiLastSource", reply.wifiLastSource());
                            details.putLong("wifiLastAt", reply.wifiLastAt());
                            details.putBoolean("liveWindowEnabled", reply.liveWindowEnabled());
                            details.putString("liveWatchSupport", reply.liveWatchSupport());
                            details.putInt("liveActiveCount", reply.liveActiveCount());
                            details.putString("liveLastResult", reply.liveLastResult());
                            details.putString("liveLastSource", reply.liveLastSource());
                            details.putString("liveDispatchMode", reply.liveDispatchMode());
                            details.putString("liveTestState", reply.liveTestState());
                            pending.setResult(ACK, null, details);
                        } catch (RuntimeException e) {
                            ProbeLog.failure("HEALTH_HOST_CONTROL_FAILED", e);
                        } finally { pending.finish(); }
                    });
                } catch (RejectedExecutionException unavailable) { pending.finish(); }
            }
        };
        host.registerReceiver(receiver, new IntentFilter(ACTION), ClockActionWire.PERMISSION, null, Context.RECEIVER_EXPORTED);
        return receiver;
    }

    /** Always replies once on the main thread. */
    public static void send(Context owner, String command, Consumer<Reply> callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (Build.VERSION.SDK_INT < 34 || !validCommand(command)
                || !BuildConfig.APPLICATION_ID.equals(owner.getPackageName())
                || owner.getApplicationInfo().uid != Process.myUid()
                || !SenderVerifier.trustedReminderTarget(owner, TargetProfile.HEALTH)) {
            main.post(() -> callback.accept(unavailable()));
            return;
        }
        sendApi34(owner, command, main, callback);
    }

    @RequiresApi(34)
    private static void sendApi34(Context owner, String command, Handler main, Consumer<Reply> callback) {
        AtomicBoolean finished = new AtomicBoolean();
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) callback.accept(unavailable());
        };
        String id = UUID.randomUUID().toString();
        Intent request = DailyAlarmWire.route(ACTION, TargetProfile.HEALTH.packageName)
                .putExtra("request", id).putExtra("command", command)
                .putExtra("at", System.currentTimeMillis()).putExtra("elapsed", SystemClock.elapsedRealtime());
        main.postDelayed(timeout, TIMEOUT);
        try {
            owner.sendOrderedBroadcast(request, null,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
                    new BroadcastReceiver() {
                        @Override public void onReceive(Context context, Intent response) {
                            if (!finished.compareAndSet(false, true)) return;
                            main.removeCallbacks(timeout);
                            Bundle data = getResultExtras(false);
                            if (getResultCode() == ACK && data != null && id.equals(data.getString("request"))
                                    && data.containsKey("enabled")) {
                                callback.accept(new Reply(true,
                                        data.getBoolean("enabled"),
                                        data.getBoolean("calendarEnabled", false),
                                        data.getBoolean("remotePrefs"),
                                        data.getBoolean("serviceWitness"),
                                        data.getBoolean("sdkObserver"),
                                        data.getString("symbolSource"),
                                        data.getString("watchState"),
                                        data.getString("lastDispatchMode"),
                                        data.getString("lastActionSource"),
                                        data.getString("testState"),
                                        data.getString("testDispatchMode"),
                                        data.getString("testActionSource"),
                                        data.getBoolean("wifiAutoSyncEnabled", false),
                                        data.getString("wifiWatchSupport"),
                                        data.getBoolean("wifiAckReady", false),
                                        data.getInt("wifiSyncedCount", 0),
                                        data.getString("wifiLastResult"),
                                        data.getString("wifiLastSource"),
                                        data.getLong("wifiLastAt", 0L),
                                        data.getBoolean("liveWindowEnabled", false),
                                        data.getString("liveWatchSupport"),
                                        data.getInt("liveActiveCount", 0),
                                        data.getString("liveLastResult"),
                                        data.getString("liveLastSource"),
                                        data.getString("liveDispatchMode"),
                                        data.getString("liveTestState")));
                            } else callback.accept(unavailable());
                        }
                    }, main, 0, null, null);
        } catch (RuntimeException failed) {
            main.removeCallbacks(timeout);
            main.post(timeout);
        }
    }

    private HealthHostControl() {}
}
