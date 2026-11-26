package io.github.watchlink.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.validation.ClockActionWire.Command;
import io.github.watchlink.validation.ClockHostControl;
import io.github.watchlink.validation.DailyAlarmWire;
import io.github.watchlink.validation.HuaweiReminderService;
import io.github.watchlink.validation.PhoneRingWire;
import io.github.watchlink.timer.TimerRing;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Clock-side occurrence tracker that forwards real rings directly to Huawei Health (:PhoneService)
 * without ever starting WatchLink or requiring AUTH_STATE_REQ on Clock.
 */
@RequiresApi(34)
final class ClockDailyAlarms extends BroadcastReceiver {
    static final int MIN_NOTICE = 0x50000000;
    static final int MAX_NOTICE = MIN_NOTICE + 0x0fffffff;
    private static final int HISTORY = 32;
    private static final long ACK_TIMEOUT_MS = 10_000L;

    private final Context app;
    private final HostSettings settings;
    private final boolean localClockControl;
    private final String symbolSource;
    private final Executor worker;
    private final ClockLiveActions clock;
    private final SharedPreferences ledger;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver controlReceiver;
    private volatile Active active;

    private static final class Active {
        final PhoneRingWire.Event ring;
        final String title;
        final String run = UUID.randomUUID().toString();
        final long at, elapsed;
        final int minutes, id;
        boolean acked, serviceReceived, outbound, closeOutbound;
        volatile boolean answered;

        Active(PhoneRingWire.Event ring, String title, int id) {
            this.ring = ring;
            this.title = title;
            this.id = id;
            this.at = ring.at();
            this.elapsed = ring.elapsed();
            this.minutes = ring.snoozeMinutes();
        }

        String openIdentifier() {
            return HuaweiReminderService.dailyIdentifier(run, id, minutes / 5);
        }
    }

    ClockDailyAlarms(Context app, int ownerUid, HostSettings settings, boolean localClockControl,
                     String symbolSource, Executor worker, ClockLiveActions clock) {
        this.app = app;
        this.settings = settings;
        this.localClockControl = localClockControl;
        this.symbolSource = symbolSource == null ? "UNKNOWN" : symbolSource;
        this.worker = worker;
        this.clock = clock;
        this.ledger = app.getSharedPreferences(BuildConfig.APPLICATION_ID + ".clock_reminders_v3", Context.MODE_PRIVATE);
        this.settings.onDisabled(() -> enqueue(() -> {
            if (active != null) finish(active, true);
        }));
        enqueue(this::restore);
        IntentFilter filter = new IntentFilter(DailyAlarmWire.STATUS);
        filter.addAction(DailyAlarmWire.HEALTH_READY);
        app.registerReceiver(this, filter, Context.RECEIVER_EXPORTED);
        try {
            controlReceiver = ClockHostControl.register(app, ownerUid, worker, this::control);
        } catch (RuntimeException failure) {
            app.unregisterReceiver(this);
            throw failure;
        }
    }

    boolean hasActiveRing() {
        return active != null;
    }

    void close() {
        settings.onDisabled(null);
        try { app.unregisterReceiver(this); } catch (RuntimeException ignored) {}
        if (controlReceiver != null) {
            try { app.unregisterReceiver(controlReceiver); } catch (RuntimeException ignored) {}
        }
        enqueue(() -> { if (active != null) finish(active, true); });
    }

    private void restore() {
        int id = ledger.getInt("activeId", 0);
        if (id < MIN_NOTICE || id > MAX_NOTICE) return;
        String run = ledger.getString("lastRun", "");
        if (DailyAlarmWire.isRun(run)) endHealth(run, id, true);
        ledger.edit().remove("activeId").apply();
    }

    static int nextNotice(int last) {
        if (last < MIN_NOTICE - 1 || last >= MAX_NOTICE) return 0;
        return last + 1;
    }

    static boolean freshStatus(long at, long elapsed, long now, long monotonic, long since) {
        return elapsed >= since && DailyAlarmWire.inWindow(at, elapsed, 8_000, now, monotonic);
    }

    private void enqueue(Runnable job) {
        try {
            worker.execute(() -> {
                try { job.run(); }
                catch (RuntimeException failure) { ProbeLog.failure("DAILY_CLOCK_EVENT_FAILED", failure); }
            });
        } catch (RejectedExecutionException busy) {
            ProbeLog.failure("DAILY_CLOCK_QUEUE_FULL", busy);
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !TargetProfile.CLOCK.packageName.equals(intent.getPackage())
                || !TargetProfile.HEALTH.packageName.equals(getSentFromPackage())) return;
        int uid = getSentFromUid();
        enqueue(() -> {
            if (!SenderVerifier.verifyReminderSender(app, TargetProfile.HEALTH, uid, TargetProfile.HEALTH.packageName)) return;
            if (DailyAlarmWire.STATUS.equals(intent.getAction())) {
                if (TimerRing.TYPE.equals(intent.getStringExtra(TimerRing.TYPE_EXTRA))) return;
                status(intent);
            } else if (DailyAlarmWire.HEALTH_READY.equals(intent.getAction())) {
                Active current = active;
                if (current != null && !current.acked && valid(current)) sendOpen(current);
            }
        });
    }

    private ClockHostControl.Reply control(String command) {
        if (ClockHostControl.DISABLE.equals(command)) {
            if (!settings.setLocalFallback(false)) {
                return new ClockHostControl.Reply(true, settings.isEnabled(), "UNAVAILABLE",
                        settings.remoteAvailable(), localClockControl, symbolSource);
            }
            if (active != null) finish(active, true);
            return buildReply("DISABLED");
        }
        if (ClockHostControl.ENABLE.equals(command)) {
            if (!settings.setLocalFallback(true)) {
                return new ClockHostControl.Reply(true, settings.isEnabled(), "UNAVAILABLE",
                        settings.remoteAvailable(), localClockControl, symbolSource);
            }
        }
        return buildReply("READY");
    }

    private ClockHostControl.Reply buildReply(String result) {
        return new ClockHostControl.Reply(
                true,
                settings.isEnabled(),
                result,
                settings.remoteAvailable(),
                localClockControl,
                symbolSource,
                ledger.getLong("lastRingAt", 0),
                ledger.getString("delivery", ""),
                ledger.getString("dispatchMode", ""),
                ledger.getString("action", ""),
                ledger.getString("actionSource", ""),
                ledger.getString("result", ""),
                ledger.getString("close", ""));
    }

    void occurrence(PhoneRingWire.Event event) {
        Active current = active;
        if (!event.ringing()) {
            if (current != null && current.ring.key().equals(event.key())) finish(current, true);
            return;
        }
        if (!settings.isEnabled() || !event.fresh(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                || !liveRing(event)) return;
        if (Arrays.asList(ledger.getString("rings", "").split(",")).contains(event.key())) return;
        if (current != null) finish(current, true);
        begin(event);
    }

    private void begin(PhoneRingWire.Event ring) {
        int id = nextNotice(ledger.getInt("lastId", MIN_NOTICE - 1));
        if (id == 0) id = MIN_NOTICE;
        String title = DailyReminderTitle.forWatch(clock.displayTitle(ring));
        Active next = new Active(ring, title == null ? HuaweiReminderService.ALARM_TITLE : title, id);
        String keys = ring.key() + "," + ledger.getString("rings", "");
        boolean saved = ledger.edit()
                .putInt("lastId", id)
                .putInt("activeId", id)
                .putString("lastRun", next.run)
                .putString("rings", keys.substring(0, Math.min(keys.length(), 65 * HISTORY)))
                .putLong("lastRingAt", next.at)
                .putString("lastRealRun", next.run)
                .putInt("lastRealId", next.id)
                .putString("delivery", "SENDING")
                .remove("dispatchMode").remove("action").remove("actionSource").remove("result").remove("close")
                .commit();
        if (!saved) return;
        active = next;
        try {
            if (!valid(next)) {
                delivery("ENDED_BEFORE_OPEN");
                finish(next, false);
                return;
            }
            sendOpen(next);
            main.postDelayed(() -> enqueue(() -> {
                if (active == next && !next.acked) {
                    delivery("HEALTH_UNANSWERED");
                    ProbeLog.info("DAILY_CLOCK_HEALTH_UNANSWERED");
                    finish(next, false);
                }
            }), ACK_TIMEOUT_MS);
            main.postDelayed(() -> enqueue(() -> {
                if (active == next) finish(next, true);
            }), DailyAlarmWire.MAX_WINDOW);
        } catch (RuntimeException failure) {
            delivery("FAILED");
            finish(next, false);
            ProbeLog.failure("DAILY_CLOCK_OPEN_FAILED", failure);
        }
    }

    private void sendOpen(Active session) {
        DailyAlarmWire.send(app, DailyAlarmWire.route(DailyAlarmWire.OPEN, TargetProfile.HEALTH.packageName)
                .putExtra("run", session.run)
                .putExtra("reminder", session.id)
                .putExtra("at", session.at)
                .putExtra("elapsed", session.elapsed)
                .putExtra("title", session.title)
                .putExtra("snoozeMinutes", session.minutes)
                .putExtra("openIdentifier", session.openIdentifier()));
        ProbeLog.info("DAILY_CLOCK_OPEN_SENT");
    }

    private void endHealth(String run, int id, boolean closeWatch) {
        try {
            DailyAlarmWire.send(app, DailyAlarmWire.route(DailyAlarmWire.END, TargetProfile.HEALTH.packageName)
                    .putExtra("run", run)
                    .putExtra("reminder", id)
                    .putExtra("closeWatch", closeWatch)
                    .putExtra("at", System.currentTimeMillis())
                    .putExtra("elapsed", SystemClock.elapsedRealtime()));
            if (closeWatch && run.equals(ledger.getString("lastRealRun", "")) && id == ledger.getInt("lastRealId", -1)
                    && !"OUTBOUND".equals(ledger.getString("close", ""))) {
                ledger.edit().putString("close", "SENT").apply();
            }
        } catch (RuntimeException failure) {
            ProbeLog.failure("DAILY_CLOCK_END_UNCONFIRMED", failure);
        }
    }

    private boolean liveRing(PhoneRingWire.Event start) {
        try { return clock.verifiedCurrent(start); }
        catch (Exception failure) {
            ProbeLog.failure("DAILY_CLOCK_RING_CHANGED", failure);
            return false;
        }
    }

    private boolean valid(Active session) {
        return DailyAlarmWire.inWindow(session.at, session.elapsed, PhoneRingWire.MAX_AGE)
                && settings.isEnabled() && liveRing(session.ring);
    }

    private void delivery(String kind) {
        ledger.edit().putString("delivery", kind).apply();
    }

    private void status(Intent intent) {
        String kind = intent.getStringExtra("kind");
        long at = intent.getLongExtra("at", 0), elapsed = intent.getLongExtra("elapsed", -1);
        long now = System.currentTimeMillis(), monotonic = SystemClock.elapsedRealtime();
        String run = intent.getStringExtra("run");
        int id = intent.getIntExtra("reminder", 0);
        String dispatchMode = intent.getStringExtra("dispatchMode");
        String actionSource = intent.getStringExtra("actionSource");
        if (kind == null || !freshStatus(at, elapsed, now, monotonic, 0)) return;

        boolean isLastReal = run != null && run.equals(ledger.getString("lastRealRun", ""))
                && id == ledger.getInt("lastRealId", -1);
        if ("CLOSE_OUTBOUND".equals(kind) && isLastReal) {
            ledger.edit().putString("close", "OUTBOUND").apply();
        }
        if ("ACTION_DUAL_CONFIRMED".equals(kind) && isLastReal) {
            ledger.edit().putString("actionSource", "SDK_AND_INTENT").apply();
            return;
        }

        Active current = active;
        if (current == null || !current.run.equals(run) || current.id != id || elapsed < current.elapsed) return;

        switch (kind) {
            case "SENT", "SENT_DIRECT" -> {
                current.acked = true;
                SharedPreferences.Editor edit = ledger.edit();
                if (!current.outbound && !current.serviceReceived) edit.putString("delivery", kind);
                if (dispatchMode != null && !dispatchMode.isEmpty()) edit.putString("dispatchMode", dispatchMode);
                edit.apply();
            }
            case "SERVICE_RECEIVED" -> {
                current.acked = true;
                current.serviceReceived = true;
                if (!current.outbound) delivery(kind);
            }
            case "OUTBOUND" -> {
                current.acked = true;
                current.outbound = true;
                delivery(kind);
                ProbeLog.info("DAILY_CLOCK_HEALTH_OUTBOUND");
            }
            case "DISMISS", "SNOOZE" -> {
                if ((!current.outbound && !current.serviceReceived && !current.acked)
                        || current.answered || !settings.isEnabled() || !liveRing(current.ring)) return;
                current.answered = true;
                SharedPreferences.Editor edit = ledger.edit().putString("action", kind);
                if (actionSource != null && !actionSource.isEmpty()) edit.putString("actionSource", actionSource);
                edit.apply();
                clock.watchAction(app, new Command(current.run, current.ring.key(), kind, at, elapsed));
            }
            case "CLOSE_OUTBOUND" -> {
                current.closeOutbound = true;
                finish(current, false);
            }
            case "WATCH_UNAVAILABLE", "DISABLED", "BACKGROUND_RESTRICTED", "DENIED", "BUSY", "FAILED", "UNAVAILABLE" -> {
                current.acked = true;
                delivery(kind);
                ProbeLog.info("DAILY_CLOCK_STATUS result=" + kind);
                finish(current, false);
            }
            default -> {}
        }
    }

    void actionResult(Command command, String status) {
        enqueue(() -> {
            if (!command.run().equals(ledger.getString("lastRealRun", ""))) return;
            String prior = ledger.getString("result", "");
            if ("VERIFIED".equals(prior) || "UNCERTAIN".equals(prior) || "REJECTED".equals(prior)) return;
            ledger.edit().putString("action", command.action()).putString("result", status).apply();
        });
    }

    boolean allows(Command command) {
        Active current = active;
        return current != null && current.answered
                && current.run.equals(command.run())
                && current.ring.key().equals(command.key())
                && settings.isEnabled();
    }

    private void finish(Active current, boolean closeWatch) {
        if (active != current) return;
        active = null;
        boolean shouldClose = closeWatch && current.acked && !current.closeOutbound;
        if ("SENDING".equals(ledger.getString("delivery", ""))) delivery("ENDED_BEFORE_OPEN");
        if (current.acked || shouldClose) endHealth(current.run, current.id, shouldClose);
        ledger.edit().remove("activeId").apply();
    }
}
