package io.github.watchlink.xposed;

import java.io.File;
import org.junit.Test;
import static org.junit.Assert.*;

public class HealthSymbolResolverTest {
    @Test public void symbolsRoundTripThroughApkSymbolCacheAndInvalidateOnUpdate() {
        HealthSymbolResolver.Symbols scanned = new HealthSymbolResolver.Symbols(
                "eqd", "zcj",
                "com.huawei.devicesdk.service.DevicesManagementService$8",
                "nfz", "c", "DEX_SCAN");
        assertTrue(scanned.completeSdk());
        assertTrue(scanned.hasVoipAllowlist());

        ApkSymbolCache cache = new ApkSymbolCache(new ClockSymbolResolverTest.MemoryPrefs());
        cache.write("health-v2|/health.apk:900:1000", scanned.toMap());

        HealthSymbolResolver.Symbols cached = HealthSymbolResolver.Symbols.fromCache(
                cache.read("health-v2|/health.apk:900:1000"));
        assertNotNull(cached);
        assertEquals("CACHE", cached.source());
        assertEquals("eqd", cached.deviceCatalogClass());
        assertEquals("zcj", cached.sendFacadeClass());
        assertEquals("com.huawei.devicesdk.service.DevicesManagementService$8", cached.receiveCallbackClass());
        assertEquals("nfz", cached.deviceMgrFactoryClass());
        assertEquals("c", cached.voipAllowlistMethod());
        assertTrue(cached.completeSdk());
        assertTrue(cached.hasVoipAllowlist());

        assertNull(HealthSymbolResolver.Symbols.fromCache(cache.read("health-v2|/health.apk:901:1000")));
    }

    @Test public void scansRealPulledHealthApkWhenAvailableLocally() {
        File healthApk = new File("/tmp/watchlink-apks/health.apk");
        if (!healthApk.isFile()) return;
        ApkSymbolCache cache = new ApkSymbolCache(new ClockSymbolResolverTest.MemoryPrefs());

        // Passing null installer bypasses fast path so we explicitly test the full 48-DEX structural scan!
        HealthSymbolResolver.Symbols scanned = HealthSymbolResolver.resolve(null, cache, healthApk.getAbsolutePath());
        assertNotNull(scanned);
        assertEquals("DEX_SCAN", scanned.source());
        assertTrue(scanned.completeSdk());
        assertTrue(scanned.hasVoipAllowlist());
        assertEquals("eqd", scanned.deviceCatalogClass());
        assertEquals("zcj", scanned.sendFacadeClass());
        assertEquals("com.huawei.devicesdk.service.DevicesManagementService$8", scanned.receiveCallbackClass());
        assertEquals("nfz", scanned.deviceMgrFactoryClass());
        assertEquals("c", scanned.voipAllowlistMethod());

        // Second call returns cached symbols with zero DEX parsing.
        HealthSymbolResolver.Symbols cached = HealthSymbolResolver.resolve(null, cache, healthApk.getAbsolutePath());
        assertNotNull(cached);
        assertEquals("CACHE", cached.source());
        assertEquals(scanned.sendFacadeClass(), cached.sendFacadeClass());
        assertEquals(scanned.deviceCatalogClass(), cached.deviceCatalogClass());
        assertEquals(scanned.receiveCallbackClass(), cached.receiveCallbackClass());
        assertEquals(scanned.deviceMgrFactoryClass(), cached.deviceMgrFactoryClass());
        assertEquals(scanned.voipAllowlistMethod(), cached.voipAllowlistMethod());
    }
}
