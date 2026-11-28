package io.github.watchlink.xposed;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.notify.NotificationDndPolicy;
import io.github.watchlink.notify.SleepDndCoordinator;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Huawei Health :PhoneService bridge linking real-time watch sleep/wake detection
 * (BtProxySleepStatusManager.dealDeviceSleepAction: action 16 = fall asleep, 4 = wake up)
 * to two independent Android Do Not Disturb features (enter DND on fall asleep, exit DND on wake up),
 * while persisting the latest observed fall-asleep and wake-up timestamps for diagnostics.
 */
@RequiresApi(34)
final class HealthSleepDndBridge {
    static final String DAEMON_DND_ACTION = BuildConfig.APPLICATION_ID + ".notify.DAEMON_DND_V1";
    static final String EXTRA_FILTER = "filter";
    static final String EXTRA_WATCHLINK_SILENT_PUSH = "watchlink_silent_push";
    static final String EXTRA_WATCHLINK_SILENT_REASON = "watchlink_silent_reason";
    private static final String EXTRA_REMINDER_STATUS = "data_extra_reminder_status";
    private static final int TLV_TAG_REMINDER = 0x13;
    private static final String TLV_SILENT_REMINDER_HEX = "13020000";
    private static final long SILENT_LOG_INTERVAL_MS = 10_000L;

    private static final String PREFS_NAME = BuildConfig.APPLICATION_ID + ".sleep_dnd_state_v1";
    private static final String KEY_LAST_FALL_ASLEEP_AT = "last_fall_asleep_at";
    private static final String KEY_LAST_WAKE_UP_AT = "last_wake_up_at";
    private static final String SLEEP_CALLBACK_CLASS =
            "com.huawei.hwdevice.phoneprocess.mgr.btproxy.callback.BtProxySleepCallback";

    record StatusSnapshot(
            String watchSupport,
            boolean hookReady,
            boolean dndPolicyAccess,
            String lastEvent,
            long lastAt,
            long lastFallAsleepAt,
            long lastWakeUpAt) {}

    private final Context app;
    private final HostSettings settings;
    private final ScheduledExecutorService worker;
    private final HealthCooperativeReadiness readiness;
    private final SharedPreferences statePrefs;

    private BroadcastReceiver dndReceiver;
    private ContentObserver zenModeObserver;
    private volatile boolean installed;
    private volatile boolean hookReady;
    private volatile boolean silentPushHookReady;
    private volatile boolean phoneDndActive;
    private volatile long lastSilentLogElapsedMs;
    private volatile String lastEvent = "NONE";
    private volatile long lastAt;
    private volatile long lastFallAsleepAt;
    private volatile long lastWakeUpAt;

    HealthSleepDndBridge(Context app, HostSettings settings, ScheduledExecutorService worker,
                         HealthCooperativeReadiness readiness) {
        this.app = app;
        this.settings = settings;
        this.worker = worker;
        this.readiness = readiness;
        this.statePrefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.lastFallAsleepAt = Math.max(0L, statePrefs.getLong(KEY_LAST_FALL_ASLEEP_AT, 0L));
        this.lastWakeUpAt = Math.max(0L, statePrefs.getLong(KEY_LAST_WAKE_UP_AT, 0L));
    }

    void install(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        registerDndObservers();
        phoneDndActive = isPhoneDndActive();
        hookReady = installSleepActionHook(installer, symbols);
        silentPushHookReady = installSilentReminderHook(installer, symbols);
        installed = true;
        ProbeLog.info((hookReady ? "HEALTH_SLEEP_DND_READY" : "HEALTH_SLEEP_DND_DEGRADED")
                + " source=HEALTH");
        ProbeLog.info((silentPushHookReady ? "HEALTH_NOTIFY_SILENT_READY" : "HEALTH_NOTIFY_SILENT_DEGRADED")
                + " source=HEALTH");
    }

    void close() {
        installed = false;
        if (dndReceiver != null) {
            try { app.unregisterReceiver(dndReceiver); } catch (RuntimeException ignored) {}
            dndReceiver = null;
        }
        if (zenModeObserver != null) {
            try { app.getContentResolver().unregisterContentObserver(zenModeObserver); } catch (RuntimeException ignored) {}
            zenModeObserver = null;
        }
    }

    StatusSnapshot statusSnapshot() {
        String support = readiness == null ? "UNKNOWN" : readiness.readSleepWatchSupport();
        return new StatusSnapshot(support, hookReady, hasDirectPolicyAccess(), lastEvent, lastAt,
                lastFallAsleepAt, lastWakeUpAt);
    }

    private boolean installSleepActionHook(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        String className = symbols != null ? symbols.sleepStatusMgrClass() : "";
        if (installer == null || className == null || className.isEmpty()) return false;
        try {
            Class<?> sleepMgr = installer.target(className);
            Class<?> cb = installer.target(SLEEP_CALLBACK_CLASS);
            if (!hasFieldOfType(sleepMgr, cb)) return false;
            Method dealAction = uniqueSleepActionMethod(sleepMgr);
            if (dealAction == null) return false;
            installer.observeAround("health.sleep.action", dealAction, chain -> {
                Object rawAction = chain.getArg(1);
                if (installed && rawAction instanceof Integer action
                        && (action == SleepDndCoordinator.ACTION_FALL_ASLEEP
                        || action == SleepDndCoordinator.ACTION_WAKE_UP)) {
                    int code = action;
                    try {
                        worker.execute(() -> handleSleepActionLocked(code));
                    } catch (RuntimeException ignored) {}
                }
                return null;
            }, (chain, token, result) -> {});
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /**
     * Hooks NotifySendData.packagingReminderHex (Locp.j(int, Bundle)) — with unique-signature
     * fallback to packagingReminderHexOld (Locp.c(int, Bundle, int)) — so WatchLink's silent
     * watch notification flag emits TLV 13 02 00 00 directly without being dropped by Huawei's
     * bit-94 gate or overwritten to 0x000F inside packagingReminderHexOld.
     */
    private boolean installSilentReminderHook(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        String className = symbols != null ? symbols.notifySendDataClass() : "";
        if (installer == null || className == null || className.isEmpty()) return false;
        try {
            Class<?> notifySendCls = installer.target(className);
            Method primary = null;
            String methodName = symbols.notifyReminderMethod();
            if (methodName != null && !methodName.isEmpty()) {
                try {
                    Method m = notifySendCls.getDeclaredMethod(methodName, int.class, Bundle.class);
                    if (!Modifier.isStatic(m.getModifiers()) && m.getReturnType() == String.class) {
                        primary = m;
                    }
                } catch (NoSuchMethodException ignored) {}
            }
            if (primary != null) {
                installer.intercept("health.notify.reminder", primary, chain -> {
                    try {
                        String forced = maybeForceSilentReminderTlv(chain.getArg(0), chain.getArg(1));
                        if (forced != null) return forced;
                    } catch (RuntimeException | LinkageError err) {
                        ProbeLog.failure("HEALTH_NOTIFY_SILENT_DEGRADED", err);
                    }
                    return chain.proceed();
                });
                return true;
            }
            Method fallback = uniqueOldReminderMethod(notifySendCls);
            if (fallback != null) {
                installer.intercept("health.notify.reminder.old", fallback, chain -> {
                    try {
                        String forced = maybeForceSilentReminderTlv(chain.getArg(0), chain.getArg(1));
                        if (forced != null) return forced;
                    } catch (RuntimeException | LinkageError err) {
                        ProbeLog.failure("HEALTH_NOTIFY_SILENT_DEGRADED", err);
                    }
                    return chain.proceed();
                });
                return true;
            }
            return false;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private String maybeForceSilentReminderTlv(Object rawTag, Object rawBundle) {
        if (!installed || !(rawTag instanceof Integer tag) || tag != TLV_TAG_REMINDER
                || !(rawBundle instanceof Bundle bundle)) {
            return null;
        }
        boolean silentOnDnd = settings.isNotifyMuteDndEnabled();
        boolean silentOnPhoneSilent = settings.isNotifySilentPushEnabled();
        if (!silentOnDnd && !silentOnPhoneSilent) return null;

        String pkg = bundle.getString("packagename");
        if (pkg == null || pkg.isEmpty()) pkg = bundle.getString("data_package_name");
        String category = bundle.getString("data_category");
        String daemonReason = bundle.getString(EXTRA_WATCHLINK_SILENT_REASON);
        boolean daemonTagged = bundle.getBoolean(EXTRA_WATCHLINK_SILENT_PUSH, false);
        boolean dndActive = phoneDndActive || "DND".equals(daemonReason);
        boolean markedSilent = "PHONE_SILENT".equals(daemonReason)
                || (daemonTagged && daemonReason == null)
                || bundle.getInt(EXTRA_REMINDER_STATUS, 15) == 0;
        if (!NotificationDndPolicy.shouldPushSilentlyToWatch(
                silentOnDnd, silentOnPhoneSilent, dndActive, markedSilent, pkg, category)) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastSilentLogElapsedMs >= SILENT_LOG_INTERVAL_MS) {
            lastSilentLogElapsedMs = now;
            ProbeLog.info("HEALTH_NOTIFY_SILENT_PUSH source=HEALTH silent_reason="
                    + (silentOnDnd && dndActive ? "DND" : "PHONE_SILENT"));
        }
        return TLV_SILENT_REMINDER_HEX;
    }

    private void registerDndObservers() {
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!installed) return;
                    try {
                        worker.execute(() -> phoneDndActive = isPhoneDndActive());
                    } catch (RuntimeException ignored) {}
                }
            };
            app.registerReceiver(r,
                    new IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                    Context.RECEIVER_EXPORTED);
            dndReceiver = r;
        } catch (RuntimeException ignored) {}

        try {
            Uri zenUri = Settings.Global.getUriFor("zen_mode");
            if (zenUri != null) {
                ContentObserver obs = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean selfChange) {
                        if (!installed) return;
                        try {
                            worker.execute(() -> phoneDndActive = isPhoneDndActive());
                        } catch (RuntimeException ignored) {}
                    }
                };
                app.getContentResolver().registerContentObserver(zenUri, false, obs);
                zenModeObserver = obs;
            }
        } catch (RuntimeException ignored) {}
    }

    private static Method uniqueOldReminderMethod(Class<?> cls) {
        Method found = null;
        for (Method m : cls.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isStatic(mod) || m.isSynthetic()) continue;
            if (m.getReturnType() != String.class || m.getParameterCount() != 3) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p[0] == int.class && p[1] == Bundle.class && p[2] == int.class) {
                if (found != null) return null;
                found = m;
            }
        }
        return found;
    }

    private void handleSleepActionLocked(int action) {
        if (!installed) return;
        long nowWall = System.currentTimeMillis();
        String actionName = action == SleepDndCoordinator.ACTION_FALL_ASLEEP ? "FALL_ASLEEP" : "WAKE_UP";
        recordSleepTimestamp(action, nowWall);
        ProbeLog.info("HEALTH_SLEEP_ACTION source=HEALTH sleep_action=" + actionName);

        boolean sleepEnableDnd = settings.isSleepAutoDndEnabled();
        boolean wakeExitDnd = settings.isWakeExitDndEnabled();
        boolean phoneDndActive = isPhoneDndActive();
        SleepDndCoordinator.Decision decision = SleepDndCoordinator.onSleepAction(
                sleepEnableDnd, wakeExitDnd, action, phoneDndActive);
        switch (decision) {
            case ENABLE_DND -> {
                String apply = applyDndFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                if ("APPLIED".equals(apply) || "DAEMON_FALLBACK".equals(apply)) {
                    lastEvent = "FALL_ASLEEP_DND_ON";
                    lastAt = nowWall;
                    ProbeLog.info("HEALTH_SLEEP_DND_APPLIED source=HEALTH dnd_result="
                            + ("APPLIED".equals(apply) ? "ENABLE_DND" : "DAEMON_FALLBACK"));
                } else {
                    lastEvent = "NO_POLICY_ACCESS".equals(apply) ? "NO_POLICY_ACCESS" : "FAILED";
                    lastAt = nowWall;
                    ProbeLog.info("HEALTH_SLEEP_DND_APPLIED source=HEALTH dnd_result=" + lastEvent);
                }
            }
            case DISABLE_DND -> {
                String apply = applyDndFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                if ("APPLIED".equals(apply) || "DAEMON_FALLBACK".equals(apply)) {
                    lastEvent = "WAKE_UP_DND_OFF";
                    lastAt = nowWall;
                    ProbeLog.info("HEALTH_SLEEP_DND_APPLIED source=HEALTH dnd_result="
                            + ("APPLIED".equals(apply) ? "DISABLE_DND" : "DAEMON_FALLBACK"));
                } else {
                    lastEvent = "NO_POLICY_ACCESS".equals(apply) ? "NO_POLICY_ACCESS" : "FAILED";
                    lastAt = nowWall;
                    ProbeLog.info("HEALTH_SLEEP_DND_APPLIED source=HEALTH dnd_result=" + lastEvent);
                }
            }
            case SKIP_ALREADY_DND -> {
                lastEvent = "FALL_ASLEEP_SKIP_ALREADY_DND";
                lastAt = nowWall;
                ProbeLog.info("HEALTH_SLEEP_DND_APPLIED source=HEALTH dnd_result=SKIP_ALREADY_DND");
            }
            case SKIP_ALREADY_OFF -> {
                lastEvent = "WAKE_UP_SKIP_ALREADY_OFF";
                lastAt = nowWall;
                ProbeLog.info("HEALTH_SLEEP_DND_APPLIED source=HEALTH dnd_result=SKIP_ALREADY_OFF");
            }
            case DISABLED, IGNORE_ACTION -> {}
        }
    }

    private String applyDndFilter(int filter) {
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm != null && nm.isNotificationPolicyAccessGranted()) {
            try {
                nm.setInterruptionFilter(filter);
                return "APPLIED";
            } catch (SecurityException denied) {
                return requestViaDaemon(filter) ? "DAEMON_FALLBACK" : "NO_POLICY_ACCESS";
            } catch (RuntimeException err) {
                return requestViaDaemon(filter) ? "DAEMON_FALLBACK" : "FAILED";
            }
        }
        return requestViaDaemon(filter) ? "DAEMON_FALLBACK" : "NO_POLICY_ACCESS";
    }

    private boolean requestViaDaemon(int filter) {
        try {
            Intent req = new Intent(DAEMON_DND_ACTION)
                    .setPackage(TargetProfile.HEALTH.packageName)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    .putExtra(EXTRA_FILTER, filter);
            app.sendBroadcast(req);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean hasDirectPolicyAccess() {
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationPolicyAccessGranted();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isPhoneDndActive() {
        int filter = NotificationDndPolicy.INTERRUPTION_FILTER_UNKNOWN;
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm != null) filter = nm.getCurrentInterruptionFilter();
        } catch (RuntimeException ignored) {}
        int zenMode = 0;
        try {
            zenMode = Settings.Global.getInt(app.getContentResolver(), "zen_mode", 0);
        } catch (RuntimeException ignored) {}
        return NotificationDndPolicy.isPhoneDndActive(filter, zenMode);
    }

    private void recordSleepTimestamp(int action, long nowWall) {
        if (nowWall <= 0L) return;
        if (action == SleepDndCoordinator.ACTION_FALL_ASLEEP) {
            lastFallAsleepAt = nowWall;
            try { statePrefs.edit().putLong(KEY_LAST_FALL_ASLEEP_AT, nowWall).apply(); }
            catch (RuntimeException ignored) {}
        } else if (action == SleepDndCoordinator.ACTION_WAKE_UP) {
            lastWakeUpAt = nowWall;
            try { statePrefs.edit().putLong(KEY_LAST_WAKE_UP_AT, nowWall).apply(); }
            catch (RuntimeException ignored) {}
        }
    }

    private static boolean hasFieldOfType(Class<?> owner, Class<?> fieldType) {
        for (java.lang.reflect.Field f : owner.getDeclaredFields()) {
            if (f.getType() == fieldType) return true;
        }
        return false;
    }

    private static Method uniqueSleepActionMethod(Class<?> cls) {
        Method found = null;
        for (Method m : cls.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (!Modifier.isPublic(mod) || Modifier.isStatic(mod) || m.isSynthetic()) continue;
            if (m.getReturnType() != void.class || m.getParameterCount() != 2) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p[0] == String.class && p[1] == int.class) {
                if (found != null) return null;
                found = m;
            }
        }
        return found;
    }
}
