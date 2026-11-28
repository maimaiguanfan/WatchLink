package io.github.watchlink.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HealthWatchStatusCollectorTest {
    @Test
    public void parsesStandardHuaweiBatteryResponseFrame() {
        // Service 1, Command 8, Tag 0x01, Len 0x01, Value 0x56 (86%)
        byte[] frame = new byte[]{0x01, 0x08, 0x01, 0x01, 0x56};
        assertTrue(HealthWatchStatusCollector.isBatteryResponseFrame(frame));
        assertEquals(86, HealthWatchStatusCollector.parseBatteryResponseFrame(frame));
    }

    @Test
    public void parsesBatteryWhenOtherTlvsPrecedeTagOne() {
        // Service 1, Command 8, Tag 0x02 len 0x03 (earbuds), Tag 0x01 len 0x01 value 0x4B (75%)
        byte[] frame = new byte[]{0x01, 0x08, 0x02, 0x03, 0x50, 0x50, 0x64, 0x01, 0x01, 0x4B};
        assertEquals(75, HealthWatchStatusCollector.parseBatteryResponseFrame(frame));
    }

    @Test
    public void clampsOverHundredAndRejectsUnknownOrTimeout() {
        assertEquals(100, HealthWatchStatusCollector.parseBatteryResponseFrame(
                new byte[]{0x01, 0x08, 0x01, 0x01, 0x70}));
        assertEquals(0, HealthWatchStatusCollector.parseBatteryResponseFrame(
                new byte[]{0x01, 0x08, 0x01, 0x01, 0x00}));
        assertEquals(-1, HealthWatchStatusCollector.parseBatteryResponseFrame(
                new byte[]{0x01, 0x08, 0x01, 0x01, (byte) 0xFF}));
        assertEquals(-1, HealthWatchStatusCollector.parseBatteryResponseFrame(
                new byte[]{0x01, 0x08, 0x7F, 0x04, 0x00, 0x01, (byte) 0x86, (byte) 0xA0}));
    }

    @Test
    public void rejectsNonBatteryOrTruncatedFrames() {
        assertFalse(HealthWatchStatusCollector.isBatteryResponseFrame(null));
        assertFalse(HealthWatchStatusCollector.isBatteryResponseFrame(new byte[]{0x01, 0x08, 0x01, 0x01}));
        assertFalse(HealthWatchStatusCollector.isBatteryResponseFrame(new byte[]{0x08, 0x09, 0x01, 0x01, 0x50}));
        assertEquals(-1, HealthWatchStatusCollector.parseBatteryResponseFrame(new byte[]{0x01, 0x08, 0x01, 0x05, 0x50}));
    }
}
