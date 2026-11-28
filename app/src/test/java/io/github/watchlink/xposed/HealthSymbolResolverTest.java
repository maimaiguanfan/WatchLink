package io.github.watchlink.xposed;

import java.io.File;
import org.junit.Test;
import static org.junit.Assert.*;

public class HealthSymbolResolverTest {
    @Test public void symbolsRoundTripThroughApkSymbolCacheAndInvalidateOnUpdate() {
        HealthSymbolResolver.Symbols scanned = new HealthSymbolResolver.Symbols(
                "eqd", "zcj",
                "com.huawei.devicesdk.service.DevicesManagementService$8",
                "nfz", "oai", "oca", "eqe", "nhp", "ocp", "j", "DEX_SCAN");
        assertTrue(scanned.completeSdk());

        ApkSymbolCache cache = new ApkSymbolCache(new ClockSymbolResolverTest.MemoryPrefs());
        cache.write("health-v6|/health.apk:900:1000", scanned.toMap());

        HealthSymbolResolver.Symbols cached = HealthSymbolResolver.Symbols.fromCache(
                cache.read("health-v6|/health.apk:900:1000"));
        assertNotNull(cached);
        assertEquals("CACHE", cached.source());
        assertEquals("eqd", cached.deviceCatalogClass());
        assertEquals("zcj", cached.sendFacadeClass());
        assertEquals("com.huawei.devicesdk.service.DevicesManagementService$8", cached.receiveCallbackClass());
        assertEquals("nfz", cached.deviceMgrFactoryClass());
        assertEquals("oai", cached.wifiSyncMgrClass());
        assertEquals("oca", cached.liveNotifyMgrClass());
        assertEquals("eqe", cached.sampleCommandApiClass());
        assertEquals("nhp", cached.sleepStatusMgrClass());
        assertEquals("ocp", cached.notifySendDataClass());
        assertEquals("j", cached.notifyReminderMethod());
        assertTrue(cached.completeSdk());

        assertNull(HealthSymbolResolver.Symbols.fromCache(cache.read("health-v6|/health.apk:901:1000")));
    }

    @Test public void scansRealPulledHealthApkWhenAvailableLocally() {
        File healthApk = new File("/tmp/watchlink-apks/health.apk");
        if (!healthApk.isFile()) {
            healthApk = new File("/home/leo/.gemini/antigravity-cli/brain/2157be28-1c8e-4554-b0c7-7d29afda6b4a/scratch/health.apk");
        }
        if (!healthApk.isFile()) return;
        ApkSymbolCache cache = new ApkSymbolCache(new ClockSymbolResolverTest.MemoryPrefs());

        // Passing null installer bypasses fast path so we explicitly test the full 48-DEX structural scan!
        HealthSymbolResolver.Symbols scanned = HealthSymbolResolver.resolve(null, cache, healthApk.getAbsolutePath());
        assertNotNull(scanned);
        assertEquals("DEX_SCAN", scanned.source());
        assertTrue(scanned.completeSdk());
        assertEquals("eqd", scanned.deviceCatalogClass());
        assertEquals("zcj", scanned.sendFacadeClass());
        assertEquals("com.huawei.devicesdk.service.DevicesManagementService$8", scanned.receiveCallbackClass());
        assertEquals("nfz", scanned.deviceMgrFactoryClass());
        assertEquals("oai", scanned.wifiSyncMgrClass());
        assertEquals("oca", scanned.liveNotifyMgrClass());
        assertEquals("eqe", scanned.sampleCommandApiClass());
        assertEquals("nhp", scanned.sleepStatusMgrClass());
        assertEquals("ocp", scanned.notifySendDataClass());
        assertEquals("j", scanned.notifyReminderMethod());

        // Second call returns cached symbols with zero DEX parsing.
        HealthSymbolResolver.Symbols cached = HealthSymbolResolver.resolve(null, cache, healthApk.getAbsolutePath());
        assertNotNull(cached);
        assertEquals("CACHE", cached.source());
        assertEquals(scanned.sendFacadeClass(), cached.sendFacadeClass());
        assertEquals(scanned.deviceCatalogClass(), cached.deviceCatalogClass());
        assertEquals(scanned.receiveCallbackClass(), cached.receiveCallbackClass());
        assertEquals(scanned.deviceMgrFactoryClass(), cached.deviceMgrFactoryClass());
        assertEquals(scanned.wifiSyncMgrClass(), cached.wifiSyncMgrClass());
        assertEquals(scanned.liveNotifyMgrClass(), cached.liveNotifyMgrClass());
        assertEquals(scanned.sampleCommandApiClass(), cached.sampleCommandApiClass());
        assertEquals(scanned.sleepStatusMgrClass(), cached.sleepStatusMgrClass());
        assertEquals(scanned.notifySendDataClass(), cached.notifySendDataClass());
        assertEquals(scanned.notifyReminderMethod(), cached.notifyReminderMethod());
    }
}
