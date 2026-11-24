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

/** On-demand UI query and fallback toggle sync for Huawei Health :DaemonService (notification pictures & VoIP). */
public final class HealthDaemonControl {
    public static final String QUERY = "QUERY",
            ENABLE_PICTURE = "ENABLE_PICTURE", DISABLE_PICTURE = "DISABLE_PICTURE",
            ENABLE_VOIP = "ENABLE_VOIP", DISABLE_VOIP = "DISABLE_VOIP";
    private static final String ACTION = BuildConfig.APPLICATION_ID + ".cooperative.HEALTH_DAEMON_CONTROL_V1";
    private static final int ACK = 1;
    private static final long TIMEOUT = 5_000L;

    private static final Set<String> SYMBOL_SOURCES = Set.of("CACHE", "DEX_SCAN", "DEGRADED", "UNKNOWN");
    private static final Set<String> PICTURE_RESULTS = Set.of(
            "NONE", "ENCODED_MESSAGING", "ENCODED_BIG_PICTURE", "SKIPPED_UNSUPPORTED", "FAILED");
    private static final Set<String> VOIP_RESULTS = Set.of(
            "NONE", "DETECTED_CALL_STYLE", "DETECTED_CATEGORY_CALL", "ONGOING_SUPPRESSED",
            "REJECTED_BY_WATCH", "REJECT_FAILED");

    public record Reply(boolean reachable,
                        boolean pictureEnabled,
                        boolean voipEnabled,
                        boolean remotePrefs,
                        boolean pictureHookReady,
                        boolean voipHookReady,
                        String symbolSource,
                        boolean pictureSupported,
                        int pictureWidth,
                        int pictureNumber,
                        boolean voipSupported,
                        long lastPictureAt,
                        String lastPictureResult,
                        long lastVoipAt,
                        String lastVoipResult) {
        public Reply {
            pictureEnabled = reachable && pictureEnabled;
            voipEnabled = reachable && voipEnabled;
            remotePrefs = reachable && remotePrefs;
            pictureHookReady = reachable && pictureHookReady;
            voipHookReady = reachable && voipHookReady;
            symbolSource = pick(reachable, SYMBOL_SOURCES, symbolSource, "UNKNOWN");
            pictureSupported = reachable && pictureSupported;
            pictureWidth = reachable && pictureWidth > 0 && pictureWidth <= 2048 ? pictureWidth : 0;
            pictureNumber = reachable && pictureNumber > 0 && pictureNumber <= 100 ? pictureNumber : 0;
            voipSupported = reachable && voipSupported;
            lastPictureAt = reachable && lastPictureAt > 0 ? lastPictureAt : 0L;
            lastPictureResult = pick(reachable, PICTURE_RESULTS, lastPictureResult, "NONE");
            lastVoipAt = reachable && lastVoipAt > 0 ? lastVoipAt : 0L;
            lastVoipResult = pick(reachable, VOIP_RESULTS, lastVoipResult, "NONE");
        }

        private static String pick(boolean reachable, Set<String> allowed, String value, String fallback) {
            return reachable && value != null && allowed.contains(value) ? value : fallback;
        }
    }

    public interface CommandHandler { Reply handle(String command); }

    private static boolean validCommand(String value) {
        return QUERY.equals(value)
                || ENABLE_PICTURE.equals(value) || DISABLE_PICTURE.equals(value)
                || ENABLE_VOIP.equals(value) || DISABLE_VOIP.equals(value);
    }

    public static Reply unavailable() {
        return new Reply(false, false, false, false, false, false, "UNKNOWN",
                false, 0, 0, false, 0L, "NONE", 0L, "NONE");
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
                            details.putBoolean("pictureEnabled", reply.pictureEnabled());
                            details.putBoolean("voipEnabled", reply.voipEnabled());
                            details.putBoolean("remotePrefs", reply.remotePrefs());
                            details.putBoolean("pictureHookReady", reply.pictureHookReady());
                            details.putBoolean("voipHookReady", reply.voipHookReady());
                            details.putString("symbolSource", reply.symbolSource());
                            details.putBoolean("pictureSupported", reply.pictureSupported());
                            details.putInt("pictureWidth", reply.pictureWidth());
                            details.putInt("pictureNumber", reply.pictureNumber());
                            details.putBoolean("voipSupported", reply.voipSupported());
                            details.putLong("lastPictureAt", reply.lastPictureAt());
                            details.putString("lastPictureResult", reply.lastPictureResult());
                            details.putLong("lastVoipAt", reply.lastVoipAt());
                            details.putString("lastVoipResult", reply.lastVoipResult());
                            pending.setResult(ACK, null, details);
                        } catch (RuntimeException e) {
                            ProbeLog.failure("HEALTH_DAEMON_CONTROL_FAILED", e);
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
                                    && data.containsKey("pictureEnabled")) {
                                callback.accept(new Reply(true,
                                        data.getBoolean("pictureEnabled"),
                                        data.getBoolean("voipEnabled"),
                                        data.getBoolean("remotePrefs"),
                                        data.getBoolean("pictureHookReady"),
                                        data.getBoolean("voipHookReady"),
                                        data.getString("symbolSource"),
                                        data.getBoolean("pictureSupported"),
                                        data.getInt("pictureWidth"),
                                        data.getInt("pictureNumber"),
                                        data.getBoolean("voipSupported"),
                                        data.getLong("lastPictureAt"),
                                        data.getString("lastPictureResult"),
                                        data.getLong("lastVoipAt"),
                                        data.getString("lastVoipResult")));
                            } else callback.accept(unavailable());
                        }
                    }, main, 0, null, null);
        } catch (RuntimeException failed) {
            main.removeCallbacks(timeout);
            main.post(timeout);
        }
    }

    private HealthDaemonControl() {}
}
