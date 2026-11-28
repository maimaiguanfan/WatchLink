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

    @Test public void parsesAndClampsGameFlashbackLeadSecCommandsAndReplies() {
        assertEquals("SET_GAME_FLASHBACK_LEAD:0", HealthHostControl.setGameFlashbackLeadSecCommand(-5));
        assertEquals("SET_GAME_FLASHBACK_LEAD:7", HealthHostControl.setGameFlashbackLeadSecCommand(7));
        assertEquals("SET_GAME_FLASHBACK_LEAD:300", HealthHostControl.setGameFlashbackLeadSecCommand(999));

        assertEquals(0, HealthHostControl.parseGameFlashbackLeadSecCommand("SET_GAME_FLASHBACK_LEAD:0"));
        assertEquals(15, HealthHostControl.parseGameFlashbackLeadSecCommand("SET_GAME_FLASHBACK_LEAD:15"));
        assertEquals(300, HealthHostControl.parseGameFlashbackLeadSecCommand("SET_GAME_FLASHBACK_LEAD:300"));
        assertEquals(-1, HealthHostControl.parseGameFlashbackLeadSecCommand("SET_GAME_FLASHBACK_LEAD:301"));
        assertEquals(-1, HealthHostControl.parseGameFlashbackLeadSecCommand("SET_GAME_FLASHBACK_LEAD:abc"));
        assertEquals(-1, HealthHostControl.parseGameFlashbackLeadSecCommand("QUERY"));

        var reply = new HealthHostControl.Reply(
                true, true, false, true, true, true, "CACHE",
                "READY_SUPPORTED_ON", "START_SERVICE", "SDK_AND_INTENT",
                "IDLE", "NONE", "NONE",
                false, "UNKNOWN", false, 0, "NONE", "NONE", 0L,
                false, "SUPPORTED_LIVE_LEVEL", 1, "OPENED", "UMS", "START_SERVICE", "IDLE",
                false, true, 999);
        assertTrue(reply.gameFlashbackEnabled());
        assertEquals(300, reply.gameFlashbackLeadSec());
    }

    @Test public void sanitizesNotifyAndSleepDndTelemetryAllowlists() {
        var valid = new HealthHostControl.Reply(
                true, true, false, true, true, true, "CACHE",
                "READY_SUPPORTED_ON", "START_SERVICE", "SDK_AND_INTENT",
                "IDLE", "NONE", "NONE",
                false, "UNKNOWN", false, 0, "NONE", "NONE", 0L,
                false, "SUPPORTED_LIVE_LEVEL", 0, "NONE", "NONE", "NONE", "IDLE",
                false, false, 5,
                true, true, "SUPPORTED_136", true, true, "FALL_ASLEEP_DND_ON",
                1700000000000L, 1700000000000L, 1700025200000L, true, true, "SUPPORTED_94");
        assertTrue(valid.notifyMuteDndEnabled());
        assertTrue(valid.notifySilentPushEnabled());
        assertEquals("SUPPORTED_94", valid.silentPushWatchSupport());
        assertTrue(valid.sleepAutoDndEnabled());
        assertTrue(valid.wakeExitDndEnabled());
        assertEquals("SUPPORTED_136", valid.sleepWatchSupport());
        assertTrue(valid.sleepHookReady());
        assertTrue(valid.dndPolicyAccess());
        assertEquals("FALL_ASLEEP_DND_ON", valid.sleepLastEvent());
        assertEquals(1700000000000L, valid.sleepLastAt());
        assertEquals(1700000000000L, valid.sleepLastFallAsleepAt());
        assertEquals(1700025200000L, valid.sleepLastWakeUpAt());

        var poisoned = new HealthHostControl.Reply(
                true, false, false, true, true, true, "CACHE",
                "READY_SUPPORTED_ON", "NONE", "NONE",
                "IDLE", "NONE", "NONE",
                false, "UNKNOWN", false, 0, "NONE", "NONE", 0L,
                false, "UNKNOWN", 0, "NONE", "NONE", "NONE", "IDLE",
                false, false, 5,
                true, true, "bad_cap", true, true, "secret_msg", -5L, -10L, -20L, false, true, "evil94");
        assertFalse(poisoned.wakeExitDndEnabled());
        assertTrue(poisoned.notifySilentPushEnabled());
        assertEquals("UNKNOWN", poisoned.silentPushWatchSupport());
        assertEquals("UNKNOWN", poisoned.sleepWatchSupport());
        assertEquals("NONE", poisoned.sleepLastEvent());
        assertEquals(0L, poisoned.sleepLastAt());
        assertEquals(0L, poisoned.sleepLastFallAsleepAt());
        assertEquals(0L, poisoned.sleepLastWakeUpAt());
    }
}
