package io.github.watchlink.xposed;

import android.content.SharedPreferences;
import io.github.watchlink.setup.WatchLinkPrefs;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class HostSettingsTest {
    private static final class MemoryPrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        final Set<OnSharedPreferenceChangeListener> listeners = new HashSet<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String k, String d) { return (String) values.getOrDefault(k, d); }
        @Override public Set<String> getStringSet(String k, Set<String> d) { return d; }
        @Override public int getInt(String k, int d) { return (Integer) values.getOrDefault(k, d); }
        @Override public long getLong(String k, long d) { return (Long) values.getOrDefault(k, d); }
        @Override public float getFloat(String k, float d) { return (Float) values.getOrDefault(k, d); }
        @Override public boolean getBoolean(String k, boolean d) { return (Boolean) values.getOrDefault(k, d); }
        @Override public boolean contains(String k) { return values.containsKey(k); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { listeners.add(l); }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { listeners.remove(l); }

        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> staged = new HashMap<>();
                @Override public Editor putString(String k, String v) { staged.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { return this; }
                @Override public Editor putInt(String k, int v) { staged.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { staged.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { staged.put(k, v); return this; }
                @Override public Editor putBoolean(String k, boolean v) { staged.put(k, v); return this; }
                @Override public Editor remove(String k) { staged.put(k, null); return this; }
                @Override public Editor clear() { values.clear(); return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() {
                    for (var entry : staged.entrySet()) {
                        if (entry.getValue() == null) values.remove(entry.getKey());
                        else values.put(entry.getKey(), entry.getValue());
                        for (var listener : new HashSet<>(listeners)) {
                            listener.onSharedPreferenceChanged(MemoryPrefs.this, entry.getKey());
                        }
                    }
                }
            };
        }
    }

    @Test public void remotePreferencesTakePrecedenceAndNotifyWhenDisabled() {
        MemoryPrefs remote = new MemoryPrefs();
        MemoryPrefs local = new MemoryPrefs();
        HostSettings settings = new HostSettings(remote, local);
        assertTrue(settings.remoteAvailable());
        assertFalse(settings.isEnabled());

        // Before remote has the key, local fallback applies.
        assertTrue(settings.setLocalFallback(true));
        assertTrue(settings.isEnabled());

        // Once remote writes false, remote overrides local and fires onDisabled.
        AtomicInteger disabledCount = new AtomicInteger();
        settings.onDisabled(disabledCount::incrementAndGet);
        remote.edit().putBoolean(WatchLinkPrefs.KEY_ENABLED, false).commit();
        assertFalse(settings.isEnabled());
        assertEquals(1, disabledCount.get());

        // Remote writes true -> enabled, does not trigger onDisabled.
        remote.edit().putBoolean(WatchLinkPrefs.KEY_ENABLED, true).commit();
        assertTrue(settings.isEnabled());
        assertEquals(1, disabledCount.get());

        settings.close();
        assertTrue(remote.listeners.isEmpty());
    }

    @Test public void fallsBackToLocalStorageWhenRemoteUnavailable() {
        MemoryPrefs local = new MemoryPrefs();
        HostSettings settings = new HostSettings(null, local);
        assertFalse(settings.remoteAvailable());
        assertFalse(settings.isEnabled());
        assertFalse(settings.isCalendarEnabled());
        assertTrue(settings.setLocalFallback(true));
        assertTrue(settings.isEnabled());
        assertFalse(settings.isCalendarEnabled());
        assertTrue(settings.setLocalCalendarFallback(true));
        assertTrue(settings.isCalendarEnabled());
    }

    @Test public void calendarToggleRemainsIndependentAndNotifiesWhenDisabled() {
        MemoryPrefs remote = new MemoryPrefs();
        MemoryPrefs local = new MemoryPrefs();
        HostSettings settings = new HostSettings(remote, local);
        AtomicInteger clockDisabled = new AtomicInteger();
        AtomicInteger calDisabled = new AtomicInteger();
        settings.onDisabled(clockDisabled::incrementAndGet);
        settings.onCalendarDisabled(calDisabled::incrementAndGet);

        remote.edit().putBoolean(WatchLinkPrefs.KEY_ENABLED, true).commit();
        remote.edit().putBoolean(WatchLinkPrefs.KEY_CALENDAR_ENABLED, true).commit();
        assertTrue(settings.isEnabled());
        assertTrue(settings.isCalendarEnabled());

        remote.edit().putBoolean(WatchLinkPrefs.KEY_CALENDAR_ENABLED, false).commit();
        assertTrue(settings.isEnabled());
        assertFalse(settings.isCalendarEnabled());
        assertEquals(0, clockDisabled.get());
        assertEquals(1, calDisabled.get());
    }
}
