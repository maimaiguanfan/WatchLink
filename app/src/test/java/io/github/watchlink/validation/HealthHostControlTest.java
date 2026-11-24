package io.github.watchlink.validation;

import org.junit.Test;
import static org.junit.Assert.*;

public class HealthHostControlTest {
    @Test public void reachableReplyRetainsAllowlistedTelemetry() {
        var reply = new HealthHostControl.Reply(
                true, true, true, true, true, "DEX_SCAN",
                "READY_SUPPORTED_ON", "START_SERVICE", "SDK_AND_INTENT",
                "SNOOZE", "DIRECT_SDK", "INTENT_ONLY");
        assertTrue(reply.reachable());
        assertTrue(reply.enabled());
        assertTrue(reply.remotePrefs());
        assertTrue(reply.serviceWitness());
        assertTrue(reply.sdkObserver());
        assertEquals("DEX_SCAN", reply.symbolSource());
        assertEquals("READY_SUPPORTED_ON", reply.watchState());
        assertEquals("START_SERVICE", reply.lastDispatchMode());
        assertEquals("SDK_AND_INTENT", reply.lastActionSource());
        assertEquals("SNOOZE", reply.testState());
        assertEquals("DIRECT_SDK", reply.testDispatchMode());
        assertEquals("INTENT_ONLY", reply.testActionSource());
    }

    @Test public void unknownOrUnreachableFieldsFallBackSafely() {
        var sanitized = new HealthHostControl.Reply(
                true, false, false, true, false, "arbitrary",
                "private-mac", "bad", "bad", "bad", "bad", "bad");
        assertEquals("UNKNOWN", sanitized.symbolSource());
        assertEquals("UNKNOWN", sanitized.watchState());
        assertEquals("NONE", sanitized.lastDispatchMode());
        assertEquals("NONE", sanitized.lastActionSource());
        assertEquals("IDLE", sanitized.testState());
        assertEquals("NONE", sanitized.testDispatchMode());
        assertEquals("NONE", sanitized.testActionSource());

        var offline = HealthHostControl.unavailable();
        assertFalse(offline.reachable());
        assertFalse(offline.enabled());
        assertEquals("UNKNOWN", offline.symbolSource());
        assertEquals("UNKNOWN", offline.watchState());
        assertEquals("IDLE", offline.testState());
    }
}
