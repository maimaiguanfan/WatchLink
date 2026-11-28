package io.github.watchlink.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BatteryAlertPolicyTest {

    private static BatteryAlertPolicy.PhoneSample phoneSample(
            int level,
            int plugged,
            int status,
            boolean overheat,
            boolean bypass,
            boolean smartProtect,
            boolean regularLimitEnabled,
            int regularLimitLevel,
            boolean regularLimitActive,
            boolean chargeProtectActive,
            long elapsedMs) {
        return new BatteryAlertPolicy.PhoneSample(
                level, plugged, status, overheat, bypass, smartProtect,
                regularLimitEnabled, regularLimitLevel, regularLimitActive, chargeProtectActive, elapsedMs);
    }

    @Test
    public void coldStartAtFullOrLowDoesNotSpam() {
        BatteryAlertPolicy policy = new BatteryAlertPolicy();
        assertNull(policy.onPhoneSample(
                phoneSample(100, 1, BatteryAlertPolicy.STATUS_FULL, false, false, false, false, 0, false, false, 1_000L),
                true, true, 20));
        assertNull(policy.onPhoneSample(
                phoneSample(100, 1, BatteryAlertPolicy.STATUS_FULL, false, false, false, false, 0, false, false, 5_000L),
                true, true, 20));

        BatteryAlertPolicy lowPolicy = new BatteryAlertPolicy();
        assertNull(lowPolicy.onPhoneSample(
                phoneSample(15, 0, BatteryAlertPolicy.STATUS_DISCHARGING, false, false, false, false, 0, false, false, 1_000L),
                true, true, 20));
        assertNull(lowPolicy.onPhoneSample(
                phoneSample(14, 0, BatteryAlertPolicy.STATUS_DISCHARGING, false, false, false, false, 0, false, false, 2_000L),
                true, true, 20));
    }

    @Test
    public void phoneStandardFullFiresOncePerPlugSession() {
        BatteryAlertPolicy policy = new BatteryAlertPolicy();
        // Seed unplugged at 95%.
        assertNull(policy.onPhoneSample(
                phoneSample(95, 0, BatteryAlertPolicy.STATUS_DISCHARGING, false, false, false, false, 0, false, false, 1_000L),
                true, false, 20));
        // Plug in at 95% charging.
        assertNull(policy.onPhoneSample(
                phoneSample(95, 1, BatteryAlertPolicy.STATUS_CHARGING, false, false, false, false, 0, false, false, 2_000L),
                true, false, 20));
        // Reach 100% -> fires once.
        BatteryAlertPolicy.PhoneAlert alert = policy.onPhoneSample(
                phoneSample(100, 1, BatteryAlertPolicy.STATUS_FULL, false, false, false, false, 0, false, false, 3_000L),
                true, false, 20);
        assertNotNull(alert);
        assertTrue(alert.fullAlert());
        assertEquals(BatteryAlertPolicy.PhoneFullKind.STANDARD_100, alert.fullKind());
        assertEquals(100, alert.level());

        // Stays 100% -> no duplicate.
        assertNull(policy.onPhoneSample(
                phoneSample(100, 1, BatteryAlertPolicy.STATUS_FULL, false, false, false, false, 0, false, false, 4_000L),
                true, false, 20));

        // Unplug and replug at 98% -> fires again at 100%.
        assertNull(policy.onPhoneSample(
                phoneSample(98, 0, BatteryAlertPolicy.STATUS_DISCHARGING, false, false, false, false, 0, false, false, 5_000L),
                true, false, 20));
        assertNull(policy.onPhoneSample(
                phoneSample(98, 1, BatteryAlertPolicy.STATUS_CHARGING, false, false, false, false, 0, false, false, 6_000L),
                true, false, 20));
        BatteryAlertPolicy.PhoneAlert second = policy.onPhoneSample(
                phoneSample(100, 1, BatteryAlertPolicy.STATUS_CHARGING, false, false, false, false, 0, false, false, 7_000L),
                true, false, 20);
        assertNotNull(second);
        assertEquals(BatteryAlertPolicy.PhoneFullKind.STANDARD_100, second.fullKind());
    }

    @Test
    public void colorosChargeLimitTreatsTargetCapAsFull() {
        BatteryAlertPolicy policy = new BatteryAlertPolicy();
        // Seed charging at 85% with ColorOS 90% charging limit enabled.
        assertNull(policy.onPhoneSample(
                phoneSample(85, 1, BatteryAlertPolicy.STATUS_CHARGING, false, false, false, true, 90, false, false, 1_000L),
                true, false, 20));
        // Hits 90% and stops charging with regularLimitActive=true -> immediately fires COLOROS_CHARGE_LIMIT.
        BatteryAlertPolicy.PhoneAlert alert = policy.onPhoneSample(
                phoneSample(90, 1, BatteryAlertPolicy.STATUS_NOT_CHARGING, false, false, false, true, 90, true, true, 2_000L),
                true, false, 20);
        assertNotNull(alert);
        assertTrue(alert.fullAlert());
        assertEquals(BatteryAlertPolicy.PhoneFullKind.COLOROS_CHARGE_LIMIT, alert.fullKind());
        assertEquals(90, alert.level());

        // Subsequent updates at 90% do not re-fire.
        assertNull(policy.onPhoneSample(
                phoneSample(90, 1, BatteryAlertPolicy.STATUS_NOT_CHARGING, false, false, false, true, 90, true, true, 30_000L),
                true, false, 20));
    }

    @Test
    public void colorosChargeLimitSustainedWithoutExplicitStatusAlsoFires() {
        BatteryAlertPolicy policy = new BatteryAlertPolicy();
        assertNull(policy.onPhoneSample(
                phoneSample(78, 1, BatteryAlertPolicy.STATUS_CHARGING, false, false, false, true, 80, false, false, 1_000L),
                true, false, 20));
        // Reaches 80% while status still reports CHARGING -> arms 15s sustain check.
        assertNull(policy.onPhoneSample(
                phoneSample(80, 1, BatteryAlertPolicy.STATUS_CHARGING, false, false, false, true, 80, false, false, 10_000L),
                true, false, 20));
        assertTrue(policy.pendingSustainCheckDelayMs(10_000L) > 0L);

        // Before 15s -> still waiting.
        assertNull(policy.onPhoneSample(
                phoneSample(80, 1, BatteryAlertPolicy.STATUS_CHARGING, false, false, false, true, 80, false, false, 20_000L),
                true, false, 20));
        // At >= 15s -> fires COLOROS_CHARGE_LIMIT.
        BatteryAlertPolicy.PhoneAlert alert = policy.onPhoneSample(
                phoneSample(80, 1, BatteryAlertPolicy.STATUS_CHARGING, false, false, false, true, 80, false, false, 25_000L),
                true, false, 20);
        assertNotNull(alert);
        assertEquals(BatteryAlertPolicy.PhoneFullKind.COLOROS_CHARGE_LIMIT, alert.fullKind());
        assertEquals(80, alert.level());
    }

    @Test
    public void smartChargeProtectionBypassAndOverheatNeverTriggerFullAlert() {
        BatteryAlertPolicy policy = new BatteryAlertPolicy();
        assertNull(policy.onPhoneSample(
                phoneSample(75, 1, BatteryAlertPolicy.STATUS_CHARGING, false, false, false, false, 90, false, false, 1_000L),
                true, false, 20));

        // Overnight smart charge protection paused at 80% -> MUST NOT fire.
        assertNull(policy.onPhoneSample(
                phoneSample(80, 1, BatteryAlertPolicy.STATUS_NOT_CHARGING, false, false, true, false, 90, false, true, 5_000L),
                true, false, 20));
        assertNull(policy.onPhoneSample(
                phoneSample(80, 1, BatteryAlertPolicy.STATUS_NOT_CHARGING, false, false, true, false, 90, false, true, 60_000L),
                true, false, 20));

        // Bypass charging in game -> MUST NOT fire even at 90% with regular limit enabled.
        assertNull(policy.onPhoneSample(
                phoneSample(90, 1, BatteryAlertPolicy.STATUS_NOT_CHARGING, false, true, false, true, 90, true, true, 90_000L),
                true, false, 20));

        // Overheat -> MUST NOT fire.
        assertNull(policy.onPhoneSample(
                phoneSample(90, 1, BatteryAlertPolicy.STATUS_NOT_CHARGING, true, false, false, true, 90, true, true, 120_000L),
                true, false, 20));
    }

    @Test
    public void genericNotChargingAt80PlusFiresAfterSustainWindow() {
        BatteryAlertPolicy policy = new BatteryAlertPolicy();
        assertNull(policy.onPhoneSample(
                phoneSample(79, 1, BatteryAlertPolicy.STATUS_CHARGING, false, false, false, false, -1, false, false, 1_000L),
                true, false, 20));
        assertNull(policy.onPhoneSample(
                phoneSample(85, 1, BatteryAlertPolicy.STATUS_NOT_CHARGING, false, false, false, false, -1, false, false, 10_000L),
                true, false, 20));
        assertNull(policy.onPhoneSample(
                phoneSample(85, 1, BatteryAlertPolicy.STATUS_NOT_CHARGING, false, false, false, false, -1, false, false, 25_000L),
                true, false, 20));
        BatteryAlertPolicy.PhoneAlert alert = policy.onPhoneSample(
                phoneSample(85, 1, BatteryAlertPolicy.STATUS_NOT_CHARGING, false, false, false, false, -1, false, false, 30_000L),
                true, false, 20);
        assertNotNull(alert);
        assertEquals(BatteryAlertPolicy.PhoneFullKind.GENERIC_CHARGE_LIMIT, alert.fullKind());
        assertEquals(85, alert.level());
    }

    @Test
    public void phoneLowAlertUsesCustomThresholdAndPlusFiveHysteresis() {
        BatteryAlertPolicy policy = new BatteryAlertPolicy();
        assertNull(policy.onPhoneSample(
                phoneSample(35, 0, BatteryAlertPolicy.STATUS_DISCHARGING, false, false, false, false, 0, false, false, 1_000L),
                false, true, 30));

        // Drops to 30% -> fires low alert.
        BatteryAlertPolicy.PhoneAlert low = policy.onPhoneSample(
                phoneSample(30, 0, BatteryAlertPolicy.STATUS_DISCHARGING, false, false, false, false, 0, false, false, 2_000L),
                false, true, 30);
        assertNotNull(low);
        assertFalse(low.fullAlert());
        assertEquals(30, low.level());

        // Bounces to 34% (< 30 + 5) then back to 29% -> does not re-fire.
        assertNull(policy.onPhoneSample(
                phoneSample(34, 0, BatteryAlertPolicy.STATUS_DISCHARGING, false, false, false, false, 0, false, false, 3_000L),
                false, true, 30));
        assertNull(policy.onPhoneSample(
                phoneSample(29, 0, BatteryAlertPolicy.STATUS_DISCHARGING, false, false, false, false, 0, false, false, 4_000L),
                false, true, 30));

        // Recovers to 35% (>= 30 + 5), then drops back to 30% -> fires again.
        assertNull(policy.onPhoneSample(
                phoneSample(35, 0, BatteryAlertPolicy.STATUS_DISCHARGING, false, false, false, false, 0, false, false, 5_000L),
                false, true, 30));
        BatteryAlertPolicy.PhoneAlert lowAgain = policy.onPhoneSample(
                phoneSample(30, 0, BatteryAlertPolicy.STATUS_DISCHARGING, false, false, false, false, 0, false, false, 6_000L),
                false, true, 30);
        assertNotNull(lowAgain);
        assertFalse(lowAgain.fullAlert());
    }

    @Test
    public void watchFullFiresOnceAndDeduplicatesNativeAlert() {
        BatteryAlertPolicy policy = new BatteryAlertPolicy();
        // Seed charging at 90%.
        assertNull(policy.onWatchSample(true, 90, true, 1_000L, true, false, 20));
        BatteryAlertPolicy.WatchAlert full = policy.onWatchSample(true, 100, true, 2_000L, true, false, 20);
        assertNotNull(full);
        assertTrue(full.fullAlert());
        assertEquals(100, full.level());

        // Still 100% -> no duplicate.
        assertNull(policy.onWatchSample(true, 100, true, 3_000L, true, false, 20));

        // Take off charger, drops to 97%, put back on charger, native Health fires first -> suppressed!
        assertNull(policy.onWatchSample(true, 97, false, 4_000L, true, false, 20));
        assertNull(policy.onWatchSample(true, 97, true, 5_000L, true, false, 20));
        policy.markNativeWatchFullNotified(6_000L);
        assertNull(policy.onWatchSample(true, 100, true, 7_000L, true, false, 20));
    }

    @Test
    public void watchLowAlertSupportsRange1To80WithPlusFiveHysteresis() {
        BatteryAlertPolicy policy = new BatteryAlertPolicy();
        assertEquals(6, BatteryAlertPolicy.hysteresisResetLevel(1));
        assertEquals(85, BatteryAlertPolicy.hysteresisResetLevel(80));

        // Seed connected at 85% not charging.
        assertNull(policy.onWatchSample(true, 85, false, 1_000L, false, true, 80));
        BatteryAlertPolicy.WatchAlert low = policy.onWatchSample(true, 80, false, 2_000L, false, true, 80);
        assertNotNull(low);
        assertFalse(low.fullAlert());
        assertEquals(80, low.level());

        // Drops to 79 -> no duplicate.
        assertNull(policy.onWatchSample(true, 79, false, 3_000L, false, true, 80));
        // Plug in to charge -> resets; unplug at 78 -> fires again.
        assertNull(policy.onWatchSample(true, 79, true, 4_000L, false, true, 80));
        BatteryAlertPolicy.WatchAlert afterCharge = policy.onWatchSample(true, 78, false, 5_000L, false, true, 80);
        assertNotNull(afterCharge);
        assertEquals(78, afterCharge.level());
    }
}
