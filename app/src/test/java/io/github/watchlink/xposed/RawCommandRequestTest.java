package io.github.watchlink.xposed;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class RawCommandRequestTest {
    @Test public void parsesShorthandFiveDotServiceDotCommand() {
        RawCommandRequest.Parsed parsed = RawCommandRequest.parse(
                null, "5.49.5", 0, 0, "01 01 0E 12 01 01 13 01 01", null,
                null, null, 0L, 0, 1200);
        assertTrue(parsed.valid());
        assertEquals(RawCommandRequest.MODE_DEVICE_COMMAND, parsed.mode());
        assertEquals(49, parsed.serviceId());
        assertEquals(5, parsed.commandId());
        assertEquals("5.49.5", parsed.descriptor());
        assertEquals(1200, parsed.waitMs());
        assertArrayEquals(
                new byte[]{0x01, 0x01, 0x0E, 0x12, 0x01, 0x01, 0x13, 0x01, 0x01},
                parsed.payload());
    }

    @Test public void parsesTwoPartCodeAndExplicitServiceCommand() {
        RawCommandRequest.Parsed twoPart = RawCommandRequest.parse(
                "device_command", "1.12", 0, 0, "0x8114020101", null,
                null, null, 0L, 0, 99_999);
        assertTrue(twoPart.valid());
        assertEquals(1, twoPart.serviceId());
        assertEquals(12, twoPart.commandId());
        assertEquals(RawCommandRequest.MAX_WAIT_MS, twoPart.waitMs());
        assertEquals("8114020101", RawCommandRequest.toHex(twoPart.payload(), 32));

        RawCommandRequest.Parsed explicit = RawCommandRequest.parse(
                "device_command", null, 49, 5, "", null,
                null, null, 0L, 0, -1);
        assertTrue(explicit.valid());
        assertEquals(49, explicit.serviceId());
        assertEquals(5, explicit.commandId());
        assertEquals(0, explicit.payload().length);
        assertEquals(RawCommandRequest.DEFAULT_WAIT_MS, explicit.waitMs());
    }

    @Test public void parsesSampleEventWithJsonPayload() {
        RawCommandRequest.Parsed parsed = RawCommandRequest.parse(
                "sample_event", null, 0, 2, null, "{\"permission\":1,\"disturbStatus\":1}",
                "hw.unitedevice.disturb", "hw.device.disturb", 800100041L, 0, 2000);
        assertTrue(parsed.valid());
        assertEquals(RawCommandRequest.MODE_SAMPLE_EVENT, parsed.mode());
        assertEquals("hw.unitedevice.disturb", parsed.srcPkg());
        assertEquals("hw.device.disturb", parsed.wearPkg());
        assertEquals(2, parsed.commandId());
        assertEquals(800100041L, parsed.eventId());
        assertEquals("{\"permission\":1,\"disturbStatus\":1}",
                new String(parsed.payload(), StandardCharsets.UTF_8));
    }

    @Test public void rejectsInvalidCodeHexOrMode() {
        assertFalse(RawCommandRequest.parse(null, "4.49.5", 0, 0, "", null, null, null, 0L, 0, 0).valid());
        assertFalse(RawCommandRequest.parse(null, "5.0.5", 0, 0, "", null, null, null, 0L, 0, 0).valid());
        assertFalse(RawCommandRequest.parse(null, "5.49.5", 0, 0, "010", null, null, null, 0L, 0, 0).valid());
        assertFalse(RawCommandRequest.parse(null, "5.49.5", 0, 0, "ZZZZ", null, null, null, 0L, 0, 0).valid());
        assertFalse(RawCommandRequest.parse("sample_event", null, 0, 2, null, "{}", "", "hw.device.disturb", 1L, 0, 0).valid());
        assertFalse(RawCommandRequest.parse("unknown", null, 1, 1, "", null, null, null, 0L, 0, 0).valid());
    }
}
