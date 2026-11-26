package io.github.watchlink.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.live.LiveWindowPayload;
import io.github.watchlink.live.LiveWindowWire;
import io.github.watchlink.timer.Countdown;
import io.github.watchlink.timer.TimerCards;
import io.github.watchlink.timer.TimerRing;
import io.github.watchlink.validation.DailyAlarmWire;
import io.github.watchlink.validation.HuaweiReminderService;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@RequiresApi(34)
final class ClockCountdownBridge extends BroadcastReceiver {
    private static final Uri URI = Uri.parse("content://com.coloros.alarmclock.alarmclock/timer_schedule");
    private static final String[] COLUMNS = {"_id", "first_start_time", "duration", "state", "alert_time", "pause_remain_time"};
    private final Context app;
    private final HostSettings settings;
    private final HandlerThread thread = new HandlerThread("WatchLink-clock-timer");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TimerCards cards = new TimerCards();
    private Handler worker;
    private ContentObserver observer;
    private boolean registered;
    private volatile boolean closed;
    private volatile long earliest = Long.MAX_VALUE;
    private volatile boolean enabled;
    private Method nativeStop;
    private boolean restartVerified;

    // Service callbacks and native controls run on Clock's main thread. Identity changes immediately,
    // even if an older watch reply has already been queued on the worker.
    private volatile Ring current;
    private WeakReference<Object> klaxon = new WeakReference<>(null);
    private Ring klaxonRing;
    private Ring dispatched; // worker only
    private final Runnable ringTimeout = this::finishDispatched;
    private static final class Ring {
        final TimerRing event;
        final WeakReference<Object> service;
        boolean actionTaken; // main thread only
        volatile boolean stopping;
        Ring(TimerRing event, Object service) {
            this.event = event;
            this.service = new WeakReference<>(service);
        }
    }

    ClockCountdownBridge(Context app, HostSettings settings) {
        this.app = app;
        this.settings = settings;
    }

    void install(AlarmObserverInstaller installer, String[] sources) {
        thread.start();
        worker = new Handler(thread.getLooper());
        var symbols = ClockTimerSymbolResolver.resolve(new ApkSymbolCache(app, "clock-timer"), sources);
        if (symbols != null && !symbols.show().isEmpty()) {
            try { installRinging(installer, symbols); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                ProbeLog.failure("TIMER_CLOCK_UNAVAILABLE", unavailable);
            }
        } else ProbeLog.info("TIMER_CLOCK_UNAVAILABLE");
        var filter = new IntentFilter(DailyAlarmWire.STATUS);
        filter.addAction(DailyAlarmWire.HEALTH_READY);
        app.registerReceiver(this, filter, null, worker, Context.RECEIVER_EXPORTED);
        registered = true;
        settings.onTimerChanged(() -> worker.post(this::configure));
        worker.post(this::configure);
        ProbeLog.info("TIMER_CLOCK_READY");
    }

    private void installRinging(AlarmObserverInstaller installer, ClockTimerSymbolResolver.Symbols symbols)
            throws ReflectiveOperationException {
        Class<?> view = installer.target(ClockTimerSymbolResolver.VIEW);
        Class<?> sound = installer.target(ClockTimerSymbolResolver.KLAXON);
        Method show = installer.method(view, symbols.show(), void.class, false, Context.class, Intent.class);
        Method viewCommand = installer.method(view, "onStartCommand", int.class, false, Intent.class, int.class, int.class);
        Method soundStart = installer.method(sound, "onStartCommand", int.class, false, Intent.class, int.class, int.class);
        Method destroy = installer.method(sound, "onDestroy", void.class, false);
        if (!symbols.stop().isEmpty()) {
            try { nativeStop = installer.staticMethod(view, symbols.stop(), void.class, Context.class, boolean.class); }
            catch (ReflectiveOperationException ignored) { /* Keep one-way ringing. */ }
        }
        if (nativeStop == null) ProbeLog.info("TIMER_CLOCK_ACTIONS_UNAVAILABLE");
        else installer.observe("timer.clock.native_stop", nativeStop, (chain, result) -> {
            Ring ring = current;
            if (ring != null) ring.stopping = true;
        });
        restartVerified = nativeStop != null && symbols.restart();
        installer.observe("timer.clock.ringing", show, (chain, result) ->
                shown(chain.getThisObject(), (Intent) chain.getArg(1)));
        installer.observe("timer.clock.stop", viewCommand, (chain, result) -> {
            Intent intent = (Intent) chain.getArg(0);
            Ring ring = current;
            if (ring != null && ring.service.get() == chain.getThisObject() && intent != null
                    && "stop_timer_action".equals(intent.getAction()) && klaxonRing != ring) ended(ring);
        });
        installer.observe("timer.clock.klaxon_start", soundStart, (chain, result) -> {
            klaxon = new WeakReference<>(chain.getThisObject());
            if (current != null) klaxonRing = current;
        });
        installer.observe("timer.clock.klaxon_destroy", destroy, (chain, result) -> {
            if (klaxon.get() == chain.getThisObject()) {
                if (klaxonRing != null && klaxonRing == current && klaxonRing.actionTaken)
                    ProbeLog.info("TIMER_CLOCK_PLAYBACK_ENDED");
                ended(klaxonRing);
                klaxonRing = null;
                klaxon.clear();
            }
        });
    }

    private void shown(Object service, Intent intent) {
        if (closed || !enabled || intent == null || !"start_timer_action".equals(intent.getAction())) return;
        long id;
        try { id = Long.parseLong(intent.getStringExtra("timer_index")); }
        catch (NumberFormatException invalid) { return; }
        long first = intent.getLongExtra("timer_first_start_time", -1);
        if (id <= 0 || first <= 0) return;
        Ring previous = current;
        if (previous != null && previous.event.id() == id && previous.event.firstStart() == first
                && previous.service.get() == service) return;
        Ring ring = new Ring(TimerRing.start(id, first, System.currentTimeMillis(), SystemClock.elapsedRealtime()), service);
        current = ring;
        if (klaxon.get() != null) klaxonRing = ring;
        worker.post(() -> {
            if (closed || current != ring || !settings.isTimerEnabled()) return;
            finishDispatched();
            sendCard(TimerCards.close(Countdown.key(id, first)));
            dispatched = ring;
            open(ring);
            worker.postDelayed(ringTimeout, DailyAlarmWire.MAX_WINDOW);
            ProbeLog.info("TIMER_CLOCK_RING");
        });
    }

    private void ended(Ring ring) {
        if (ring == null || current != ring) return;
        current = null;
        worker.post(() -> { if (dispatched == ring) finishDispatched(); });
    }

    private void open(Ring ring) {
        TimerRing event = ring.event;
        int snooze = restartVerified && settings.isTimerRestartEnabled() ? HuaweiReminderService.SNOOZE_MINUTES : 0;
        sendDaily(message(DailyAlarmWire.OPEN, event)
                .putExtra("at", System.currentTimeMillis()).putExtra("elapsed", SystemClock.elapsedRealtime())
                .putExtra("title", "倒计时").putExtra("snoozeMinutes", snooze)
                .putExtra("openIdentifier", HuaweiReminderService.dailyIdentifier(event.run(), event.reminder(), snooze / 5)));
    }

    private void sendDaily(Intent intent) {
        try { DailyAlarmWire.send(app, intent); }
        catch (RuntimeException unavailable) { ProbeLog.failure("TIMER_CLOCK_UNAVAILABLE", unavailable); }
    }

    private void sendCard(LiveWindowPayload payload) {
        try { LiveWindowWire.sendToHealth(app, payload); }
        catch (RuntimeException unavailable) { ProbeLog.failure("TIMER_CLOCK_UNAVAILABLE", unavailable); }
    }

    private Intent message(String action, TimerRing event) {
        return DailyAlarmWire.route(action, TargetProfile.HEALTH.packageName)
                .putExtra(TimerRing.TYPE_EXTRA, TimerRing.TYPE)
                .putExtra("run", event.run()).putExtra("reminder", event.reminder());
    }

    private void finishDispatched() {
        if (dispatched == null) return;
        TimerRing event = dispatched.event;
        dispatched = null;
        worker.removeCallbacks(ringTimeout);
        sendDaily(message(DailyAlarmWire.END, event).putExtra("closeWatch", true));
        ProbeLog.info("TIMER_CLOCK_END");
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (closed || !enabled || intent == null || !TargetProfile.CLOCK.packageName.equals(intent.getPackage())
                || intent.getComponent() != null || (intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0
                || !SenderVerifier.verifyReminderSender(app, TargetProfile.HEALTH, getSentFromUid(), getSentFromPackage())) return;
        Ring ring = dispatched;
        if (DailyAlarmWire.HEALTH_READY.equals(intent.getAction())) {
            if (observer != null) refresh(true);
            if (ring != null && current == ring && DailyAlarmWire.inWindow(ring.event.wall(), ring.event.elapsed(),
                    DailyAlarmWire.MAX_WINDOW)) open(ring);
            return;
        }
        if (!DailyAlarmWire.STATUS.equals(intent.getAction()) || ring == null || current != ring || nativeStop == null) return;
        String action = intent.getStringExtra("kind");
        boolean repeat = "SNOOZE".equals(action);
        if (!(repeat || "DISMISS".equals(action))) return;
        String type = intent.getStringExtra(TimerRing.TYPE_EXTRA), run = intent.getStringExtra("run");
        int reminder = intent.getIntExtra("reminder", 0);
        long at = intent.getLongExtra("at", 0), seen = intent.getLongExtra("elapsed", -1);
        if (!ring.event.accepts(type, run, reminder, at, seen, System.currentTimeMillis(), SystemClock.elapsedRealtime())) return;
        // RESTART_TIMER uses the database ID alone. Reject a removed or reused row before invoking it.
        if (repeat && (!settings.isTimerRestartEnabled() || !restartVerified || !sameStoredOccurrence(ring.event))) return;
        main.post(() -> {
            if (closed || !enabled || current != ring || ring.stopping || ring.actionTaken
                    || (ring.service.get() == null && klaxonRing != ring)
                    || !settings.isTimerEnabled() || (repeat && !(restartVerified && settings.isTimerRestartEnabled()))
                    || !ring.event.accepts(type, run, reminder, at, seen, System.currentTimeMillis(), SystemClock.elapsedRealtime())) return;
            ring.actionTaken = true;
            try {
                if (repeat) app.sendBroadcast(new Intent(ClockTimerSymbolResolver.RESTART)
                        .setClassName(app.getPackageName(), ClockTimerSymbolResolver.RECEIVER)
                        .putExtra("timerIndex", Long.toString(ring.event.id())));
                nativeStop.invoke(null, app, repeat);
                ProbeLog.info("TIMER_CLOCK_ACTION operation=" + action);
            } catch (ReflectiveOperationException | RuntimeException err) {
                ProbeLog.failure("TIMER_CLOCK_ACTIONS_UNAVAILABLE", err);
            }
        });
    }

    private boolean sameStoredOccurrence(TimerRing ring) {
        try (Cursor row = app.getContentResolver().query(URI, new String[]{"_id", "first_start_time"},
                "_id = ?", new String[]{Long.toString(ring.id())}, null)) {
            return row != null && row.moveToFirst() && row.getLong(0) == ring.id()
                    && row.getLong(1) == ring.firstStart() && !row.moveToNext();
        } catch (RuntimeException unavailable) {
            ProbeLog.failure("TIMER_CLOCK_ACTIONS_UNAVAILABLE", unavailable);
            return false;
        }
    }

    private void configure() {
        if (closed) return;
        enabled = settings.isTimerEnabled() && SenderVerifier.trustedReminderTarget(app, TargetProfile.HEALTH);
        if (!enabled) {
            current = null;
            finishDispatched();
        }
        if (enabled && settings.isTimerLiveWindowEnabled()) {
            if (observer == null) {
                ContentObserver next = new ContentObserver(worker) {
                    @Override public void onChange(boolean selfChange) { refresh(false); }
                };
                try {
                    app.getContentResolver().registerContentObserver(URI, true, next);
                    observer = next;
                } catch (RuntimeException unavailable) {
                    ProbeLog.failure("TIMER_CLOCK_UNAVAILABLE", unavailable);
                    return;
                }
            }
            refresh(false);
        } else clearCards();
    }

    private final Runnable tick = () -> refresh(false);

    private void refresh(boolean replay) {
        if (closed || observer == null) return;
        List<Countdown> snapshot = new ArrayList<>();
        try (Cursor rows = app.getContentResolver().query(URI, COLUMNS, "state IN (1,2)", null, null)) {
            if (rows == null) throw new IllegalStateException("Timer provider unavailable");
            while (rows.moveToNext()) {
                int state = rows.getInt(3);
                if (state != 1 && state != 2) continue;
                boolean running = state == 1;
                Countdown timer = new Countdown(rows.getLong(0), rows.getLong(1), rows.getLong(2),
                        running ? Countdown.Phase.RUNNING : Countdown.Phase.PAUSED, rows.getLong(running ? 4 : 5));
                Ring ring = current;
                if (ring != null && ring.event.id() == timer.id() && ring.event.firstStart() == timer.firstStart()) continue;
                if (timer.valid()) snapshot.add(timer);
            }
        } catch (RuntimeException unavailable) {
            ProbeLog.failure("TIMER_CLOCK_UNAVAILABLE", unavailable);
            worker.removeCallbacks(tick);
            worker.postDelayed(tick, 60_000);
            return;
        }
        earliest = snapshot.stream().filter(timer -> timer.phase() == Countdown.Phase.RUNNING)
                .mapToLong(Countdown::value).min().orElse(Long.MAX_VALUE);
        applyCards(snapshot, replay);
    }

    private void applyCards(List<Countdown> timers, boolean replay) {
        long elapsed = SystemClock.elapsedRealtime();
        for (var payload : cards.reconcile(timers, elapsed, System.currentTimeMillis(), ZoneId.systemDefault(), replay))
            sendCard(payload);
        worker.removeCallbacks(tick);
        long next = cards.nextUpdate();
        if (next != Long.MAX_VALUE) worker.postDelayed(tick, Math.max(1, next - elapsed));
    }

    private void clearCards() {
        if (observer != null) {
            try { app.getContentResolver().unregisterContentObserver(observer); }
            catch (RuntimeException unavailable) { ProbeLog.failure("TIMER_CLOCK_UNAVAILABLE", unavailable); }
        }
        observer = null;
        earliest = Long.MAX_VALUE;
        applyCards(List.of(), false);
    }

    boolean busy() {
        long remaining = earliest - SystemClock.elapsedRealtime();
        Ring ring = current;
        return !closed && ((ring != null && SystemClock.elapsedRealtime() - ring.event.elapsed() < DailyAlarmWire.MAX_WINDOW)
                || (remaining >= 0 && remaining <= 15_000));
    }

    void close() {
        closed = true;
        current = null;
        settings.onTimerChanged(null);
        if (registered) {
            try { app.unregisterReceiver(this); } catch (IllegalArgumentException ignored) {}
        }
        if (worker != null) worker.post(() -> {
            try { finishDispatched(); clearCards(); }
            finally { thread.quitSafely(); }
        });
    }
}
