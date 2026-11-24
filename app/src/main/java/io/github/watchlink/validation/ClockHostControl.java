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

/** On-demand settings UI query/sync with the running Clock host. Never wakes Clock in the background. */
public final class ClockHostControl {
    public static final String QUERY = "QUERY", ENABLE = "ENABLE", DISABLE = "DISABLE";
    private static final String ACTION = BuildConfig.APPLICATION_ID + ".cooperative.CLOCK_CONTROL_V2";
    private static final int ACK = 1;
    private static final long TIMEOUT = 5_000L;

    private static final Set<String> CONTROLS = Set.of("READY", "DISABLED", "UNAVAILABLE");
    private static final Set<String> SYMBOL_SOURCES = Set.of("CACHE", "DEX_SCAN", "UNKNOWN");
    private static final Set<String> DELIVERIES = Set.of(
            "SENDING", "SENT", "SENT_DIRECT", "SERVICE_RECEIVED", "OUTBOUND", "ENDED_BEFORE_OPEN",
            "DISABLED", "WATCH_UNAVAILABLE", "HEALTH_UNANSWERED", "HEALTH_STOPPED",
            "BACKGROUND_RESTRICTED", "DENIED", "BUSY", "FAILED", "UNAVAILABLE");
    private static final Set<String> DISPATCH_MODES = Set.of("START_SERVICE", "DIRECT_SDK");
    private static final Set<String> ACTIONS = Set.of("SNOOZE", "DISMISS");
    private static final Set<String> ACTION_SOURCES = Set.of("SDK_AND_INTENT", "SDK_ONLY", "INTENT_ONLY");
    private static final Set<String> ACTION_RESULTS = Set.of("APPLIED", "VERIFIED", "REJECTED", "UNCERTAIN");
    private static final Set<String> CLOSE_RESULTS = Set.of("SENT", "OUTBOUND", "UNCERTAIN");

    public record Reply(boolean reachable, boolean enabled, String result,
                        boolean remotePrefs, boolean localClockControl, String symbolSource,
                        long lastRingAt, String delivery, String dispatchMode,
                        String action, String actionSource, String actionResult, String close) {
        public Reply(boolean reachable, boolean enabled, String result, boolean remotePrefs, boolean localClockControl) {
            this(reachable, enabled, result, remotePrefs, localClockControl, "UNKNOWN", 0, "", "", "", "", "", "");
        }

        public Reply(boolean reachable, boolean enabled, String result, boolean remotePrefs, boolean localClockControl,
                     String symbolSource) {
            this(reachable, enabled, result, remotePrefs, localClockControl, symbolSource, 0, "", "", "", "", "", "");
        }

        public Reply {
            enabled = reachable && enabled;
            result = reachable && result != null && CONTROLS.contains(result) ? result : "UNAVAILABLE";
            remotePrefs = reachable && remotePrefs;
            localClockControl = reachable && localClockControl;
            symbolSource = reachable && symbolSource != null && SYMBOL_SOURCES.contains(symbolSource)
                    ? symbolSource : "UNKNOWN";
            if (!reachable || lastRingAt < 0 || lastRingAt > System.currentTimeMillis()) lastRingAt = 0;
            delivery = history(lastRingAt, DELIVERIES, delivery);
            dispatchMode = history(lastRingAt, DISPATCH_MODES, dispatchMode);
            action = history(lastRingAt, ACTIONS, action);
            actionSource = history(lastRingAt, ACTION_SOURCES, actionSource);
            actionResult = history(lastRingAt, ACTION_RESULTS, actionResult);
            close = history(lastRingAt, CLOSE_RESULTS, close);
        }

        private static String history(long at, Set<String> known, String value) {
            return at > 0 && value != null && known.contains(value) ? value : "";
        }
    }

    public interface CommandHandler { Reply handle(String command); }

    private static boolean validCommand(String value) {
        return QUERY.equals(value) || ENABLE.equals(value) || DISABLE.equals(value);
    }

    private static Reply unavailable() {
        return new Reply(false, false, "UNAVAILABLE", false, false, "UNKNOWN");
    }

    @RequiresApi(34)
    public static BroadcastReceiver register(Context host, int ownerUid, Executor worker, CommandHandler handler) {
        if (ownerUid < 0 || !TargetProfile.CLOCK.packageName.equals(host.getPackageName())) return null;
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
                            details.putString("result", reply.result());
                            details.putBoolean("remotePrefs", reply.remotePrefs());
                            details.putBoolean("localClockControl", reply.localClockControl());
                            details.putString("symbolSource", reply.symbolSource());
                            details.putLong("lastRingAt", reply.lastRingAt());
                            details.putString("delivery", reply.delivery());
                            details.putString("dispatchMode", reply.dispatchMode());
                            details.putString("action", reply.action());
                            details.putString("actionSource", reply.actionSource());
                            details.putString("actionResult", reply.actionResult());
                            details.putString("close", reply.close());
                            pending.setResult(ACK, null, details);
                        } catch (RuntimeException e) {
                            ProbeLog.failure("CLOCK_HOST_CONTROL_FAILED", e);
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
                || !SenderVerifier.trustedReminderTarget(owner, TargetProfile.CLOCK)) {
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
        Intent request = DailyAlarmWire.route(ACTION, TargetProfile.CLOCK.packageName)
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
                                    && data.getString("result") != null && data.containsKey("enabled")) {
                                callback.accept(new Reply(true,
                                        data.getBoolean("enabled"),
                                        data.getString("result"),
                                        data.getBoolean("remotePrefs"),
                                        data.getBoolean("localClockControl"),
                                        data.getString("symbolSource"),
                                        data.getLong("lastRingAt"),
                                        data.getString("delivery"),
                                        data.getString("dispatchMode"),
                                        data.getString("action"),
                                        data.getString("actionSource"),
                                        data.getString("actionResult"),
                                        data.getString("close")));
                            } else callback.accept(unavailable());
                        }
                    }, main, 0, null, null);
        } catch (RuntimeException failed) {
            main.removeCallbacks(timeout);
            main.post(timeout);
        }
    }

    private ClockHostControl() {}
}
