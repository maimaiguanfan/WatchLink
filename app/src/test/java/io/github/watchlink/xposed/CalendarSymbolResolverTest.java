package io.github.watchlink.xposed;

import java.io.File;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class CalendarSymbolResolverTest {
    private static final String PULLED_CALENDAR_APK = "/tmp/watchlink-apks/calendar.apk";

    @Test public void cacheRoundTripPreservesResolvedContractAndRejectsIncompleteEntries() {
        CalendarSymbolResolver.Symbols original = new CalendarSymbolResolver.Symbols(
                "t", "m9.k", "t", "w", "v", "m9.c", "l", "k", "m9.k$c", "m9.d",
                "c", "b", "S", "z", "m9.o", "w", "x", "R", "t", true, "DEX_SCAN");
        Map<String, String> encoded = original.toMap();
        CalendarSymbolResolver.Symbols restored = CalendarSymbolResolver.Symbols.fromCache(encoded);
        assertNotNull(restored);
        assertEquals("CACHE", restored.source());
        assertTrue(restored.actionContractVerified());
        assertEquals("m9.k", restored.controllerClass());
        assertEquals("S", restored.lockScreenDismissMethod());
        assertEquals("z", restored.lockScreenSnoozeMethod());
        assertEquals("R", restored.topWindowDismissMethod());
        assertEquals("t", restored.topWindowSnoozeMethod());

        encoded.put("lockScreenDismissMethod", "");
        CalendarSymbolResolver.Symbols degraded = CalendarSymbolResolver.Symbols.fromCache(encoded);
        assertNotNull(degraded);
        assertFalse(degraded.actionContractVerified());
        assertEquals("", degraded.lockScreenDismissMethod());

        encoded.remove("controllerClass");
        assertNull(CalendarSymbolResolver.Symbols.fromCache(encoded));
    }

    @Test public void resolvesColorOsCalendar17630StructurallyFromUnobfuscatedAnchors() {
        File apk = new File(PULLED_CALENDAR_APK);
        assumeTrue("Pulled ColorOS Calendar APK not present at " + PULLED_CALENDAR_APK, apk.isFile());

        CalendarSymbolResolver.Symbols resolved = CalendarSymbolResolver.scanApk(apk.getAbsolutePath());
        assertNotNull(resolved);
        assertEquals("DEX_SCAN", resolved.source());
        assertEquals("t", resolved.applicationGetter());
        assertEquals("m9.k", resolved.controllerClass());
        assertEquals("t", resolved.showUiMethod());
        assertEquals("w", resolved.stopRingMethod());
        assertEquals("v", resolved.stopRingNoArgMethod());
        assertEquals("m9.c", resolved.dataBeanClass());
        assertEquals("l", resolved.titleGetter());
        assertEquals("k", resolved.targetIdGetter());
        assertEquals("m9.k$c", resolved.fluidCheckInterface());
        assertEquals("m9.d", resolved.listenerInterface());
        assertEquals("c", resolved.listenerDismissMethod());
        assertEquals("b", resolved.listenerSnoozeMethod());
        assertEquals("S", resolved.lockScreenDismissMethod());
        assertEquals("z", resolved.lockScreenSnoozeMethod());
        assertEquals("m9.o", resolved.topWindowClass());
        assertEquals("w", resolved.topWindowInstanceField());
        assertEquals("x", resolved.topWindowShowingField());
        assertEquals("R", resolved.topWindowDismissMethod());
        assertEquals("t", resolved.topWindowSnoozeMethod());
        assertTrue(resolved.actionContractVerified());
    }
}
