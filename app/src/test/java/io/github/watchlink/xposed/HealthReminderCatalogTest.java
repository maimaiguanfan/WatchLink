package io.github.watchlink.xposed;

import org.junit.Test;
import static org.junit.Assert.*;

public class HealthReminderCatalogTest {
    interface DeviceApi {}
    static class PluginProxy {}
    static class Older extends PluginProxy implements DeviceApi {
        public static Older b() { throw new AssertionError("must not initialize"); }
    }
    static class Newer extends PluginProxy implements DeviceApi {
        public static Newer c() { throw new AssertionError("must not initialize"); }
        public static Newer unrelated(int index) { return null; }
    }
    static class AnotherCatalog extends PluginProxy {
        public static AnotherCatalog c() { return null; }
    }
    static class MissingProxyBase implements DeviceApi {
        public static MissingProxyBase c() { return null; }
    }
    static class TwoFactories extends PluginProxy implements DeviceApi {
        public static TwoFactories b() { return null; }
        public static TwoFactories c() { return null; }
    }

    @Test public void followsNamedInterfaceAcrossOwnerAndFactoryChanges() throws Exception {
        assertEquals(Older.class.getDeclaredMethod("b"),
                HealthReminderCatalog.factory(PluginProxy.class, DeviceApi.class, Older.class, AnotherCatalog.class));
        assertEquals(Newer.class.getDeclaredMethod("c"),
                HealthReminderCatalog.factory(PluginProxy.class, DeviceApi.class, MissingProxyBase.class, Newer.class));
        assertEquals(Newer.class.getDeclaredMethod("c"),
                HealthReminderCatalog.factory(PluginProxy.class, DeviceApi.class, null, Newer.class));
    }

    @Test public void ambiguousOrUnrecognizedCatalogCannotProduceADevice() {
        assertThrows(NoSuchMethodException.class, () ->
                HealthReminderCatalog.factory(PluginProxy.class, DeviceApi.class, Older.class, Newer.class));
        assertThrows(NoSuchMethodException.class, () ->
                HealthReminderCatalog.factory(PluginProxy.class, DeviceApi.class, TwoFactories.class));
        assertThrows(NoSuchMethodException.class, () ->
                HealthReminderCatalog.factory(PluginProxy.class, DeviceApi.class, MissingProxyBase.class, AnotherCatalog.class));
    }
}
