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
import io.github.watchlink.validation.CalendarHostControl;
import io.github.watchlink.validation.DailyAlarmWire;
import io.github.watchlink.validation.HuaweiReminderService;
import io.github.watchlink.validation.PhoneRingWire;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Calendar-side strong-reminder (ForceAlert) tracker that forwards active Calendar alarms
 * directly to Huawei Health (:PhoneService) and routes watch STOP/SNOOZE actions back to
 * Calendar's live UI (LockScreen / TopWindow / FluidCard).
 */
@RequiresApi(34)
final class CalendarDailyAlarms extends BroadcastReceiver {
    static final int MIN_NOTICE = 0x40000000;
    static final int MAX_NOTICE = MIN_NOTICE + 0x0fffffff;
    private static final int HISTORY = 32;
    private static final long ACK_TIMEOUT_MS = 10_000L;
    private static final long CALENDAR_ALERT_WINDOW_MS = 75_000L;

    interface ActionExecutor {
        boolean hasLiveAlert();
        void watchAction(String run, String action);
    }

    private final Context app;
    private final HostSettings settings;
    private final boolean localCalendarControl;
    private final String symbolSource;
    private final Executor worker;
    private final ActionExecutor executor;
    private final SharedPreferences ledger;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver controlReceiver;
    private volatile Active active;

    private static final class Active {
        final String key;
        final String title;
        final String run = UUID.randomUUID().toString();
        final long at, elapsed;
        final int minutes, id;
        boolean acked, serviceReceived, outbound, closeOutbound;
        volatile boolean answered;

        Active(String key, String title, int id, long at, long elapsed, int minutes) {
            this.key = key;
            this.title = title;
            this.id = id;
            this.at = at;
            this.elapsed = elapsed;
            this.minutes = minutes;
        }

        String openIdentifier() {
            return HuaweiReminderService.dailyIdentifier(run, id, minutes / 5);
        }
    }

    CalendarDailyAlarms(Context app, int ownerUid, HostSettings settings, boolean localCalendarControl,
                        String symbolSource, Executor worker, ActionExecutor executor) {
        this.app = app;
        this.settings = settings;
        this.localCalendarControl = localCalendarControl;
        this.symbolSource = symbolSource == null ? "UNKNOWN" : symbolSource;
        this.worker = worker;
        this.executor = executor;
        this.ledger = app.getSharedPreferences(BuildConfig.APPLICATION_ID + ".calendar_reminders_v1", Context.MODE_PRIVATE);
        this.settings.onCalendarDisabled(() -> enqueue(() -> {
            if (active != null) finish(active, true);
        }));
        enqueue(this::restore);
        IntentFilter filter = new IntentFilter(DailyAlarmWire.STATUS);
        filter.addAction(DailyAlarmWire.HEALTH_READY);
        app.registerReceiver(this, filter, Context.RECEIVER_EXPORTED);
        try {
            controlReceiver = CalendarHostControl.register(app, ownerUid, worker, this::control);
        } catch (RuntimeException failure) {
            app.unregisterReceiver(this);
            throw failure;
        }
    }

    boolean hasActiveRing() {
        return active != null;
    }

    void close() {
        settings.onCalendarDisabled(null);
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

    private void enqueue(Runnable job) {
        try {
            worker.execute(() -> {
                try { job.run(); }
                catch (RuntimeException failure) { ProbeLog.failure("DAILY_CALENDAR_EVENT_FAILED", failure); }
            });
        } catch (RejectedExecutionException busy) {
            ProbeLog.failure("DAILY_CALENDAR_QUEUE_FULL", busy);
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !TargetProfile.CALENDAR.packageName.equals(intent.getPackage())
                || !TargetProfile.HEALTH.packageName.equals(getSentFromPackage())) return;
        int uid = getSentFromUid();
        enqueue(() -> {
            if (!SenderVerifier.verifyReminderSender(app, TargetProfile.HEALTH, uid, TargetProfile.HEALTH.packageName)) return;
            if (DailyAlarmWire.STATUS.equals(intent.getAction())) {
                status(intent);
            } else if (DailyAlarmWire.HEALTH_READY.equals(intent.getAction())) {
                Active current = active;
                if (current != null && !current.acked && valid(current)) sendOpen(current);
            }
        });
    }

    private CalendarHostControl.Reply control(String command) {
        if (CalendarHostControl.DISABLE.equals(command)) {
            if (!settings.setLocalCalendarFallback(false)) {
                return new CalendarHostControl.Reply(true, settings.isCalendarEnabled(), "UNAVAILABLE",
                        settings.remoteAvailable(), localCalendarControl, symbolSource);
            }
            if (active != null) finish(active, true);
            return buildReply("DISABLED");
        }
        if (CalendarHostControl.ENABLE.equals(command)) {
            if (!settings.setLocalCalendarFallback(true)) {
                return new CalendarHostControl.Reply(true, settings.isCalendarEnabled(), "UNAVAILABLE",
                        settings.remoteAvailable(), localCalendarControl, symbolSource);
            }
        }
        return buildReply("READY");
    }

    private CalendarHostControl.Reply buildReply(String result) {
        return new CalendarHostControl.Reply(
                true,
                settings.isCalendarEnabled(),
                result,
                settings.remoteAvailable(),
                localCalendarControl,
                symbolSource,
                ledger.getLong("lastRingAt", 0),
                ledger.getString("delivery", ""),
                ledger.getString("dispatchMode", ""),
                ledger.getString("action", ""),
                ledger.getString("actionSource", ""),
                ledger.getString("result", ""),
                ledger.getString("close", ""));
    }

    void onRingStart(String key, String rawTitle, long at, long elapsed) {
        enqueue(() -> {
            if (!settings.isCalendarEnabled()) return;
            if (!DailyAlarmWire.inWindow(at, elapsed, PhoneRingWire.MAX_AGE)) return;
            if (Arrays.asList(ledger.getString("rings", "").split(",")).contains(key)) return;
            Active current = active;
            if (current != null) finish(current, true);
            begin(key, rawTitle, at, elapsed);
        });
    }

    void onRingEnd() {
        enqueue(() -> {
            Active current = active;
            if (current != null) finish(current, true);
        });
    }

    private void begin(String key, String rawTitle, long at, long elapsed) {
        int id = nextNotice(ledger.getInt("lastId", MIN_NOTICE - 1));
        if (id == 0) id = MIN_NOTICE;
        String title = DailyReminderTitle.forWatch(rawTitle);
        Active next = new Active(
                key,
                title == null ? HuaweiReminderService.CALENDAR_TITLE : title,
                id, at, elapsed, HuaweiReminderService.SNOOZE_MINUTES);
        String keys = key + "," + ledger.getString("rings", "");
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
                    ProbeLog.info("DAILY_CALENDAR_HEALTH_UNANSWERED");
                    finish(next, false);
                }
            }), ACK_TIMEOUT_MS);
            main.postDelayed(() -> enqueue(() -> {
                if (active == next) finish(next, true);
            }), CALENDAR_ALERT_WINDOW_MS);
        } catch (RuntimeException failure) {
            delivery("FAILED");
            finish(next, false);
            ProbeLog.failure("DAILY_CALENDAR_OPEN_FAILED", failure);
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
        ProbeLog.info("DAILY_CALENDAR_OPEN_SENT");
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
            ProbeLog.failure("DAILY_CALENDAR_END_UNCONFIRMED", failure);
        }
    }

    private boolean valid(Active session) {
        return DailyAlarmWire.inWindow(session.at, session.elapsed, PhoneRingWire.MAX_AGE)
                && settings.isCalendarEnabled() && executor.hasLiveAlert();
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
        if (kind == null || !ClockDailyAlarms.freshStatus(at, elapsed, now, monotonic, 0)) return;

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
                ProbeLog.info("DAILY_CALENDAR_HEALTH_OUTBOUND");
            }
            case "DISMISS", "SNOOZE" -> {
                if ((!current.outbound && !current.serviceReceived && !current.acked)
                        || current.answered || !settings.isCalendarEnabled() || !executor.hasLiveAlert()) return;
                current.answered = true;
                SharedPreferences.Editor edit = ledger.edit().putString("action", kind);
                if (actionSource != null && !actionSource.isEmpty()) edit.putString("actionSource", actionSource);
                edit.apply();
                executor.watchAction(current.run, kind);
            }
            case "CLOSE_OUTBOUND" -> {
                current.closeOutbound = true;
                finish(current, false);
            }
            case "WATCH_UNAVAILABLE", "DISABLED", "BACKGROUND_RESTRICTED", "DENIED", "BUSY", "FAILED", "UNAVAILABLE" -> {
                current.acked = true;
                delivery(kind);
                ProbeLog.info("DAILY_CALENDAR_STATUS result=" + kind);
                finish(current, false);
            }
            default -> {}
        }
    }

    void actionResult(String run, String action, String status) {
        enqueue(() -> {
            if (run == null || !run.equals(ledger.getString("lastRealRun", ""))) return;
            String prior = ledger.getString("result", "");
            if ("VERIFIED".equals(prior) || "UNCERTAIN".equals(prior) || "REJECTED".equals(prior)) return;
            ledger.edit().putString("action", action).putString("result", status).apply();
        });
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
