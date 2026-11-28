package io.github.watchlink.xposed;

import android.content.SharedPreferences;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.setup.WatchLinkPrefs;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reads WatchLink's feature toggles from LSPosed RemotePreferences when available,
 * falling back cleanly to host-local SharedPreferences. Keeps a strong listener reference.
 */
final class HostSettings {
    private final SharedPreferences remote;
    private final SharedPreferences local;
    // Strong reference prevents SharedPreferences' WeakHashMap from evicting the listener.
    private final SharedPreferences.OnSharedPreferenceChangeListener remoteListener;
    private volatile Runnable disabledCallback;
    private volatile Runnable timerChanged;
    private volatile Runnable calendarDisabledCallback;
    private volatile Consumer<Boolean> wifiAutoSyncChangedCallback;
    private volatile Consumer<Boolean> liveWindowChangedCallback;
    private volatile Consumer<Boolean> gameFlashbackChangedCallback;
    private volatile Consumer<Integer> gameFlashbackLeadSecChangedCallback;
    private volatile Consumer<Boolean> notifyMuteDndChangedCallback;
    private volatile Consumer<Boolean> notifySilentPushChangedCallback;
    private volatile Consumer<Boolean> notifySilentScreenOnChangedCallback;
    private volatile Consumer<Boolean> notifySilentFocusChangedCallback;
    private volatile Consumer<Boolean> notifySilentGameChangedCallback;
    private volatile Consumer<Boolean> notifyDisconnectStageChangedCallback;
    private volatile Consumer<Boolean> notifyBurstThrottleChangedCallback;
    private volatile Consumer<Integer> notifyBurstThrottleSecChangedCallback;
    private volatile Consumer<Boolean> batteryPhoneFullWatchChangedCallback;
    private volatile Consumer<Boolean> batteryPhoneLowWatchChangedCallback;
    private volatile Consumer<Integer> batteryPhoneLowThresholdChangedCallback;
    private volatile Consumer<Boolean> batteryWatchFullPhoneChangedCallback;
    private volatile Consumer<Boolean> batteryWatchLowPhoneChangedCallback;
    private volatile Consumer<Integer> batteryWatchLowThresholdChangedCallback;
    private volatile Consumer<Boolean> sleepAutoDndChangedCallback;
    private volatile Consumer<Boolean> wakeExitDndChangedCallback;
    private volatile Consumer<Boolean> sleepPauseMediaChangedCallback;
    private volatile Consumer<Boolean> sleepStopVideoChangedCallback;
    private volatile Consumer<String> sleepStopVideoCustomPkgsChangedCallback;
    private volatile Consumer<Boolean> myDevicesCardChangedCallback;
    private volatile Consumer<String> myDevicesTapTargetChangedCallback;
    private volatile Consumer<Boolean> rawCommandDebugChangedCallback;

    HostSettings(SharedPreferences remote, SharedPreferences local) {
        this.remote = remote;
        this.local = Objects.requireNonNull(local);
        if (remote != null) {
            remoteListener = (prefs, key) -> {
                if (WatchLinkPrefs.KEY_ENABLED.equals(key) && !prefs.getBoolean(WatchLinkPrefs.KEY_ENABLED, false)) {
                    Runnable callback = disabledCallback;
                    if (callback != null) callback.run();
                } else if (key == null || WatchLinkPrefs.timerKey(key)) {
                    mirrorTimerSettings();
                    Runnable callback = timerChanged;
                    if (callback != null) callback.run();
                } else if (WatchLinkPrefs.KEY_CALENDAR_ENABLED.equals(key)
                        && !prefs.getBoolean(WatchLinkPrefs.KEY_CALENDAR_ENABLED, false)) {
                    Runnable callback = calendarDisabledCallback;
                    if (callback != null) callback.run();
                } else if (WatchLinkPrefs.KEY_WIFI_AUTO_SYNC_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = wifiAutoSyncChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_WIFI_AUTO_SYNC_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_LIVE_WINDOW_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = liveWindowChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_LIVE_WINDOW_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_GAME_FLASHBACK_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = gameFlashbackChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_GAME_FLASHBACK_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_GAME_FLASHBACK_LEAD_SEC.equals(key)) {
                    Consumer<Integer> callback = gameFlashbackLeadSecChangedCallback;
                    if (callback != null) {
                        int sec = WatchLinkPrefs.clampGameFlashbackLeadSec(
                                prefs.getInt(WatchLinkPrefs.KEY_GAME_FLASHBACK_LEAD_SEC,
                                        WatchLinkPrefs.DEFAULT_GAME_FLASHBACK_LEAD_SEC));
                        callback.accept(sec);
                    }
                } else if (WatchLinkPrefs.KEY_NOTIFY_MUTE_DND_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = notifyMuteDndChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_NOTIFY_MUTE_DND_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_NOTIFY_SILENT_PUSH_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = notifySilentPushChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_PUSH_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_NOTIFY_SILENT_SCREEN_ON_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = notifySilentScreenOnChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_SCREEN_ON_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_NOTIFY_SILENT_FOCUS_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = notifySilentFocusChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_FOCUS_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_NOTIFY_SILENT_GAME_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = notifySilentGameChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_GAME_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_NOTIFY_DISCONNECT_STAGE_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = notifyDisconnectStageChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_NOTIFY_DISCONNECT_STAGE_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_NOTIFY_BURST_THROTTLE_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = notifyBurstThrottleChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_NOTIFY_BURST_THROTTLE_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_NOTIFY_BURST_THROTTLE_SEC.equals(key)) {
                    Consumer<Integer> callback = notifyBurstThrottleSecChangedCallback;
                    if (callback != null) {
                        callback.accept(WatchLinkPrefs.clampNotifyBurstThrottleSec(
                                prefs.getInt(WatchLinkPrefs.KEY_NOTIFY_BURST_THROTTLE_SEC,
                                        WatchLinkPrefs.DEFAULT_NOTIFY_BURST_THROTTLE_SEC)));
                    }
                } else if (WatchLinkPrefs.KEY_BATTERY_PHONE_FULL_WATCH_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = batteryPhoneFullWatchChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_BATTERY_PHONE_FULL_WATCH_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_BATTERY_PHONE_LOW_WATCH_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = batteryPhoneLowWatchChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_BATTERY_PHONE_LOW_WATCH_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_BATTERY_PHONE_LOW_THRESHOLD.equals(key)) {
                    Consumer<Integer> callback = batteryPhoneLowThresholdChangedCallback;
                    if (callback != null) {
                        callback.accept(WatchLinkPrefs.clampBatteryLowThreshold(
                                prefs.getInt(WatchLinkPrefs.KEY_BATTERY_PHONE_LOW_THRESHOLD,
                                        WatchLinkPrefs.DEFAULT_BATTERY_LOW_THRESHOLD)));
                    }
                } else if (WatchLinkPrefs.KEY_BATTERY_WATCH_FULL_PHONE_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = batteryWatchFullPhoneChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_BATTERY_WATCH_FULL_PHONE_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_BATTERY_WATCH_LOW_PHONE_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = batteryWatchLowPhoneChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_BATTERY_WATCH_LOW_PHONE_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_BATTERY_WATCH_LOW_THRESHOLD.equals(key)) {
                    Consumer<Integer> callback = batteryWatchLowThresholdChangedCallback;
                    if (callback != null) {
                        callback.accept(WatchLinkPrefs.clampBatteryLowThreshold(
                                prefs.getInt(WatchLinkPrefs.KEY_BATTERY_WATCH_LOW_THRESHOLD,
                                        WatchLinkPrefs.DEFAULT_BATTERY_LOW_THRESHOLD)));
                    }
                } else if (WatchLinkPrefs.KEY_SLEEP_AUTO_DND_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = sleepAutoDndChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_SLEEP_AUTO_DND_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_WAKE_EXIT_DND_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = wakeExitDndChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_WAKE_EXIT_DND_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_SLEEP_PAUSE_MEDIA_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = sleepPauseMediaChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_SLEEP_PAUSE_MEDIA_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_SLEEP_STOP_VIDEO_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = sleepStopVideoChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_SLEEP_STOP_VIDEO_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_SLEEP_STOP_VIDEO_CUSTOM_PKGS.equals(key)) {
                    Consumer<String> callback = sleepStopVideoCustomPkgsChangedCallback;
                    if (callback != null) {
                        callback.accept(io.github.watchlink.notify.SleepVideoStopPolicy.serializeCustomPackages(
                                io.github.watchlink.notify.SleepVideoStopPolicy.parseCustomPackages(
                                        prefs.getString(WatchLinkPrefs.KEY_SLEEP_STOP_VIDEO_CUSTOM_PKGS, ""))));
                    }
                } else if (WatchLinkPrefs.KEY_MYDEVICES_CARD_ENABLED.equals(key)) {
                    Consumer<Boolean> callback = myDevicesCardChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_MYDEVICES_CARD_ENABLED, false));
                    }
                } else if (WatchLinkPrefs.KEY_MYDEVICES_TAP_TARGET.equals(key)) {
                    Consumer<String> callback = myDevicesTapTargetChangedCallback;
                    if (callback != null) {
                        callback.accept(WatchLinkPrefs.normalizeTapTarget(
                                prefs.getString(WatchLinkPrefs.KEY_MYDEVICES_TAP_TARGET,
                                        WatchLinkPrefs.TAP_TARGET_HEALTH)));
                    }
                } else if (WatchLinkPrefs.KEY_RAW_COMMAND_DEBUG.equals(key)) {
                    Consumer<Boolean> callback = rawCommandDebugChangedCallback;
                    if (callback != null) {
                        callback.accept(prefs.getBoolean(WatchLinkPrefs.KEY_RAW_COMMAND_DEBUG, false));
                    }
                }
            };
            try {
                remote.registerOnSharedPreferenceChangeListener(remoteListener);
                mirrorTimerSettings();
            } catch (RuntimeException ignored) { /* Proceed with direct reads if listener unsupported. */ }
        } else {
            remoteListener = null;
        }
    }

    private void mirrorTimerSettings() {
        if (remote == null) return;
        try {
            var editor = local.edit();
            for (String key : java.util.List.of(WatchLinkPrefs.KEY_TIMER_ENABLED, WatchLinkPrefs.KEY_TIMER_LIVE,
                    WatchLinkPrefs.KEY_TIMER_RESTART)) {
                if (remote.contains(key)) editor.putBoolean(key, remote.getBoolean(key, false));
                else editor.remove(key);
            }
            editor.apply();
        } catch (RuntimeException unavailable) { /* Retain the last synchronized settings. */ }
    }

    boolean remoteAvailable() {
        return remote != null;
    }

    boolean isEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_ENABLED);
    }

    boolean isTimerEnabled() { return readBoolean(WatchLinkPrefs.KEY_TIMER_ENABLED); }

    boolean isTimerLiveWindowEnabled() { return readBoolean(WatchLinkPrefs.KEY_TIMER_LIVE); }

    boolean isTimerRestartEnabled() { return readBoolean(WatchLinkPrefs.KEY_TIMER_RESTART); }

    void onTimerChanged(Runnable callback) { timerChanged = callback; }

    boolean isCalendarEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_CALENDAR_ENABLED);
    }

    boolean isWifiAutoSyncEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_WIFI_AUTO_SYNC_ENABLED);
    }

    boolean isLiveWindowEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_LIVE_WINDOW_ENABLED);
    }

    boolean isGameFlashbackEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_GAME_FLASHBACK_ENABLED);
    }

    int gameFlashbackLeadSec() {
        if (remote != null) {
            try {
                if (remote.contains(WatchLinkPrefs.KEY_GAME_FLASHBACK_LEAD_SEC)) {
                    return WatchLinkPrefs.clampGameFlashbackLeadSec(
                            remote.getInt(WatchLinkPrefs.KEY_GAME_FLASHBACK_LEAD_SEC,
                                    WatchLinkPrefs.DEFAULT_GAME_FLASHBACK_LEAD_SEC));
                }
            } catch (RuntimeException ignored) { /* Fall back to host-local preference. */ }
        }
        return WatchLinkPrefs.clampGameFlashbackLeadSec(
                local.getInt(WatchLinkPrefs.KEY_GAME_FLASHBACK_LEAD_SEC,
                        WatchLinkPrefs.DEFAULT_GAME_FLASHBACK_LEAD_SEC));
    }

    boolean isNotifyMuteDndEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_NOTIFY_MUTE_DND_ENABLED);
    }

    boolean isNotifySilentPushEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_PUSH_ENABLED);
    }

    boolean isNotifySilentScreenOnEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_SCREEN_ON_ENABLED);
    }

    boolean isNotifySilentFocusEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_FOCUS_ENABLED);
    }

    boolean isNotifySilentGameEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_GAME_ENABLED);
    }

    boolean isNotifyDisconnectStageEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_NOTIFY_DISCONNECT_STAGE_ENABLED);
    }

    boolean isNotifyBurstThrottleEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_NOTIFY_BURST_THROTTLE_ENABLED);
    }

    int notifyBurstThrottleSec() {
        if (remote != null) {
            try {
                if (remote.contains(WatchLinkPrefs.KEY_NOTIFY_BURST_THROTTLE_SEC)) {
                    return WatchLinkPrefs.clampNotifyBurstThrottleSec(
                            remote.getInt(WatchLinkPrefs.KEY_NOTIFY_BURST_THROTTLE_SEC,
                                    WatchLinkPrefs.DEFAULT_NOTIFY_BURST_THROTTLE_SEC));
                }
            } catch (RuntimeException ignored) {}
        }
        return WatchLinkPrefs.clampNotifyBurstThrottleSec(
                local.getInt(WatchLinkPrefs.KEY_NOTIFY_BURST_THROTTLE_SEC,
                        WatchLinkPrefs.DEFAULT_NOTIFY_BURST_THROTTLE_SEC));
    }

    boolean isBatteryPhoneFullWatchEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_BATTERY_PHONE_FULL_WATCH_ENABLED);
    }

    boolean isBatteryPhoneLowWatchEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_BATTERY_PHONE_LOW_WATCH_ENABLED);
    }

    int batteryPhoneLowThreshold() {
        if (remote != null) {
            try {
                if (remote.contains(WatchLinkPrefs.KEY_BATTERY_PHONE_LOW_THRESHOLD)) {
                    return WatchLinkPrefs.clampBatteryLowThreshold(
                            remote.getInt(WatchLinkPrefs.KEY_BATTERY_PHONE_LOW_THRESHOLD,
                                    WatchLinkPrefs.DEFAULT_BATTERY_LOW_THRESHOLD));
                }
            } catch (RuntimeException ignored) {}
        }
        return WatchLinkPrefs.clampBatteryLowThreshold(
                local.getInt(WatchLinkPrefs.KEY_BATTERY_PHONE_LOW_THRESHOLD,
                        WatchLinkPrefs.DEFAULT_BATTERY_LOW_THRESHOLD));
    }

    boolean isBatteryWatchFullPhoneEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_BATTERY_WATCH_FULL_PHONE_ENABLED);
    }

    boolean isBatteryWatchLowPhoneEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_BATTERY_WATCH_LOW_PHONE_ENABLED);
    }

    int batteryWatchLowThreshold() {
        if (remote != null) {
            try {
                if (remote.contains(WatchLinkPrefs.KEY_BATTERY_WATCH_LOW_THRESHOLD)) {
                    return WatchLinkPrefs.clampBatteryLowThreshold(
                            remote.getInt(WatchLinkPrefs.KEY_BATTERY_WATCH_LOW_THRESHOLD,
                                    WatchLinkPrefs.DEFAULT_BATTERY_LOW_THRESHOLD));
                }
            } catch (RuntimeException ignored) {}
        }
        return WatchLinkPrefs.clampBatteryLowThreshold(
                local.getInt(WatchLinkPrefs.KEY_BATTERY_WATCH_LOW_THRESHOLD,
                        WatchLinkPrefs.DEFAULT_BATTERY_LOW_THRESHOLD));
    }

    boolean isSleepAutoDndEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_SLEEP_AUTO_DND_ENABLED);
    }

    boolean isWakeExitDndEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_WAKE_EXIT_DND_ENABLED);
    }

    boolean isSleepPauseMediaEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_SLEEP_PAUSE_MEDIA_ENABLED);
    }

    boolean isSleepStopVideoEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_SLEEP_STOP_VIDEO_ENABLED);
    }

    String sleepStopVideoCustomPkgs() {
        if (remote != null) {
            try {
                if (remote.contains(WatchLinkPrefs.KEY_SLEEP_STOP_VIDEO_CUSTOM_PKGS)) {
                    return io.github.watchlink.notify.SleepVideoStopPolicy.serializeCustomPackages(
                            io.github.watchlink.notify.SleepVideoStopPolicy.parseCustomPackages(
                                    remote.getString(WatchLinkPrefs.KEY_SLEEP_STOP_VIDEO_CUSTOM_PKGS, "")));
                }
            } catch (RuntimeException ignored) {}
        }
        return io.github.watchlink.notify.SleepVideoStopPolicy.serializeCustomPackages(
                io.github.watchlink.notify.SleepVideoStopPolicy.parseCustomPackages(
                        local.getString(WatchLinkPrefs.KEY_SLEEP_STOP_VIDEO_CUSTOM_PKGS, "")));
    }

    boolean isMyDevicesCardEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_MYDEVICES_CARD_ENABLED);
    }

    String myDevicesTapTarget() {
        if (remote != null) {
            try {
                if (remote.contains(WatchLinkPrefs.KEY_MYDEVICES_TAP_TARGET)) {
                    return WatchLinkPrefs.normalizeTapTarget(
                            remote.getString(WatchLinkPrefs.KEY_MYDEVICES_TAP_TARGET,
                                    WatchLinkPrefs.TAP_TARGET_HEALTH));
                }
            } catch (RuntimeException ignored) {}
        }
        return WatchLinkPrefs.normalizeTapTarget(
                local.getString(WatchLinkPrefs.KEY_MYDEVICES_TAP_TARGET,
                        WatchLinkPrefs.TAP_TARGET_HEALTH));
    }

    boolean isRawCommandDebugEnabled() {
        return readBoolean(WatchLinkPrefs.KEY_RAW_COMMAND_DEBUG);
    }

    boolean isEnabledFor(TargetProfile source) {
        return isEnabledFor(source, false);
    }

    boolean isEnabledFor(TargetProfile source, boolean timer) {
        if (timer) return source == TargetProfile.CLOCK && isTimerEnabled();
        if (source == TargetProfile.CALENDAR) return isCalendarEnabled();
        if (source == TargetProfile.CLOCK) return isEnabled();
        return false;
    }

    private boolean readBoolean(String key) {
        if (remote != null) {
            try {
                if (remote.contains(key)) {
                    return remote.getBoolean(key, false);
                }
            } catch (RuntimeException ignored) { /* Fall back to host-local preference. */ }
        }
        return local.getBoolean(key, false);
    }

    boolean setLocalFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_ENABLED, enabled).commit();
    }

    boolean setLocalCalendarFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_CALENDAR_ENABLED, enabled).commit();
    }

    boolean setLocalWifiAutoSyncFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_WIFI_AUTO_SYNC_ENABLED, enabled).commit();
    }

    boolean setLocalLiveWindowFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_LIVE_WINDOW_ENABLED, enabled).commit();
    }

    boolean setLocalGameFlashbackFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_GAME_FLASHBACK_ENABLED, enabled).commit();
    }

    boolean setLocalGameFlashbackLeadSecFallback(int leadSec) {
        return local.edit().putInt(WatchLinkPrefs.KEY_GAME_FLASHBACK_LEAD_SEC,
                WatchLinkPrefs.clampGameFlashbackLeadSec(leadSec)).commit();
    }

    boolean setLocalNotifyMuteDndFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_NOTIFY_MUTE_DND_ENABLED, enabled).commit();
    }

    boolean setLocalNotifySilentPushFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_PUSH_ENABLED, enabled).commit();
    }

    boolean setLocalNotifySilentScreenOnFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_SCREEN_ON_ENABLED, enabled).commit();
    }

    boolean setLocalNotifySilentFocusFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_FOCUS_ENABLED, enabled).commit();
    }

    boolean setLocalNotifySilentGameFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_GAME_ENABLED, enabled).commit();
    }

    boolean setLocalNotifyDisconnectStageFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_NOTIFY_DISCONNECT_STAGE_ENABLED, enabled).commit();
    }

    boolean setLocalNotifyBurstThrottleFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_NOTIFY_BURST_THROTTLE_ENABLED, enabled).commit();
    }

    boolean setLocalNotifyBurstThrottleSecFallback(int sec) {
        return local.edit().putInt(WatchLinkPrefs.KEY_NOTIFY_BURST_THROTTLE_SEC,
                WatchLinkPrefs.clampNotifyBurstThrottleSec(sec)).commit();
    }

    boolean setLocalBatteryPhoneFullWatchFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_BATTERY_PHONE_FULL_WATCH_ENABLED, enabled).commit();
    }

    boolean setLocalBatteryPhoneLowWatchFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_BATTERY_PHONE_LOW_WATCH_ENABLED, enabled).commit();
    }

    boolean setLocalBatteryPhoneLowThresholdFallback(int percent) {
        return local.edit().putInt(WatchLinkPrefs.KEY_BATTERY_PHONE_LOW_THRESHOLD,
                WatchLinkPrefs.clampBatteryLowThreshold(percent)).commit();
    }

    boolean setLocalBatteryWatchFullPhoneFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_BATTERY_WATCH_FULL_PHONE_ENABLED, enabled).commit();
    }

    boolean setLocalBatteryWatchLowPhoneFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_BATTERY_WATCH_LOW_PHONE_ENABLED, enabled).commit();
    }

    boolean setLocalBatteryWatchLowThresholdFallback(int percent) {
        return local.edit().putInt(WatchLinkPrefs.KEY_BATTERY_WATCH_LOW_THRESHOLD,
                WatchLinkPrefs.clampBatteryLowThreshold(percent)).commit();
    }

    boolean setLocalSleepAutoDndFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_SLEEP_AUTO_DND_ENABLED, enabled).commit();
    }

    boolean setLocalWakeExitDndFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_WAKE_EXIT_DND_ENABLED, enabled).commit();
    }

    boolean setLocalSleepPauseMediaFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_SLEEP_PAUSE_MEDIA_ENABLED, enabled).commit();
    }

    boolean setLocalSleepStopVideoFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_SLEEP_STOP_VIDEO_ENABLED, enabled).commit();
    }

    boolean setLocalSleepStopVideoCustomPkgsFallback(String raw) {
        String normalized = io.github.watchlink.notify.SleepVideoStopPolicy.serializeCustomPackages(
                io.github.watchlink.notify.SleepVideoStopPolicy.parseCustomPackages(raw));
        return local.edit().putString(WatchLinkPrefs.KEY_SLEEP_STOP_VIDEO_CUSTOM_PKGS, normalized).commit();
    }

    boolean setLocalMyDevicesCardFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_MYDEVICES_CARD_ENABLED, enabled).commit();
    }

    boolean setLocalMyDevicesTapTargetFallback(String target) {
        return local.edit().putString(WatchLinkPrefs.KEY_MYDEVICES_TAP_TARGET,
                WatchLinkPrefs.normalizeTapTarget(target)).commit();
    }

    boolean setLocalRawCommandDebugFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_RAW_COMMAND_DEBUG, enabled).commit();
    }

    void onDisabled(Runnable callback) {
        disabledCallback = callback;
    }

    void onCalendarDisabled(Runnable callback) {
        calendarDisabledCallback = callback;
    }

    void onWifiAutoSyncChanged(Consumer<Boolean> callback) {
        wifiAutoSyncChangedCallback = callback;
    }

    void onLiveWindowChanged(Consumer<Boolean> callback) {
        liveWindowChangedCallback = callback;
    }

    void onGameFlashbackChanged(Consumer<Boolean> callback) {
        gameFlashbackChangedCallback = callback;
    }

    void onGameFlashbackLeadSecChanged(Consumer<Integer> callback) {
        gameFlashbackLeadSecChangedCallback = callback;
    }

    void onNotifyMuteDndChanged(Consumer<Boolean> callback) {
        notifyMuteDndChangedCallback = callback;
    }

    void onNotifySilentPushChanged(Consumer<Boolean> callback) {
        notifySilentPushChangedCallback = callback;
    }

    void onNotifySilentScreenOnChanged(Consumer<Boolean> callback) {
        notifySilentScreenOnChangedCallback = callback;
    }

    void onNotifySilentFocusChanged(Consumer<Boolean> callback) {
        notifySilentFocusChangedCallback = callback;
    }

    void onNotifySilentGameChanged(Consumer<Boolean> callback) {
        notifySilentGameChangedCallback = callback;
    }

    void onNotifyDisconnectStageChanged(Consumer<Boolean> callback) {
        notifyDisconnectStageChangedCallback = callback;
    }

    void onNotifyBurstThrottleChanged(Consumer<Boolean> callback) {
        notifyBurstThrottleChangedCallback = callback;
    }

    void onNotifyBurstThrottleSecChanged(Consumer<Integer> callback) {
        notifyBurstThrottleSecChangedCallback = callback;
    }

    void onBatteryPhoneFullWatchChanged(Consumer<Boolean> callback) {
        batteryPhoneFullWatchChangedCallback = callback;
    }

    void onBatteryPhoneLowWatchChanged(Consumer<Boolean> callback) {
        batteryPhoneLowWatchChangedCallback = callback;
    }

    void onBatteryPhoneLowThresholdChanged(Consumer<Integer> callback) {
        batteryPhoneLowThresholdChangedCallback = callback;
    }

    void onBatteryWatchFullPhoneChanged(Consumer<Boolean> callback) {
        batteryWatchFullPhoneChangedCallback = callback;
    }

    void onBatteryWatchLowPhoneChanged(Consumer<Boolean> callback) {
        batteryWatchLowPhoneChangedCallback = callback;
    }

    void onBatteryWatchLowThresholdChanged(Consumer<Integer> callback) {
        batteryWatchLowThresholdChangedCallback = callback;
    }

    void onSleepAutoDndChanged(Consumer<Boolean> callback) {
        sleepAutoDndChangedCallback = callback;
    }

    void onWakeExitDndChanged(Consumer<Boolean> callback) {
        wakeExitDndChangedCallback = callback;
    }

    void onSleepPauseMediaChanged(Consumer<Boolean> callback) {
        sleepPauseMediaChangedCallback = callback;
    }

    void onSleepStopVideoChanged(Consumer<Boolean> callback) {
        sleepStopVideoChangedCallback = callback;
    }

    void onSleepStopVideoCustomPkgsChanged(Consumer<String> callback) {
        sleepStopVideoCustomPkgsChangedCallback = callback;
    }

    void onMyDevicesCardChanged(Consumer<Boolean> callback) {
        myDevicesCardChangedCallback = callback;
    }

    void onMyDevicesTapTargetChanged(Consumer<String> callback) {
        myDevicesTapTargetChangedCallback = callback;
    }

    void onRawCommandDebugChanged(Consumer<Boolean> callback) {
        rawCommandDebugChangedCallback = callback;
    }

    void close() {
        disabledCallback = null;
        timerChanged = null;
        calendarDisabledCallback = null;
        wifiAutoSyncChangedCallback = null;
        liveWindowChangedCallback = null;
        gameFlashbackChangedCallback = null;
        gameFlashbackLeadSecChangedCallback = null;
        notifyMuteDndChangedCallback = null;
        notifySilentPushChangedCallback = null;
        notifySilentScreenOnChangedCallback = null;
        notifySilentFocusChangedCallback = null;
        notifySilentGameChangedCallback = null;
        notifyDisconnectStageChangedCallback = null;
        notifyBurstThrottleChangedCallback = null;
        notifyBurstThrottleSecChangedCallback = null;
        batteryPhoneFullWatchChangedCallback = null;
        batteryPhoneLowWatchChangedCallback = null;
        batteryPhoneLowThresholdChangedCallback = null;
        batteryWatchFullPhoneChangedCallback = null;
        batteryWatchLowPhoneChangedCallback = null;
        batteryWatchLowThresholdChangedCallback = null;
        sleepAutoDndChangedCallback = null;
        wakeExitDndChangedCallback = null;
        sleepPauseMediaChangedCallback = null;
        sleepStopVideoChangedCallback = null;
        sleepStopVideoCustomPkgsChangedCallback = null;
        myDevicesCardChangedCallback = null;
        myDevicesTapTargetChangedCallback = null;
        rawCommandDebugChangedCallback = null;
        if (remote != null && remoteListener != null) {
            try { remote.unregisterOnSharedPreferenceChangeListener(remoteListener); }
            catch (RuntimeException ignored) { /* Closing. */ }
        }
    }
}
