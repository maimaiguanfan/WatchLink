package io.github.watchlink.validation;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.setup.WatchLinkPrefs;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** On-demand settings UI query, fallback toggle sync, and standalone watch test on Health :PhoneService. */
public final class HealthHostControl {
    public static final String QUERY = "QUERY", ENABLE = "ENABLE", DISABLE = "DISABLE",
            ENABLE_CALENDAR = "ENABLE_CALENDAR", DISABLE_CALENDAR = "DISABLE_CALENDAR",
            ENABLE_WIFI_AUTO = "ENABLE_WIFI_AUTO", DISABLE_WIFI_AUTO = "DISABLE_WIFI_AUTO",
            SYNC_CURRENT_WIFI = "SYNC_CURRENT_WIFI",
            ENABLE_LIVE_WINDOW = "ENABLE_LIVE_WINDOW", DISABLE_LIVE_WINDOW = "DISABLE_LIVE_WINDOW",
            ENABLE_GAME_FLASHBACK = "ENABLE_GAME_FLASHBACK", DISABLE_GAME_FLASHBACK = "DISABLE_GAME_FLASHBACK",
            SET_GAME_FLASHBACK_LEAD_PREFIX = "SET_GAME_FLASHBACK_LEAD:",
            ENABLE_NOTIFY_MUTE_DND = "ENABLE_NOTIFY_MUTE_DND", DISABLE_NOTIFY_MUTE_DND = "DISABLE_NOTIFY_MUTE_DND",
            ENABLE_NOTIFY_SILENT_PUSH = "ENABLE_NOTIFY_SILENT_PUSH", DISABLE_NOTIFY_SILENT_PUSH = "DISABLE_NOTIFY_SILENT_PUSH",
            ENABLE_NOTIFY_SILENT_SCREEN_ON = "ENABLE_NOTIFY_SILENT_SCREEN_ON", DISABLE_NOTIFY_SILENT_SCREEN_ON = "DISABLE_NOTIFY_SILENT_SCREEN_ON",
            ENABLE_NOTIFY_SILENT_FOCUS = "ENABLE_NOTIFY_SILENT_FOCUS", DISABLE_NOTIFY_SILENT_FOCUS = "DISABLE_NOTIFY_SILENT_FOCUS",
            ENABLE_NOTIFY_SILENT_GAME = "ENABLE_NOTIFY_SILENT_GAME", DISABLE_NOTIFY_SILENT_GAME = "DISABLE_NOTIFY_SILENT_GAME",
            ENABLE_SLEEP_AUTO_DND = "ENABLE_SLEEP_AUTO_DND", DISABLE_SLEEP_AUTO_DND = "DISABLE_SLEEP_AUTO_DND",
            ENABLE_WAKE_EXIT_DND = "ENABLE_WAKE_EXIT_DND", DISABLE_WAKE_EXIT_DND = "DISABLE_WAKE_EXIT_DND",
            ENABLE_SLEEP_PAUSE_MEDIA = "ENABLE_SLEEP_PAUSE_MEDIA", DISABLE_SLEEP_PAUSE_MEDIA = "DISABLE_SLEEP_PAUSE_MEDIA",
            ENABLE_MYDEVICES_CARD = "ENABLE_MYDEVICES_CARD", DISABLE_MYDEVICES_CARD = "DISABLE_MYDEVICES_CARD",
            SET_MYDEVICES_TAP_HEALTH = "SET_MYDEVICES_TAP_HEALTH", SET_MYDEVICES_TAP_WATCHLINK = "SET_MYDEVICES_TAP_WATCHLINK",
            TEST_LIVE_WINDOW = "TEST_LIVE_WINDOW", CANCEL_LIVE_WINDOW = "CANCEL_LIVE_WINDOW",
            ENABLE_RAW_DEBUG = "ENABLE_RAW_DEBUG", DISABLE_RAW_DEBUG = "DISABLE_RAW_DEBUG",
            TEST = "TEST", CANCEL_TEST = "CANCEL_TEST";

    public static String setGameFlashbackLeadSecCommand(int leadSec) {
        return SET_GAME_FLASHBACK_LEAD_PREFIX + WatchLinkPrefs.clampGameFlashbackLeadSec(leadSec);
    }

    public static int parseGameFlashbackLeadSecCommand(String command) {
        if (command == null || !command.startsWith(SET_GAME_FLASHBACK_LEAD_PREFIX)) return -1;
        String tail = command.substring(SET_GAME_FLASHBACK_LEAD_PREFIX.length());
        if (!tail.matches("\\d{1,2}")) return -1;
        try {
            int v = Integer.parseInt(tail);
            return (v >= WatchLinkPrefs.MIN_GAME_FLASHBACK_LEAD_SEC && v <= WatchLinkPrefs.MAX_GAME_FLASHBACK_LEAD_SEC)
                    ? v : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
    private static final String ACTION = BuildConfig.APPLICATION_ID + ".cooperative.HEALTH_CONTROL_V1";
    private static final int ACK = 1;
    private static final long TIMEOUT = 5_000L;

    private static final Set<String> SYMBOL_SOURCES = Set.of("CACHE", "FAST_PATH", "DEX_SCAN", "DEGRADED", "UNKNOWN");
    private static final Set<String> WATCH_STATES = Set.of(
            "READY_SUPPORTED_ON", "READY_SUPPORTED_UNSET", "READY_SUPPORTED_OFF",
            "READY_UNSUPPORTED", "READY_DEVICE_MISMATCH", "WATCH_DISCONNECTED", "DEGRADED", "UNKNOWN");
    private static final Set<String> DISPATCH_MODES = Set.of(
            "START_SERVICE", "DIRECT_SDK", "BG_RESTRICTED", "DENIED", "FAILED", "NONE");
    private static final Set<String> ACTION_SOURCES = Set.of(
            "SDK_AND_INTENT", "SDK_ONLY", "INTENT_ONLY", "NONE");
    private static final Set<String> TEST_STATES = Set.of(
            "IDLE", "SENDING", "SENT", "SENT_DIRECT", "SERVICE_RECEIVED", "OUTBOUND",
            "SNOOZE", "DISMISS", "CLOSED", "WATCH_UNAVAILABLE", "BUSY",
            "BACKGROUND_RESTRICTED", "DENIED", "FAILED");
    private static final Set<String> WIFI_WATCH_SUPPORTS = Set.of(
            "SUPPORTED_NEW", "SUPPORTED_LEGACY", "SUPPORTED_UNKNOWN_CAP",
            "UNSUPPORTED", "WATCH_DISCONNECTED", "UNKNOWN");
    private static final Set<String> WIFI_RESULTS = Set.of(
            "NONE", "SENDING", "ACK_OK", "SENT_NO_ACK_HOOK", "ACK_FAIL", "ACK_TIMEOUT", "SKIPPED_DUP",
            "WATCH_DISCONNECTED", "UNSUPPORTED_WATCH", "UNSUPPORTED_NETWORK",
            "WIRELESS_UNANSWERED", "NOT_CONNECTED", "DISABLED", "DIRECT_UNAVAILABLE", "FAILED");
    private static final Set<String> WIFI_SOURCES = Set.of("NONE", "BULK_1A", "AUTO_1B");
    private static final Set<String> LIVE_WATCH_SUPPORTS = Set.of(
            "SUPPORTED_LIVE_LEVEL", "SUPPORTED_BASIC", "UNSUPPORTED", "WATCH_DISCONNECTED", "UNKNOWN");
    private static final Set<String> LIVE_RESULTS = Set.of(
            "NONE", "OPENED", "UPDATED", "CLOSED", "WATCH_DISCONNECTED",
            "UNSUPPORTED_WATCH", "DISABLED", "BG_RESTRICTED", "FAILED");
    private static final Set<String> LIVE_SOURCES = Set.of("NONE", "UMS", "DAEMON", "TEST", "CLOCK_TIMER");
    private static final Set<String> LIVE_TEST_STATES = Set.of(
            "IDLE", "OPENED", "UPDATED", "CLOSED", "WATCH_UNAVAILABLE", "FAILED");
    private static final Set<String> SLEEP_WATCH_SUPPORTS = Set.of(
            "SUPPORTED_136", "UNSUPPORTED", "WATCH_DISCONNECTED", "UNKNOWN");
    private static final Set<String> SILENT_PUSH_SUPPORTS = Set.of(
            "SUPPORTED_94", "UNSUPPORTED", "WATCH_DISCONNECTED", "UNKNOWN");
    private static final Set<String> SLEEP_EVENTS = Set.of(
            "NONE", "FALL_ASLEEP_DND_ON", "FALL_ASLEEP_SKIP_ALREADY_DND", "FALL_ASLEEP_MEDIA_PAUSED",
            "WAKE_UP_DND_OFF", "WAKE_UP_SKIP_ALREADY_OFF", "WAKE_UP_SKIP_NOT_OWNED",
            "NO_POLICY_ACCESS", "FAILED");

    public record Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                        boolean serviceWitness, boolean sdkObserver, String symbolSource,
                        String watchState, String lastDispatchMode, String lastActionSource,
                        String testState, String testDispatchMode, String testActionSource,
                        boolean wifiAutoSyncEnabled, String wifiWatchSupport, boolean wifiAckReady,
                        int wifiSyncedCount, String wifiLastResult, String wifiLastSource, long wifiLastAt,
                        boolean liveWindowEnabled, String liveWatchSupport, int liveActiveCount,
                        String liveLastResult, String liveLastSource, String liveDispatchMode, String liveTestState,
                        boolean rawCommandDebugEnabled,
                        boolean gameFlashbackEnabled, int gameFlashbackLeadSec,
                        boolean notifyMuteDndEnabled, boolean sleepAutoDndEnabled,
                        String sleepWatchSupport, boolean sleepHookReady, boolean dndPolicyAccess,
                        String sleepLastEvent, long sleepLastAt,
                        long sleepLastFallAsleepAt, long sleepLastWakeUpAt,
                        boolean wakeExitDndEnabled, boolean notifySilentPushEnabled,
                        String silentPushWatchSupport,
                        boolean notifySilentScreenOnEnabled, boolean notifySilentFocusEnabled,
                        boolean notifySilentGameEnabled, boolean sleepPauseMediaEnabled,
                        boolean watchConnected, String watchName, int watchBattery,
                        boolean watchCharging, long watchBatteryAt,
                        boolean myDevicesCardEnabled, String myDevicesTapTarget) {
        public Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource,
                     boolean wifiAutoSyncEnabled, String wifiWatchSupport, boolean wifiAckReady,
                     int wifiSyncedCount, String wifiLastResult, String wifiLastSource, long wifiLastAt,
                     boolean liveWindowEnabled, String liveWatchSupport, int liveActiveCount,
                     String liveLastResult, String liveLastSource, String liveDispatchMode, String liveTestState,
                     boolean rawCommandDebugEnabled,
                     boolean gameFlashbackEnabled, int gameFlashbackLeadSec,
                     boolean notifyMuteDndEnabled, boolean sleepAutoDndEnabled,
                     String sleepWatchSupport, boolean sleepHookReady, boolean dndPolicyAccess,
                     String sleepLastEvent, long sleepLastAt,
                     long sleepLastFallAsleepAt, long sleepLastWakeUpAt,
                     boolean wakeExitDndEnabled, boolean notifySilentPushEnabled,
                     String silentPushWatchSupport,
                     boolean notifySilentScreenOnEnabled, boolean notifySilentFocusEnabled,
                     boolean notifySilentGameEnabled, boolean sleepPauseMediaEnabled,
                     boolean watchConnected, String watchName, int watchBattery,
                     boolean watchCharging, long watchBatteryAt) {
            this(reachable, enabled, calendarEnabled, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    wifiAutoSyncEnabled, wifiWatchSupport, wifiAckReady, wifiSyncedCount, wifiLastResult,
                    wifiLastSource, wifiLastAt,
                    liveWindowEnabled, liveWatchSupport, liveActiveCount, liveLastResult, liveLastSource,
                    liveDispatchMode, liveTestState, rawCommandDebugEnabled,
                    gameFlashbackEnabled, gameFlashbackLeadSec,
                    notifyMuteDndEnabled, sleepAutoDndEnabled, sleepWatchSupport, sleepHookReady,
                    dndPolicyAccess, sleepLastEvent, sleepLastAt, sleepLastFallAsleepAt, sleepLastWakeUpAt,
                    wakeExitDndEnabled, notifySilentPushEnabled, silentPushWatchSupport,
                    notifySilentScreenOnEnabled, notifySilentFocusEnabled, notifySilentGameEnabled,
                    sleepPauseMediaEnabled, watchConnected, watchName, watchBattery, watchCharging,
                    watchBatteryAt, false, WatchLinkPrefs.TAP_TARGET_HEALTH);
        }
        public Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource,
                     boolean wifiAutoSyncEnabled, String wifiWatchSupport, boolean wifiAckReady,
                     int wifiSyncedCount, String wifiLastResult, String wifiLastSource, long wifiLastAt,
                     boolean liveWindowEnabled, String liveWatchSupport, int liveActiveCount,
                     String liveLastResult, String liveLastSource, String liveDispatchMode, String liveTestState,
                     boolean rawCommandDebugEnabled,
                     boolean gameFlashbackEnabled, int gameFlashbackLeadSec,
                     boolean notifyMuteDndEnabled, boolean sleepAutoDndEnabled,
                     String sleepWatchSupport, boolean sleepHookReady, boolean dndPolicyAccess,
                     String sleepLastEvent, long sleepLastAt,
                     long sleepLastFallAsleepAt, long sleepLastWakeUpAt,
                     boolean wakeExitDndEnabled, boolean notifySilentPushEnabled,
                     String silentPushWatchSupport,
                     boolean notifySilentScreenOnEnabled, boolean notifySilentFocusEnabled,
                     boolean notifySilentGameEnabled, boolean sleepPauseMediaEnabled) {
            this(reachable, enabled, calendarEnabled, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    wifiAutoSyncEnabled, wifiWatchSupport, wifiAckReady, wifiSyncedCount, wifiLastResult,
                    wifiLastSource, wifiLastAt,
                    liveWindowEnabled, liveWatchSupport, liveActiveCount, liveLastResult, liveLastSource,
                    liveDispatchMode, liveTestState, rawCommandDebugEnabled,
                    gameFlashbackEnabled, gameFlashbackLeadSec,
                    notifyMuteDndEnabled, sleepAutoDndEnabled, sleepWatchSupport, sleepHookReady,
                    dndPolicyAccess, sleepLastEvent, sleepLastAt, sleepLastFallAsleepAt, sleepLastWakeUpAt,
                    wakeExitDndEnabled, notifySilentPushEnabled, silentPushWatchSupport,
                    notifySilentScreenOnEnabled, notifySilentFocusEnabled, notifySilentGameEnabled,
                    sleepPauseMediaEnabled, false, "", -1, false, 0L);
        }
        public Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource,
                     boolean wifiAutoSyncEnabled, String wifiWatchSupport, boolean wifiAckReady,
                     int wifiSyncedCount, String wifiLastResult, String wifiLastSource, long wifiLastAt,
                     boolean liveWindowEnabled, String liveWatchSupport, int liveActiveCount,
                     String liveLastResult, String liveLastSource, String liveDispatchMode, String liveTestState,
                     boolean rawCommandDebugEnabled,
                     boolean gameFlashbackEnabled, int gameFlashbackLeadSec,
                     boolean notifyMuteDndEnabled, boolean sleepAutoDndEnabled,
                     String sleepWatchSupport, boolean sleepHookReady, boolean dndPolicyAccess,
                     String sleepLastEvent, long sleepLastAt,
                     long sleepLastFallAsleepAt, long sleepLastWakeUpAt,
                     boolean wakeExitDndEnabled, boolean notifySilentPushEnabled,
                     String silentPushWatchSupport) {
            this(reachable, enabled, calendarEnabled, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    wifiAutoSyncEnabled, wifiWatchSupport, wifiAckReady, wifiSyncedCount, wifiLastResult,
                    wifiLastSource, wifiLastAt,
                    liveWindowEnabled, liveWatchSupport, liveActiveCount, liveLastResult, liveLastSource,
                    liveDispatchMode, liveTestState, rawCommandDebugEnabled,
                    gameFlashbackEnabled, gameFlashbackLeadSec,
                    notifyMuteDndEnabled, sleepAutoDndEnabled, sleepWatchSupport, sleepHookReady,
                    dndPolicyAccess, sleepLastEvent, sleepLastAt, sleepLastFallAsleepAt, sleepLastWakeUpAt,
                    wakeExitDndEnabled, notifySilentPushEnabled, silentPushWatchSupport,
                    false, false, false, false);
        }
        public Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource,
                     boolean wifiAutoSyncEnabled, String wifiWatchSupport, boolean wifiAckReady,
                     int wifiSyncedCount, String wifiLastResult, String wifiLastSource, long wifiLastAt,
                     boolean liveWindowEnabled, String liveWatchSupport, int liveActiveCount,
                     String liveLastResult, String liveLastSource, String liveDispatchMode, String liveTestState,
                     boolean rawCommandDebugEnabled,
                     boolean gameFlashbackEnabled, int gameFlashbackLeadSec,
                     boolean notifyMuteDndEnabled, boolean sleepAutoDndEnabled,
                     String sleepWatchSupport, boolean sleepHookReady, boolean dndPolicyAccess,
                     String sleepLastEvent, long sleepLastAt,
                     long sleepLastFallAsleepAt, long sleepLastWakeUpAt,
                     boolean wakeExitDndEnabled) {
            this(reachable, enabled, calendarEnabled, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    wifiAutoSyncEnabled, wifiWatchSupport, wifiAckReady, wifiSyncedCount, wifiLastResult,
                    wifiLastSource, wifiLastAt,
                    liveWindowEnabled, liveWatchSupport, liveActiveCount, liveLastResult, liveLastSource,
                    liveDispatchMode, liveTestState, rawCommandDebugEnabled,
                    gameFlashbackEnabled, gameFlashbackLeadSec,
                    notifyMuteDndEnabled, sleepAutoDndEnabled, sleepWatchSupport, sleepHookReady,
                    dndPolicyAccess, sleepLastEvent, sleepLastAt, sleepLastFallAsleepAt, sleepLastWakeUpAt,
                    wakeExitDndEnabled, false, "UNKNOWN");
        }
        public Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource,
                     boolean wifiAutoSyncEnabled, String wifiWatchSupport, boolean wifiAckReady,
                     int wifiSyncedCount, String wifiLastResult, String wifiLastSource, long wifiLastAt,
                     boolean liveWindowEnabled, String liveWatchSupport, int liveActiveCount,
                     String liveLastResult, String liveLastSource, String liveDispatchMode, String liveTestState,
                     boolean rawCommandDebugEnabled,
                     boolean gameFlashbackEnabled, int gameFlashbackLeadSec,
                     boolean notifyMuteDndEnabled, boolean sleepAutoDndEnabled,
                     String sleepWatchSupport, boolean sleepHookReady, boolean dndPolicyAccess,
                     String sleepLastEvent, long sleepLastAt,
                     long sleepLastFallAsleepAt, long sleepLastWakeUpAt) {
            this(reachable, enabled, calendarEnabled, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    wifiAutoSyncEnabled, wifiWatchSupport, wifiAckReady, wifiSyncedCount, wifiLastResult,
                    wifiLastSource, wifiLastAt,
                    liveWindowEnabled, liveWatchSupport, liveActiveCount, liveLastResult, liveLastSource,
                    liveDispatchMode, liveTestState, rawCommandDebugEnabled,
                    gameFlashbackEnabled, gameFlashbackLeadSec,
                    notifyMuteDndEnabled, sleepAutoDndEnabled, sleepWatchSupport, sleepHookReady,
                    dndPolicyAccess, sleepLastEvent, sleepLastAt, sleepLastFallAsleepAt, sleepLastWakeUpAt, false);
        }
        public Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource,
                     boolean wifiAutoSyncEnabled, String wifiWatchSupport, boolean wifiAckReady,
                     int wifiSyncedCount, String wifiLastResult, String wifiLastSource, long wifiLastAt,
                     boolean liveWindowEnabled, String liveWatchSupport, int liveActiveCount,
                     String liveLastResult, String liveLastSource, String liveDispatchMode, String liveTestState,
                     boolean rawCommandDebugEnabled,
                     boolean gameFlashbackEnabled, int gameFlashbackLeadSec,
                     boolean notifyMuteDndEnabled, boolean sleepAutoDndEnabled,
                     String sleepWatchSupport, boolean sleepHookReady, boolean dndPolicyAccess,
                     String sleepLastEvent, long sleepLastAt) {
            this(reachable, enabled, calendarEnabled, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    wifiAutoSyncEnabled, wifiWatchSupport, wifiAckReady, wifiSyncedCount, wifiLastResult,
                    wifiLastSource, wifiLastAt,
                    liveWindowEnabled, liveWatchSupport, liveActiveCount, liveLastResult, liveLastSource,
                    liveDispatchMode, liveTestState, rawCommandDebugEnabled,
                    gameFlashbackEnabled, gameFlashbackLeadSec,
                    notifyMuteDndEnabled, sleepAutoDndEnabled, sleepWatchSupport, sleepHookReady,
                    dndPolicyAccess, sleepLastEvent, sleepLastAt, 0L, 0L);
        }
        public Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource,
                     boolean wifiAutoSyncEnabled, String wifiWatchSupport, boolean wifiAckReady,
                     int wifiSyncedCount, String wifiLastResult, String wifiLastSource, long wifiLastAt,
                     boolean liveWindowEnabled, String liveWatchSupport, int liveActiveCount,
                     String liveLastResult, String liveLastSource, String liveDispatchMode, String liveTestState,
                     boolean rawCommandDebugEnabled,
                     boolean gameFlashbackEnabled, int gameFlashbackLeadSec) {
            this(reachable, enabled, calendarEnabled, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    wifiAutoSyncEnabled, wifiWatchSupport, wifiAckReady, wifiSyncedCount, wifiLastResult,
                    wifiLastSource, wifiLastAt,
                    liveWindowEnabled, liveWatchSupport, liveActiveCount, liveLastResult, liveLastSource,
                    liveDispatchMode, liveTestState, rawCommandDebugEnabled,
                    gameFlashbackEnabled, gameFlashbackLeadSec,
                    false, false, "UNKNOWN", false, false, "NONE", 0L);
        }
        public Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource,
                     boolean wifiAutoSyncEnabled, String wifiWatchSupport, boolean wifiAckReady,
                     int wifiSyncedCount, String wifiLastResult, String wifiLastSource, long wifiLastAt,
                     boolean liveWindowEnabled, String liveWatchSupport, int liveActiveCount,
                     String liveLastResult, String liveLastSource, String liveDispatchMode, String liveTestState,
                     boolean rawCommandDebugEnabled) {
            this(reachable, enabled, calendarEnabled, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    wifiAutoSyncEnabled, wifiWatchSupport, wifiAckReady, wifiSyncedCount, wifiLastResult,
                    wifiLastSource, wifiLastAt,
                    liveWindowEnabled, liveWatchSupport, liveActiveCount, liveLastResult, liveLastSource,
                    liveDispatchMode, liveTestState, rawCommandDebugEnabled,
                    false, WatchLinkPrefs.DEFAULT_GAME_FLASHBACK_LEAD_SEC);
        }
        public Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource,
                     boolean wifiAutoSyncEnabled, String wifiWatchSupport, boolean wifiAckReady,
                     int wifiSyncedCount, String wifiLastResult, String wifiLastSource, long wifiLastAt,
                     boolean liveWindowEnabled, String liveWatchSupport, int liveActiveCount,
                     String liveLastResult, String liveLastSource, String liveDispatchMode, String liveTestState) {
            this(reachable, enabled, calendarEnabled, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    wifiAutoSyncEnabled, wifiWatchSupport, wifiAckReady, wifiSyncedCount, wifiLastResult,
                    wifiLastSource, wifiLastAt,
                    liveWindowEnabled, liveWatchSupport, liveActiveCount, liveLastResult, liveLastSource,
                    liveDispatchMode, liveTestState, false);
        }

        public Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource,
                     boolean wifiAutoSyncEnabled, String wifiWatchSupport, boolean wifiAckReady,
                     int wifiSyncedCount, String wifiLastResult, String wifiLastSource, long wifiLastAt) {
            this(reachable, enabled, calendarEnabled, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    wifiAutoSyncEnabled, wifiWatchSupport, wifiAckReady, wifiSyncedCount, wifiLastResult,
                    wifiLastSource, wifiLastAt,
                    false, "UNKNOWN", 0, "NONE", "NONE", "NONE", "IDLE", false);
        }

        public Reply(boolean reachable, boolean enabled, boolean calendarEnabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource) {
            this(reachable, enabled, calendarEnabled, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    false, "UNKNOWN", false, 0, "NONE", "NONE", 0L,
                    false, "UNKNOWN", 0, "NONE", "NONE", "NONE", "IDLE", false);
        }

        public Reply(boolean reachable, boolean enabled, boolean remotePrefs,
                     boolean serviceWitness, boolean sdkObserver, String symbolSource,
                     String watchState, String lastDispatchMode, String lastActionSource,
                     String testState, String testDispatchMode, String testActionSource) {
            this(reachable, enabled, false, remotePrefs, serviceWitness, sdkObserver, symbolSource,
                    watchState, lastDispatchMode, lastActionSource, testState, testDispatchMode, testActionSource,
                    false, "UNKNOWN", false, 0, "NONE", "NONE", 0L,
                    false, "UNKNOWN", 0, "NONE", "NONE", "NONE", "IDLE", false);
        }

        public Reply {
            enabled = reachable && enabled;
            calendarEnabled = reachable && calendarEnabled;
            remotePrefs = reachable && remotePrefs;
            serviceWitness = reachable && serviceWitness;
            sdkObserver = reachable && sdkObserver;
            symbolSource = pick(reachable, SYMBOL_SOURCES, symbolSource, "UNKNOWN");
            watchState = pick(reachable, WATCH_STATES, watchState, "UNKNOWN");
            lastDispatchMode = pick(reachable, DISPATCH_MODES, lastDispatchMode, "NONE");
            lastActionSource = pick(reachable, ACTION_SOURCES, lastActionSource, "NONE");
            testState = pick(reachable, TEST_STATES, testState, "IDLE");
            testDispatchMode = pick(reachable, DISPATCH_MODES, testDispatchMode, "NONE");
            testActionSource = pick(reachable, ACTION_SOURCES, testActionSource, "NONE");
            wifiAutoSyncEnabled = reachable && wifiAutoSyncEnabled;
            wifiWatchSupport = pick(reachable, WIFI_WATCH_SUPPORTS, wifiWatchSupport, "UNKNOWN");
            wifiAckReady = reachable && wifiAckReady;
            wifiSyncedCount = reachable ? Math.max(0, wifiSyncedCount) : 0;
            wifiLastResult = pick(reachable, WIFI_RESULTS, wifiLastResult, "NONE");
            wifiLastSource = pick(reachable, WIFI_SOURCES, wifiLastSource, "NONE");
            wifiLastAt = reachable ? Math.max(0L, wifiLastAt) : 0L;
            liveWindowEnabled = reachable && liveWindowEnabled;
            liveWatchSupport = pick(reachable, LIVE_WATCH_SUPPORTS, liveWatchSupport, "UNKNOWN");
            liveActiveCount = reachable ? Math.max(0, liveActiveCount) : 0;
            liveLastResult = pick(reachable, LIVE_RESULTS, liveLastResult, "NONE");
            liveLastSource = pick(reachable, LIVE_SOURCES, liveLastSource, "NONE");
            liveDispatchMode = pick(reachable, DISPATCH_MODES, liveDispatchMode, "NONE");
            liveTestState = pick(reachable, LIVE_TEST_STATES, liveTestState, "IDLE");
            rawCommandDebugEnabled = reachable && rawCommandDebugEnabled;
            gameFlashbackEnabled = reachable && gameFlashbackEnabled;
            gameFlashbackLeadSec = reachable
                    ? WatchLinkPrefs.clampGameFlashbackLeadSec(gameFlashbackLeadSec)
                    : WatchLinkPrefs.DEFAULT_GAME_FLASHBACK_LEAD_SEC;
            notifyMuteDndEnabled = reachable && notifyMuteDndEnabled;
            sleepAutoDndEnabled = reachable && sleepAutoDndEnabled;
            sleepWatchSupport = pick(reachable, SLEEP_WATCH_SUPPORTS, sleepWatchSupport, "UNKNOWN");
            sleepHookReady = reachable && sleepHookReady;
            dndPolicyAccess = reachable && dndPolicyAccess;
            sleepLastEvent = pick(reachable, SLEEP_EVENTS, sleepLastEvent, "NONE");
            sleepLastAt = reachable ? Math.max(0L, sleepLastAt) : 0L;
            sleepLastFallAsleepAt = reachable ? Math.max(0L, sleepLastFallAsleepAt) : 0L;
            sleepLastWakeUpAt = reachable ? Math.max(0L, sleepLastWakeUpAt) : 0L;
            wakeExitDndEnabled = reachable && wakeExitDndEnabled;
            notifySilentPushEnabled = reachable && notifySilentPushEnabled;
            silentPushWatchSupport = pick(reachable, SILENT_PUSH_SUPPORTS, silentPushWatchSupport, "UNKNOWN");
            notifySilentScreenOnEnabled = reachable && notifySilentScreenOnEnabled;
            notifySilentFocusEnabled = reachable && notifySilentFocusEnabled;
            notifySilentGameEnabled = reachable && notifySilentGameEnabled;
            sleepPauseMediaEnabled = reachable && sleepPauseMediaEnabled;
            watchConnected = reachable && watchConnected;
            watchName = reachable && watchName != null ? sanitizeWatchName(watchName) : "";
            watchBattery = (reachable && watchBattery >= 0 && watchBattery <= 100) ? watchBattery : -1;
            watchCharging = reachable && watchConnected && watchCharging;
            watchBatteryAt = reachable ? Math.max(0L, watchBatteryAt) : 0L;
            myDevicesCardEnabled = reachable && myDevicesCardEnabled;
            myDevicesTapTarget = WatchLinkPrefs.normalizeTapTarget(myDevicesTapTarget);
        }

        private static String sanitizeWatchName(String raw) {
            String cleaned = raw.replace('\n', ' ').replace('\r', ' ').trim();
            return cleaned.length() > 40 ? cleaned.substring(0, 40).trim() : cleaned;
        }

        private static String pick(boolean reachable, Set<String> allowed, String value, String fallback) {
            return reachable && value != null && allowed.contains(value) ? value : fallback;
        }
    }

    public interface CommandHandler { Reply handle(String command); }

    private static boolean validCommand(String value) {
        return QUERY.equals(value) || ENABLE.equals(value) || DISABLE.equals(value)
                || ENABLE_CALENDAR.equals(value) || DISABLE_CALENDAR.equals(value)
                || ENABLE_WIFI_AUTO.equals(value) || DISABLE_WIFI_AUTO.equals(value)
                || SYNC_CURRENT_WIFI.equals(value)
                || ENABLE_LIVE_WINDOW.equals(value) || DISABLE_LIVE_WINDOW.equals(value)
                || ENABLE_GAME_FLASHBACK.equals(value) || DISABLE_GAME_FLASHBACK.equals(value)
                || parseGameFlashbackLeadSecCommand(value) >= 0
                || ENABLE_NOTIFY_MUTE_DND.equals(value) || DISABLE_NOTIFY_MUTE_DND.equals(value)
                || ENABLE_NOTIFY_SILENT_PUSH.equals(value) || DISABLE_NOTIFY_SILENT_PUSH.equals(value)
                || ENABLE_NOTIFY_SILENT_SCREEN_ON.equals(value) || DISABLE_NOTIFY_SILENT_SCREEN_ON.equals(value)
                || ENABLE_NOTIFY_SILENT_FOCUS.equals(value) || DISABLE_NOTIFY_SILENT_FOCUS.equals(value)
                || ENABLE_NOTIFY_SILENT_GAME.equals(value) || DISABLE_NOTIFY_SILENT_GAME.equals(value)
                || ENABLE_SLEEP_AUTO_DND.equals(value) || DISABLE_SLEEP_AUTO_DND.equals(value)
                || ENABLE_WAKE_EXIT_DND.equals(value) || DISABLE_WAKE_EXIT_DND.equals(value)
                || ENABLE_SLEEP_PAUSE_MEDIA.equals(value) || DISABLE_SLEEP_PAUSE_MEDIA.equals(value)
                || ENABLE_MYDEVICES_CARD.equals(value) || DISABLE_MYDEVICES_CARD.equals(value)
                || SET_MYDEVICES_TAP_HEALTH.equals(value) || SET_MYDEVICES_TAP_WATCHLINK.equals(value)
                || TEST_LIVE_WINDOW.equals(value) || CANCEL_LIVE_WINDOW.equals(value)
                || ENABLE_RAW_DEBUG.equals(value) || DISABLE_RAW_DEBUG.equals(value)
                || TEST.equals(value) || CANCEL_TEST.equals(value);
    }

    public static Reply unavailable() {
        return new Reply(false, false, false, false, false, false, "UNKNOWN",
                "UNKNOWN", "NONE", "NONE", "IDLE", "NONE", "NONE",
                false, "UNKNOWN", false, 0, "NONE", "NONE", 0L,
                false, "UNKNOWN", 0, "NONE", "NONE", "NONE", "IDLE");
    }

    @RequiresApi(34)
    public static BroadcastReceiver register(Context host, int ownerUid, Executor worker, CommandHandler handler) {
        if (ownerUid < 0 || !TargetProfile.HEALTH.packageName.equals(host.getPackageName())) return null;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!isOrderedBroadcast() || getSentFromUid() != ownerUid
                        || !BuildConfig.APPLICATION_ID.equals(getSentFromPackage())
                        || !ACTION.equals(intent.getAction()) || intent.getComponent() != null
                        || !host.getPackageName().equals(intent.getPackage())
                        || (intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) return;
                String command = intent.getStringExtra("command");
                String id = intent.getStringExtra("request");
                long wall = intent.getLongExtra("at", 0), elapsed = intent.getLongExtra("elapsed", -1);
                if (!validCommand(command) || !DailyAlarmWire.isRun(id)) return;
                PendingResult pending = goAsync();
                try {
                    worker.execute(() -> {
                        try {
                            if (!DailyAlarmWire.inWindow(wall, elapsed, TIMEOUT)) return;
                            Reply reply = handler.handle(command);
                            if (reply == null || !reply.reachable()) return;
                            Bundle details = new Bundle();
                            details.putString("request", id);
                            details.putBoolean("enabled", reply.enabled());
                            details.putBoolean("calendarEnabled", reply.calendarEnabled());
                            details.putBoolean("remotePrefs", reply.remotePrefs());
                            details.putBoolean("serviceWitness", reply.serviceWitness());
                            details.putBoolean("sdkObserver", reply.sdkObserver());
                            details.putString("symbolSource", reply.symbolSource());
                            details.putString("watchState", reply.watchState());
                            details.putString("lastDispatchMode", reply.lastDispatchMode());
                            details.putString("lastActionSource", reply.lastActionSource());
                            details.putString("testState", reply.testState());
                            details.putString("testDispatchMode", reply.testDispatchMode());
                            details.putString("testActionSource", reply.testActionSource());
                            details.putBoolean("wifiAutoSyncEnabled", reply.wifiAutoSyncEnabled());
                            details.putString("wifiWatchSupport", reply.wifiWatchSupport());
                            details.putBoolean("wifiAckReady", reply.wifiAckReady());
                            details.putInt("wifiSyncedCount", reply.wifiSyncedCount());
                            details.putString("wifiLastResult", reply.wifiLastResult());
                            details.putString("wifiLastSource", reply.wifiLastSource());
                            details.putLong("wifiLastAt", reply.wifiLastAt());
                            details.putBoolean("liveWindowEnabled", reply.liveWindowEnabled());
                            details.putString("liveWatchSupport", reply.liveWatchSupport());
                            details.putInt("liveActiveCount", reply.liveActiveCount());
                            details.putString("liveLastResult", reply.liveLastResult());
                            details.putString("liveLastSource", reply.liveLastSource());
                            details.putString("liveDispatchMode", reply.liveDispatchMode());
                            details.putString("liveTestState", reply.liveTestState());
                            details.putBoolean("rawCommandDebugEnabled", reply.rawCommandDebugEnabled());
                            details.putBoolean("gameFlashbackEnabled", reply.gameFlashbackEnabled());
                            details.putInt("gameFlashbackLeadSec", reply.gameFlashbackLeadSec());
                            details.putBoolean("notifyMuteDndEnabled", reply.notifyMuteDndEnabled());
                            details.putBoolean("sleepAutoDndEnabled", reply.sleepAutoDndEnabled());
                            details.putString("sleepWatchSupport", reply.sleepWatchSupport());
                            details.putBoolean("sleepHookReady", reply.sleepHookReady());
                            details.putBoolean("dndPolicyAccess", reply.dndPolicyAccess());
                            details.putString("sleepLastEvent", reply.sleepLastEvent());
                            details.putLong("sleepLastAt", reply.sleepLastAt());
                            details.putLong("sleepLastFallAsleepAt", reply.sleepLastFallAsleepAt());
                            details.putLong("sleepLastWakeUpAt", reply.sleepLastWakeUpAt());
                            details.putBoolean("wakeExitDndEnabled", reply.wakeExitDndEnabled());
                            details.putBoolean("notifySilentPushEnabled", reply.notifySilentPushEnabled());
                            details.putString("silentPushWatchSupport", reply.silentPushWatchSupport());
                            details.putBoolean("notifySilentScreenOnEnabled", reply.notifySilentScreenOnEnabled());
                            details.putBoolean("notifySilentFocusEnabled", reply.notifySilentFocusEnabled());
                            details.putBoolean("notifySilentGameEnabled", reply.notifySilentGameEnabled());
                            details.putBoolean("sleepPauseMediaEnabled", reply.sleepPauseMediaEnabled());
                            details.putBoolean("watchConnected", reply.watchConnected());
                            details.putString("watchName", reply.watchName());
                            details.putInt("watchBattery", reply.watchBattery());
                            details.putBoolean("watchCharging", reply.watchCharging());
                            details.putLong("watchBatteryAt", reply.watchBatteryAt());
                            details.putBoolean("myDevicesCardEnabled", reply.myDevicesCardEnabled());
                            details.putString("myDevicesTapTarget", reply.myDevicesTapTarget());
                            pending.setResult(ACK, null, details);
                        } catch (RuntimeException e) {
                            ProbeLog.failure("HEALTH_HOST_CONTROL_FAILED", e);
                        } finally { pending.finish(); }
                    });
                } catch (RejectedExecutionException unavailable) { pending.finish(); }
            }
        };
        host.registerReceiver(receiver, new IntentFilter(ACTION), ClockActionWire.PERMISSION, null, Context.RECEIVER_EXPORTED);
        return receiver;
    }

    /** Always replies once on the main thread. */
    public static void send(Context owner, String command, Consumer<Reply> callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (Build.VERSION.SDK_INT < 34 || !validCommand(command)
                || !BuildConfig.APPLICATION_ID.equals(owner.getPackageName())
                || owner.getApplicationInfo().uid != Process.myUid()
                || !SenderVerifier.trustedReminderTarget(owner, TargetProfile.HEALTH)) {
            main.post(() -> callback.accept(unavailable()));
            return;
        }
        sendApi34(owner, command, main, callback);
    }

    @RequiresApi(34)
    private static void sendApi34(Context owner, String command, Handler main, Consumer<Reply> callback) {
        AtomicBoolean finished = new AtomicBoolean();
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) callback.accept(unavailable());
        };
        String id = UUID.randomUUID().toString();
        Intent request = DailyAlarmWire.route(ACTION, TargetProfile.HEALTH.packageName)
                .putExtra("request", id).putExtra("command", command)
                .putExtra("at", System.currentTimeMillis()).putExtra("elapsed", SystemClock.elapsedRealtime());
        main.postDelayed(timeout, TIMEOUT);
        try {
            owner.sendOrderedBroadcast(request, null,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
                    new BroadcastReceiver() {
                        @Override public void onReceive(Context context, Intent response) {
                            if (!finished.compareAndSet(false, true)) return;
                            main.removeCallbacks(timeout);
                            Bundle data = getResultExtras(false);
                            if (getResultCode() == ACK && data != null && id.equals(data.getString("request"))
                                    && data.containsKey("enabled")) {
                                callback.accept(new Reply(true,
                                        data.getBoolean("enabled"),
                                        data.getBoolean("calendarEnabled", false),
                                        data.getBoolean("remotePrefs"),
                                        data.getBoolean("serviceWitness"),
                                        data.getBoolean("sdkObserver"),
                                        data.getString("symbolSource"),
                                        data.getString("watchState"),
                                        data.getString("lastDispatchMode"),
                                        data.getString("lastActionSource"),
                                        data.getString("testState"),
                                        data.getString("testDispatchMode"),
                                        data.getString("testActionSource"),
                                        data.getBoolean("wifiAutoSyncEnabled", false),
                                        data.getString("wifiWatchSupport"),
                                        data.getBoolean("wifiAckReady", false),
                                        data.getInt("wifiSyncedCount", 0),
                                        data.getString("wifiLastResult"),
                                        data.getString("wifiLastSource"),
                                        data.getLong("wifiLastAt", 0L),
                                        data.getBoolean("liveWindowEnabled", false),
                                        data.getString("liveWatchSupport"),
                                        data.getInt("liveActiveCount", 0),
                                        data.getString("liveLastResult"),
                                        data.getString("liveLastSource"),
                                        data.getString("liveDispatchMode"),
                                        data.getString("liveTestState"),
                                        data.getBoolean("rawCommandDebugEnabled", false),
                                        data.getBoolean("gameFlashbackEnabled", false),
                                        data.getInt("gameFlashbackLeadSec", WatchLinkPrefs.DEFAULT_GAME_FLASHBACK_LEAD_SEC),
                                        data.getBoolean("notifyMuteDndEnabled", false),
                                        data.getBoolean("sleepAutoDndEnabled", false),
                                        data.getString("sleepWatchSupport"),
                                        data.getBoolean("sleepHookReady", false),
                                        data.getBoolean("dndPolicyAccess", false),
                                        data.getString("sleepLastEvent"),
                                        data.getLong("sleepLastAt", 0L),
                                        data.getLong("sleepLastFallAsleepAt", 0L),
                                        data.getLong("sleepLastWakeUpAt", 0L),
                                        data.getBoolean("wakeExitDndEnabled", false),
                                        data.getBoolean("notifySilentPushEnabled", false),
                                        data.getString("silentPushWatchSupport"),
                                        data.getBoolean("notifySilentScreenOnEnabled", false),
                                        data.getBoolean("notifySilentFocusEnabled", false),
                                        data.getBoolean("notifySilentGameEnabled", false),
                                        data.getBoolean("sleepPauseMediaEnabled", false),
                                        data.getBoolean("watchConnected", false),
                                        data.getString("watchName", ""),
                                        data.getInt("watchBattery", -1),
                                        data.getBoolean("watchCharging", false),
                                        data.getLong("watchBatteryAt", 0L),
                                        data.getBoolean("myDevicesCardEnabled", false),
                                        data.getString("myDevicesTapTarget", WatchLinkPrefs.TAP_TARGET_HEALTH)));
                            } else callback.accept(unavailable());
                        }
                    }, main, 0, null, null);
        } catch (RuntimeException failed) {
            main.removeCallbacks(timeout);
            main.post(timeout);
        }
    }

    private HealthHostControl() {}
}
