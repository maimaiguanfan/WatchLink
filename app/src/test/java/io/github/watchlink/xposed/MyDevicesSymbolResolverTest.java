package io.github.watchlink.xposed;

import java.io.File;
import org.junit.Test;
import static org.junit.Assert.*;

public class MyDevicesSymbolResolverTest {
    @Test public void roundTripsThroughApkSymbolCache() {
        MyDevicesSymbolResolver.Symbols scanned = new MyDevicesSymbolResolver.Symbols("aa.bn", "DEX_SCAN");
        assertTrue(scanned.ready());
        ApkSymbolCache cache = new ApkSymbolCache(new ClockSymbolResolverTest.MemoryPrefs());
        cache.write("mydevices-v1|/MyDevices.apk:100:200", scanned.toMap());
        MyDevicesSymbolResolver.Symbols cached = MyDevicesSymbolResolver.Symbols.fromCache(
                cache.read("mydevices-v1|/MyDevices.apk:100:200"));
        assertNotNull(cached);
        assertEquals("CACHE", cached.source());
        assertEquals("aa.bn", cached.providerQueryClass());
    }

    @Test public void scansRealPulledMyDevicesApkWhenAvailableLocally() {
        File apk = new File("/home/leo/.gemini/antigravity-cli/brain/2157be28-1c8e-4554-b0c7-7d29afda6b4a/scratch/mydevices.apk");
        if (!apk.isFile()) return;
        ApkSymbolCache cache = new ApkSymbolCache(new ClockSymbolResolverTest.MemoryPrefs());
        MyDevicesSymbolResolver.Symbols scanned = MyDevicesSymbolResolver.resolve(null, cache, apk.getAbsolutePath());
        assertNotNull(scanned);
        assertTrue(scanned.ready());
        assertEquals("DEX_SCAN", scanned.source());
        assertEquals("aa.bn", scanned.providerQueryClass());

        MyDevicesSymbolResolver.Symbols cached = MyDevicesSymbolResolver.resolve(null, cache, apk.getAbsolutePath());
        assertNotNull(cached);
        assertEquals("CACHE", cached.source());
        assertEquals("aa.bn", cached.providerQueryClass());
    }
}
