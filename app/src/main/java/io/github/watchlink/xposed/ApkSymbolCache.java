package io.github.watchlink.xposed;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.watchlink.BuildConfig;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists dynamically resolved host symbols keyed by the installed APK's path, size, and mtime.
 * Any host app update invalidates the cache automatically so DEX scanning runs at most once per update.
 */
final class ApkSymbolCache {
    private static final String KEY_FINGERPRINT = "_fingerprint";
    private final SharedPreferences prefs;

    ApkSymbolCache(Context app, String hostTag) {
        this(app.getSharedPreferences(
                BuildConfig.APPLICATION_ID + ".symbols_" + hostTag + "_v1", Context.MODE_PRIVATE));
    }

    ApkSymbolCache(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    static String fingerprint(String schemaVersion, String[] sources) {
        StringBuilder out = new StringBuilder(schemaVersion);
        if (sources != null) {
            for (String path : sources) {
                out.append('|');
                if (path == null) continue;
                File file = new File(path);
                out.append(path).append(':').append(file.length()).append(':').append(file.lastModified());
            }
        }
        return out.toString();
    }

    Map<String, String> read(String expectedFingerprint) {
        if (expectedFingerprint == null || !expectedFingerprint.equals(prefs.getString(KEY_FINGERPRINT, null))) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!KEY_FINGERPRINT.equals(entry.getKey()) && entry.getValue() instanceof String value) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    void write(String fingerprint, Map<String, String> values) {
        SharedPreferences.Editor editor = prefs.edit().clear().putString(KEY_FINGERPRINT, fingerprint);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() != null && !KEY_FINGERPRINT.equals(entry.getKey()) && entry.getValue() != null) {
                editor.putString(entry.getKey(), entry.getValue());
            }
        }
        editor.apply();
    }
}
