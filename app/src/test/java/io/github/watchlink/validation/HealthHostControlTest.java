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
        assertEquals("UNKNOWN", offline.wifiWatchSupport());
        assertEquals("NONE", offline.wifiLastResult());
    }

    @Test public void sanitizesWifiTelemetryAllowlists() {
        var valid = new HealthHostControl.Reply(
                true, true, true, true, true, true, "CACHE",
                "READY_SUPPORTED_ON", "START_SERVICE", "SDK_AND_INTENT",
                "IDLE", "NONE", "NONE",
                true, "SUPPORTED_NEW", true, 5, "ACK_OK", "AUTO_1B", 1700000000000L);
        assertTrue(valid.wifiAutoSyncEnabled());
        assertEquals("SUPPORTED_NEW", valid.wifiWatchSupport());
        assertTrue(valid.wifiAckReady());
        assertEquals(5, valid.wifiSyncedCount());
        assertEquals("ACK_OK", valid.wifiLastResult());
        assertEquals("AUTO_1B", valid.wifiLastSource());
        assertEquals(1700000000000L, valid.wifiLastAt());

        var poisoned = new HealthHostControl.Reply(
                true, true, false, true, true, true, "CACHE",
                "READY_SUPPORTED_ON", "NONE", "NONE",
                "IDLE", "NONE", "NONE",
                true, "HomeSsidLeak", true, -3, "SecretPass", "evil", -9L);
        assertEquals("UNKNOWN", poisoned.wifiWatchSupport());
        assertEquals(0, poisoned.wifiSyncedCount());
        assertEquals("NONE", poisoned.wifiLastResult());
        assertEquals("NONE", poisoned.wifiLastSource());
        assertEquals(0L, poisoned.wifiLastAt());
    }

    @Test public void sanitizesLiveWindowTelemetryAllowlists() {
        var valid = new HealthHostControl.Reply(
                true, true, false, true, true, true, "CACHE",
                "READY_SUPPORTED_ON", "START_SERVICE", "SDK_AND_INTENT",
                "IDLE", "NONE", "NONE",
                false, "UNKNOWN", false, 0, "NONE", "NONE", 0L,
                true, "SUPPORTED_LIVE_LEVEL", 2, "OPENED", "UMS", "START_SERVICE", "UPDATED");
        assertTrue(valid.liveWindowEnabled());
        assertEquals("SUPPORTED_LIVE_LEVEL", valid.liveWatchSupport());
        assertEquals(2, valid.liveActiveCount());
        assertEquals("OPENED", valid.liveLastResult());
        assertEquals("UMS", valid.liveLastSource());
        assertEquals("START_SERVICE", valid.liveDispatchMode());
        assertEquals("UPDATED", valid.liveTestState());

        var poisoned = new HealthHostControl.Reply(
                true, true, false, true, true, true, "CACHE",
                "READY_SUPPORTED_ON", "NONE", "NONE",
                "IDLE", "NONE", "NONE",
                false, "UNKNOWN", false, 0, "NONE", "NONE", 0L,
                true, "PickupCodeA739", -4, "CottiCoffee", "evil", "raw", "leak");
        assertEquals("UNKNOWN", poisoned.liveWatchSupport());
        assertEquals(0, poisoned.liveActiveCount());
        assertEquals("NONE", poisoned.liveLastResult());
        assertEquals("NONE", poisoned.liveLastSource());
        assertEquals("NONE", poisoned.liveDispatchMode());
        assertEquals("IDLE", poisoned.liveTestState());
    }
}
