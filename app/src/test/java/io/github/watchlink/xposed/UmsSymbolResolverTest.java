package io.github.watchlink.xposed;

import java.io.File;
import org.junit.Test;
import static org.junit.Assert.*;

public class UmsSymbolResolverTest {
    @Test public void symbolsRoundTripThroughApkSymbolCache() {
        UmsSymbolResolver.Symbols scanned = new UmsSymbolResolver.Symbols(
                "com.oplus.pantanal.ums.cardservice.valueobject.strategy.g", "a", "c", "DEX_SCAN");
        assertTrue(scanned.complete());

        ApkSymbolCache cache = new ApkSymbolCache(new ClockSymbolResolverTest.MemoryPrefs());
        cache.write("ums-v1|/UMS.apk:100:200", scanned.toMap());

        UmsSymbolResolver.Symbols cached = UmsSymbolResolver.Symbols.fromCache(
                cache.read("ums-v1|/UMS.apk:100:200"));
        assertNotNull(cached);
        assertEquals("CACHE", cached.source());
        assertEquals("com.oplus.pantanal.ums.cardservice.valueobject.strategy.g", cached.strategyClass());
        assertEquals("a", cached.pushMethod());
        assertEquals("c", cached.removeMethod());
        assertNull(UmsSymbolResolver.Symbols.fromCache(cache.read("ums-v1|/UMS.apk:101:200")));
    }

    @Test public void scansRealPulledUmsApkWhenAvailableLocally() {
        File umsApk = new File("/tmp/watchbridge-analysis/coloros/UMS.apk");
        if (!umsApk.isFile()) return;
        ApkSymbolCache cache = new ApkSymbolCache(new ClockSymbolResolverTest.MemoryPrefs());

        // Passing null installer forces full DEX structural scan against ColorOS 17 UMS.apk.
        UmsSymbolResolver.Symbols scanned = UmsSymbolResolver.resolve(null, cache, umsApk.getAbsolutePath());
        assertNotNull(scanned);
        assertEquals("DEX_SCAN", scanned.source());
        assertTrue(scanned.complete());
        assertEquals("com.oplus.pantanal.ums.cardservice.valueobject.strategy.g", scanned.strategyClass());
        assertEquals("a", scanned.pushMethod());
        assertEquals("c", scanned.removeMethod());

        UmsSymbolResolver.Symbols cached = UmsSymbolResolver.resolve(null, cache, umsApk.getAbsolutePath());
        assertNotNull(cached);
        assertEquals("CACHE", cached.source());
        assertEquals(scanned.strategyClass(), cached.strategyClass());
    }
}
