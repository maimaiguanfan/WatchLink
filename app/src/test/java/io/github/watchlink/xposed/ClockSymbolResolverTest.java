package io.github.watchlink.xposed;

import android.content.SharedPreferences;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22b;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClockSymbolResolverTest {
    private static final ImmutableMethodReference TARGET = new ImmutableMethodReference(
            "LService;", "stop", List.of("Landroid/content/Context;", "J", "Z"), "V");

    @Test public void readsBooleanFromTheActualFourthArgument() {
        var call = new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 4, 5, 2, 3, 7, 0, TARGET);
        var snooze = new ImmutableInstruction11n(Opcode.CONST_4, 7, 0);
        assertTrue(ClockSymbolResolver.passesStopFlag(snooze, call, 0));
        assertFalse(ClockSymbolResolver.passesStopFlag(snooze, call, 1));
        assertFalse(ClockSymbolResolver.passesStopFlag(new ImmutableInstruction11n(Opcode.CONST_4, 5, 0), call, 0));
        assertFalse(ClockSymbolResolver.passesStopFlag(new ImmutableInstruction22b(Opcode.ADD_INT_LIT8, 7, 5, 0), call, 0));
    }

    @Test public void handlesRenumberedRangeInvocationsAndRejectsAnUnprovenFlag() {
        var call = new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 8, 4, TARGET);
        assertTrue(ClockSymbolResolver.passesStopFlag(new ImmutableInstruction11n(Opcode.CONST_4, 11, 1), call, 1));
        assertFalse(ClockSymbolResolver.passesStopFlag(new ImmutableInstruction11n(Opcode.CONST_4, 10, 1), call, 1));
        assertFalse(ClockSymbolResolver.passesStopFlag(null, call, 1));
    }

    @Test public void symbolsRoundTripThroughApkSymbolCacheAndInvalidateOnChange() {
        ClockSymbolResolver.Symbols scanned = new ClockSymbolResolver.Symbols(
                "com.oplus.alarmclock.alarmclock.g2",
                "com.oplus.alarmclock.alarmclock.r0",
                "p", "i", "t", "s", "m", "e", "B", "y",
                "g", "R", "notifyUriDirect", "k",
                "D", "I", true, "DEX_SCAN");
        ApkSymbolCache cache = new ApkSymbolCache(new MemoryPrefs());
        cache.write("clock-v2|/apk:100:200", scanned.toMap());

        ClockSymbolResolver.Symbols cached = ClockSymbolResolver.Symbols.fromCache(cache.read("clock-v2|/apk:100:200"));
        assertNotNull(cached);
        assertEquals("CACHE", cached.source());
        assertEquals("com.oplus.alarmclock.alarmclock.g2", cached.occurrenceClass());
        assertEquals("com.oplus.alarmclock.alarmclock.r0", cached.alarmModelClass());
        assertEquals("D", cached.dismissMethod());
        assertEquals("I", cached.snoozeMethod());
        assertTrue(cached.actionContractVerified());

        // Fingerprint mismatch must invalidate cache automatically.
        assertTrue(cache.read("clock-v2|/apk:101:200").isEmpty());
        assertNull(ClockSymbolResolver.Symbols.fromCache(cache.read("clock-v2|/apk:101:200")));
    }

    @Test public void scansRealPulledClockApkWhenAvailableLocally() {
        File clockApk = new File("/tmp/watchlink-apks/clock.apk");
        if (!clockApk.isFile()) return;
        ApkSymbolCache cache = new ApkSymbolCache(new MemoryPrefs());

        ClockSymbolResolver.Symbols first = ClockSymbolResolver.resolve(cache, clockApk.getAbsolutePath());
        assertNotNull(first);
        assertEquals("DEX_SCAN", first.source());
        assertEquals("com.oplus.alarmclock.alarmclock.g2", first.occurrenceClass());
        assertEquals("com.oplus.alarmclock.alarmclock.r0", first.alarmModelClass());
        assertEquals("p", first.scheduleGetter());
        assertEquals("i", first.parentGetter());
        assertEquals("t", first.timeGetter());
        assertEquals("s", first.snoozesGetter());
        assertEquals("m", first.stateGetter());
        assertEquals("e", first.modelGetter());
        assertEquals("B", first.canSnoozeMethod());
        assertEquals("y", first.uuidGetter());
        assertEquals("g", first.serviceInstanceField());
        assertEquals("R", first.serviceAcceptMethod());
        assertEquals("notifyUriDirect", first.providerNotifyMethod());
        assertEquals("k", first.applicationGetter());
        assertEquals("D", first.dismissMethod());
        assertEquals("I", first.snoozeMethod());
        assertTrue(first.actionContractVerified());

        // Second resolve on same APK hits cache without re-scanning DEX.
        ClockSymbolResolver.Symbols second = ClockSymbolResolver.resolve(cache, clockApk.getAbsolutePath());
        assertNotNull(second);
        assertEquals("CACHE", second.source());
        assertEquals(first.dismissMethod(), second.dismissMethod());
        assertEquals(first.snoozeMethod(), second.snoozeMethod());
    }

    static final class MemoryPrefs implements SharedPreferences {
        private final Map<String, Object> data = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(data); }
        @Override public String getString(String key, String defValue) {
            return data.get(key) instanceof String s ? s : defValue;
        }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
        @Override public int getInt(String key, int defValue) { return defValue; }
        @Override public long getLong(String key, long defValue) { return defValue; }
        @Override public float getFloat(String key, float defValue) { return defValue; }
        @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
        @Override public boolean contains(String key) { return data.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, Object> staged = new HashMap<>();
                private boolean cleared;
                @Override public Editor putString(String k, String v) { staged.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { return this; }
                @Override public Editor putInt(String k, int v) { return this; }
                @Override public Editor putLong(String k, long v) { return this; }
                @Override public Editor putFloat(String k, float v) { return this; }
                @Override public Editor putBoolean(String k, boolean v) { return this; }
                @Override public Editor remove(String k) { staged.remove(k); return this; }
                @Override public Editor clear() { cleared = true; staged.clear(); return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() {
                    if (cleared) data.clear();
                    data.putAll(staged);
                }
            };
        }
    }
}
