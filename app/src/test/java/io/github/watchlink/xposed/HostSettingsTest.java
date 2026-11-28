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

    @Test public void wifiAutoSyncToggleNotifiesChangesAndFallsBackLocally() {
        MemoryPrefs remote = new MemoryPrefs();
        MemoryPrefs local = new MemoryPrefs();
        HostSettings settings = new HostSettings(remote, local);
        AtomicInteger enabledEvents = new AtomicInteger();
        settings.onWifiAutoSyncChanged(on -> {
            if (Boolean.TRUE.equals(on)) enabledEvents.incrementAndGet();
        });

        assertFalse(settings.isWifiAutoSyncEnabled());
        assertTrue(settings.setLocalWifiAutoSyncFallback(true));
        assertTrue(settings.isWifiAutoSyncEnabled());

        remote.edit().putBoolean(WatchLinkPrefs.KEY_WIFI_AUTO_SYNC_ENABLED, false).commit();
        assertFalse(settings.isWifiAutoSyncEnabled());
        assertEquals(0, enabledEvents.get());

        remote.edit().putBoolean(WatchLinkPrefs.KEY_WIFI_AUTO_SYNC_ENABLED, true).commit();
        assertTrue(settings.isWifiAutoSyncEnabled());
        assertEquals(1, enabledEvents.get());
    }

    @Test public void liveWindowToggleNotifiesChangesAndFallsBackLocally() {
        MemoryPrefs remote = new MemoryPrefs();
        MemoryPrefs local = new MemoryPrefs();
        HostSettings settings = new HostSettings(remote, local);
        AtomicInteger onCount = new AtomicInteger();
        AtomicInteger offCount = new AtomicInteger();
        settings.onLiveWindowChanged(on -> {
            if (Boolean.TRUE.equals(on)) onCount.incrementAndGet();
            else offCount.incrementAndGet();
        });

        assertFalse(settings.isLiveWindowEnabled());
        assertTrue(settings.setLocalLiveWindowFallback(true));
        assertTrue(settings.isLiveWindowEnabled());

        remote.edit().putBoolean(WatchLinkPrefs.KEY_LIVE_WINDOW_ENABLED, false).commit();
        assertFalse(settings.isLiveWindowEnabled());
        assertEquals(1, offCount.get());

        remote.edit().putBoolean(WatchLinkPrefs.KEY_LIVE_WINDOW_ENABLED, true).commit();
        assertTrue(settings.isLiveWindowEnabled());
        assertEquals(1, onCount.get());
    }
    @Test public void countdownTogglesAreIndependentAndCopiedToHostFallback() {
        MemoryPrefs remote = new MemoryPrefs();
        MemoryPrefs local = new MemoryPrefs();
        HostSettings settings = new HostSettings(remote, local);
        var clock = io.github.watchlink.alarm.probe.TargetProfile.CLOCK;
        assertFalse(settings.isTimerEnabled());
        assertFalse(settings.isTimerLiveWindowEnabled());
        assertFalse(settings.isTimerRestartEnabled());
        remote.edit().putBoolean(WatchLinkPrefs.KEY_TIMER_ENABLED, true)
                .putBoolean(WatchLinkPrefs.KEY_TIMER_LIVE, true)
                .putBoolean(WatchLinkPrefs.KEY_TIMER_RESTART, true).apply();
        assertTrue(settings.isEnabledFor(clock, true));
        assertFalse(settings.isEnabledFor(clock, false));
        assertFalse(settings.isEnabledFor(io.github.watchlink.alarm.probe.TargetProfile.CALENDAR, true));
        assertFalse(settings.isLiveWindowEnabled());
        assertTrue(settings.isTimerLiveWindowEnabled());
        remote.edit().putBoolean(WatchLinkPrefs.KEY_TIMER_LIVE, false).apply();
        var fallback = new HostSettings(null, local);
        assertTrue(fallback.isTimerEnabled());
        assertTrue(fallback.isTimerRestartEnabled());
        assertFalse(fallback.isTimerLiveWindowEnabled());
        assertFalse(fallback.isLiveWindowEnabled());
    }

    @Test public void rawCommandDebugToggleDefaultsOffAndNotifiesChanges() {
        MemoryPrefs remote = new MemoryPrefs();
        MemoryPrefs local = new MemoryPrefs();
        HostSettings settings = new HostSettings(remote, local);
        AtomicInteger onCount = new AtomicInteger();
        AtomicInteger offCount = new AtomicInteger();
        settings.onRawCommandDebugChanged(on -> {
            if (Boolean.TRUE.equals(on)) onCount.incrementAndGet();
            else offCount.incrementAndGet();
        });

        assertFalse(settings.isRawCommandDebugEnabled());
        assertTrue(settings.setLocalRawCommandDebugFallback(true));
        assertTrue(settings.isRawCommandDebugEnabled());

        remote.edit().putBoolean(WatchLinkPrefs.KEY_RAW_COMMAND_DEBUG, false).commit();
        assertFalse(settings.isRawCommandDebugEnabled());
        assertEquals(1, offCount.get());

        remote.edit().putBoolean(WatchLinkPrefs.KEY_RAW_COMMAND_DEBUG, true).commit();
        assertTrue(settings.isRawCommandDebugEnabled());
        assertEquals(1, onCount.get());
    }

    @Test public void gameFlashbackToggleAndLeadSecDefaultSafelyAndNotifyChanges() {
        MemoryPrefs remote = new MemoryPrefs();
        MemoryPrefs local = new MemoryPrefs();
        HostSettings settings = new HostSettings(remote, local);
        AtomicInteger toggleChanges = new AtomicInteger();
        AtomicInteger lastLeadSec = new AtomicInteger(-1);
        settings.onGameFlashbackChanged(on -> toggleChanges.incrementAndGet());
        settings.onGameFlashbackLeadSecChanged(lastLeadSec::set);

        assertFalse(settings.isGameFlashbackEnabled());
        assertEquals(WatchLinkPrefs.DEFAULT_GAME_FLASHBACK_LEAD_SEC, settings.gameFlashbackLeadSec());

        assertTrue(settings.setLocalGameFlashbackFallback(true));
        assertTrue(settings.setLocalGameFlashbackLeadSecFallback(12));
        assertTrue(settings.isGameFlashbackEnabled());
        assertEquals(12, settings.gameFlashbackLeadSec());

        remote.edit().putBoolean(WatchLinkPrefs.KEY_GAME_FLASHBACK_ENABLED, false).commit();
        remote.edit().putInt(WatchLinkPrefs.KEY_GAME_FLASHBACK_LEAD_SEC, 8).commit();
        assertFalse(settings.isGameFlashbackEnabled());
        assertEquals(8, settings.gameFlashbackLeadSec());
        assertEquals(1, toggleChanges.get());
        assertEquals(8, lastLeadSec.get());

        // Clamping out-of-range values protects against corrupt prefs.
        remote.edit().putInt(WatchLinkPrefs.KEY_GAME_FLASHBACK_LEAD_SEC, 999).commit();
        assertEquals(WatchLinkPrefs.MAX_GAME_FLASHBACK_LEAD_SEC, settings.gameFlashbackLeadSec());
        assertEquals(WatchLinkPrefs.MAX_GAME_FLASHBACK_LEAD_SEC, lastLeadSec.get());
    }

    @Test public void notifyMuteDndAndSleepAutoDndDefaultFalseAndNotifyListeners() {
        var remote = new MemoryPrefs();
        var local = new MemoryPrefs();
        var settings = new HostSettings(remote, local);

        assertFalse(settings.isNotifyMuteDndEnabled());
        assertFalse(settings.isNotifySilentPushEnabled());
        assertFalse(settings.isSleepAutoDndEnabled());
        assertFalse(settings.isWakeExitDndEnabled());

        var dndMuted = new java.util.concurrent.atomic.AtomicBoolean();
        var silentPush = new java.util.concurrent.atomic.AtomicBoolean();
        var sleepDnd = new java.util.concurrent.atomic.AtomicBoolean();
        var wakeDnd = new java.util.concurrent.atomic.AtomicBoolean();
        settings.onNotifyMuteDndChanged(v -> dndMuted.set(Boolean.TRUE.equals(v)));
        settings.onNotifySilentPushChanged(v -> silentPush.set(Boolean.TRUE.equals(v)));
        settings.onSleepAutoDndChanged(v -> sleepDnd.set(Boolean.TRUE.equals(v)));
        settings.onWakeExitDndChanged(v -> wakeDnd.set(Boolean.TRUE.equals(v)));

        assertTrue(settings.setLocalNotifyMuteDndFallback(true));
        assertTrue(settings.setLocalNotifySilentPushFallback(true));
        assertTrue(settings.setLocalSleepAutoDndFallback(true));
        assertTrue(settings.setLocalWakeExitDndFallback(true));
        assertTrue(settings.isNotifyMuteDndEnabled());
        assertTrue(settings.isNotifySilentPushEnabled());
        assertTrue(settings.isSleepAutoDndEnabled());
        assertTrue(settings.isWakeExitDndEnabled());

        remote.edit().putBoolean(WatchLinkPrefs.KEY_NOTIFY_MUTE_DND_ENABLED, true).commit();
        remote.edit().putBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_PUSH_ENABLED, true).commit();
        remote.edit().putBoolean(WatchLinkPrefs.KEY_SLEEP_AUTO_DND_ENABLED, true).commit();
        remote.edit().putBoolean(WatchLinkPrefs.KEY_WAKE_EXIT_DND_ENABLED, true).commit();
        assertTrue(dndMuted.get());
        assertTrue(silentPush.get());
        assertTrue(sleepDnd.get());
        assertTrue(wakeDnd.get());

        remote.edit().putBoolean(WatchLinkPrefs.KEY_NOTIFY_MUTE_DND_ENABLED, false).commit();
        remote.edit().putBoolean(WatchLinkPrefs.KEY_NOTIFY_SILENT_PUSH_ENABLED, false).commit();
        remote.edit().putBoolean(WatchLinkPrefs.KEY_SLEEP_AUTO_DND_ENABLED, false).commit();
        remote.edit().putBoolean(WatchLinkPrefs.KEY_WAKE_EXIT_DND_ENABLED, false).commit();
        assertFalse(settings.isNotifyMuteDndEnabled());
        assertFalse(settings.isNotifySilentPushEnabled());
        assertFalse(settings.isSleepAutoDndEnabled());
        assertFalse(settings.isWakeExitDndEnabled());
        assertFalse(dndMuted.get());
        assertFalse(silentPush.get());
        assertFalse(sleepDnd.get());
        assertFalse(wakeDnd.get());
    }

    @Test public void notifyDisconnectStageToggleDefaultsFalseAndNotifiesChanges() {
        var remote = new MemoryPrefs();
        var local = new MemoryPrefs();
        var settings = new HostSettings(remote, local);

        assertFalse(settings.isNotifyDisconnectStageEnabled());
        var staged = new java.util.concurrent.atomic.AtomicBoolean();
        settings.onNotifyDisconnectStageChanged(v -> staged.set(Boolean.TRUE.equals(v)));

        assertTrue(settings.setLocalNotifyDisconnectStageFallback(true));
        assertTrue(settings.isNotifyDisconnectStageEnabled());

        remote.edit().putBoolean(WatchLinkPrefs.KEY_NOTIFY_DISCONNECT_STAGE_ENABLED, true).commit();
        assertTrue(settings.isNotifyDisconnectStageEnabled());
        assertTrue(staged.get());

        remote.edit().putBoolean(WatchLinkPrefs.KEY_NOTIFY_DISCONNECT_STAGE_ENABLED, false).commit();
        assertFalse(settings.isNotifyDisconnectStageEnabled());
        assertFalse(staged.get());
    }

    @Test public void burstThrottleAndBatteryAlertsDefaultSafelyAndClampValues() {
        var remote = new MemoryPrefs();
        var local = new MemoryPrefs();
        var settings = new HostSettings(remote, local);

        assertFalse(settings.isNotifyBurstThrottleEnabled());
        assertEquals(WatchLinkPrefs.DEFAULT_NOTIFY_BURST_THROTTLE_SEC, settings.notifyBurstThrottleSec());
        assertFalse(settings.isBatteryPhoneFullWatchEnabled());
        assertFalse(settings.isBatteryPhoneLowWatchEnabled());
        assertEquals(WatchLinkPrefs.DEFAULT_BATTERY_LOW_THRESHOLD, settings.batteryPhoneLowThreshold());
        assertFalse(settings.isBatteryWatchFullPhoneEnabled());
        assertFalse(settings.isBatteryWatchLowPhoneEnabled());
        assertEquals(WatchLinkPrefs.DEFAULT_BATTERY_LOW_THRESHOLD, settings.batteryWatchLowThreshold());

        var burstOn = new java.util.concurrent.atomic.AtomicBoolean();
        var burstSec = new AtomicInteger(-1);
        var phoneLow = new AtomicInteger(-1);
        var watchLow = new AtomicInteger(-1);
        settings.onNotifyBurstThrottleChanged(v -> burstOn.set(Boolean.TRUE.equals(v)));
        settings.onNotifyBurstThrottleSecChanged(burstSec::set);
        settings.onBatteryPhoneLowThresholdChanged(phoneLow::set);
        settings.onBatteryWatchLowThresholdChanged(watchLow::set);

        assertTrue(settings.setLocalNotifyBurstThrottleFallback(true));
        assertTrue(settings.setLocalNotifyBurstThrottleSecFallback(25));
        assertTrue(settings.setLocalBatteryPhoneFullWatchFallback(true));
        assertTrue(settings.setLocalBatteryPhoneLowWatchFallback(true));
        assertTrue(settings.setLocalBatteryPhoneLowThresholdFallback(15));
        assertTrue(settings.setLocalBatteryWatchFullPhoneFallback(true));
        assertTrue(settings.setLocalBatteryWatchLowPhoneFallback(true));
        assertTrue(settings.setLocalBatteryWatchLowThresholdFallback(30));

        assertTrue(settings.isNotifyBurstThrottleEnabled());
        assertEquals(25, settings.notifyBurstThrottleSec());
        assertTrue(settings.isBatteryPhoneFullWatchEnabled());
        assertTrue(settings.isBatteryPhoneLowWatchEnabled());
        assertEquals(15, settings.batteryPhoneLowThreshold());
        assertTrue(settings.isBatteryWatchFullPhoneEnabled());
        assertTrue(settings.isBatteryWatchLowPhoneEnabled());
        assertEquals(30, settings.batteryWatchLowThreshold());

        remote.edit().putBoolean(WatchLinkPrefs.KEY_NOTIFY_BURST_THROTTLE_ENABLED, true).commit();
        remote.edit().putInt(WatchLinkPrefs.KEY_NOTIFY_BURST_THROTTLE_SEC, 999).commit();
        remote.edit().putInt(WatchLinkPrefs.KEY_BATTERY_PHONE_LOW_THRESHOLD, 0).commit();
        remote.edit().putInt(WatchLinkPrefs.KEY_BATTERY_WATCH_LOW_THRESHOLD, 95).commit();

        assertTrue(burstOn.get());
        assertEquals(WatchLinkPrefs.MAX_NOTIFY_BURST_THROTTLE_SEC, settings.notifyBurstThrottleSec());
        assertEquals(WatchLinkPrefs.MAX_NOTIFY_BURST_THROTTLE_SEC, burstSec.get());
        assertEquals(WatchLinkPrefs.MIN_BATTERY_LOW_THRESHOLD, settings.batteryPhoneLowThreshold());
        assertEquals(WatchLinkPrefs.MIN_BATTERY_LOW_THRESHOLD, phoneLow.get());
        assertEquals(WatchLinkPrefs.MAX_BATTERY_LOW_THRESHOLD, settings.batteryWatchLowThreshold());
        assertEquals(WatchLinkPrefs.MAX_BATTERY_LOW_THRESHOLD, watchLow.get());
    }
}
