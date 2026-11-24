package io.github.watchlink.xposed;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.alarm.probe.ProbeLog;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hooks ColorOS Calendar strong reminders (ForceAlert) to observe ringing start/stop
 * and execute watch STOP (dismiss) / SNOOZE (5-minute delay) actions across all UI modes:
 * LockScreen activity, unlocked floating top window, Fluid Cloud card, and listener fallback.
 */
@RequiresApi(34)
final class CalendarLiveBridge implements CalendarDailyAlarms.ActionExecutor {
    private static final String LOCK_SCREEN_FINISH_ACTION = "action_finish_alert_lock_screen";
    private static final long VERIFY_TIMEOUT_MS = 2_500L;

    private final Context app;
    private final int ownerUid;
    private final CalendarSymbolResolver.Symbols symbols;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "WatchLink-calendar-bridge");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();

    private final Method titleGetter;
    private final Method targetIdGetter;
    private final Method stopRingNoArgMethod;
    private final Method listenerDismissMethod;
    private final Method listenerSnoozeMethod;
    private final Method lockScreenDismissMethod;
    private final Method lockScreenSnoozeMethod;
    private final Field topWindowInstanceField;
    private final Field topWindowShowingField;
    private final Method topWindowDismissMethod;
    private final Method topWindowSnoozeMethod;
    private final boolean actionsResolved;

    // Optional unobfuscated Fluid Cloud card controls (course strong reminders on ColorOS 16+).
    private Object fluidManagerInstance;
    private Method fluidGetForceCards;
    private Object fluidRingtoneInstance;
    private Method fluidStopRingtone;

    private volatile CalendarDailyAlarms daily;
    private volatile boolean alertActive;
    private volatile WeakReference<Activity> activeLockScreen = new WeakReference<>(null);
    private volatile WeakReference<Object> activeListener = new WeakReference<>(null);
    private volatile String pendingRun;
    private volatile String pendingAction;

    CalendarLiveBridge(Context app, AlarmObserverInstaller installer, CalendarSymbolResolver.Symbols symbols)
            throws ReflectiveOperationException {
        this.app = app;
        this.ownerUid = installer.ownerUid();
        this.symbols = symbols;

        Class<?> dataBean = installer.target(symbols.dataBeanClass());
        this.titleGetter = installer.method(dataBean, symbols.titleGetter(), String.class, false);
        this.targetIdGetter = installer.method(dataBean, symbols.targetIdGetter(), long.class, false);

        Method stopNoArg = null, lDismiss = null, lSnooze = null, lockDismiss = null, lockSnooze = null;
        Method topDismiss = null, topSnooze = null;
        Field topInst = null, topShow = null;
        boolean resolved = false;
        if (symbols.actionContractVerified()) {
            try {
                Class<?> controller = installer.target(symbols.controllerClass());
                Class<?> listener = installer.target(symbols.listenerInterface());
                Class<?> lockScreen = installer.target(CalendarSymbolResolver.LOCK_SCREEN);
                Class<?> topWindow = installer.target(symbols.topWindowClass());

                stopNoArg = installer.staticMethod(controller, symbols.stopRingNoArgMethod(), void.class);
                lDismiss = listener.getMethod(symbols.listenerDismissMethod());
                lSnooze = listener.getMethod(symbols.listenerSnoozeMethod());

                lockDismiss = lockScreen.getDeclaredMethod(symbols.lockScreenDismissMethod());
                lockDismiss.setAccessible(true);
                lockSnooze = lockScreen.getDeclaredMethod(symbols.lockScreenSnoozeMethod());
                lockSnooze.setAccessible(true);

                topInst = topWindow.getDeclaredField(symbols.topWindowInstanceField());
                topInst.setAccessible(true);
                topShow = topWindow.getDeclaredField(symbols.topWindowShowingField());
                topShow.setAccessible(true);
                topDismiss = topWindow.getDeclaredMethod(symbols.topWindowDismissMethod());
                topDismiss.setAccessible(true);
                topSnooze = topWindow.getDeclaredMethod(symbols.topWindowSnoozeMethod());
                topSnooze.setAccessible(true);

                resolved = lDismiss.getReturnType() == void.class && lSnooze.getReturnType() == void.class
                        && lockDismiss.getReturnType() == void.class && lockSnooze.getReturnType() == void.class
                        && topDismiss.getReturnType() == void.class && topSnooze.getReturnType() == void.class;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                resolved = false;
            }
        }
        this.stopRingNoArgMethod = stopNoArg;
        this.listenerDismissMethod = lDismiss;
        this.listenerSnoozeMethod = lSnooze;
        this.lockScreenDismissMethod = lockDismiss;
        this.lockScreenSnoozeMethod = lockSnooze;
        this.topWindowInstanceField = topInst;
        this.topWindowShowingField = topShow;
        this.topWindowDismissMethod = topDismiss;
        this.topWindowSnoozeMethod = topSnooze;
        this.actionsResolved = resolved;

        resolveOptionalFluidControls(installer);
    }

    private void resolveOptionalFluidControls(AlarmObserverInstaller installer) {
        try {
            Class<?> mgr = installer.target(CalendarSymbolResolver.FLUID_MANAGER);
            Field inst = mgr.getDeclaredField("INSTANCE");
            inst.setAccessible(true);
            fluidManagerInstance = inst.get(null);
            fluidGetForceCards = mgr.getMethod("getForceAlertFluidSeedingCards");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            fluidManagerInstance = null;
            fluidGetForceCards = null;
        }
        try {
            Class<?> ringMgr = installer.target(CalendarSymbolResolver.FLUID_RINGTONE);
            Field inst = ringMgr.getDeclaredField("INSTANCE");
            inst.setAccessible(true);
            fluidRingtoneInstance = inst.get(null);
            fluidStopRingtone = ringMgr.getMethod("stopRingtoneAndVibrate");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            fluidRingtoneInstance = null;
            fluidStopRingtone = null;
        }
    }

    boolean actionsResolved() {
        return symbols.actionContractVerified() && actionsResolved;
    }

    void install(AlarmObserverInstaller installer, HostSettings settings) throws ReflectiveOperationException {
        Class<?> controller = installer.target(symbols.controllerClass());
        Class<?> dataBean = installer.target(symbols.dataBeanClass());
        Class<?> fluidCheck = installer.target(symbols.fluidCheckInterface());
        Class<?> listener = installer.target(symbols.listenerInterface());
        Class<?> lockScreen = installer.target(CalendarSymbolResolver.LOCK_SCREEN);

        Method showUi = installer.staticMethod(controller, symbols.showUiMethod(), boolean.class,
                Context.class, dataBean, fluidCheck, listener);
        Method stopRing = installer.staticMethod(controller, symbols.stopRingMethod(), void.class, boolean.class);
        Method lockOnCreate = lockScreen.getDeclaredMethod("onCreate", Bundle.class);
        Method lockOnDestroy = lockScreen.getDeclaredMethod("onDestroy");

        boolean localControl = actionsResolved();
        if (!localControl) ProbeLog.info("REMINDER_CALENDAR_ACTIONS_UNAVAILABLE");
        daily = new CalendarDailyAlarms(app, ownerUid, settings, localControl, symbols.source(), worker, this);

        installer.observe("calendar.force.show", showUi, (chain, result) -> {
            if (!Boolean.TRUE.equals(result)) return;
            Object bean = chain.getArg(1);
            Object listenerObj = chain.getArg(3);
            if (bean == null) return;
            String title = null;
            long targetId = 0L;
            try { title = (String) titleGetter.invoke(bean); } catch (Exception ignored) {}
            try {
                Object rawId = targetIdGetter.invoke(bean);
                if (rawId instanceof Long l) targetId = l;
            } catch (Exception ignored) {}
            long wall = System.currentTimeMillis();
            long elapsed = SystemClock.elapsedRealtime();
            activeListener = new WeakReference<>(listenerObj);
            alertActive = true;
            String key = targetId + "@" + (wall / 5_000L);
            CalendarDailyAlarms d = daily;
            if (d != null) d.onRingStart(key, title, wall, elapsed);
        });

        installer.observe("calendar.force.stop", stopRing, (chain, result) -> {
            if (!Boolean.FALSE.equals(chain.getArg(0))) return;
            alertActive = false;
            String run = pendingRun;
            String action = pendingAction;
            if (run != null && action != null) {
                pendingRun = null;
                pendingAction = null;
                ProbeLog.info("CALENDAR_ACTION_TERMINAL operation=" + action + " result=VERIFIED");
                CalendarDailyAlarms d = daily;
                if (d != null) d.actionResult(run, action, "VERIFIED");
            }
            CalendarDailyAlarms d = daily;
            if (d != null) d.onRingEnd();
        });

        installer.observe("calendar.lock.create", lockOnCreate, (chain, result) -> {
            if (chain.getThisObject() instanceof Activity activity) {
                activeLockScreen = new WeakReference<>(activity);
            }
        });

        installer.observe("calendar.lock.destroy", lockOnDestroy, (chain, result) -> {
            if (chain.getThisObject() instanceof Activity activity && activeLockScreen.get() == activity) {
                activeLockScreen = new WeakReference<>(null);
            }
        });
    }

    boolean hasActiveRing() {
        CalendarDailyAlarms d = daily;
        return d != null && d.hasActiveRing();
    }

    @Override
    public boolean hasLiveAlert() {
        if (alertActive) return true;
        Activity lock = activeLockScreen.get();
        if (lock != null && !lock.isFinishing() && !lock.isDestroyed()) return true;
        if (isTopWindowShowing()) return true;
        return !activeFluidCards().isEmpty();
    }

    @Override
    public void watchAction(String run, String action) {
        if (closed.get() || !actionsResolved()) {
            reportAction(run, action, "REJECTED");
            return;
        }
        boolean snooze = "SNOOZE".equals(action);
        if (!snooze && !"DISMISS".equals(action)) {
            reportAction(run, action, "REJECTED");
            return;
        }
        main.post(() -> {
            if (closed.get()) {
                reportAction(run, action, "REJECTED");
                return;
            }
            pendingRun = run;
            pendingAction = action;
            String uiMode = dispatchOnMain(snooze);
            if (uiMode == null) {
                if (run.equals(pendingRun)) {
                    pendingRun = null;
                    pendingAction = null;
                }
                ProbeLog.info("CALENDAR_LIVE_ACTION operation=" + action + " result=REJECTED");
                reportAction(run, action, "REJECTED");
                return;
            }
            ProbeLog.info("CALENDAR_LIVE_ACTION operation=" + action + " ui_mode=" + uiMode + " result=APPLIED");
            // If stopRing(false) already ran synchronously during dispatchOnMain, pendingRun is already cleared.
            if (run.equals(pendingRun)) {
                reportAction(run, action, "APPLIED");
                main.postDelayed(() -> {
                    if (run.equals(pendingRun)) {
                        pendingRun = null;
                        pendingAction = null;
                        ProbeLog.info("CALENDAR_ACTION_TERMINAL operation=" + action + " result=UNCERTAIN");
                        reportAction(run, action, "UNCERTAIN");
                    }
                }, VERIFY_TIMEOUT_MS);
            }
        });
    }

    private String dispatchOnMain(boolean snooze) {
        // 1. LockScreen activity (full-screen strong reminder)
        Activity lock = activeLockScreen.get();
        if (lock != null && !lock.isFinishing() && !lock.isDestroyed()) {
            try {
                (snooze ? lockScreenSnoozeMethod : lockScreenDismissMethod).invoke(lock);
                return "LOCK_SCREEN";
            } catch (Exception | LinkageError failed) {
                ProbeLog.failure("CALENDAR_HOST_CONTROL_FAILED", failed);
            }
        }

        // 2. Unlocked floating top window (Lm9/o or Lm9/s)
        try {
            Object topWin = topWindowInstanceField.get(null);
            boolean showing = topWindowShowingField.getBoolean(null);
            if (topWin != null && showing) {
                (snooze ? topWindowSnoozeMethod : topWindowDismissMethod).invoke(topWin);
                return "TOP_WINDOW";
            }
        } catch (Exception | LinkageError failed) {
            ProbeLog.failure("CALENDAR_HOST_CONTROL_FAILED", failed);
        }

        // 3. Unlocked Fluid Cloud card (ForceReminderCard)
        List<?> fluidCards = activeFluidCards();
        if (!fluidCards.isEmpty()) {
            boolean handled = false;
            for (Object card : fluidCards) {
                if (card == null) continue;
                try {
                    if (snooze) {
                        Method later = card.getClass().getMethod("laterReminder");
                        later.invoke(card);
                    }
                    Method close = card.getClass().getMethod("closeFluidCard");
                    close.invoke(card);
                    handled = true;
                } catch (Exception | LinkageError failed) {
                    ProbeLog.failure("CALENDAR_HOST_CONTROL_FAILED", failed);
                }
            }
            if (handled) {
                stopFluidRingtoneQuietly();
                return "FLUID_CARD";
            }
        }

        // 4. Direct listener fallback (covers foldable secondary display or in-flight Activity launch)
        Object listener = activeListener.get();
        if (listener != null && alertActive) {
            try {
                (snooze ? listenerSnoozeMethod : listenerDismissMethod).invoke(listener);
                stopRingNoArgMethod.invoke(null);
                if (!snooze) {
                    try {
                        app.sendBroadcast(new Intent(LOCK_SCREEN_FINISH_ACTION).setPackage(app.getPackageName()));
                    } catch (RuntimeException ignored) {}
                }
                return "LISTENER";
            } catch (Exception | LinkageError failed) {
                ProbeLog.failure("CALENDAR_HOST_CONTROL_FAILED", failed);
            }
        }
        return null;
    }

    private boolean isTopWindowShowing() {
        if (topWindowInstanceField == null || topWindowShowingField == null) return false;
        try {
            return topWindowInstanceField.get(null) != null && topWindowShowingField.getBoolean(null);
        } catch (Exception | LinkageError ignored) {
            return false;
        }
    }

    private List<?> activeFluidCards() {
        if (fluidManagerInstance == null || fluidGetForceCards == null) return List.of();
        try {
            Object raw = fluidGetForceCards.invoke(fluidManagerInstance);
            return raw instanceof List<?> list ? list : List.of();
        } catch (Exception | LinkageError ignored) {
            return List.of();
        }
    }

    private void stopFluidRingtoneQuietly() {
        if (fluidRingtoneInstance != null && fluidStopRingtone != null) {
            try {
                fluidStopRingtone.invoke(fluidRingtoneInstance);
                return;
            } catch (Exception | LinkageError ignored) {}
        }
        if (stopRingNoArgMethod != null) {
            try { stopRingNoArgMethod.invoke(null); } catch (Exception | LinkageError ignored) {}
        }
    }

    private void reportAction(String run, String action, String status) {
        CalendarDailyAlarms d = daily;
        if (d != null) d.actionResult(run, action, status);
    }

    void close() {
        if (!closed.compareAndSet(false, true)) return;
        alertActive = false;
        pendingRun = null;
        pendingAction = null;
        activeLockScreen = new WeakReference<>(null);
        activeListener = new WeakReference<>(null);
        CalendarDailyAlarms d = daily;
        daily = null;
        if (d != null) d.close();
        worker.shutdownNow();
    }
}
