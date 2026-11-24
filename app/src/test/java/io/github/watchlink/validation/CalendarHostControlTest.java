package io.github.watchlink.validation;

import org.junit.Test;
import static org.junit.Assert.*;

public class CalendarHostControlTest {
    @Test public void validFieldsAndHistoryPassThroughWhenReachable() {
        long now = System.currentTimeMillis();
        CalendarHostControl.Reply reply = new CalendarHostControl.Reply(
                true, true, "READY", true, true, "CACHE",
                now, "OUTBOUND", "START_SERVICE", "DISMISS", "SDK_AND_INTENT", "VERIFIED", "OUTBOUND");
        assertTrue(reply.reachable());
        assertTrue(reply.enabled());
        assertTrue(reply.remotePrefs());
        assertTrue(reply.localCalendarControl());
        assertEquals("READY", reply.result());
        assertEquals("CACHE", reply.symbolSource());
        assertEquals("OUTBOUND", reply.delivery());
        assertEquals("START_SERVICE", reply.dispatchMode());
        assertEquals("DISMISS", reply.action());
        assertEquals("SDK_AND_INTENT", reply.actionSource());
        assertEquals("VERIFIED", reply.actionResult());
        assertEquals("OUTBOUND", reply.close());
    }

    @Test public void arbitraryTextAndUnreachableRepliesAreSanitized() {
        CalendarHostControl.Reply sanitized = new CalendarHostControl.Reply(
                true, false, "meeting title", false, false, "bad",
                1234, "bad", "bad", "bad", "bad", "bad", "bad");
        assertEquals("UNAVAILABLE", sanitized.result());
        assertEquals("UNKNOWN", sanitized.symbolSource());
        assertEquals("", sanitized.delivery());
        assertEquals("", sanitized.action());

        CalendarHostControl.Reply offline = CalendarHostControl.unavailable();
        assertFalse(offline.reachable());
        assertFalse(offline.enabled());
        assertFalse(offline.localCalendarControl());
        assertEquals("UNAVAILABLE", offline.result());
    }
}
