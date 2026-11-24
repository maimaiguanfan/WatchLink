package io.github.watchlink.validation;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClockHostControlTest {
    @Test public void validFieldsAndHistoryPassThroughWhenReachable() {
        long now = System.currentTimeMillis();
        ClockHostControl.Reply reply = new ClockHostControl.Reply(
                true, true, "READY", true, true, "CACHE",
                now, "OUTBOUND", "START_SERVICE", "SNOOZE", "SDK_AND_INTENT", "VERIFIED", "OUTBOUND");
        assertTrue(reply.reachable());
        assertTrue(reply.enabled());
        assertTrue(reply.remotePrefs());
        assertTrue(reply.localClockControl());
        assertEquals("READY", reply.result());
        assertEquals("CACHE", reply.symbolSource());
        assertEquals("OUTBOUND", reply.delivery());
        assertEquals("START_SERVICE", reply.dispatchMode());
        assertEquals("SNOOZE", reply.action());
        assertEquals("SDK_AND_INTENT", reply.actionSource());
        assertEquals("VERIFIED", reply.actionResult());
        assertEquals("OUTBOUND", reply.close());
    }

    @Test public void arbitraryTextAndHistoryWithoutARealRingAreExcluded() {
        ClockHostControl.Reply unknown = new ClockHostControl.Reply(
                true, false, "private label", false, false, "private resolver",
                1234, "private delivery", "private mode", "VERIFIED", "private source", "DISMISS", null);
        assertEquals("UNAVAILABLE", unknown.result());
        assertEquals("UNKNOWN", unknown.symbolSource());
        assertEquals("", unknown.delivery());
        assertEquals("", unknown.dispatchMode());
        assertEquals("", unknown.action());
        assertEquals("", unknown.actionSource());
        assertEquals("", unknown.actionResult());
        assertEquals("", unknown.close());

        for (long invalid : new long[]{-1, 0, Long.MAX_VALUE}) {
            ClockHostControl.Reply reply = new ClockHostControl.Reply(
                    true, false, "READY", true, true, "DEX_SCAN",
                    invalid, "OUTBOUND", "START_SERVICE", "DISMISS", "INTENT_ONLY", "VERIFIED", "OUTBOUND");
            assertEquals("DEX_SCAN", reply.symbolSource());
            assertEquals(0, reply.lastRingAt());
            assertEquals("", reply.delivery());
            assertEquals("", reply.dispatchMode());
            assertEquals("", reply.actionSource());
            assertEquals("", reply.actionResult());
            assertEquals("", reply.close());
        }
    }

    @Test public void unreachableHostClearsAllState() {
        ClockHostControl.Reply reply = new ClockHostControl.Reply(
                false, true, "READY", true, true, "CACHE",
                1234, "OUTBOUND", "DIRECT_SDK", "SNOOZE", "SDK_ONLY", "VERIFIED", "OUTBOUND");
        assertFalse(reply.reachable());
        assertFalse(reply.enabled());
        assertFalse(reply.remotePrefs());
        assertFalse(reply.localClockControl());
        assertEquals("UNAVAILABLE", reply.result());
        assertEquals("UNKNOWN", reply.symbolSource());
        assertEquals(0, reply.lastRingAt());
        assertEquals("", reply.delivery());
    }
}
