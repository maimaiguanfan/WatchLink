package io.github.watchlink.xposed;

import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.timer.TimerRing;
import io.github.watchlink.timer.TimerCards;
import io.github.watchlink.timer.Countdown;
import io.github.watchlink.live.LiveWindowTracker;
import java.time.ZoneOffset;
import java.util.List;
import io.github.watchlink.validation.DailyAlarmWire;
import io.github.watchlink.validation.HuaweiReminderService;
import org.junit.Test;
import static org.junit.Assert.*;

public class CountdownReminderTest {
    @Test public void currentRunAndBothClocksAreRequiredForControls() {
        TimerRing ring = TimerRing.start(9, 10, 100, 80);
        TimerRing repeat = TimerRing.start(9, 11, 105, 85);
        assertTrue(ring.accepts("TIMER", ring.run(), ring.reminder(), 101, 81, 102, 82));
        assertFalse(ring.accepts("ALARM", ring.run(), ring.reminder(), 101, 81, 102, 82));
        assertFalse(repeat.accepts("TIMER", ring.run(), ring.reminder(), 106, 86, 107, 87));
        assertFalse(ring.accepts("TIMER", ring.run(), ring.reminder(), 99, 79, 102, 82));
        assertFalse(ring.accepts("TIMER", ring.run(), ring.reminder(), 101, 81, 8102, 8082));
        long expired = DailyAlarmWire.MAX_WINDOW + 200;
        assertFalse(ring.accepts("TIMER", ring.run(), ring.reminder(), expired, expired, expired, expired));
        assertFalse(TimerRing.timerNotice(ClockDailyAlarms.MIN_NOTICE));
        assertFalse(TimerRing.timerNotice(CalendarDailyAlarms.MIN_NOTICE));
        assertFalse(TimerRing.timerNotice(0x60000001));
    }

    @Test public void databaseExpiryCanCloseCardBeforeNativeRingOpensAndReturnsAnAction() {
        var cards = new TimerCards();
        var timer = new Countdown(3, 150, 60_000,
                Countdown.Phase.RUNNING, 60_150);
        var zone = ZoneOffset.UTC;
        var tracker = new LiveWindowTracker();
        var created = cards.reconcile(List.of(timer), 150, 1000, zone, false).get(0);
        tracker.onUpsert(created, 150);
        var removed = cards.reconcile(List.of(), 60_150, 61_000, zone, false).get(0);
        assertNotNull(tracker.onRemove(removed.stableKey()));
        var ring = TimerRing.start(3, 150, 61_001, 60_151);
        assertNull(tracker.onRemove(Countdown.key(ring.id(), ring.firstStart())));
        var health = new DailyAlarmObservation();
        String identifier = HuaweiReminderService.dailyIdentifier(ring.run(), ring.reminder(), 0);
        assertEquals("READY", health.arm(TargetProfile.CLOCK, ring.run(), ring.reminder(), ring.wall(), ring.elapsed(),
                null, identifier, ring.wall(), ring.elapsed(), false));
        assertNotNull(health.serviceReceived(ring.reminder(), identifier, 61_005, 60_155));
        var stop = health.serviceAction(ring.reminder(), 2, 61_010, 60_160);
        assertEquals("DISMISS", stop.kind());
        assertTrue(ring.accepts("TIMER", stop.run(), stop.reminder(), stop.wall(), stop.elapsed(), 61_012, 60_162));
    }

    @Test public void timerAndAlarmCannotClaimEachOthersActionsOrEnd() {
        var ring = TimerRing.start(2, 22, 1000, 500);
        var health = new DailyAlarmObservation();
        int timer = ring.reminder();
        String identifier = HuaweiReminderService.dailyIdentifier(ring.run(), timer, 0);
        assertEquals("READY", health.arm(TargetProfile.CLOCK, ring.run(), timer, 1000, 500,
                null, identifier, 1000, 500, false));
        assertNotNull(health.serviceReceived(timer, identifier, 1001, 501));
        int alarm = ClockDailyAlarms.MIN_NOTICE;
        assertEquals("UNAVAILABLE", health.arm(TargetProfile.CLOCK, "other", alarm, 1002, 502,
                null, HuaweiReminderService.dailyIdentifier("other", alarm, 0), 1002, 502, false));
        health.end(TargetProfile.CLOCK, "other", alarm, 1003, 503);
        assertFalse(health.shouldClose(TargetProfile.CALENDAR, ring.run(), timer, 1003, 503));
        assertNull(health.serviceAction(alarm, 2, 1004, 504));
        assertEquals("DISMISS", health.serviceAction(timer, 2, 1005, 505).kind());
        assertNull(health.serviceAction(timer, 2, 1005, 505));
        health.end(TargetProfile.CLOCK, ring.run(), timer, 1006, 506);
        assertNull(health.serviceAction(timer, 1, 1007, 507));
    }
}
