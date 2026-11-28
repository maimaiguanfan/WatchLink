package io.github.watchlink.xposed;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.notify.NotificationDndPolicy;
import io.github.watchlink.notify.SleepDndCoordinator;
import io.github.watchlink.notify.SleepVideoStopPolicy;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Huawei Health :PhoneService bridge linking real-time watch sleep/wake detection
 * (BtProxySleepStatusManager.dealDeviceSleepAction: action 16 = fall asleep, 4 = wake up)
 * to independent Android Do Not Disturb toggles and fall-asleep media playback pause,
 * plus 5.2.1 TLV 13 02 00 00 silent-push enforcement across DND, Focus/ZenSpace, Game foreground,
 * unlocked screen, and silent phone notifications.
 */
@RequiresApi(34)
final class HealthSleepDndBridge {
    static final String DAEMON_DND_ACTION = BuildConfig.APPLICATION_ID + ".notify.DAEMON_DND_V1";
    static final String EXTRA_FILTER = "filter";
    static final String EXTRA_WATCHLINK_SILENT_PUSH = "watchlink_silent_push";
    static final String EXTRA_WATCHLINK_SILENT_REASON = "watchlink_silent_reason";
    static final String EXTRA_WATCHLINK_FORCE_SILENT_STAGED = "watchlink_force_silent_staged";
    static final String EXTRA_WATCHLINK_FORCE_SILENT_BURST = "watchlink_force_silent_burst";
    private static final String EXTRA_REMINDER_STATUS = "data_extra_reminder_status";
    private static final int TLV_TAG_REMINDER = 0x13;
    private static final String TLV_SILENT_REMINDER_HEX = "13020000";
    private static final long SILENT_LOG_INTERVAL_MS = 10_000L;
    private static final long AUDIO_FOCUS_RELEASE_DELAY_MS = 1_500L;
    private static final long VIDEO_STOP_COOLDOWN_MS = 60_000L;
    private static final String HEALTH_NOTIFICATION_LISTENER =
            "com.huawei.bone.ui.setting.NotificationPushListener";

    private static final String PREFS_NAME = BuildConfig.APPLICATION_ID + ".sleep_dnd_state_v1";
    private static final String KEY_LAST_FALL_ASLEEP_AT = "last_fall_asleep_at";
    private static final String KEY_LAST_WAKE_UP_AT = "last_wake_up_at";
    private static final String KEY_SAVED_SCREEN_TIMEOUT = "pending_restore_screen_timeout";
    private static final int SLEEP_TEMP_SCREEN_TIMEOUT_MS = 1_000;
    private static final int MIN_RESTORE_SCREEN_TIMEOUT_MS = 15_000;
    private static final long SCREEN_TIMEOUT_RESTORE_FALLBACK_MS = 15_000L;
    private static final String SLEEP_CALLBACK_CLASS =
            "com.huawei.hwdevice.phoneprocess.mgr.btproxy.callback.BtProxySleepCallback";

    record StatusSnapshot(
            String watchSupport,
            boolean hookReady,
            boolean dndPolicyAccess,
            String lastEvent,
            long lastAt,
            long lastFallAsleepAt,
            long lastWakeUpAt,
            boolean writeSettingsAccess) {
        StatusSnapshot(String watchSupport, boolean hookReady, boolean dndPolicyAccess,
                       String lastEvent, long lastAt, long lastFallAsleepAt, long lastWakeUpAt) {
            this(watchSupport, hookReady, dndPolicyAccess, lastEvent, lastAt,
                    lastFallAsleepAt, lastWakeUpAt, false);
        }
    }

    private final Context app;
    private final HostSettings settings;
    private final ScheduledExecutorService worker;
    private final HealthCooperativeReadiness readiness;
    private final SharedPreferences statePrefs;
    private final PhoneModeObserver modeObserver;

    private volatile boolean installed;
    private volatile boolean hookReady;
    private volatile boolean silentPushHookReady;
    private volatile long lastSilentLogElapsedMs;
    private volatile long lastMediaPauseElapsedMs;
    private volatile long lastVideoStopElapsedMs;
    private volatile String lastEvent = "NONE";
    private volatile long lastAt;
    private volatile long lastFallAsleepAt;
    private volatile long lastWakeUpAt;
    private BroadcastReceiver screenOffRestoreReceiver;
    private ScheduledFuture<?> screenOffRestoreTask;

    HealthSleepDndBridge(Context app, HostSettings settings, ScheduledExecutorService worker,
                         HealthCooperativeReadiness readiness) {
        this.app = app;
        this.settings = settings;
        this.worker = worker;
        this.readiness = readiness;
        this.statePrefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.modeObserver = new PhoneModeObserver(app, worker);
        this.lastFallAsleepAt = Math.max(0L, statePrefs.getLong(KEY_LAST_FALL_ASLEEP_AT, 0L));
        this.lastWakeUpAt = Math.max(0L, statePrefs.getLong(KEY_LAST_WAKE_UP_AT, 0L));
    }

    void install(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        restorePendingScreenTimeoutIfAny();
        modeObserver.start();
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
        restorePendingScreenTimeoutIfAny();
        modeObserver.close();
    }

    StatusSnapshot statusSnapshot() {
        String support = readiness == null ? "UNKNOWN" : readiness.readSleepWatchSupport();
        return new StatusSnapshot(support, hookReady, hasDirectPolicyAccess(), lastEvent, lastAt,
                lastFallAsleepAt, lastWakeUpAt, hasWriteSettingsAccess());
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
        if (bundle.getBoolean(EXTRA_WATCHLINK_FORCE_SILENT_STAGED, false)) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastSilentLogElapsedMs >= SILENT_LOG_INTERVAL_MS) {
                lastSilentLogElapsedMs = now;
                ProbeLog.info("HEALTH_NOTIFY_SILENT_PUSH source=HEALTH silent_reason="
                        + NotificationDndPolicy.REASON_DISCONNECT_STAGED);
            }
            return TLV_SILENT_REMINDER_HEX;
        }
        if (bundle.getBoolean(EXTRA_WATCHLINK_FORCE_SILENT_BURST, false)) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastSilentLogElapsedMs >= SILENT_LOG_INTERVAL_MS) {
                lastSilentLogElapsedMs = now;
                ProbeLog.info("HEALTH_NOTIFY_SILENT_PUSH source=HEALTH silent_reason="
                        + NotificationDndPolicy.REASON_BURST_THROTTLE);
            }
            return TLV_SILENT_REMINDER_HEX;
        }
        String reason = evaluateModeSilentReason(bundle);
        if (reason == null) return null;

        long now = SystemClock.elapsedRealtime();
        if (now - lastSilentLogElapsedMs >= SILENT_LOG_INTERVAL_MS) {
            lastSilentLogElapsedMs = now;
            ProbeLog.info("HEALTH_NOTIFY_SILENT_PUSH source=HEALTH silent_reason=" + reason);
        }
        return TLV_SILENT_REMINDER_HEX;
    }

    /** Returns true when enabled 5-way silent-push rules would silence this notification bundle. */
    boolean wouldBeSilentByMode(Bundle bundle) {
        return installed && bundle != null && evaluateModeSilentReason(bundle) != null;
    }

    private String evaluateModeSilentReason(Bundle bundle) {
        boolean silentOnDnd = settings.isNotifyMuteDndEnabled();
        boolean silentOnFocus = settings.isNotifySilentFocusEnabled();
        boolean silentOnGame = settings.isNotifySilentGameEnabled();
        boolean silentOnScreenOn = settings.isNotifySilentScreenOnEnabled();
        boolean silentOnPhoneSilent = settings.isNotifySilentPushEnabled();
        if (!silentOnDnd && !silentOnFocus && !silentOnGame && !silentOnScreenOn && !silentOnPhoneSilent) {
            return null;
        }

        String pkg = bundle.getString("packagename");
        if (pkg == null || pkg.isEmpty()) pkg = bundle.getString("data_package_name");
        String category = bundle.getString("data_category");
        String daemonReason = bundle.getString(EXTRA_WATCHLINK_SILENT_REASON);
        boolean daemonTagged = bundle.getBoolean(EXTRA_WATCHLINK_SILENT_PUSH, false);

        boolean dndActive = modeObserver.isDndActive() || NotificationDndPolicy.REASON_DND.equals(daemonReason);
        boolean focusActive = modeObserver.isFocusActive() || NotificationDndPolicy.REASON_FOCUS.equals(daemonReason);
        boolean gameActive = modeObserver.isGameForeground() || NotificationDndPolicy.REASON_GAME.equals(daemonReason);
        boolean screenUnlocked = modeObserver.isScreenUnlocked() || NotificationDndPolicy.REASON_SCREEN_ON.equals(daemonReason);
        boolean markedSilent = NotificationDndPolicy.REASON_PHONE_SILENT.equals(daemonReason)
                || (daemonTagged && daemonReason == null)
                || bundle.getInt(EXTRA_REMINDER_STATUS, 15) == 0;

        return NotificationDndPolicy.evaluateSilentReason(
                silentOnDnd, silentOnFocus, silentOnGame, silentOnScreenOn, silentOnPhoneSilent,
                dndActive, focusActive, gameActive, screenUnlocked, markedSilent,
                pkg, category);
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
        modeObserver.refreshDnd();
        boolean phoneDndActive = modeObserver.isDndActive();
        SleepDndCoordinator.Decision decision = SleepDndCoordinator.onSleepAction(
                sleepEnableDnd, wakeExitDnd, action, phoneDndActive);
        boolean dndUpdatedLastEvent = false;
        switch (decision) {
            case ENABLE_DND -> {
                String apply = applyDndFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                if ("APPLIED".equals(apply) || "DAEMON_FALLBACK".equals(apply)) {
                    lastEvent = "FALL_ASLEEP_DND_ON";
                    lastAt = nowWall;
                    dndUpdatedLastEvent = true;
                    ProbeLog.info("HEALTH_SLEEP_DND_APPLIED source=HEALTH dnd_result="
                            + ("APPLIED".equals(apply) ? "ENABLE_DND" : "DAEMON_FALLBACK"));
                } else {
                    lastEvent = "NO_POLICY_ACCESS".equals(apply) ? "NO_POLICY_ACCESS" : "FAILED";
                    lastAt = nowWall;
                    dndUpdatedLastEvent = true;
                    ProbeLog.info("HEALTH_SLEEP_DND_APPLIED source=HEALTH dnd_result=" + lastEvent);
                }
            }
            case DISABLE_DND -> {
                String apply = applyDndFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                if ("APPLIED".equals(apply) || "DAEMON_FALLBACK".equals(apply)) {
                    lastEvent = "WAKE_UP_DND_OFF";
                    lastAt = nowWall;
                    dndUpdatedLastEvent = true;
                    ProbeLog.info("HEALTH_SLEEP_DND_APPLIED source=HEALTH dnd_result="
                            + ("APPLIED".equals(apply) ? "DISABLE_DND" : "DAEMON_FALLBACK"));
                } else {
                    lastEvent = "NO_POLICY_ACCESS".equals(apply) ? "NO_POLICY_ACCESS" : "FAILED";
                    lastAt = nowWall;
                    dndUpdatedLastEvent = true;
                    ProbeLog.info("HEALTH_SLEEP_DND_APPLIED source=HEALTH dnd_result=" + lastEvent);
                }
            }
            case SKIP_ALREADY_DND -> {
                lastEvent = "FALL_ASLEEP_SKIP_ALREADY_DND";
                lastAt = nowWall;
                dndUpdatedLastEvent = true;
                ProbeLog.info("HEALTH_SLEEP_DND_APPLIED source=HEALTH dnd_result=SKIP_ALREADY_DND");
            }
            case SKIP_ALREADY_OFF -> {
                lastEvent = "WAKE_UP_SKIP_ALREADY_OFF";
                lastAt = nowWall;
                dndUpdatedLastEvent = true;
                ProbeLog.info("HEALTH_SLEEP_DND_APPLIED source=HEALTH dnd_result=SKIP_ALREADY_OFF");
            }
            case DISABLED, IGNORE_ACTION -> {}
        }

        boolean mediaUpdated = handleSleepPauseMediaLocked(action, nowWall, !dndUpdatedLastEvent);
        handleSleepStopVideoLocked(action, nowWall, !dndUpdatedLastEvent && !mediaUpdated);
    }

    private boolean handleSleepPauseMediaLocked(int action, long nowWall, boolean updateLastEvent) {
        AudioManager am = null;
        boolean musicActive = false;
        boolean inCall = false;
        try {
            am = app.getSystemService(AudioManager.class);
            if (am != null) {
                int mode = am.getMode();
                inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
                musicActive = am.isMusicActive();
            }
        } catch (RuntimeException ignored) {}

        long nowElapsed = SystemClock.elapsedRealtime();
        SleepDndCoordinator.MediaPauseDecision mediaDecision = SleepDndCoordinator.onSleepMediaAction(
                settings.isSleepPauseMediaEnabled(), action, musicActive, inCall, nowElapsed, lastMediaPauseElapsedMs);
        switch (mediaDecision) {
            case PAUSE_MEDIA -> {
                boolean paused = pauseMediaPlayback(am, false);
                if (paused) {
                    lastMediaPauseElapsedMs = nowElapsed;
                    if (updateLastEvent) {
                        lastEvent = "FALL_ASLEEP_MEDIA_PAUSED";
                        lastAt = nowWall;
                    }
                    ProbeLog.info("HEALTH_SLEEP_MEDIA_PAUSED source=HEALTH media_result=PAUSED");
                    return true;
                } else {
                    ProbeLog.info("HEALTH_SLEEP_MEDIA_PAUSED source=HEALTH media_result=FAILED");
                }
            }
            case SKIP_IN_CALL -> ProbeLog.info("HEALTH_SLEEP_MEDIA_PAUSED source=HEALTH media_result=SKIP_IN_CALL");
            case SKIP_NOT_PLAYING -> ProbeLog.info("HEALTH_SLEEP_MEDIA_PAUSED source=HEALTH media_result=SKIP_NOT_PLAYING");
            case SKIP_COOLDOWN -> ProbeLog.info("HEALTH_SLEEP_MEDIA_PAUSED source=HEALTH media_result=SKIP_COOLDOWN");
            case DISABLED, IGNORE_ACTION -> {}
        }
        return false;
    }

    private void handleSleepStopVideoLocked(int action, long nowWall, boolean updateLastEvent) {
        if (!settings.isSleepStopVideoEnabled() || action != SleepDndCoordinator.ACTION_FALL_ASLEEP) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        if (lastVideoStopElapsedMs > 0L && nowElapsed >= lastVideoStopElapsedMs
                && (nowElapsed - lastVideoStopElapsedMs) < VIDEO_STOP_COOLDOWN_MS) {
            ProbeLog.info("HEALTH_SLEEP_VIDEO_STOPPED source=HEALTH video_result=SKIP_COOLDOWN");
            return;
        }
        AudioManager am = null;
        boolean musicActive = false;
        boolean inCall = false;
        try {
            am = app.getSystemService(AudioManager.class);
            if (am != null) {
                int mode = am.getMode();
                inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
                musicActive = am.isMusicActive();
            }
        } catch (RuntimeException ignored) {}

        Set<String> targets = SleepVideoStopPolicy.combinedTargetPackages(settings.sleepStopVideoCustomPkgs());
        boolean matchedTargetSession = false;
        boolean hasNonTargetActiveSession = false;
        try {
            MediaSessionManager msm = app.getSystemService(MediaSessionManager.class);
            if (msm != null) {
                ComponentName listener = new ComponentName(app.getPackageName(), HEALTH_NOTIFICATION_LISTENER);
                List<MediaController> controllers = msm.getActiveSessions(listener);
                if (controllers != null) {
                    for (MediaController controller : controllers) {
                        if (controller == null) continue;
                        String pkg = controller.getPackageName();
                        PlaybackState ps = controller.getPlaybackState();
                        boolean active = ps != null && (ps.getState() == PlaybackState.STATE_PLAYING
                                || ps.getState() == PlaybackState.STATE_BUFFERING
                                || ps.getState() == PlaybackState.STATE_CONNECTING);
                        if (pkg != null && targets.contains(pkg)) {
                            if (active || musicActive) {
                                matchedTargetSession = true;
                                try {
                                    controller.getTransportControls().pause();
                                    controller.getTransportControls().stop();
                                } catch (RuntimeException ignored) {}
                            }
                        } else if (active) {
                            hasNonTargetActiveSession = true;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) { /* Proceed with screen/audio fallback. */ }

        boolean hasInstalledTarget = hasAnyInstalledPackage(targets);
        boolean screenUnlocked = modeObserver.isScreenUnlocked();
        SleepVideoStopPolicy.Decision decision = SleepVideoStopPolicy.evaluate(
                true, action, inCall, matchedTargetSession, hasNonTargetActiveSession,
                screenUnlocked, musicActive, hasInstalledTarget);
        switch (decision) {
            case STOP_MATCHED_SESSION, STOP_SCREEN_ON_VIDEO, STOP_ACTIVE_AUDIO_VIDEO -> {
                boolean stoppedAudio = pauseMediaPlayback(am, true);
                boolean returnedHome = returnToHomeScreen();
                if (returnedHome || stoppedAudio || matchedTargetSession) {
                    worker.schedule(this::triggerScreenOffViaTimeout, 400L, TimeUnit.MILLISECONDS);
                }
                if (stoppedAudio || returnedHome || matchedTargetSession) {
                    lastVideoStopElapsedMs = nowElapsed;
                    if (updateLastEvent) {
                        lastEvent = "FALL_ASLEEP_VIDEO_STOPPED";
                        lastAt = nowWall;
                    }
                    ProbeLog.info("HEALTH_SLEEP_VIDEO_STOPPED source=HEALTH video_result=" + decision.name());
                } else {
                    ProbeLog.info("HEALTH_SLEEP_VIDEO_STOPPED source=HEALTH video_result=FAILED");
                }
            }
            case SKIP_IN_CALL -> ProbeLog.info("HEALTH_SLEEP_VIDEO_STOPPED source=HEALTH video_result=SKIP_IN_CALL");
            case SKIP_NO_TARGET -> ProbeLog.info("HEALTH_SLEEP_VIDEO_STOPPED source=HEALTH video_result=SKIP_NO_TARGET");
            case DISABLED, IGNORE_ACTION -> {}
        }
    }

    private boolean hasAnyInstalledPackage(Set<String> packages) {
        PackageManager pm = app.getPackageManager();
        if (pm == null) return false;
        for (String pkg : packages) {
            try {
                pm.getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {}
        }
        return false;
    }

    private boolean returnToHomeScreen() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(home);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean hasWriteSettingsAccess() {
        try {
            return Settings.System.canWrite(app);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Turns off the screen cleanly via Android's public SCREEN_OFF_TIMEOUT setting when Health
     * holds WRITE_SETTINGS, then immediately restores the user's original timeout upon SCREEN_OFF
     * (or after a bounded safety timer).
     */
    private synchronized void triggerScreenOffViaTimeout() {
        if (!installed || !hasWriteSettingsAccess()) return;
        try {
            int saved = statePrefs.getInt(KEY_SAVED_SCREEN_TIMEOUT, -1);
            int original = saved >= MIN_RESTORE_SCREEN_TIMEOUT_MS
                    ? saved
                    : Settings.System.getInt(app.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 60_000);
            if (original < MIN_RESTORE_SCREEN_TIMEOUT_MS) {
                original = 60_000;
            }
            statePrefs.edit().putInt(KEY_SAVED_SCREEN_TIMEOUT, original).apply();
            unregisterScreenOffRestoreLocked();
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        try { worker.execute(HealthSleepDndBridge.this::restorePendingScreenTimeoutIfAny); }
                        catch (RuntimeException ignored) { restorePendingScreenTimeoutIfAny(); }
                    }
                }
            };
            app.registerReceiver(r, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_EXPORTED);
            screenOffRestoreReceiver = r;
            screenOffRestoreTask = worker.schedule(
                    this::restorePendingScreenTimeoutIfAny,
                    SCREEN_TIMEOUT_RESTORE_FALLBACK_MS,
                    TimeUnit.MILLISECONDS);
            Settings.System.putInt(app.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, SLEEP_TEMP_SCREEN_TIMEOUT_MS);
        } catch (RuntimeException ignored) {
            restorePendingScreenTimeoutIfAny();
        }
    }

    private synchronized void restorePendingScreenTimeoutIfAny() {
        unregisterScreenOffRestoreLocked();
        int saved = statePrefs.getInt(KEY_SAVED_SCREEN_TIMEOUT, -1);
        if (saved >= MIN_RESTORE_SCREEN_TIMEOUT_MS && hasWriteSettingsAccess()) {
            try {
                Settings.System.putInt(app.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, saved);
            } catch (RuntimeException ignored) {}
        }
        if (saved != -1) {
            try { statePrefs.edit().remove(KEY_SAVED_SCREEN_TIMEOUT).apply(); }
            catch (RuntimeException ignored) {}
        }
    }

    private void unregisterScreenOffRestoreLocked() {
        if (screenOffRestoreTask != null) {
            screenOffRestoreTask.cancel(false);
            screenOffRestoreTask = null;
        }
        if (screenOffRestoreReceiver != null) {
            try { app.unregisterReceiver(screenOffRestoreReceiver); } catch (RuntimeException ignored) {}
            screenOffRestoreReceiver = null;
        }
    }

    private boolean pauseMediaPlayback(AudioManager am, boolean includeStopKey) {
        if (am == null) return false;
        boolean dispatched = false;
        try {
            long eventTime = SystemClock.uptimeMillis();
            if (includeStopKey) {
                am.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_MEDIA_STOP, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_MEDIA_STOP, 0));
            }
            am.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            dispatched = true;
        } catch (RuntimeException ignored) {}

        // Permanent AUDIOFOCUS_GAIN grab (held briefly then abandoned) triggers AUDIOFOCUS_LOSS so
        // short-video / live players (Douyin, Bilibili, Hongguo, etc.) stop without auto-resuming.
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(change -> {})
                    .build();
            int focus = am.requestAudioFocus(req);
            if (focus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                dispatched = true;
                worker.schedule(() -> {
                    try { am.abandonAudioFocusRequest(req); } catch (RuntimeException ignored) {}
                }, AUDIO_FOCUS_RELEASE_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        } catch (RuntimeException ignored) {}
        return dispatched;
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
