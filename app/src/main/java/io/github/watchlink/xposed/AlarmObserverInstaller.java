package io.github.watchlink.xposed;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.UserManager;
import androidx.annotation.RequiresApi;
import io.github.libxposed.api.XposedInterface;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.diagnostics.DiagnosticShare;
import io.github.watchlink.setup.ScopeWitness;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Installs live cooperative alarm/calendar hooks atomically inside Clock, Calendar, or Health :PhoneService. */
@RequiresApi(34)
final class AlarmObserverInstaller {
    private final XposedInterface module;
    private final int ownerUid;
    private final TargetProfile profile;
    private final ClassLoader loader;
    private final SharedPreferences remotePrefs;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private Context hostContext;
    private BroadcastReceiver unlockReceiver;
    private AutoCloseable diagnosticHandle;
    private BroadcastReceiver scopeWitnessReceiver;
    private ClockNovemberLive clockLive;
    private ClockCountdownBridge clockTimer;
    private CalendarLiveBridge calendarLive;
    private HealthAlarmBridge healthBridge;
    private WirelessSettingsBridge wirelessBridge;
    private UmsFluidBridge umsBridge;
    private MyDevicesBridge myDevicesBridge;
    private HostSettings hostSettings;

    AlarmObserverInstaller(XposedInterface module, TargetProfile profile, ClassLoader loader,
                           int ownerUid, SharedPreferences remotePrefs) {
        this(module, profile, loader, ownerUid, remotePrefs, null, null);
    }

    AlarmObserverInstaller(XposedInterface module, TargetProfile profile, ClassLoader loader,
                           int ownerUid, SharedPreferences remotePrefs,
                           WirelessSettingsBridge preInstalledWireless,
                           MyDevicesBridge preInstalledMyDevices) {
        this.module = module;
        this.ownerUid = ownerUid;
        this.profile = profile;
        this.loader = loader;
        this.remotePrefs = remotePrefs;
        this.wirelessBridge = preInstalledWireless;
        this.myDevicesBridge = preInstalledMyDevices;
    }

    @RequiresApi(34)
    void installReminder(String[] sources, Context readyApplication, Consumer<Context> onContextReady) {
        if (closed.get()) return;
        ClockSymbolResolver.Symbols preScannedClock = null;
        CalendarSymbolResolver.Symbols preScannedCalendar = null;
        try {
            Context context = readyApplication;
            if (context == null) {
                String appGetter = profile == TargetProfile.CALENDAR ? "t" : "k";
                if (profile == TargetProfile.CLOCK) {
                    preScannedClock = ClockSymbolResolver.scanApk(sources);
                    if (preScannedClock != null) appGetter = preScannedClock.applicationGetter();
                } else if (profile == TargetProfile.CALENDAR) {
                    preScannedCalendar = CalendarSymbolResolver.scanApk(sources);
                    if (preScannedCalendar != null) appGetter = preScannedCalendar.applicationGetter();
                }
                context = legacyApplicationContext(profile, appGetter);
            }
            if (closed.get()) return;
            if (context == null || !profile.packageName.equals(context.getPackageName())
                    || !SenderVerifier.trustedReminderTarget(context, profile)) {
                throw new IllegalStateException("REMINDER_TARGET_IDENTITY");
            }
            hostContext = context.getApplicationContext();
            if (onContextReady != null) onContextReady.accept(hostContext);
            if (waitForUnlock(hostContext, sources, onContextReady)) return;
            if (closed.get()) return;

            diagnosticHandle = DiagnosticShare.installHost(hostContext, ownerUid);
            String localSuffix = switch (profile) {
                case CLOCK -> ".clock_settings_v1";
                case CALENDAR -> ".calendar_settings_v1";
                case HEALTH -> ".health_settings_v1";
                case WIRELESS_SETTINGS -> ".wireless_settings_v1";
                case UMS -> ".ums_settings_v1";
                case MY_DEVICES -> ".mydevices_settings_v1";
            };
            SharedPreferences local = hostContext.getSharedPreferences(
                    BuildConfig.APPLICATION_ID + localSuffix, Context.MODE_PRIVATE);
            HostSettings settings = new HostSettings(remotePrefs, local);
            hostSettings = settings;
            ProbeLog.info(settings.remoteAvailable() ? "REMOTE_PREFS_READY source=" + profile
                    : "REMOTE_PREFS_UNAVAILABLE source=" + profile);

            boolean localControl = false;
            if (profile == TargetProfile.CLOCK) {
                ApkSymbolCache symbolCache = new ApkSymbolCache(hostContext, "clock");
                ClockSymbolResolver.Symbols symbols = preScannedClock != null
                        ? preScannedClock : ClockSymbolResolver.resolve(symbolCache, sources);
                if (symbols == null) {
                    ProbeLog.info("CLOCK_SYMBOLS_RESOLVED source=CLOCK resolver=UNAVAILABLE");
                    throw new NoSuchMethodException("Clock symbols unavailable");
                }
                if (preScannedClock != null) {
                    symbolCache.write(ApkSymbolCache.fingerprint(ClockSymbolResolver.SCHEMA, sources), symbols.toMap());
                }
                ProbeLog.info("CLOCK_SYMBOLS_RESOLVED source=CLOCK resolver=" + symbols.source());

                Class<?> occurrence = target(symbols.occurrenceClass());
                Class<?> alarmModel = target(symbols.alarmModelClass());
                Class<?> service = target(ClockSymbolResolver.SERVICE);
                if (!android.app.Service.class.isAssignableFrom(service)) {
                    throw new NoSuchMethodException("Clock service");
                }
                ClockNovemberLive live = new ClockNovemberLive(this, occurrence, alarmModel, service, symbols);
                clockLive = live;
                live.install(this, occurrence, service, symbols);
                active.set(true);
                clockTimer = new ClockCountdownBridge(hostContext, settings);
                try { clockTimer.install(this, sources); }
                catch (Exception | LinkageError unavailable) {
                    clockTimer.close();
                    clockTimer = null;
                    ProbeLog.failure("TIMER_CLOCK_UNAVAILABLE", unavailable);
                }
                if (live.actions != null) {
                    localControl = symbols.actionContractVerified() && live.actions.actionsResolved();
                    if (!localControl) {
                        ProbeLog.info("REMINDER_CLOCK_ACTIONS_UNAVAILABLE");
                    }
                    live.actions.registerVerified(hostContext, settings, localControl, symbols.source());
                }
            } else if (profile == TargetProfile.CALENDAR) {
                ApkSymbolCache symbolCache = new ApkSymbolCache(hostContext, "calendar");
                CalendarSymbolResolver.Symbols symbols = preScannedCalendar != null
                        ? preScannedCalendar : CalendarSymbolResolver.resolve(symbolCache, sources);
                if (symbols == null) {
                    ProbeLog.info("CALENDAR_SYMBOLS_RESOLVED source=CALENDAR resolver=UNAVAILABLE");
                    throw new NoSuchMethodException("Calendar symbols unavailable");
                }
                if (preScannedCalendar != null) {
                    symbolCache.write(ApkSymbolCache.fingerprint(CalendarSymbolResolver.SCHEMA, sources), symbols.toMap());
                }
                ProbeLog.info("CALENDAR_SYMBOLS_RESOLVED source=CALENDAR resolver=" + symbols.source());

                CalendarLiveBridge live = new CalendarLiveBridge(hostContext, this, symbols);
                calendarLive = live;
                live.install(this, settings);
                localControl = live.actionsResolved();
            } else if (profile == TargetProfile.WIRELESS_SETTINGS) {
                WirelessSettingsBridge bridge = wirelessBridge;
                if (bridge == null) {
                    bridge = WirelessSettingsBridge.installEarlyHook(module, loader, remotePrefs);
                    wirelessBridge = bridge;
                }
                if (bridge == null || !bridge.isHookActive()) {
                    throw new IllegalStateException("WIRELESS_SETTINGS_HOOK_UNAVAILABLE");
                }
                bridge.attachSettings(settings);
            } else if (profile == TargetProfile.UMS) {
                UmsFluidBridge bridge = new UmsFluidBridge(hostContext, settings, sources);
                umsBridge = bridge;
                bridge.install(this);
            } else if (profile == TargetProfile.MY_DEVICES) {
                MyDevicesBridge bridge = myDevicesBridge;
                if (bridge == null) {
                    bridge = MyDevicesBridge.installEarlyHook(module, loader, remotePrefs);
                    myDevicesBridge = bridge;
                }
                bridge.attachContext(hostContext, settings, this, sources);
            } else {
                HealthAlarmBridge bridge = new HealthAlarmBridge(hostContext, ownerUid, settings, sources);
                healthBridge = bridge;
                bridge.install(this);
            }
            if (closed.get()) {
                close();
                return;
            }
            active.set(true);
            String controlKey = profile == TargetProfile.CALENDAR ? " local_calendar_control=" : " local_clock_control=";
            ProbeLog.info("REMINDER_ADAPTER_READY source=" + profile
                    + controlKey + localControl
                    + " remote_prefs=" + settings.remoteAvailable());
            try {
                scopeWitnessReceiver = ScopeWitness.register(hostContext, ownerUid);
            } catch (RuntimeException | LinkageError unavailable) {
                ProbeLog.failure("REMINDER_SCOPE_WITNESS_UNAVAILABLE", unavailable);
            }
        } catch (Exception | LinkageError error) {
            close();
            ProbeLog.failure("REMINDER_ADAPTER_UNAVAILABLE source=" + profile, error);
        }
    }

    synchronized boolean isBusy() {
        return (clockTimer != null && clockTimer.busy()) || (clockLive != null && clockLive.hasActiveRing())
                || (calendarLive != null && calendarLive.hasActiveRing())
                || (healthBridge != null && healthBridge.hasActiveSession());
    }

    synchronized void close() {
        closed.set(true);
        active.set(false);
        Context ctx = hostContext;
        if (unlockReceiver != null && ctx != null) {
            try { ctx.unregisterReceiver(unlockReceiver); } catch (RuntimeException ignored) {}
            unlockReceiver = null;
        }
        if (scopeWitnessReceiver != null && ctx != null) {
            try { ctx.unregisterReceiver(scopeWitnessReceiver); } catch (RuntimeException ignored) {}
            scopeWitnessReceiver = null;
        }
        if (diagnosticHandle != null) {
            try { diagnosticHandle.close(); } catch (Exception ignored) {}
            diagnosticHandle = null;
        }
        if (clockTimer != null) {
            clockTimer.close();
            clockTimer = null;
        }
        if (clockLive != null) {
            try { clockLive.close(); }
            catch (RuntimeException failure) { ProbeLog.failure("CLOCK_ACTION_UNREGISTER_FAILED", failure); }
            clockLive = null;
        }
        if (calendarLive != null) {
            try { calendarLive.close(); } catch (RuntimeException ignored) {}
            calendarLive = null;
        }
        if (healthBridge != null) {
            try { healthBridge.close(); } catch (RuntimeException ignored) {}
            healthBridge = null;
        }
        if (wirelessBridge != null) {
            try { wirelessBridge.close(); } catch (RuntimeException ignored) {}
            wirelessBridge = null;
        }
        if (umsBridge != null) {
            try { umsBridge.close(); } catch (RuntimeException ignored) {}
            umsBridge = null;
        }
        if (myDevicesBridge != null) {
            try { myDevicesBridge.close(); } catch (RuntimeException ignored) {}
            myDevicesBridge = null;
        }
        if (hostSettings != null) {
            try { hostSettings.close(); } catch (RuntimeException ignored) {}
            hostSettings = null;
        }
        rollbackHooks();
    }

    private Context legacyApplicationContext(TargetProfile target, String appGetter)
            throws ReflectiveOperationException {
        Method getter;
        if (target == TargetProfile.CLOCK) {
            Class<?> application = target(ClockSymbolResolver.APPLICATION);
            if (!Context.class.isAssignableFrom(application)) throw new NoSuchMethodException("Clock application");
            getter = staticMethod(application, appGetter, application);
        } else if (target == TargetProfile.CALENDAR) {
            Class<?> application = target(CalendarSymbolResolver.APPLICATION);
            if (!Context.class.isAssignableFrom(application)) throw new NoSuchMethodException("Calendar application");
            getter = staticMethod(application, appGetter, Application.class);
        } else if (target == TargetProfile.WIRELESS_SETTINGS || target == TargetProfile.UMS
                || target == TargetProfile.MY_DEVICES) {
            return null;
        } else {
            getter = staticMethod(target("com.huawei.hwcommonmodel.application.BaseApplication"),
                    "getContext", Context.class);
        }
        for (int i = 0; i < 30; i++) {
            if (closed.get()) return null;
            Context app = (Context) getter.invoke(null);
            if (app != null && app.getApplicationContext() != null) return app.getApplicationContext();
            try { Thread.sleep(i < 5 ? 10 : 200); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** Clock or Calendar can start at LOCKED_BOOT_COMPLETED and keep that process after first unlock. */
    private boolean waitForUnlock(Context app, String[] sources, Consumer<Context> onContextReady) {
        UserManager users = app.getSystemService(UserManager.class);
        if (users == null || users.isUserUnlocked()) return false;
        class UnlockReceiver extends BroadcastReceiver {
            private final AtomicBoolean started = new AtomicBoolean();

            @Override public void onReceive(Context ignored, Intent intent) {
                if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) resumeIfUnlocked();
            }

            void resumeIfUnlocked() {
                if (closed.get() || !users.isUserUnlocked() || !started.compareAndSet(false, true)) return;
                synchronized (AlarmObserverInstaller.this) {
                    if (unlockReceiver == this) unlockReceiver = null;
                }
                try { app.unregisterReceiver(this); }
                catch (RuntimeException ignored) { /* Already unregistered. */ }
                Thread thread = new Thread(() -> installReminder(sources, app, onContextReady),
                        "WatchLink-unlocked-install");
                thread.setDaemon(true);
                thread.start();
            }
        }
        UnlockReceiver receiver = new UnlockReceiver();
        synchronized (this) { unlockReceiver = receiver; }
        app.registerReceiver(receiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED);
        receiver.resumeIfUnlocked();
        return true;
    }

    private void rollbackHooks() {
        active.set(false);
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); }
            catch (RuntimeException failure) { ProbeLog.failure("ALARM_HOOK_ROLLBACK_FAILED", failure); }
        }
        handles.clear();
    }

    int ownerUid() { return ownerUid; }

    Class<?> target(String name) throws ClassNotFoundException {
        Class<?> type = Class.forName(name, false, loader);
        if (type.getClassLoader() != loader) throw new IllegalStateException("Defining loader mismatch");
        return type;
    }

    Method method(Class<?> type, String name, Class<?> returns, boolean isPrivate,
                  Class<?>... arguments) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, arguments);
        int modifiers = method.getModifiers();
        if (method.getReturnType() != returns || Modifier.isStatic(modifiers)
                || (isPrivate ? !Modifier.isPrivate(modifiers) : !Modifier.isPublic(modifiers))) {
            throw new NoSuchMethodException("Unexpected target shape");
        }
        return method;
    }

    Method staticMethod(Class<?> type, String name, Class<?> returns, Class<?>... arguments)
            throws ReflectiveOperationException {
        Method result = type.getDeclaredMethod(name, arguments);
        int flags = result.getModifiers();
        if (result.getReturnType() != returns || !Modifier.isPublic(flags) || !Modifier.isStatic(flags)) {
            throw new NoSuchMethodException("Unexpected static target shape");
        }
        return result;
    }

    interface Capture { void after(XposedInterface.Chain chain, Object result) throws Exception; }
    interface BeforeCapture { Object before(XposedInterface.Chain chain) throws Exception; }
    interface AfterCapture { void after(XposedInterface.Chain chain, Object token, Object result) throws Exception; }

    private XposedInterface.HookBuilder hookBuilder(Method method, String hookId) {
        XposedInterface.HookBuilder builder = module.hook(method);
        if (hookId != null && !hookId.isEmpty() && module.getApiVersion() >= XposedInterface.API_102) {
            builder.setId(hookId);
        }
        return builder;
    }

    void guard(String hookId, Method method, XposedInterface.Hooker hooker) {
        handles.add(hookBuilder(method, hookId)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> active.get() ? hooker.intercept(chain) : chain.proceed()));
    }

    void intercept(String hookId, Method method, XposedInterface.Hooker hooker) {
        handles.add(hookBuilder(method, hookId)
                .intercept(chain -> active.get() ? hooker.intercept(chain) : chain.proceed()));
    }

    void observeAround(String hookId, Method method, BeforeCapture before, AfterCapture after) {
        handles.add(hookBuilder(method, hookId).intercept(chain -> {
            boolean capture = active.get();
            Object token = null;
            if (capture) {
                try { token = before.before(chain); }
                catch (Exception | LinkageError error) {
                    capture = false;
                    ProbeLog.failure("ALARM_BEFORE_FAILED", error);
                }
            }
            Object result = chain.proceed();
            if (capture) {
                try { after.after(chain, token, result); }
                catch (Exception | LinkageError error) { ProbeLog.failure("ALARM_AFTER_FAILED", error); }
            }
            return result;
        }));
    }

    void observe(String hookId, Method method, Capture capture) {
        handles.add(hookBuilder(method, hookId).intercept(chain -> {
            Object result = chain.proceed();
            if (active.get()) {
                try { capture.after(chain, result); }
                catch (Exception | LinkageError error) { ProbeLog.failure("ALARM_CAPTURE_FAILED", error); }
            }
            return result;
        }));
    }
}
