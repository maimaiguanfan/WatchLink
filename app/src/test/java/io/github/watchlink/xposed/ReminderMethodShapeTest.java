package io.github.watchlink.xposed;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class ReminderMethodShapeTest {
    static class Device {}
    static class VendorPacket {}
    static class OtherPacket {}
    static class Compatible {
        public void onDataReceived(Device device, VendorPacket renamedPacket, int code) {}
        public void onDataReceived(Device device, String platformType, int code) {}
    }
    static class Ambiguous {
        public void onDataReceived(Device device, VendorPacket first, int code) {}
        public void onDataReceived(Device device, OtherPacket second, int code) {}
    }
    interface Engine {}
    interface Channel {}
    static class Facade implements Engine, Channel {}
    static class Delegate implements Channel {}
    static class Duplicate implements Engine, Channel {}
    static class Packet330 {
        public void d(byte[] frame) {}
        public byte[] d() { return null; }
    }
    static class AmbiguousPacket {
        public byte[] body() { return null; }
        public byte[] envelope() { return null; }
    }

    @Test public void reusedFacadeNameCannotSelectTheDelegate() throws Exception {
        var roles = List.<Class<?>>of(Engine.class, Channel.class);
        assertEquals(Facade.class, ReminderMethodShape.implementation(Class::forName, roles,
                Delegate.class.getName(), "missing.Type", Facade.class.getName()));
        assertThrows(NoSuchMethodException.class, () -> ReminderMethodShape.implementation(Class::forName, roles,
                Delegate.class.getName()));
        assertThrows(NoSuchMethodException.class, () -> ReminderMethodShape.implementation(Class::forName, roles,
                Facade.class.getName(), Duplicate.class.getName()));
    }

    @Test public void acceptsRenamedFrameGetterOnlyWhenUnambiguous() throws Exception {
        assertEquals(Packet330.class.getDeclaredMethod("d"), ReminderMethodShape.packetBytes(Packet330.class));
        assertThrows(NoSuchMethodException.class, () -> ReminderMethodShape.packetBytes(AmbiguousPacket.class));
        assertThrows(NoSuchMethodException.class, () -> ReminderMethodShape.packetBytes(VendorPacket.class));
    }

    @Test public void resolvesActualVendorPacketWithoutItsName() throws Exception {
        assertEquals(VendorPacket.class, ReminderMethodShape.packetMethod(Compatible.class,
                "onDataReceived", 1, Device.class, null, int.class).getParameterTypes()[1]);
    }

    @Test public void rejectsAmbiguousAndChangedCallbacks() {
        assertThrows(NoSuchMethodException.class, () -> ReminderMethodShape.packetMethod(Ambiguous.class,
                "onDataReceived", 1, Device.class, null, int.class));
        assertThrows(NoSuchMethodException.class, () -> ReminderMethodShape.packetMethod(Compatible.class,
                "onDataReceived", 1, Device.class, null, boolean.class));
    }
}
