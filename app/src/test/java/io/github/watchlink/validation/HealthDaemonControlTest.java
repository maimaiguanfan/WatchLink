package io.github.watchlink.validation;

import org.junit.Test;
import static org.junit.Assert.*;

public class HealthDaemonControlTest {
    @Test public void reachableReplyRetainsAllowlistedTelemetryAndBounds() {
        var reply = new HealthDaemonControl.Reply(
                true, true, true, true, true, true, "CACHE",
                true, 360, 10, true, 1700000000000L, "ENCODED_BIG_PICTURE",
                1700000005000L, "REJECTED_BY_WATCH");
        assertTrue(reply.reachable());
        assertTrue(reply.pictureEnabled());
        assertTrue(reply.voipEnabled());
        assertTrue(reply.remotePrefs());
        assertTrue(reply.pictureHookReady());
        assertTrue(reply.voipHookReady());
        assertEquals("CACHE", reply.symbolSource());
        assertTrue(reply.pictureSupported());
        assertEquals(360, reply.pictureWidth());
        assertEquals(10, reply.pictureNumber());
        assertTrue(reply.voipSupported());
        assertEquals(1700000000000L, reply.lastPictureAt());
        assertEquals("ENCODED_BIG_PICTURE", reply.lastPictureResult());
        assertEquals(1700000005000L, reply.lastVoipAt());
        assertEquals("REJECTED_BY_WATCH", reply.lastVoipResult());
    }

    @Test public void unknownOrOutOfBoundsValuesSanitizeSafely() {
        var sanitized = new HealthDaemonControl.Reply(
                true, true, false, false, true, false, "evil",
                true, 99999, -5, false, -1L, "secret-file", -10L, "caller-name");
        assertEquals("UNKNOWN", sanitized.symbolSource());
        assertEquals(0, sanitized.pictureWidth());
        assertEquals(0, sanitized.pictureNumber());
        assertEquals(0L, sanitized.lastPictureAt());
        assertEquals("NONE", sanitized.lastPictureResult());
        assertEquals(0L, sanitized.lastVoipAt());
        assertEquals("NONE", sanitized.lastVoipResult());

        var offline = HealthDaemonControl.unavailable();
        assertFalse(offline.reachable());
        assertFalse(offline.pictureEnabled());
        assertFalse(offline.voipEnabled());
        assertEquals("UNKNOWN", offline.symbolSource());
        assertEquals("NONE", offline.lastPictureResult());
        assertEquals("NONE", offline.lastVoipResult());
    }
}
