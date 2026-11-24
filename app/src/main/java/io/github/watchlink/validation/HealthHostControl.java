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
            ENABLE_VOIP = "ENABLE_VOIP", DISABLE_VOIP = "DISABLE_VOIP",
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

    public record Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean voipEnabled,
                        boolean remotePrefs, boolean serviceWitness, boolean sdkObserver, boolean voipAllowlistReady,
                        String symbolSource, String watchState, String lastDispatchMode, String lastActionSource,
                        String testState, String testDispatchMode, String testActionSource) {
        public Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource) {
            this(reachable, enabled, calendarEnabled, false, remotePrefs, serviceWitness, sdkObserver, false,
                    symbolSource, watchState, lastDispatchMode, lastActionSource,
                    testState, testDispatchMode, testActionSource);
        }

        public Reply(boolean reachable, boolean enabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource) {
            this(reachable, enabled, false, false, remotePrefs, serviceWitness, sdkObserver, false,
                    symbolSource, watchState, lastDispatchMode, lastActionSource,
                    testState, testDispatchMode, testActionSource);
        }

        public Reply {
            enabled = reachable && enabled;
            calendarEnabled = reachable && calendarEnabled;
            voipEnabled = reachable && voipEnabled;
            remotePrefs = reachable && remotePrefs;
            serviceWitness = reachable && serviceWitness;
            sdkObserver = reachable && sdkObserver;
            voipAllowlistReady = reachable && voipAllowlistReady;
            symbolSource = pick(reachable, SYMBOL_SOURCES, symbolSource, "UNKNOWN");
            watchState = pick(reachable, WATCH_STATES, watchState, "UNKNOWN");
            lastDispatchMode = pick(reachable, DISPATCH_MODES, lastDispatchMode, "NONE");
            lastActionSource = pick(reachable, ACTION_SOURCES, lastActionSource, "NONE");
            testState = pick(reachable, TEST_STATES, testState, "IDLE");
            testDispatchMode = pick(reachable, DISPATCH_MODES, testDispatchMode, "NONE");
            testActionSource = pick(reachable, ACTION_SOURCES, testActionSource, "NONE");
        }

        private static String pick(boolean reachable, Set<String> allowed, String value, String fallback) {
            return reachable && value != null && allowed.contains(value) ? value : fallback;
        }
    }

    public interface CommandHandler { Reply handle(String command); }

    private static boolean validCommand(String value) {
        return QUERY.equals(value) || ENABLE.equals(value) || DISABLE.equals(value)
                || ENABLE_CALENDAR.equals(value) || DISABLE_CALENDAR.equals(value)
                || ENABLE_VOIP.equals(value) || DISABLE_VOIP.equals(value)
                || TEST.equals(value) || CANCEL_TEST.equals(value);
    }

    public static Reply unavailable() {
        return new Reply(false, false, false, false, false, false, false, false, "UNKNOWN",
                "UNKNOWN", "NONE", "NONE", "IDLE", "NONE", "NONE");
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
                            details.putBoolean("voipEnabled", reply.voipEnabled());
                            details.putBoolean("remotePrefs", reply.remotePrefs());
                            details.putBoolean("serviceWitness", reply.serviceWitness());
                            details.putBoolean("sdkObserver", reply.sdkObserver());
                            details.putBoolean("voipAllowlistReady", reply.voipAllowlistReady());
                            details.putString("symbolSource", reply.symbolSource());
                            details.putString("watchState", reply.watchState());
                            details.putString("lastDispatchMode", reply.lastDispatchMode());
                            details.putString("lastActionSource", reply.lastActionSource());
                            details.putString("testState", reply.testState());
                            details.putString("testDispatchMode", reply.testDispatchMode());
                            details.putString("testActionSource", reply.testActionSource());
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
                                        data.getBoolean("voipEnabled", false),
                                        data.getBoolean("remotePrefs"),
                                        data.getBoolean("serviceWitness"),
                                        data.getBoolean("sdkObserver"),
                                        data.getBoolean("voipAllowlistReady", false),
                                        data.getString("symbolSource"),
                                        data.getString("watchState"),
                                        data.getString("lastDispatchMode"),
                                        data.getString("lastActionSource"),
                                        data.getString("testState"),
                                        data.getString("testDispatchMode"),
                                        data.getString("testActionSource")));
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
