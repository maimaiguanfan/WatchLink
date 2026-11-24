package io.github.watchlink.xposed;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.alarm.probe.NativeUuid;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.validation.PhoneRingWire;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Binds native Clock events to a single occurrence and verifies its terminal state on a worker. */
@RequiresApi(34)
final class ClockNovemberLive implements ClockLiveActions.Reader {
    private final Method schedule, parent, time, snoozes, model, uuid;
    private final Field serviceInstance;
    final ClockLiveActions actions;
    private final AtomicLong revision = new AtomicLong();
    private final AtomicReference<ClockNovemberOccurrence> current = new AtomicReference<>();
    private final ThreadLocal<Acceptance> accepts = ThreadLocal.withInitial(() -> new Acceptance(0, 0));
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), job -> {
                Thread t = new Thread(job, "WatchLink-clock-live");
                t.setDaemon(true);
                return t;
            });
    private ClockNovemberOccurrence bound;

    record Acceptance(long dismiss, long snooze) {
        Acceptance after(String action) {
            if ("com.oplus.alarmclock.ALARM_DISMISS_SERVICE_ACTION".equals(action))
                return new Acceptance(dismiss + 1, snooze);
            if ("com.oplus.alarmclock.ALARM_SNOOZE_OR_DISMISS_SERVICE_ACTION".equals(action))
                return new Acceptance(dismiss, snooze + 1);
            return this;
        }

        boolean exactlyOneSince(Acceptance before, boolean dismissed) {
            if (dismissed) return dismiss == before.dismiss + 1 && snooze == before.snooze;
            return snooze == before.snooze + 1 && dismiss == before.dismiss;
        }
    }

    ClockNovemberLive(AlarmObserverInstaller i, Class<?> occurrence, Class<?> alarmModel,
                      Class<?> service, ClockSymbolResolver.Symbols symbols) throws ReflectiveOperationException {
        schedule = i.method(occurrence, symbols.scheduleGetter(), long.class, false);
        parent = i.method(occurrence, symbols.parentGetter(), long.class, false);
        time = i.method(occurrence, symbols.timeGetter(), long.class, false);
        snoozes = i.method(occurrence, symbols.snoozesGetter(), int.class, false);
        model = i.method(occurrence, symbols.modelGetter(), alarmModel, false);
        uuid = i.method(alarmModel, symbols.uuidGetter(), String.class, false);
        serviceInstance = service.getDeclaredField(symbols.serviceInstanceField());
        if (!Modifier.isPublic(serviceInstance.getModifiers()) || Modifier.isStatic(serviceInstance.getModifiers())
                || serviceInstance.getType() != occurrence) {
            throw new NoSuchFieldException("Clock service occurrence shape");
        }
        ClockLiveActions candidate = null;
        try {
            candidate = new ClockLiveActions(i, this, worker, serviceInstance, occurrence, alarmModel, model, symbols);
        } catch (ReflectiveOperationException unavailable) {
            ProbeLog.failure("REMINDER_CLOCK_ACTION_SHAPE_UNAVAILABLE", unavailable);
        }
        actions = candidate;
        worker.allowCoreThreadTimeOut(true);
    }

    void install(AlarmObserverInstaller i, Class<?> occurrence, Class<?> service,
                 ClockSymbolResolver.Symbols symbols) throws ReflectiveOperationException {
        Class<?> provider = i.target(ClockSymbolResolver.PROVIDER);
        if (!android.content.ContentProvider.class.isAssignableFrom(provider))
            throw new NoSuchMethodException("Clock provider shape");
        i.observe("clock.provider.notify",
                i.method(provider, symbols.providerNotifyMethod(), void.class, true, Uri.class),
                (chain, ignored) -> {
                    Uri uri = (Uri) chain.getArg(0);
                    if (uri != null && "com.coloros.alarmclock.alarmclock".equals(uri.getAuthority()))
                        revision.incrementAndGet();
                });
        Method start = i.staticMethod(i.target(ClockSymbolResolver.KLAXON), "start",
                void.class, Context.class, occurrence, Boolean.class);
        Method accepted = i.method(service, symbols.serviceAcceptMethod(), void.class, false, String.class);
        Method command = i.method(service, "onStartCommand", int.class, false, Intent.class, int.class, int.class);
        i.observe("clock.klaxon.start", start, (chain, ignored) -> {
            Context c = ((Context) chain.getArg(0)).getApplicationContext();
            Object instance = chain.getArg(1);
            ClockNovemberOccurrence ring = capture(instance);
            if (actions != null) actions.started(ring, instance);
            current.set(ring);
            if (ring == null) return;
            long started = System.currentTimeMillis();
            long elapsed = SystemClock.elapsedRealtime();
            try { worker.execute(() -> confirmStart(c, ring, started, elapsed)); }
            catch (RejectedExecutionException full) { ProbeLog.failure("CLOCK_RING_QUEUE_FULL", full); }
        });
        i.observe("clock.service.accept", accepted,
                (chain, ignored) -> accepts.set(accepts.get().after((String) chain.getArg(0))));
        i.observeAround("clock.service.command", command,
                chain -> before((Service) chain.getThisObject(), (Intent) chain.getArg(0)),
                (chain, token, result) -> {
                    if (!(token instanceof Stop stop)) return;
                    Context c = ((Service) chain.getThisObject()).getApplicationContext();
                    if (!stop.matches || !accepts.get().exactlyOneSince(stop.previousAccept, stop.dismissed)
                            || !current.compareAndSet(stop.ring, null)) {
                        ProbeLog.info("CLOCK_TERMINAL_UNCONFIRMED");
                        return;
                    }
                    try {
                        worker.execute(() -> finish(c, stop));
                    } catch (RejectedExecutionException full) {
                        ProbeLog.info("CLOCK_TERMINAL_UNCONFIRMED");
                    }
                });
    }

    boolean hasActiveRing() {
        return current.get() != null || (actions != null && actions.hasActiveRing());
    }

    void close() {
        if (actions != null) actions.close();
        worker.shutdownNow();
    }

    private void confirmStart(Context c, ClockNovemberOccurrence ring, long started, long elapsed) {
        bound = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (attempt != 0) Thread.sleep(150L * attempt);
                if (current.get() != ring || SystemClock.elapsedRealtime() - elapsed >= PhoneRingWire.MAX_AGE) return;
                ClockInventory read = stable(c);
                if (!sameParent(read, ring) || !ring.ringing(read.schedules.get(ring.schedule))) continue;
                if (current.get() != ring) return;
                bound = ring;
                PhoneRingWire.Event start = new PhoneRingWire.Event(ring.key(), true, started, elapsed,
                        read.rows.get(ring.parent).snooze);
                if (actions != null) actions.bound(ring, start);
                ProbeLog.info("CLOCK_RING_BOUND");
                if (actions != null) actions.occurrence(start);
                return;
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception unavailable) {
                if (attempt == 2) ProbeLog.failure("CLOCK_RING_SNAPSHOT_FAILED", unavailable);
            }
        }
        ProbeLog.info("CLOCK_RING_SNAPSHOT_UNCONFIRMED");
    }

    @Override public ClockNovemberOccurrence capture(Object occurrence) throws ReflectiveOperationException {
        if (occurrence == null) return null;
        Object alarm = model.invoke(occurrence);
        if (alarm == null) return null;
        return new ClockNovemberOccurrence((Long) schedule.invoke(occurrence), (Long) parent.invoke(occurrence),
                (Long) time.invoke(occurrence), (Integer) snoozes.invoke(occurrence), (String) uuid.invoke(alarm));
    }

    @Override public boolean isCurrent(ClockNovemberOccurrence ring) {
        return current.get() == ring;
    }

    private record Stop(ClockNovemberOccurrence ring, boolean dismissed, boolean matches,
                        Acceptance previousAccept, long at, long elapsed, ClockLiveClaim claim) {}

    private Stop before(Service service, Intent intent) throws ReflectiveOperationException {
        if (actions != null) actions.service(service);
        if (intent == null || !"STOP_ALARM".equals(intent.getAction())) return null;
        ClockNovemberOccurrence ring = current.get();
        ClockNovemberOccurrence active = capture(serviceInstance.get(service));
        Uri uri = intent.getData();
        Bundle extras = intent.getExtras();
        Object flag = extras == null ? null : extras.get("IS_ALARM_DISMISSED");
        boolean matches = flag instanceof Boolean && active != null && ring != null
                && ring.matches(active.schedule, active.parent, active.uuid)
                && uri != null && "content".equals(uri.getScheme())
                && "com.coloros.alarmclock.alarmclock".equals(uri.getAuthority());
        if (matches) {
            List<String> path = uri.getPathSegments();
            matches = path.size() == 2 && "schedules".equals(path.get(0))
                    && Long.toString(ring.schedule).equals(path.get(1));
        }
        return new Stop(ring, Boolean.TRUE.equals(flag), matches, accepts.get(), System.currentTimeMillis(),
                SystemClock.elapsedRealtime(), actions == null ? null : actions.claimFor(ring));
    }

    private boolean sameParent(ClockInventory read, ClockNovemberOccurrence ring) throws Exception {
        ClockInventory.Row row = read.rows.get(ring.parent);
        String token = NativeUuid.digest(ring.uuid);
        if (row == null || token == null || !token.equals(row.token)) return false;
        int matches = 0;
        for (ClockInventory.Row other : read.rows.values()) if (token.equals(other.token)) matches++;
        return matches == 1;
    }

    @Override public boolean stillRinging(Context app, ClockNovemberOccurrence ring) throws Exception {
        if (current.get() != ring || bound != ring) return false;
        ClockInventory read = stable(app);
        ClockInventory.Schedule row = read.schedules.get(ring.schedule);
        return current.get() == ring && sameParent(read, ring) && ring.actionableRow(row);
    }

    private ClockInventory stable(Context app) throws Exception {
        long before = revision.get();
        ClockInventory first = ClockInventory.readLive(app);
        ClockInventory second = ClockInventory.readLive(app);
        if (before != revision.get() || !first.same(second)) throw new IllegalStateException("PHONE_LIVE_CHANGED");
        return second;
    }

    private boolean terminalParent(ClockInventory read, Stop stop) throws Exception {
        if (sameParent(read, stop.ring)) return true;
        if (!stop.dismissed || read.rows.containsKey(stop.ring.parent) || !read.scheduleIds(stop.ring.parent).isEmpty())
            return false;
        String token = NativeUuid.digest(stop.ring.uuid);
        return token != null && read.rows.values().stream().noneMatch(row -> token.equals(row.token));
    }

    private void finish(Context c, Stop stop) {
        boolean wasBound = bound == stop.ring;
        if (wasBound) {
            bound = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    if (attempt != 0) Thread.sleep(150L * attempt);
                    ClockInventory read = stable(c);
                    if (terminalParent(read, stop)
                            && stop.ring.terminal(stop.dismissed, read.schedules.get(stop.ring.schedule), stop.at)) {
                        ProbeLog.info(stop.dismissed ? "CLOCK_DISMISS_BOUND" : "CLOCK_SNOOZE_BOUND");
                        if (actions != null) actions.terminal(stop.claim, stop.dismissed, true);
                        PhoneRingWire.Event end = new PhoneRingWire.Event(stop.ring.key(), false, stop.at, stop.elapsed, 0);
                        if (actions != null) actions.occurrence(end);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception unavailable) {
                    // Retry brief snapshot races within the callback window.
                }
            }
        }
        if (actions != null) actions.terminal(stop.claim, stop.dismissed, false);
        ProbeLog.info("CLOCK_TERMINAL_UNCONFIRMED");
    }
}
