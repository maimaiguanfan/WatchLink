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

    void onRawCommandDebugChanged(Consumer<Boolean> callback) {
        rawCommandDebugChangedCallback = callback;
    }

    void close() {
        disabledCallback = null;
        timerChanged = null;
        calendarDisabledCallback = null;
        wifiAutoSyncChangedCallback = null;
        liveWindowChangedCallback = null;
        rawCommandDebugChangedCallback = null;
        if (remote != null && remoteListener != null) {
            try { remote.unregisterOnSharedPreferenceChangeListener(remoteListener); }
            catch (RuntimeException ignored) { /* Closing. */ }
        }
    }
}
