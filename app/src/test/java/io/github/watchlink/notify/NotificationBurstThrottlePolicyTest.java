package io.github.watchlink.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.watchlink.alarm.probe.TargetProfile;
import org.junit.Test;

public class NotificationBurstThrottlePolicyTest {

    @Test
    public void fixedWindowSilencesSubsequentSameAppWithinInterval() {
        NotificationBurstThrottlePolicy policy = new NotificationBurstThrottlePolicy();
        // t=1000ms: first message from WeChat vibrates.
        assertFalse(policy.shouldSilenceAndRecord(
                true, 10, "com.tencent.mm", "msg", false, false, false, false, false, 1_000L));
        // t=3000ms: within 10s -> silenced (does NOT slide the window!).
        assertTrue(policy.shouldSilenceAndRecord(
                true, 10, "com.tencent.mm", "msg", false, false, false, false, false, 3_000L));
        // t=9999ms: still within 10s of t=1000 -> silenced.
        assertTrue(policy.shouldSilenceAndRecord(
                true, 10, "com.tencent.mm", "msg", false, false, false, false, false, 10_999L));
        // t=11000ms: >= 10s since first vibrate (fixed window) -> vibrates again and starts new window!
        assertFalse(policy.shouldSilenceAndRecord(
                true, 10, "com.tencent.mm", "msg", false, false, false, false, false, 11_000L));
        // t=12000ms: within new 10s window -> silenced.
        assertTrue(policy.shouldSilenceAndRecord(
                true, 10, "com.tencent.mm", "msg", false, false, false, false, false, 12_000L));
    }

    @Test
    public void packagesAreTrackedIndependently() {
        NotificationBurstThrottlePolicy policy = new NotificationBurstThrottlePolicy();
        assertFalse(policy.shouldSilenceAndRecord(
                true, 10, "com.tencent.mm", "msg", false, false, false, false, false, 1_000L));
        assertFalse(policy.shouldSilenceAndRecord(
                true, 10, "com.tencent.mobileqq", "msg", false, false, false, false, false, 2_000L));
        assertTrue(policy.shouldSilenceAndRecord(
                true, 10, "com.tencent.mm", "msg", false, false, false, false, false, 3_000L));
        assertTrue(policy.shouldSilenceAndRecord(
                true, 10, "com.tencent.mobileqq", "msg", false, false, false, false, false, 4_000L));
    }

    @Test
    public void alreadySilentMessageNeverArmsBurstWindow() {
        NotificationBurstThrottlePolicy policy = new NotificationBurstThrottlePolicy();
        // Screen-on / DND silent message at t=1000ms -> returns false and does not record.
        assertFalse(policy.shouldSilenceAndRecord(
                true, 10, "com.tencent.mm", "msg", false, false, false, false, true, 1_000L));
        assertEquals(0, policy.trackedCount());
        // Real vibrating message at t=2000ms -> MUST vibrate!
        assertFalse(policy.shouldSilenceAndRecord(
                true, 10, "com.tencent.mm", "msg", false, false, false, false, false, 2_000L));
        assertEquals(1, policy.trackedCount());
    }

    @Test
    public void callsAlarmsReplaysAndBatteryAlertsAreExempt() {
        NotificationBurstThrottlePolicy policy = new NotificationBurstThrottlePolicy();
        // Arm WeChat window first.
        assertFalse(policy.shouldSilenceAndRecord(
                true, 30, "com.tencent.mm", "msg", false, false, false, false, false, 1_000L));
        // WeChat voice call is never silenced.
        assertFalse(policy.shouldSilenceAndRecord(
                true, 30, "com.tencent.mm", "msg", true, false, false, false, false, 2_000L));
        // Non-WeChat VoIP call is never silenced.
        assertFalse(policy.shouldSilenceAndRecord(
                true, 30, "com.tencent.mm", "msg", false, true, false, false, false, 3_000L));
        // Category call / alarm / Clock are never silenced.
        assertFalse(policy.shouldSilenceAndRecord(
                true, 30, "com.tencent.mm", "call", false, false, false, false, false, 4_000L));
        assertFalse(policy.shouldSilenceAndRecord(
                true, 30, TargetProfile.CLOCK.packageName, "alarm", false, false, false, false, false, 5_000L));
        // Short-disconnect replay is never silenced by burst throttle.
        assertFalse(policy.shouldSilenceAndRecord(
                true, 30, "com.tencent.mm", "msg", false, false, true, false, false, 6_000L));
        // WatchLink battery alerts are never silenced by burst throttle.
        assertFalse(policy.shouldSilenceAndRecord(
                true, 30, "com.tencent.mm", "status", false, false, false, true, false, 7_000L));
    }

    @Test
    public void disabledOrClearResetsBehavior() {
        NotificationBurstThrottlePolicy policy = new NotificationBurstThrottlePolicy();
        assertFalse(policy.shouldSilenceAndRecord(
                false, 10, "com.tencent.mm", "msg", false, false, false, false, false, 1_000L));
        assertEquals(0, policy.trackedCount());

        assertFalse(policy.shouldSilenceAndRecord(
                true, 10, "com.tencent.mm", "msg", false, false, false, false, false, 2_000L));
        assertEquals(1, policy.trackedCount());
        policy.clear();
        assertEquals(0, policy.trackedCount());
        assertFalse(policy.shouldSilenceAndRecord(
                true, 10, "com.tencent.mm", "msg", false, false, false, false, false, 3_000L));
    }
}
