package io.github.watchlink.setup;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.TargetProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Owner UI only. Manages LSPosed API 102 scope, running-target inspection, hot reload, and RemotePreferences. */
public final class ModuleScope {
    public static final List<String> REQUIRED_PACKAGES = List.of(
            TargetProfile.CLOCK.packageName, TargetProfile.HEALTH.packageName);
    public static final List<String> OPTIONAL_PACKAGES = List.of(
            TargetProfile.CALENDAR.packageName,
            TargetProfile.WIRELESS_SETTINGS.packageName,
            TargetProfile.UMS.packageName);

    public record TargetRuntime(String state, long loadedVersionCode) {
        public static final TargetRuntime UNSUPPORTED = new TargetRuntime("UNSUPPORTED", 0);
        public static final TargetRuntime ABSENT = new TargetRuntime("ABSENT", 0);

        public boolean isStale() {
            return "STALE".equals(state) || "FAILED".equals(state);
        }

        public boolean isReloading() {
            return "RELOADING".equals(state);
        }

        public boolean isRunning() {
            return !"UNSUPPORTED".equals(state) && !"ABSENT".equals(state);
        }
    }

    public record TimerOptions(boolean enabled, boolean live, boolean restart) {
        static TimerOptions read(SharedPreferences prefs) {
            return new TimerOptions(prefs.getBoolean(WatchLinkPrefs.KEY_TIMER_ENABLED, false),
                    prefs.getBoolean(WatchLinkPrefs.KEY_TIMER_LIVE, false),
                    prefs.getBoolean(WatchLinkPrefs.KEY_TIMER_RESTART, false));
        }
    }

    public record View(boolean frameworkConnected,
                       String frameworkName,
                       String frameworkVersion,
                       int frameworkApiVersion,
                       boolean clockGranted, boolean healthGranted, boolean calendarGranted,
                       boolean wirelessGranted, boolean umsGranted,
                       boolean clockActive, boolean healthActive, boolean calendarActive,
                       boolean wirelessActive, boolean umsActive,
                       TargetRuntime clockRuntime, TargetRuntime healthRuntime,
                       TargetRuntime calendarRuntime, TargetRuntime wirelessRuntime,
                       TargetRuntime umsRuntime, TargetRuntime daemonRuntime,
                       boolean checking,
                       boolean requestPending, String requestResult,
                       boolean remotePrefsSupported,
                       Boolean remoteEnabled, Boolean remoteCalendarEnabled,
                       Boolean remoteWifiAutoSyncEnabled, Boolean remoteLiveWindowEnabled,
                       TimerOptions timerOptions, Boolean remoteRawCommandDebugEnabled,
                       Boolean remoteGameFlashbackEnabled, Integer remoteGameFlashbackLeadSec,
                       Boolean remoteNotifyMuteDndEnabled, Boolean remoteSleepAutoDndEnabled,
                       Boolean remoteWakeExitDndEnabled, Boolean remoteNotifySilentPushEnabled,
                       Boolean remoteNotifySilentScreenOnEnabled, Boolean remoteNotifySilentFocusEnabled,
                       Boolean remoteNotifySilentGameEnabled, Boolean remoteSleepPauseMediaEnabled) {
        public View(boolean frameworkConnected,
                    String frameworkName,
                    String frameworkVersion,
                    int frameworkApiVersion,
                    boolean clockGranted, boolean healthGranted, boolean calendarGranted,
                    boolean wirelessGranted, boolean umsGranted,
                    boolean clockActive, boolean healthActive, boolean calendarActive,
                    boolean wirelessActive, boolean umsActive,
                    TargetRuntime clockRuntime, TargetRuntime healthRuntime,
                    TargetRuntime calendarRuntime, TargetRuntime wirelessRuntime,
                    TargetRuntime umsRuntime, TargetRuntime daemonRuntime,
                    boolean checking,
                    boolean requestPending, String requestResult,
                    boolean remotePrefsSupported,
                    Boolean remoteEnabled, Boolean remoteCalendarEnabled,
                    Boolean remoteWifiAutoSyncEnabled, Boolean remoteLiveWindowEnabled,
                    TimerOptions timerOptions, Boolean remoteRawCommandDebugEnabled,
                    Boolean remoteGameFlashbackEnabled, Integer remoteGameFlashbackLeadSec,
                    Boolean remoteNotifyMuteDndEnabled, Boolean remoteSleepAutoDndEnabled,
                    Boolean remoteWakeExitDndEnabled, Boolean remoteNotifySilentPushEnabled) {
            this(frameworkConnected, frameworkName, frameworkVersion, frameworkApiVersion, clockGranted,
                    healthGranted, calendarGranted, wirelessGranted, umsGranted, clockActive, healthActive,
                    calendarActive, wirelessActive, umsActive, clockRuntime, healthRuntime, calendarRuntime,
                    wirelessRuntime, umsRuntime, daemonRuntime, checking, requestPending, requestResult,
                    remotePrefsSupported, remoteEnabled, remoteCalendarEnabled, remoteWifiAutoSyncEnabled,
                    remoteLiveWindowEnabled, timerOptions, remoteRawCommandDebugEnabled,
                    remoteGameFlashbackEnabled, remoteGameFlashbackLeadSec,
                    remoteNotifyMuteDndEnabled, remoteSleepAutoDndEnabled, remoteWakeExitDndEnabled,
                    remoteNotifySilentPushEnabled, null, null, null, null);
        }
        public View(boolean frameworkConnected,
                    String frameworkName,
                    String frameworkVersion,
                    int frameworkApiVersion,
                    boolean clockGranted, boolean healthGranted, boolean calendarGranted,
                    boolean wirelessGranted, boolean umsGranted,
                    boolean clockActive, boolean healthActive, boolean calendarActive,
                    boolean wirelessActive, boolean umsActive,
                    TargetRuntime clockRuntime, TargetRuntime healthRuntime,
                    TargetRuntime calendarRuntime, TargetRuntime wirelessRuntime,
                    TargetRuntime umsRuntime, TargetRuntime daemonRuntime,
                    boolean checking,
                    boolean requestPending, String requestResult,
                    boolean remotePrefsSupported,
                    Boolean remoteEnabled, Boolean remoteCalendarEnabled,
                    Boolean remoteWifiAutoSyncEnabled, Boolean remoteLiveWindowEnabled,
                    TimerOptions timerOptions, Boolean remoteRawCommandDebugEnabled,
                    Boolean remoteGameFlashbackEnabled, Integer remoteGameFlashbackLeadSec,
                    Boolean remoteNotifyMuteDndEnabled, Boolean remoteSleepAutoDndEnabled,
                    Boolean remoteWakeExitDndEnabled) {
            this(frameworkConnected, frameworkName, frameworkVersion, frameworkApiVersion, clockGranted,
                    healthGranted, calendarGranted, wirelessGranted, umsGranted, clockActive, healthActive,
                    calendarActive, wirelessActive, umsActive, clockRuntime, healthRuntime, calendarRuntime,
                    wirelessRuntime, umsRuntime, daemonRuntime, checking, requestPending, requestResult,
                    remotePrefsSupported, remoteEnabled, remoteCalendarEnabled, remoteWifiAutoSyncEnabled,
                    remoteLiveWindowEnabled, timerOptions, remoteRawCommandDebugEnabled,
                    remoteGameFlashbackEnabled, remoteGameFlashbackLeadSec,
                    remoteNotifyMuteDndEnabled, remoteSleepAutoDndEnabled, remoteWakeExitDndEnabled, null);
        }
        public View(boolean frameworkConnected,
                    String frameworkName,
                    String frameworkVersion,
                    int frameworkApiVersion,
                    boolean clockGranted, boolean healthGranted, boolean calendarGranted,
                    boolean wirelessGranted, boolean umsGranted,
                    boolean clockActive, boolean healthActive, boolean calendarActive,
                    boolean wirelessActive, boolean umsActive,
                    TargetRuntime clockRuntime, TargetRuntime healthRuntime,
                    TargetRuntime calendarRuntime, TargetRuntime wirelessRuntime,
                    TargetRuntime umsRuntime, TargetRuntime daemonRuntime,
                    boolean checking,
                    boolean requestPending, String requestResult,
                    boolean remotePrefsSupported,
                    Boolean remoteEnabled, Boolean remoteCalendarEnabled,
                    Boolean remoteWifiAutoSyncEnabled, Boolean remoteLiveWindowEnabled,
                    TimerOptions timerOptions, Boolean remoteRawCommandDebugEnabled,
                    Boolean remoteGameFlashbackEnabled, Integer remoteGameFlashbackLeadSec,
                    Boolean remoteNotifyMuteDndEnabled, Boolean remoteSleepAutoDndEnabled) {
            this(frameworkConnected, frameworkName, frameworkVersion, frameworkApiVersion, clockGranted,
                    healthGranted, calendarGranted, wirelessGranted, umsGranted, clockActive, healthActive,
                    calendarActive, wirelessActive, umsActive, clockRuntime, healthRuntime, calendarRuntime,
                    wirelessRuntime, umsRuntime, daemonRuntime, checking, requestPending, requestResult,
                    remotePrefsSupported, remoteEnabled, remoteCalendarEnabled, remoteWifiAutoSyncEnabled,
                    remoteLiveWindowEnabled, timerOptions, remoteRawCommandDebugEnabled,
                    remoteGameFlashbackEnabled, remoteGameFlashbackLeadSec,
                    remoteNotifyMuteDndEnabled, remoteSleepAutoDndEnabled, null);
        }
        public View(boolean frameworkConnected,
                    String frameworkName,
                    String frameworkVersion,
                    int frameworkApiVersion,
                    boolean clockGranted, boolean healthGranted, boolean calendarGranted,
                    boolean wirelessGranted, boolean umsGranted,
                    boolean clockActive, boolean healthActive, boolean calendarActive,
                    boolean wirelessActive, boolean umsActive,
                    TargetRuntime clockRuntime, TargetRuntime healthRuntime,
                    TargetRuntime calendarRuntime, TargetRuntime wirelessRuntime,
                    TargetRuntime umsRuntime, TargetRuntime daemonRuntime,
                    boolean checking,
                    boolean requestPending, String requestResult,
                    boolean remotePrefsSupported,
                    Boolean remoteEnabled, Boolean remoteCalendarEnabled,
                    Boolean remoteWifiAutoSyncEnabled, Boolean remoteLiveWindowEnabled,
                    TimerOptions timerOptions, Boolean remoteRawCommandDebugEnabled,
                    Boolean remoteGameFlashbackEnabled, Integer remoteGameFlashbackLeadSec) {
            this(frameworkConnected, frameworkName, frameworkVersion, frameworkApiVersion, clockGranted,
                    healthGranted, calendarGranted, wirelessGranted, umsGranted, clockActive, healthActive,
                    calendarActive, wirelessActive, umsActive, clockRuntime, healthRuntime, calendarRuntime,
                    wirelessRuntime, umsRuntime, daemonRuntime, checking, requestPending, requestResult,
                    remotePrefsSupported, remoteEnabled, remoteCalendarEnabled, remoteWifiAutoSyncEnabled,
                    remoteLiveWindowEnabled, timerOptions, remoteRawCommandDebugEnabled,
                    remoteGameFlashbackEnabled, remoteGameFlashbackLeadSec, null, null);
        }
        public View(boolean frameworkConnected,
                    String frameworkName,
                    String frameworkVersion,
                    int frameworkApiVersion,
                    boolean clockGranted, boolean healthGranted, boolean calendarGranted,
                    boolean wirelessGranted, boolean umsGranted,
                    boolean clockActive, boolean healthActive, boolean calendarActive,
                    boolean wirelessActive, boolean umsActive,
                    TargetRuntime clockRuntime, TargetRuntime healthRuntime,
                    TargetRuntime calendarRuntime, TargetRuntime wirelessRuntime,
                    TargetRuntime umsRuntime, TargetRuntime daemonRuntime,
                    boolean checking,
                    boolean requestPending, String requestResult,
                    boolean remotePrefsSupported,
                    Boolean remoteEnabled, Boolean remoteCalendarEnabled,
                    Boolean remoteWifiAutoSyncEnabled, Boolean remoteLiveWindowEnabled,
                    TimerOptions timerOptions, Boolean remoteRawCommandDebugEnabled) {
            this(frameworkConnected, frameworkName, frameworkVersion, frameworkApiVersion, clockGranted,
                    healthGranted, calendarGranted, wirelessGranted, umsGranted, clockActive, healthActive,
                    calendarActive, wirelessActive, umsActive, clockRuntime, healthRuntime, calendarRuntime,
                    wirelessRuntime, umsRuntime, daemonRuntime, checking, requestPending, requestResult,
                    remotePrefsSupported, remoteEnabled, remoteCalendarEnabled, remoteWifiAutoSyncEnabled,
                    remoteLiveWindowEnabled, timerOptions, remoteRawCommandDebugEnabled, null, null);
        }
        public View(boolean frameworkConnected,
                       String frameworkName,
                       String frameworkVersion,
                       int frameworkApiVersion,
                       boolean clockGranted, boolean healthGranted, boolean calendarGranted,
                       boolean wirelessGranted, boolean umsGranted,
                       boolean clockActive, boolean healthActive, boolean calendarActive,
                       boolean wirelessActive, boolean umsActive,
                       TargetRuntime clockRuntime, TargetRuntime healthRuntime,
                       TargetRuntime calendarRuntime, TargetRuntime wirelessRuntime,
                       TargetRuntime umsRuntime, TargetRuntime daemonRuntime,
                       boolean checking,
                       boolean requestPending, String requestResult,
                       boolean remotePrefsSupported,
                       Boolean remoteEnabled, Boolean remoteCalendarEnabled,
                       Boolean remoteWifiAutoSyncEnabled, Boolean remoteLiveWindowEnabled,
                       TimerOptions timerOptions) {
            this(frameworkConnected, frameworkName, frameworkVersion, frameworkApiVersion, clockGranted,
                    healthGranted, calendarGranted, wirelessGranted, umsGranted, clockActive, healthActive,
                    calendarActive, wirelessActive, umsActive, clockRuntime, healthRuntime, calendarRuntime,
                    wirelessRuntime, umsRuntime, daemonRuntime, checking, requestPending, requestResult,
                    remotePrefsSupported, remoteEnabled, remoteCalendarEnabled, remoteWifiAutoSyncEnabled,
                    remoteLiveWindowEnabled, timerOptions, null);
        }

        public View(boolean frameworkConnected,
                       String frameworkName,
                       String frameworkVersion,
                       int frameworkApiVersion,
                       boolean clockGranted, boolean healthGranted, boolean calendarGranted,
                       boolean wirelessGranted, boolean umsGranted,
                       boolean clockActive, boolean healthActive, boolean calendarActive,
                       boolean wirelessActive, boolean umsActive,
                       TargetRuntime clockRuntime, TargetRuntime healthRuntime,
                       TargetRuntime calendarRuntime, TargetRuntime wirelessRuntime,
                       TargetRuntime umsRuntime, TargetRuntime daemonRuntime,
                       boolean checking,
                       boolean requestPending, String requestResult,
                       boolean remotePrefsSupported,
                       Boolean remoteEnabled, Boolean remoteCalendarEnabled,
                       Boolean remoteWifiAutoSyncEnabled, Boolean remoteLiveWindowEnabled) {
            this(frameworkConnected, frameworkName, frameworkVersion, frameworkApiVersion, clockGranted,
                    healthGranted, calendarGranted, wirelessGranted, umsGranted, clockActive, healthActive,
                    calendarActive, wirelessActive, umsActive, clockRuntime, healthRuntime, calendarRuntime,
                    wirelessRuntime, umsRuntime, daemonRuntime, checking, requestPending, requestResult,
                    remotePrefsSupported, remoteEnabled, remoteCalendarEnabled, remoteWifiAutoSyncEnabled,
                    remoteLiveWindowEnabled, null, null);
        }


        public View(boolean frameworkConnected,
                    String frameworkName,
                    String frameworkVersion,
                    int frameworkApiVersion,
                    boolean clockGranted, boolean healthGranted, boolean calendarGranted, boolean wirelessGranted,
                    boolean clockActive, boolean healthActive, boolean calendarActive, boolean wirelessActive,
                    TargetRuntime clockRuntime, TargetRuntime healthRuntime,
                    TargetRuntime calendarRuntime, TargetRuntime wirelessRuntime,
                    boolean checking,
                    boolean requestPending, String requestResult,
                    boolean remotePrefsSupported,
                    Boolean remoteEnabled, Boolean remoteCalendarEnabled, Boolean remoteWifiAutoSyncEnabled) {
            this(frameworkConnected, frameworkName, frameworkVersion, frameworkApiVersion,
                    clockGranted, healthGranted, calendarGranted, wirelessGranted, false,
                    clockActive, healthActive, calendarActive, wirelessActive, false,
                    clockRuntime, healthRuntime, calendarRuntime, wirelessRuntime,
                    TargetRuntime.ABSENT, TargetRuntime.ABSENT,
                    checking, requestPending, requestResult, remotePrefsSupported,
                    remoteEnabled, remoteCalendarEnabled, remoteWifiAutoSyncEnabled, null, null);
        }

        public View(boolean frameworkConnected,
                    String frameworkName,
                    String frameworkVersion,
                    int frameworkApiVersion,
                    boolean clockGranted, boolean healthGranted, boolean calendarGranted,
                    boolean clockActive, boolean healthActive, boolean calendarActive,
                    TargetRuntime clockRuntime, TargetRuntime healthRuntime, TargetRuntime calendarRuntime,
                    boolean checking,
                    boolean requestPending, String requestResult,
                    boolean remotePrefsSupported, Boolean remoteEnabled, Boolean remoteCalendarEnabled) {
            this(frameworkConnected, frameworkName, frameworkVersion, frameworkApiVersion,
                    clockGranted, healthGranted, calendarGranted, false, false,
                    clockActive, healthActive, calendarActive, false, false,
                    clockRuntime, healthRuntime, calendarRuntime, TargetRuntime.ABSENT,
                    TargetRuntime.ABSENT, TargetRuntime.ABSENT,
                    checking, requestPending, requestResult, remotePrefsSupported,
                    remoteEnabled, remoteCalendarEnabled, null, null, null);
        }

        public boolean configured() { return frameworkConnected && clockGranted && healthGranted; }

        public boolean active() {
            return clockActive && healthActive && (!frameworkConnected || configured());
        }

        public boolean calendarReady() {
            return calendarActive && (!frameworkConnected || calendarGranted);
        }

        public boolean wirelessReady() {
            return wirelessActive && (!frameworkConnected || wirelessGranted);
        }

        public boolean umsReady() {
            return umsActive && (!frameworkConnected || umsGranted);
        }

        public boolean supportsApi102() {
            return frameworkConnected && frameworkApiVersion >= XposedService.API_102;
        }

        public boolean hasStaleTarget() {
            return supportsApi102() && (clockRuntime.isStale() || healthRuntime.isStale()
                    || daemonRuntime.isStale()
                    || (calendarGranted && calendarRuntime.isStale())
                    || (wirelessGranted && wirelessRuntime.isStale())
                    || (umsGranted && umsRuntime.isStale()));
        }

        public boolean canHotReloadAny() {
            return supportsApi102()
                    && (clockRuntime.isRunning() || healthRuntime.isRunning() || daemonRuntime.isRunning()
                    || calendarRuntime.isRunning() || wirelessRuntime.isRunning() || umsRuntime.isRunning());
        }
    }

    private static final long PROBE_TIMEOUT_MS = 4_000;
    private static final long REFRESH_INTERVAL_MS = 5_000;
    private static final long REQUEST_TIMEOUT_MS = 120_000;
    private static final long HOT_RELOAD_TIMEOUT_MS = 8_000;
    private static final TargetProfile[] TARGETS = {
            TargetProfile.CLOCK, TargetProfile.CALENDAR, TargetProfile.HEALTH,
            TargetProfile.WIRELESS_SETTINGS, TargetProfile.UMS
    };
    private static final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "WatchLink-scope");
        thread.setDaemon(true);
        return thread;
    });
    static {
        worker.setKeepAliveTime(15, TimeUnit.SECONDS);
        worker.allowCoreThreadTimeOut(true);
    }

    private static volatile View snapshot = new View(
            false, "", "", 0,
            false, false, false, false, false,
            false, false, false, false, false,
            TargetRuntime.UNSUPPORTED, TargetRuntime.UNSUPPORTED,
            TargetRuntime.UNSUPPORTED, TargetRuntime.UNSUPPORTED,
            TargetRuntime.UNSUPPORTED, TargetRuntime.UNSUPPORTED,
            false, false, "IDLE", false, null, null, null, null, null);
    private static boolean listening;
    private static XposedService framework;
    private static String frameworkName = "";
    private static String frameworkVersion = "";
    private static int frameworkApiVersion;
    private static List<String> grants;
    private static boolean remotePrefsSupported;
    private static Boolean remoteEnabled;
    private static TimerOptions timerOptions;
    private static Boolean remoteCalendarEnabled;
    private static Boolean remoteWifiAutoSyncEnabled;
    private static Boolean remoteLiveWindowEnabled;
    private static Boolean remoteGameFlashbackEnabled;
    private static Integer remoteGameFlashbackLeadSec;
    private static Boolean remoteNotifyMuteDndEnabled;
    private static Boolean remoteNotifySilentPushEnabled;
    private static Boolean remoteNotifySilentScreenOnEnabled;
    private static Boolean remoteNotifySilentFocusEnabled;
    private static Boolean remoteNotifySilentGameEnabled;
    private static Boolean remoteSleepAutoDndEnabled;
    private static Boolean remoteWakeExitDndEnabled;
    private static Boolean remoteSleepPauseMediaEnabled;
    private static Boolean remoteRawCommandDebugEnabled;
    private static TargetRuntime clockRuntime = TargetRuntime.UNSUPPORTED;
    private static TargetRuntime healthRuntime = TargetRuntime.UNSUPPORTED;
    private static TargetRuntime calendarRuntime = TargetRuntime.UNSUPPORTED;
    private static TargetRuntime wirelessRuntime = TargetRuntime.UNSUPPORTED;
    private static TargetRuntime umsRuntime = TargetRuntime.UNSUPPORTED;
    private static TargetRuntime daemonRuntime = TargetRuntime.UNSUPPORTED;
    private static final Map<TargetProfile, HookedTarget> runningTargets = new HashMap<>();
    private static HookedTarget daemonTarget;
    private static final Set<String> awaiting = new HashSet<>();
    private static List<String> awaitingRequested = List.of();
    private static long requestId;
    private static boolean requestFailed;
    private static String result = "IDLE";
    private static long lastProbe = -REFRESH_INTERVAL_MS;
    private static Probe probe;
    private static boolean clockActive, healthActive, calendarActive, wirelessActive, umsActive;

    public static View inspect() { return snapshot; }

    static List<String> missingFrom(List<String> granted) {
        return missingPackages(REQUIRED_PACKAGES, granted);
    }

    static List<String> missingPackages(List<String> wanted, List<String> granted) {
        List<String> missing = new ArrayList<>(wanted);
        if (granted != null) missing.removeAll(granted);
        return missing;
    }

    static TargetRuntime classifyState(String rawState, long versionCode) {
        if (rawState == null) return TargetRuntime.ABSENT;
        return switch (rawState) {
            case "UP_TO_DATE", "STALE", "RELOADING", "FAILED" ->
                    new TargetRuntime(rawState, Math.max(0L, versionCode));
            default -> TargetRuntime.ABSENT;
        };
    }

    public static void refresh(Context owner) {
        refresh(owner, false);
    }

    public static void refresh(Context owner, boolean force) {
        Context app = owner.getApplicationContext();
        worker.execute(() -> {
            listen();
            readFrameworkState();
            long now = SystemClock.elapsedRealtime();
            if (!force && now - lastProbe < REFRESH_INTERVAL_MS) {
                publish();
                return;
            }
            lastProbe = now;
            probeHosts(app, now);
        });
    }

    /** Persists the Clock alarm toggle to LSPosed RemotePreferences so hosts read it across reboots with zero owner process. */
    public static void writeRemoteEnabled(boolean enabled, Consumer<Boolean> callback) {
        writeRemoteBoolean(WatchLinkPrefs.KEY_ENABLED, enabled, callback);
    }

    /** Persists the Calendar strong-reminder toggle to LSPosed RemotePreferences. */
    public static void writeRemoteCalendarEnabled(boolean enabled, Consumer<Boolean> callback) {
        writeRemoteBoolean(WatchLinkPrefs.KEY_CALENDAR_ENABLED, enabled, callback);
    }

    /** Persists the Route 1B automatic Wi-Fi sync toggle to LSPosed RemotePreferences. */
    public static void writeRemoteWifiAutoSyncEnabled(boolean enabled, Consumer<Boolean> callback) {
        writeRemoteBoolean(WatchLinkPrefs.KEY_WIFI_AUTO_SYNC_ENABLED, enabled, callback);
    }

    /** Persists the Fluid Cloud / Live Window sync toggle to LSPosed RemotePreferences. */
    public static void writeRemoteLiveWindowEnabled(boolean enabled, Consumer<Boolean> callback) {
        writeRemoteBoolean(WatchLinkPrefs.KEY_LIVE_WINDOW_ENABLED, enabled, callback);
    }

    /** Persists the independent Game Flashback sync toggle to LSPosed RemotePreferences. */
    public static void writeRemoteGameFlashbackEnabled(boolean enabled, Consumer<Boolean> callback) {
        writeRemoteBoolean(WatchLinkPrefs.KEY_GAME_FLASHBACK_ENABLED, enabled, callback);
    }

    /** Persists the Game Flashback revive pre-alert lead seconds (0..30) to LSPosed RemotePreferences. */
    public static void writeRemoteGameFlashbackLeadSec(int leadSec, Consumer<Boolean> callback) {
        writeRemoteInt(WatchLinkPrefs.KEY_GAME_FLASHBACK_LEAD_SEC,
                WatchLinkPrefs.clampGameFlashbackLeadSec(leadSec), callback);
    }

    /** Persists the phone-DND watch notification silent-push toggle to LSPosed RemotePreferences. */
    public static void writeRemoteNotifyMuteDndEnabled(boolean enabled, Consumer<Boolean> callback) {
        writeRemoteBoolean(WatchLinkPrefs.KEY_NOTIFY_MUTE_DND_ENABLED, enabled, callback);
    }

    /** Persists the phone-silent-notification watch silent-push toggle to LSPosed RemotePreferences. */
    public static void writeRemoteNotifySilentPushEnabled(boolean enabled, Consumer<Boolean> callback) {
        writeRemoteBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_PUSH_ENABLED, enabled, callback);
    }

    /** Persists the screen-unlocked watch silent-push toggle to LSPosed RemotePreferences. */
    public static void writeRemoteNotifySilentScreenOnEnabled(boolean enabled, Consumer<Boolean> callback) {
        writeRemoteBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_SCREEN_ON_ENABLED, enabled, callback);
    }

    /** Persists the ZenSpace/Focus-mode watch silent-push toggle to LSPosed RemotePreferences. */
    public static void writeRemoteNotifySilentFocusEnabled(boolean enabled, Consumer<Boolean> callback) {
        writeRemoteBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_FOCUS_ENABLED, enabled, callback);
    }

    /** Persists the game-foreground watch silent-push toggle to LSPosed RemotePreferences. */
    public static void writeRemoteNotifySilentGameEnabled(boolean enabled, Consumer<Boolean> callback) {
        writeRemoteBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_GAME_ENABLED, enabled, callback);
    }

    /** Persists the watch-sleep auto phone DND toggle to LSPosed RemotePreferences. */
    public static void writeRemoteSleepAutoDndEnabled(boolean enabled, Consumer<Boolean> callback) {
        writeRemoteBoolean(WatchLinkPrefs.KEY_SLEEP_AUTO_DND_ENABLED, enabled, callback);
    }

    /** Persists the watch-wake auto exit phone DND toggle to LSPosed RemotePreferences. */
    public static void writeRemoteWakeExitDndEnabled(boolean enabled, Consumer<Boolean> callback) {
        writeRemoteBoolean(WatchLinkPrefs.KEY_WAKE_EXIT_DND_ENABLED, enabled, callback);
    }

    /** Persists the watch-sleep auto pause phone media playback toggle to LSPosed RemotePreferences. */
    public static void writeRemoteSleepPauseMediaEnabled(boolean enabled, Consumer<Boolean> callback) {
        writeRemoteBoolean(WatchLinkPrefs.KEY_SLEEP_PAUSE_MEDIA_ENABLED, enabled, callback);
    }

    /** Persists the opt-in ADB raw watch command debug toggle to LSPosed RemotePreferences. */
    public static void writeRemoteRawCommandDebugEnabled(boolean enabled, Consumer<Boolean> callback) {
        writeRemoteBoolean(WatchLinkPrefs.KEY_RAW_COMMAND_DEBUG, enabled, callback);
    }

    public static void writeTimerOption(String key, boolean enabled, Consumer<Boolean> callback) {
        if (!WatchLinkPrefs.timerKey(key)) throw new IllegalArgumentException("Unknown timer setting");
        writeRemoteBoolean(key, enabled, callback);
    }

    private static void writeRemoteInt(String key, int value, Consumer<Boolean> callback) {
        worker.execute(() -> {
            listen();
            boolean written = false;
            XposedService service = framework;
            if (service != null && supportsRemote(service)) {
                try {
                    SharedPreferences prefs = service.getRemotePreferences(WatchLinkPrefs.GROUP);
                    written = prefs.edit()
                            .putInt(key, value)
                            .putLong(WatchLinkPrefs.KEY_UPDATED_AT, System.currentTimeMillis())
                            .commit();
                    if (written) {
                        remotePrefsSupported = true;
                        if (WatchLinkPrefs.KEY_GAME_FLASHBACK_LEAD_SEC.equals(key)) {
                            remoteGameFlashbackLeadSec = value;
                        }
                        ProbeLog.info("REMOTE_PREFS_SAVED");
                        publish();
                    }
                } catch (RuntimeException error) {
                    ProbeLog.failure("REMOTE_PREFS_WRITE_FAILED", error);
                }
            }
            boolean ok = written;
            new Handler(Looper.getMainLooper()).post(() -> callback.accept(ok));
        });
    }

    private static void writeRemoteBoolean(String key, boolean enabled, Consumer<Boolean> callback) {
        worker.execute(() -> {
            listen();
            boolean written = false;
            XposedService service = framework;
            if (service != null && supportsRemote(service)) {
                try {
                    SharedPreferences prefs = service.getRemotePreferences(WatchLinkPrefs.GROUP);
                    written = prefs.edit()
                            .putBoolean(key, enabled)
                            .putLong(WatchLinkPrefs.KEY_UPDATED_AT, System.currentTimeMillis())
                            .commit();
                    if (written) {
                        remotePrefsSupported = true;
                        if (WatchLinkPrefs.KEY_ENABLED.equals(key)) remoteEnabled = enabled;
                        else if (WatchLinkPrefs.timerKey(key)) timerOptions = TimerOptions.read(prefs);
                        else if (WatchLinkPrefs.KEY_CALENDAR_ENABLED.equals(key)) remoteCalendarEnabled = enabled;
                        else if (WatchLinkPrefs.KEY_WIFI_AUTO_SYNC_ENABLED.equals(key)) remoteWifiAutoSyncEnabled = enabled;
                        else if (WatchLinkPrefs.KEY_LIVE_WINDOW_ENABLED.equals(key)) remoteLiveWindowEnabled = enabled;
                        else if (WatchLinkPrefs.KEY_GAME_FLASHBACK_ENABLED.equals(key)) remoteGameFlashbackEnabled = enabled;
                        else if (WatchLinkPrefs.KEY_NOTIFY_MUTE_DND_ENABLED.equals(key)) remoteNotifyMuteDndEnabled = enabled;
                        else if (WatchLinkPrefs.KEY_NOTIFY_SILENT_PUSH_ENABLED.equals(key)) remoteNotifySilentPushEnabled = enabled;
                        else if (WatchLinkPrefs.KEY_NOTIFY_SILENT_SCREEN_ON_ENABLED.equals(key)) remoteNotifySilentScreenOnEnabled = enabled;
                        else if (WatchLinkPrefs.KEY_NOTIFY_SILENT_FOCUS_ENABLED.equals(key)) remoteNotifySilentFocusEnabled = enabled;
                        else if (WatchLinkPrefs.KEY_NOTIFY_SILENT_GAME_ENABLED.equals(key)) remoteNotifySilentGameEnabled = enabled;
                        else if (WatchLinkPrefs.KEY_SLEEP_AUTO_DND_ENABLED.equals(key)) remoteSleepAutoDndEnabled = enabled;
                        else if (WatchLinkPrefs.KEY_WAKE_EXIT_DND_ENABLED.equals(key)) remoteWakeExitDndEnabled = enabled;
                        else if (WatchLinkPrefs.KEY_SLEEP_PAUSE_MEDIA_ENABLED.equals(key)) remoteSleepPauseMediaEnabled = enabled;
                        else if (WatchLinkPrefs.KEY_RAW_COMMAND_DEBUG.equals(key)) remoteRawCommandDebugEnabled = enabled;
                        ProbeLog.info("REMOTE_PREFS_SAVED");
                        publish();
                    }
                } catch (RuntimeException error) {
                    ProbeLog.failure("REMOTE_PREFS_WRITE_FAILED", error);
                }
            }
            boolean ok = written;
            new Handler(Looper.getMainLooper()).post(() -> callback.accept(ok));
        });
    }

    /**
     * Triggers LSPosed API 102 controlled hot reload on running hosts (Clock, Calendar, WirelessSettings,
     * UMS, Health :PhoneService, and Health :DaemonService).
     */
    public static void hotReloadTargets(Context owner, boolean onlyStale, Consumer<String> callback) {
        Context app = owner.getApplicationContext();
        worker.execute(() -> {
            listen();
            readFrameworkState();
            XposedService service = framework;
            if (service == null || service.getApiVersion() < XposedService.API_102) {
                postResult(callback, "UNSUPPORTED");
                return;
            }
            hotReloadApi102(app, service, onlyStale, callback);
        });
    }

    private static void hotReloadApi102(Context app, XposedService service, boolean onlyStale, Consumer<String> callback) {
        if (service.getApiVersion() >= XposedService.API_102) {
            List<HookedTarget> candidates = new ArrayList<>();
            for (TargetProfile profile : TARGETS) {
                HookedTarget target = runningTargets.get(profile);
                if (target == null) continue;
                HookedTarget.State state = target.getState();
                boolean stale = state == HookedTarget.State.STALE || state == HookedTarget.State.FAILED;
                if (!onlyStale || stale) candidates.add(target);
            }
            if (daemonTarget != null) {
                HookedTarget.State state = daemonTarget.getState();
                boolean stale = state == HookedTarget.State.STALE || state == HookedTarget.State.FAILED;
                if (!onlyStale || stale) candidates.add(daemonTarget);
            }
            if (candidates.isEmpty()) {
                postResult(callback, "NO_TARGETS");
                return;
            }
            ProbeLog.info("HOT_RELOAD_REQUESTED");
            AtomicInteger remaining = new AtomicInteger(candidates.size());
            AtomicBoolean finished = new AtomicBoolean();
            Set<String> outcomes = Collections.synchronizedSet(new HashSet<>());
            Runnable finish = () -> {
                if (!finished.compareAndSet(false, true)) return;
                String summary = summarizeHotReload(outcomes);
                ProbeLog.info("HOT_RELOAD_RESULT result=" + summary);
                refresh(app, true);
                postResult(callback, summary);
            };
            worker.schedule(finish, HOT_RELOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            for (HookedTarget target : candidates) {
                try {
                    service.hotReloadModule(target, null, (reloaded, res) -> worker.execute(() -> {
                        String code = res == null || res.status() == null ? "FAILED" : res.status().name();
                        outcomes.add(code);
                        if (remaining.decrementAndGet() == 0) finish.run();
                    }));
                } catch (RuntimeException error) {
                    outcomes.add("FAILED");
                    if (remaining.decrementAndGet() == 0) finish.run();
                }
            }
        } else {
            postResult(callback, "UNSUPPORTED");
        }
    }

    static String summarizeHotReload(Set<String> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return "FAILED";
        if (outcomes.contains("UNSUPPORTED")) return "UNSUPPORTED";
        if (outcomes.contains("FAILED")) return "FAILED";
        if (outcomes.contains("IN_PROGRESS")) return "IN_PROGRESS";
        if (outcomes.contains("PROCESS_DIED")) return "PROCESS_DIED";
        if (outcomes.contains("SUCCEEDED")) return "SUCCEEDED";
        return "FAILED";
    }

    private static void postResult(Consumer<String> callback, String status) {
        new Handler(Looper.getMainLooper()).post(() -> callback.accept(status));
    }

    /** Prompts LSPosed only for missing core Clock/Health scopes declared in scope.list. */
    public static void request(Context owner) {
        requestPackages(owner, REQUIRED_PACKAGES);
    }

    /** Prompts LSPosed for the optional ColorOS Calendar scope declared in scope.list. */
    public static void requestCalendar(Context owner) {
        requestPackages(owner, List.of(TargetProfile.CALENDAR.packageName));
    }

    /** Prompts LSPosed for the optional ColorOS WirelessSettings scope for Route 1B Wi-Fi auto-sync. */
    public static void requestWirelessSettings(Context owner) {
        requestPackages(owner, List.of(TargetProfile.WIRELESS_SETTINGS.packageName));
    }

    /** Prompts LSPosed for the optional ColorOS Pantanal UMS scope for Fluid Cloud live-window sync. */
    public static void requestUms(Context owner) {
        requestPackages(owner, List.of(TargetProfile.UMS.packageName));
    }

    private static void requestPackages(Context owner, List<String> wanted) {
        refresh(owner, true);
        worker.execute(() -> {
            if (!awaiting.isEmpty()) return;
            readFrameworkState();
            if (grants == null) {
                result = "UNAVAILABLE";
                publish();
                return;
            }
            List<String> missing = missingPackages(wanted, grants);
            if (missing.isEmpty()) {
                result = "ALREADY_GRANTED";
                publish();
                return;
            }
            XposedService service = framework;
            long attempt = ++requestId;
            requestFailed = false;
            awaitingRequested = List.copyOf(wanted);
            awaiting.addAll(missing);
            result = "PENDING";
            publish();
            for (String target : missing) {
                try {
                    service.requestScope(Collections.singletonList(target), new XposedService.OnScopeEventListener() {
                        @Override public void onScopeRequestApproved(List<String> packages) {
                            worker.execute(() -> answered(service, attempt, target, true));
                        }
                        @Override public void onScopeRequestFailed(String reason) {
                            worker.execute(() -> answered(service, attempt, target, false));
                        }
                    });
                } catch (RuntimeException error) {
                    ProbeLog.failure("SCOPE_REQUEST_FAILED", error);
                    answered(service, attempt, target, false);
                }
            }
            worker.schedule(() -> {
                if (service == framework && attempt == requestId && !awaiting.isEmpty()) {
                    readFrameworkState();
                    awaiting.clear();
                    result = grants != null && missingPackages(awaitingRequested, grants).isEmpty()
                            ? "GRANTED" : "UNAVAILABLE";
                    publish();
                }
            }, REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        });
    }

    private static void answered(XposedService service, long attempt, String target, boolean approved) {
        if (service != framework || attempt != requestId || !awaiting.remove(target)) return;
        requestFailed |= !approved;
        readFrameworkState();
        if (awaiting.isEmpty()) {
            if (grants != null && missingPackages(awaitingRequested, grants).isEmpty()) result = "GRANTED";
            else result = requestFailed ? "DENIED" : "UNAVAILABLE";
        }
        publish();
    }

    private static void listen() {
        if (listening) return;
        listening = true;
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService service) {
                worker.execute(() -> {
                    changeService(service);
                    readFrameworkState();
                    publish();
                });
            }
            @Override public void onServiceDied(XposedService service) {
                worker.execute(() -> {
                    if (framework == service) {
                        changeService(null);
                        publish();
                    }
                });
            }
        });
    }

    private static void changeService(XposedService service) {
        if (service == framework) return;
        framework = service;
        frameworkName = "";
        frameworkVersion = "";
        frameworkApiVersion = 0;
        grants = null;
        remotePrefsSupported = false;
        remoteEnabled = null;
        timerOptions = null;
        remoteCalendarEnabled = null;
        remoteWifiAutoSyncEnabled = null;
        remoteLiveWindowEnabled = null;
        remoteGameFlashbackEnabled = null;
        remoteGameFlashbackLeadSec = null;
        remoteNotifyMuteDndEnabled = null;
        remoteNotifySilentPushEnabled = null;
        remoteNotifySilentScreenOnEnabled = null;
        remoteNotifySilentFocusEnabled = null;
        remoteNotifySilentGameEnabled = null;
        remoteSleepAutoDndEnabled = null;
        remoteWakeExitDndEnabled = null;
        remoteSleepPauseMediaEnabled = null;
        remoteRawCommandDebugEnabled = null;
        clockRuntime = TargetRuntime.UNSUPPORTED;
        healthRuntime = TargetRuntime.UNSUPPORTED;
        calendarRuntime = TargetRuntime.UNSUPPORTED;
        wirelessRuntime = TargetRuntime.UNSUPPORTED;
        umsRuntime = TargetRuntime.UNSUPPORTED;
        daemonRuntime = TargetRuntime.UNSUPPORTED;
        runningTargets.clear();
        daemonTarget = null;
        if (!awaiting.isEmpty()) result = "UNAVAILABLE";
        awaiting.clear();
        awaitingRequested = List.of();
    }

    private static boolean supportsRemote(XposedService service) {
        try {
            return (service.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE) != 0L;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static String sanitizeLabel(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        return trimmed.matches("[A-Za-z0-9._ -]{1,36}") ? trimmed : "";
    }

    private static void readFrameworkState() {
        grants = null;
        remotePrefsSupported = false;
        remoteEnabled = null;
        timerOptions = null;
        remoteCalendarEnabled = null;
        remoteWifiAutoSyncEnabled = null;
        remoteLiveWindowEnabled = null;
        remoteGameFlashbackEnabled = null;
        remoteGameFlashbackLeadSec = null;
        remoteNotifyMuteDndEnabled = null;
        remoteNotifySilentPushEnabled = null;
        remoteNotifySilentScreenOnEnabled = null;
        remoteNotifySilentFocusEnabled = null;
        remoteNotifySilentGameEnabled = null;
        remoteSleepAutoDndEnabled = null;
        remoteWakeExitDndEnabled = null;
        remoteSleepPauseMediaEnabled = null;
        remoteRawCommandDebugEnabled = null;
        runningTargets.clear();
        daemonTarget = null;
        XposedService service = framework;
        if (service == null) {
            clockRuntime = TargetRuntime.UNSUPPORTED;
            healthRuntime = TargetRuntime.UNSUPPORTED;
            calendarRuntime = TargetRuntime.UNSUPPORTED;
            wirelessRuntime = TargetRuntime.UNSUPPORTED;
            umsRuntime = TargetRuntime.UNSUPPORTED;
            daemonRuntime = TargetRuntime.UNSUPPORTED;
            return;
        }
        try {
            frameworkName = sanitizeLabel(service.getFrameworkName());
            frameworkVersion = sanitizeLabel(service.getFrameworkVersion());
            frameworkApiVersion = service.getApiVersion();
        } catch (RuntimeException ignored) {
            frameworkApiVersion = XposedService.API_101;
        }
        try {
            grants = service.getScope();
        } catch (RuntimeException error) {
            ProbeLog.failure("SCOPE_READ_FAILED", error);
        }
        if (supportsRemote(service)) {
            try {
                SharedPreferences prefs = service.getRemotePreferences(WatchLinkPrefs.GROUP);
                remotePrefsSupported = true;
                remoteEnabled = prefs.contains(WatchLinkPrefs.KEY_ENABLED)
                        ? prefs.getBoolean(WatchLinkPrefs.KEY_ENABLED, false) : null;
                timerOptions = TimerOptions.read(prefs);
                remoteCalendarEnabled = prefs.contains(WatchLinkPrefs.KEY_CALENDAR_ENABLED)
                        ? prefs.getBoolean(WatchLinkPrefs.KEY_CALENDAR_ENABLED, false) : null;
                remoteWifiAutoSyncEnabled = prefs.contains(WatchLinkPrefs.KEY_WIFI_AUTO_SYNC_ENABLED)
                        ? prefs.getBoolean(WatchLinkPrefs.KEY_WIFI_AUTO_SYNC_ENABLED, false) : null;
                remoteLiveWindowEnabled = prefs.contains(WatchLinkPrefs.KEY_LIVE_WINDOW_ENABLED)
                        ? prefs.getBoolean(WatchLinkPrefs.KEY_LIVE_WINDOW_ENABLED, false) : null;
                remoteGameFlashbackEnabled = prefs.contains(WatchLinkPrefs.KEY_GAME_FLASHBACK_ENABLED)
                        ? prefs.getBoolean(WatchLinkPrefs.KEY_GAME_FLASHBACK_ENABLED, false) : null;
                remoteGameFlashbackLeadSec = prefs.contains(WatchLinkPrefs.KEY_GAME_FLASHBACK_LEAD_SEC)
                        ? WatchLinkPrefs.clampGameFlashbackLeadSec(
                                prefs.getInt(WatchLinkPrefs.KEY_GAME_FLASHBACK_LEAD_SEC,
                                        WatchLinkPrefs.DEFAULT_GAME_FLASHBACK_LEAD_SEC)) : null;
                remoteNotifyMuteDndEnabled = prefs.contains(WatchLinkPrefs.KEY_NOTIFY_MUTE_DND_ENABLED)
                        ? prefs.getBoolean(WatchLinkPrefs.KEY_NOTIFY_MUTE_DND_ENABLED, false) : null;
                remoteNotifySilentPushEnabled = prefs.contains(WatchLinkPrefs.KEY_NOTIFY_SILENT_PUSH_ENABLED)
                        ? prefs.getBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_PUSH_ENABLED, false) : null;
                remoteNotifySilentScreenOnEnabled = prefs.contains(WatchLinkPrefs.KEY_NOTIFY_SILENT_SCREEN_ON_ENABLED)
                        ? prefs.getBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_SCREEN_ON_ENABLED, false) : null;
                remoteNotifySilentFocusEnabled = prefs.contains(WatchLinkPrefs.KEY_NOTIFY_SILENT_FOCUS_ENABLED)
                        ? prefs.getBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_FOCUS_ENABLED, false) : null;
                remoteNotifySilentGameEnabled = prefs.contains(WatchLinkPrefs.KEY_NOTIFY_SILENT_GAME_ENABLED)
                        ? prefs.getBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_GAME_ENABLED, false) : null;
                remoteSleepAutoDndEnabled = prefs.contains(WatchLinkPrefs.KEY_SLEEP_AUTO_DND_ENABLED)
                        ? prefs.getBoolean(WatchLinkPrefs.KEY_SLEEP_AUTO_DND_ENABLED, false) : null;
                remoteWakeExitDndEnabled = prefs.contains(WatchLinkPrefs.KEY_WAKE_EXIT_DND_ENABLED)
                        ? prefs.getBoolean(WatchLinkPrefs.KEY_WAKE_EXIT_DND_ENABLED, false) : null;
                remoteSleepPauseMediaEnabled = prefs.contains(WatchLinkPrefs.KEY_SLEEP_PAUSE_MEDIA_ENABLED)
                        ? prefs.getBoolean(WatchLinkPrefs.KEY_SLEEP_PAUSE_MEDIA_ENABLED, false) : null;
                remoteRawCommandDebugEnabled = prefs.contains(WatchLinkPrefs.KEY_RAW_COMMAND_DEBUG)
                        ? prefs.getBoolean(WatchLinkPrefs.KEY_RAW_COMMAND_DEBUG, false) : null;
            } catch (RuntimeException error) {
                ProbeLog.failure("REMOTE_PREFS_READ_FAILED", error);
            }
        }
        if (service.getApiVersion() >= XposedService.API_102) {
            try {
                List<HookedTarget> targets = service.getRunningTargets();
                clockRuntime = TargetRuntime.ABSENT;
                healthRuntime = TargetRuntime.ABSENT;
                calendarRuntime = TargetRuntime.ABSENT;
                wirelessRuntime = TargetRuntime.ABSENT;
                umsRuntime = TargetRuntime.ABSENT;
                daemonRuntime = TargetRuntime.ABSENT;
                if (targets != null) {
                    for (HookedTarget target : targets) {
                        if (target == null || target.getProcessName() == null) continue;
                        String proc = target.getProcessName();
                        String state = target.getState() == null ? null : target.getState().name();
                        if (TargetProfile.CLOCK.isReminderProcess(proc)) {
                            runningTargets.put(TargetProfile.CLOCK, target);
                            clockRuntime = classifyState(state, target.getLoadedVersionCode());
                        } else if (TargetProfile.HEALTH.isReminderProcess(proc)) {
                            runningTargets.put(TargetProfile.HEALTH, target);
                            healthRuntime = classifyState(state, target.getLoadedVersionCode());
                        } else if (TargetProfile.HEALTH.isHealthDaemonProcess(proc)) {
                            daemonTarget = target;
                            daemonRuntime = classifyState(state, target.getLoadedVersionCode());
                        } else if (TargetProfile.CALENDAR.isReminderProcess(proc)) {
                            runningTargets.put(TargetProfile.CALENDAR, target);
                            calendarRuntime = classifyState(state, target.getLoadedVersionCode());
                        } else if (TargetProfile.WIRELESS_SETTINGS.isReminderProcess(proc)) {
                            runningTargets.put(TargetProfile.WIRELESS_SETTINGS, target);
                            wirelessRuntime = classifyState(state, target.getLoadedVersionCode());
                        } else if (TargetProfile.UMS.isReminderProcess(proc)) {
                            runningTargets.put(TargetProfile.UMS, target);
                            umsRuntime = classifyState(state, target.getLoadedVersionCode());
                        }
                    }
                }
            } catch (RuntimeException error) {
                clockRuntime = TargetRuntime.UNSUPPORTED;
                healthRuntime = TargetRuntime.UNSUPPORTED;
                calendarRuntime = TargetRuntime.UNSUPPORTED;
                wirelessRuntime = TargetRuntime.UNSUPPORTED;
                umsRuntime = TargetRuntime.UNSUPPORTED;
                daemonRuntime = TargetRuntime.UNSUPPORTED;
            }
        } else {
            clockRuntime = TargetRuntime.UNSUPPORTED;
            healthRuntime = TargetRuntime.UNSUPPORTED;
            calendarRuntime = TargetRuntime.UNSUPPORTED;
            wirelessRuntime = TargetRuntime.UNSUPPORTED;
            umsRuntime = TargetRuntime.UNSUPPORTED;
            daemonRuntime = TargetRuntime.UNSUPPORTED;
        }
    }

    private static final class Probe {
        final long deadline;
        Boolean clock, health, calendar, wireless, ums;
        Probe(long deadline) { this.deadline = deadline; }
    }

    private static void probeHosts(Context app, long now) {
        if (Build.VERSION.SDK_INT < 34) {
            publish();
            return;
        }
        Probe current = new Probe(now + PROBE_TIMEOUT_MS);
        probe = current;
        publish();
        for (TargetProfile target : TARGETS) {
            try {
                ScopeWitness.query(app, target, ready -> worker.execute(() -> {
                    if (probe != current || SystemClock.elapsedRealtime() >= current.deadline) return;
                    if (target == TargetProfile.CLOCK) current.clock = ready;
                    else if (target == TargetProfile.HEALTH) current.health = ready;
                    else if (target == TargetProfile.CALENDAR) current.calendar = ready;
                    else if (target == TargetProfile.WIRELESS_SETTINGS) current.wireless = ready;
                    else if (target == TargetProfile.UMS) current.ums = ready;
                    if (current.clock != null && current.health != null
                            && current.calendar != null && current.wireless != null && current.ums != null) {
                        finish(current);
                    }
                }));
            } catch (RuntimeException unavailable) {
                ProbeLog.failure("SCOPE_HOST_READ_FAILED", unavailable);
            }
        }
        worker.schedule(() -> finish(current), Math.max(0, current.deadline - SystemClock.elapsedRealtime()),
                TimeUnit.MILLISECONDS);
    }

    private static void finish(Probe current) {
        if (probe != current) return;
        clockActive = Boolean.TRUE.equals(current.clock);
        healthActive = Boolean.TRUE.equals(current.health);
        calendarActive = Boolean.TRUE.equals(current.calendar);
        wirelessActive = Boolean.TRUE.equals(current.wireless);
        umsActive = Boolean.TRUE.equals(current.ums);
        probe = null;
        publish();
    }

    private static void publish() {
        snapshot = new View(
                grants != null,
                frameworkName,
                frameworkVersion,
                frameworkApiVersion,
                grants != null && grants.contains(TargetProfile.CLOCK.packageName),
                grants != null && grants.contains(TargetProfile.HEALTH.packageName),
                grants != null && grants.contains(TargetProfile.CALENDAR.packageName),
                grants != null && grants.contains(TargetProfile.WIRELESS_SETTINGS.packageName),
                grants != null && grants.contains(TargetProfile.UMS.packageName),
                clockActive, healthActive, calendarActive, wirelessActive, umsActive,
                clockRuntime, healthRuntime, calendarRuntime, wirelessRuntime, umsRuntime, daemonRuntime,
                probe != null,
                !awaiting.isEmpty(), result,
                remotePrefsSupported, remoteEnabled, remoteCalendarEnabled,
                remoteWifiAutoSyncEnabled, remoteLiveWindowEnabled, timerOptions, remoteRawCommandDebugEnabled,
                remoteGameFlashbackEnabled, remoteGameFlashbackLeadSec,
                remoteNotifyMuteDndEnabled, remoteSleepAutoDndEnabled, remoteWakeExitDndEnabled,
                remoteNotifySilentPushEnabled, remoteNotifySilentScreenOnEnabled,
                remoteNotifySilentFocusEnabled, remoteNotifySilentGameEnabled, remoteSleepPauseMediaEnabled);
    }

    private ModuleScope() {}
}
