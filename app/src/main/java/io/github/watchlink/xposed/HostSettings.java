package io.github.watchlink.xposed;

import android.content.SharedPreferences;
import io.github.watchlink.setup.WatchLinkPrefs;
import java.util.Objects;

/**
 * Reads WatchLink's enabled toggle from LSPosed RemotePreferences when available,
 * falling back cleanly to host-local SharedPreferences. Keeps a strong listener reference.
 */
final class HostSettings {
    private final SharedPreferences remote;
    private final SharedPreferences local;
    // Strong reference prevents SharedPreferences' WeakHashMap from evicting the listener.
    private final SharedPreferences.OnSharedPreferenceChangeListener remoteListener;
    private volatile Runnable disabledCallback;

    HostSettings(SharedPreferences remote, SharedPreferences local) {
        this.remote = remote;
        this.local = Objects.requireNonNull(local);
        if (remote != null) {
            remoteListener = (prefs, key) -> {
                if (WatchLinkPrefs.KEY_ENABLED.equals(key) && !prefs.getBoolean(WatchLinkPrefs.KEY_ENABLED, false)) {
                    Runnable callback = disabledCallback;
                    if (callback != null) callback.run();
                }
            };
            try {
                remote.registerOnSharedPreferenceChangeListener(remoteListener);
            } catch (RuntimeException ignored) { /* Proceed with direct reads if listener unsupported. */ }
        } else {
            remoteListener = null;
        }
    }

    boolean remoteAvailable() {
        return remote != null;
    }

    boolean isEnabled() {
        if (remote != null) {
            try {
                if (remote.contains(WatchLinkPrefs.KEY_ENABLED)) {
                    return remote.getBoolean(WatchLinkPrefs.KEY_ENABLED, false);
                }
            } catch (RuntimeException ignored) { /* Fall back to host-local preference. */ }
        }
        return local.getBoolean(WatchLinkPrefs.KEY_ENABLED, false);
    }

    boolean setLocalFallback(boolean enabled) {
        return local.edit().putBoolean(WatchLinkPrefs.KEY_ENABLED, enabled).commit();
    }

    void onDisabled(Runnable callback) {
        disabledCallback = callback;
    }

    void close() {
        disabledCallback = null;
        if (remote != null && remoteListener != null) {
            try { remote.unregisterOnSharedPreferenceChangeListener(remoteListener); }
            catch (RuntimeException ignored) { /* Closing. */ }
        }
    }
}
