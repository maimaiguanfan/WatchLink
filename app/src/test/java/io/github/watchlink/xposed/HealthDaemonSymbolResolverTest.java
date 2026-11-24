package io.github.watchlink.xposed;

import java.io.File;
import org.junit.Test;
import static org.junit.Assert.*;

public class HealthDaemonSymbolResolverTest {
    @Test public void symbolsRoundTripThroughCacheAndInvalidateOnUpdate() {
        HealthDaemonSymbolResolver.Symbols scanned = new HealthDaemonSymbolResolver.Symbols(
                "e", "crr", "b", "c", "a",
                "com.huawei.bone.ui.setting.NotificationPushListener$a",
                "d", "d", "d", "e", "DEX_SCAN");
        assertTrue(scanned.pictureReady());
        assertTrue(scanned.voipReady());
        assertTrue(scanned.anyReady());

        ApkSymbolCache cache = new ApkSymbolCache(new ClockSymbolResolverTest.MemoryPrefs());
        cache.write("health-daemon-v1|/health.apk:900:1000", scanned.toMap());

        HealthDaemonSymbolResolver.Symbols cached = HealthDaemonSymbolResolver.Symbols.fromCache(
                cache.read("health-daemon-v1|/health.apk:900:1000"));
        assertNotNull(cached);
        assertEquals("CACHE", cached.source());
        assertTrue(cached.pictureReady());
        assertTrue(cached.voipReady());
        assertEquals("crr", cached.pictureHelperClass());
        assertEquals("d", cached.spGetBoolMethod());
        assertEquals("e", cached.spGetIntMethod());

        assertNull(HealthDaemonSymbolResolver.Symbols.fromCache(
                cache.read("health-daemon-v1|/health.apk:901:1000")));
    }

    @Test public void scansRealPulledHealthApkWhenAvailableLocally() {
        File healthApk = new File("/tmp/watchlink-apks/health.apk");
        if (!healthApk.isFile()) return;
        ApkSymbolCache cache = new ApkSymbolCache(new ClockSymbolResolverTest.MemoryPrefs());

        HealthDaemonSymbolResolver.Symbols scanned = HealthDaemonSymbolResolver.resolve(
                cache, healthApk.getAbsolutePath());
        assertNotNull(scanned);
        assertEquals("DEX_SCAN", scanned.source());
        assertTrue(scanned.pictureReady());
        assertTrue(scanned.voipReady());
        assertEquals("e", scanned.pictureStyleMethod());
        assertEquals("crr", scanned.pictureHelperClass());
        assertEquals("b", scanned.pictureNextFileMethod());
        assertEquals("c", scanned.pictureSaveBitmapMethod());
        assertEquals("a", scanned.voipGateMethod());
        assertEquals("com.huawei.bone.ui.setting.NotificationPushListener$a", scanned.voipReceiverClass());
        assertEquals("d", scanned.voipRejectIntentMethod());
        assertEquals("d", scanned.voipRejectSbnMethod());
        assertEquals("d", scanned.spGetBoolMethod());
        assertEquals("e", scanned.spGetIntMethod());

        HealthDaemonSymbolResolver.Symbols cached = HealthDaemonSymbolResolver.resolve(
                cache, healthApk.getAbsolutePath());
        assertNotNull(cached);
        assertEquals("CACHE", cached.source());
        assertEquals(scanned.pictureHelperClass(), cached.pictureHelperClass());
        assertEquals(scanned.voipReceiverClass(), cached.voipReceiverClass());
    }
}
