package io.github.watchlink.xposed;

import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.validation.ClockActionWire.Command;
import io.github.watchlink.validation.PhoneRingWire;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/** Executes a watch action only for the verified current Clock occurrence. */
@RequiresApi(34)
final class ClockLiveActions {
    interface Reader {
        ClockNovemberOccurrence capture(Object target) throws ReflectiveOperationException;
        boolean isCurrent(ClockNovemberOccurrence ring);
        boolean stillRinging(Context app, ClockNovemberOccurrence ring) throws Exception;
    }

    private static final class Ring {
        final ClockNovemberOccurrence occurrence;
        final Service service;
        final Object instance;
        final ClockLiveClaim claim = new ClockLiveClaim();
        volatile PhoneRingWire.Event verifiedStart;

        Ring(ClockNovemberOccurrence occurrence, Service service, Object instance) {
            this.occurrence = occurrence;
            this.service = service;
            this.instance = instance;
        }
    }

    private final Reader reader;
    private final Executor worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int ownerUid;
    private final Field currentInstance;
    private final Method model;
    private final Method dismissAction, snoozeAction, canSnooze, state;
    private final boolean actionsResolved;
    private final ThreadLocal<Command> dispatching = new ThreadLocal<>();
    private volatile boolean controlsEnabled;
    private volatile boolean localContractVerified;
    private volatile Service currentService;
    private volatile Ring current;
    private volatile ClockDailyAlarms daily;

    ClockLiveActions(AlarmObserverInstaller i, Reader reader, Executor worker, Field currentInstance,
                     Class<?> instance, Class<?> alarm, Method model,
                     ClockSymbolResolver.Symbols symbols) throws ReflectiveOperationException {
        this.ownerUid = i.ownerUid();
        this.reader = reader;
        this.worker = worker;
        this.currentInstance = currentInstance;
        this.model = model;
        Method destroy = i.method(currentInstance.getDeclaringClass(), "onDestroy", void.class, false);
        i.observe("clock.service.destroy", destroy, (chain, ignored) -> destroyed((Service) chain.getThisObject()));

        Method resolvedDismiss = null, resolvedSnooze = null, resolvedCanSnooze = null, resolvedState = null;
        boolean resolved = false;
        if (symbols.actionContractVerified()) {
            try {
                resolvedCanSnooze = i.method(instance, symbols.canSnoozeMethod(), boolean.class, false, alarm);
                resolvedState = i.method(instance, symbols.stateGetter(), int.class, false);
                Class<?> manager = i.target(ClockSymbolResolver.STATE_MANAGER);
                resolvedDismiss = i.staticMethod(manager, symbols.dismissMethod(), void.class, Context.class, instance);
                resolvedSnooze = i.staticMethod(manager, symbols.snoozeMethod(), void.class, Context.class, instance);
                guardTransition(i, "clock.state.dismiss", resolvedDismiss, "DISMISS");
                guardTransition(i, "clock.state.snooze", resolvedSnooze, "SNOOZE");
                resolved = true;
            } catch (ReflectiveOperationException unavailable) {
                ProbeLog.failure("REMINDER_CLOCK_ACTION_SHAPE_UNAVAILABLE", unavailable);
            }
        }
        this.dismissAction = resolvedDismiss;
        this.snoozeAction = resolvedSnooze;
        this.canSnooze = resolvedCanSnooze;
        this.state = resolvedState;
        this.actionsResolved = resolved;
    }

    private void guardTransition(AlarmObserverInstaller i, String hookId, Method transition, String action) {
        i.guard(hookId, transition, chain -> {
            Command remote = ClockLiveClaim.takeDispatch(dispatching);
            Ring ring = current;
            boolean matches = false;
            if (ring != null) {
                Object target = chain.getArg(1);
                try {
                    matches = ring.occurrence.sameFiring(reader.capture(target))
                            && (remote == null || ring.instance == target);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Unidentified native action continues; module action fails closed.
                }
            }
            if (remote != null) {
                if (!matches || !action.equals(remote.action()) || !fresh(remote)
                        || !ring.claim.enter(remote, action)) return null;
            } else if (matches) {
                ring.claim.enter(null, action);
            }
            return chain.proceed();
        });
    }

    boolean actionsResolved() {
        return actionsResolved;
    }

    boolean hasActiveRing() {
        ClockDailyAlarms delivery = daily;
        return current != null || (delivery != null && delivery.hasActiveRing());
    }

    void registerVerified(Context app, HostSettings settings, boolean contractVerified, String symbolSource) {
        localContractVerified = contractVerified && actionsResolved;
        daily = new ClockDailyAlarms(app.getApplicationContext(), ownerUid, settings, localContractVerified,
                symbolSource, worker, this);
        controlsEnabled = true;
        worker.execute(() -> {
            Ring ring = current;
            if (sameService(ring) && ring.verifiedStart != null) daily.occurrence(ring.verifiedStart);
        });
    }

    void close() {
        controlsEnabled = false;
        ClockDailyAlarms delivery = daily;
        daily = null;
        if (delivery != null) delivery.close();
    }

    void service(Service service) { currentService = service; }

    void destroyed(Service service) {
        if (currentService == service) currentService = null;
        Ring active = current;
        if (active != null && active.service == service) current = null;
    }

    void started(ClockNovemberOccurrence occurrence, Object instance) {
        current = occurrence == null ? null : new Ring(occurrence, currentService, instance);
    }

    void bound(ClockNovemberOccurrence occurrence, PhoneRingWire.Event start) {
        Ring ring = current;
        if (ring != null && ring.occurrence == occurrence) ring.verifiedStart = start;
    }

    ClockLiveClaim claimFor(ClockNovemberOccurrence occurrence) {
        Ring ring = current;
        return ring != null && ring.occurrence == occurrence ? ring.claim : null;
    }

    void occurrence(PhoneRingWire.Event event) {
        ClockDailyAlarms delivery = daily;
        if (delivery != null) delivery.occurrence(event);
    }

    boolean verifiedCurrent(PhoneRingWire.Event event) throws Exception {
        Ring ring = current;
        return sameService(ring) && event.equals(ring.verifiedStart) && reader.stillRinging(ring.service, ring.occurrence);
    }

    /** Reads only the bound parent title on the Clock worker. Never logs or persists raw alarm labels. */
    String displayTitle(PhoneRingWire.Event event) {
        Ring ring = current;
        if (!sameService(ring) || !event.equals(ring.verifiedStart)) return null;
        try (Cursor row = ring.service.getContentResolver().query(
                ContentUris.withAppendedId(ClockInventory.ALARMS, ring.occurrence.parent),
                new String[]{"_id", "uuid", "message"}, null, null, null)) {
            if (row == null || !row.moveToFirst() || row.getCount() != 1
                    || row.getLong(row.getColumnIndexOrThrow("_id")) != ring.occurrence.parent
                    || !ring.occurrence.uuid.equals(row.getString(row.getColumnIndexOrThrow("uuid")))
                    || !sameService(ring) || !event.equals(ring.verifiedStart)) return null;
            int message = row.getColumnIndexOrThrow("message");
            return row.getType(message) == Cursor.FIELD_TYPE_STRING ? row.getString(message) : null;
        } catch (RuntimeException unavailable) {
            ProbeLog.failure("DAILY_CLOCK_TITLE_UNAVAILABLE", unavailable);
            return null;
        }
    }

    void watchAction(Context app, Command command) {
        prepare(app, command, current);
    }

    private boolean sameService(Ring ring) {
        return ring != null && ring.service != null && current == ring && currentService == ring.service
                && reader.isCurrent(ring.occurrence);
    }

    private boolean ringingInstance(Ring ring) throws ReflectiveOperationException {
        return sameService(ring) && state != null && currentInstance.get(ring.service) == ring.instance
                && ring.occurrence.sameFiring(reader.capture(ring.instance))
                && (Integer) state.invoke(ring.instance) == 2;
    }

    private void prepare(Context app, Command command, Ring ring) {
        try {
            if (ring != null && ring.claim.alreadyReceived(command)) return;
            if (!localContractVerified || !fresh(command) || ring == null || ring != current || ring.service == null
                    || ring.verifiedStart == null || !command.key().equals(ring.verifiedStart.key())) {
                ProbeLog.info("CLOCK_ACTION_BINDING_UNAVAILABLE");
                report(command, "REJECTED");
                return;
            }
            if (!reader.stillRinging(app, ring.occurrence)) {
                ProbeLog.info("CLOCK_ACTION_SCHEDULE_CHANGED");
                report(command, "REJECTED");
                return;
            }
            SharedPreferences ledger = app.getSharedPreferences(
                    BuildConfig.APPLICATION_ID + ".clock_action_v1", Context.MODE_PRIVATE);
            if (command.key().equals(ledger.getString("occurrence", ""))) {
                report(command, "UNCERTAIN");
                return;
            }
            if (!ring.claim.reserve(command)) {
                report(command, "REJECTED");
                return;
            }
            if (!ledger.edit().putString("occurrence", command.key()).commit()) {
                complete(ring.claim, "REJECTED");
                return;
            }
            if (!main.post(() -> apply(app, command, ring))) {
                complete(ring.claim, "REJECTED");
            }
        } catch (Exception | LinkageError unavailable) {
            if (ring != null && ring.claim.alreadyReceived(command)) complete(ring.claim, "REJECTED");
            else report(command, "REJECTED");
        }
    }

    private void apply(Context app, Command command, Ring ring) {
        boolean invoked = false;
        try {
            Object instance = ring.instance;
            boolean snooze = "SNOOZE".equals(command.action());
            if (!localContractVerified || !controlsEnabled || daily == null || !daily.allows(command)
                    || dismissAction == null || snoozeAction == null || canSnooze == null
                    || !fresh(command) || !ringingInstance(ring)) {
                ProbeLog.info("CLOCK_ACTION_SERVICE_CHANGED");
                complete(ring.claim, "REJECTED");
                return;
            }
            if (snooze && !Boolean.TRUE.equals(canSnooze.invoke(instance, model.invoke(instance)))) {
                ProbeLog.info("CLOCK_ACTION_NATIVE_SNOOZE_UNAVAILABLE");
                complete(ring.claim, "REJECTED");
                return;
            }
            dispatching.set(command);
            invoked = true;
            (snooze ? snoozeAction : dismissAction).invoke(null, app, instance);
            if (!ring.claim.enteredByWatch()) {
                complete(ring.claim, "UNCERTAIN");
                return;
            }
            report(command, "APPLIED");
            main.postDelayed(() -> complete(ring.claim, "UNCERTAIN"), 5_000);
        } catch (Exception | LinkageError unavailable) {
            complete(ring.claim, invoked ? "UNCERTAIN" : "REJECTED");
        } finally {
            dispatching.remove();
        }
    }

    private static boolean fresh(Command c) {
        return c.fresh(System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    void terminal(ClockLiveClaim claim, boolean dismissed, boolean confirmed) {
        if (claim == null) return;
        Command command = claim.takeResult();
        if (command == null) return;
        ProbeLog.info("CLOCK_ACTION_TERMINAL operation=" + command.action()
                + " overlap=" + (!"NONE".equals(claim.nativeOverlap()))
                + " readback=" + confirmed);
        report(command, confirmed && claim.proves(dismissed) ? "VERIFIED" : "UNCERTAIN");
    }

    private void complete(ClockLiveClaim claim, String status) {
        Command command = claim.takeResult();
        if (command != null) report(command, status);
    }

    private void report(Command command, String status) {
        ProbeLog.info("CLOCK_LIVE_ACTION operation=" + command.action() + " result=" + status);
        ClockDailyAlarms delivery = daily;
        if (delivery != null) delivery.actionResult(command, status);
    }
}
