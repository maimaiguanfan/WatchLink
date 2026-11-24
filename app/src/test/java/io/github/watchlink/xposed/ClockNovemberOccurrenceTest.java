package io.github.watchlink.xposed;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClockNovemberOccurrenceTest {
    private final ClockNovemberOccurrence ring = new ClockNovemberOccurrence(8, 4, 1000, 0, "fixture");

    private static ClockInventory.Schedule row(long id, long parent, long time, int state, int snoozes) {
        ClockInventory.Schedule row = new ClockInventory.Schedule();
        row.id = id; row.parent = parent; row.time = time; row.state = state; row.snoozeCount = snoozes;
        return row;
    }

    @Test public void ringRequiresSameInstanceAndScheduledTime() {
        assertTrue(ring.matches(8, 4, "fixture"));
        assertFalse(ring.matches(8, 5, "fixture"));
        assertFalse(ring.matches(8, 4, "different"));
        assertTrue(ring.ringing(row(8, 4, 1000, 2, 0)));
        assertFalse(ring.ringing(row(8, 4, 1100, 2, 0)));
        assertFalse(ring.ringing(row(8, 4, 1000, 2, -1)));
    }

    @Test public void actionableDatabaseRowUsesStoredStateRatherThanLiveModelValue() {
        assertTrue(ring.actionableRow(row(8, 4, 1000, 0, 0)));
        assertFalse(ring.actionableRow(row(8, 4, 1000, 1, 0)));
        assertFalse(ring.actionableRow(row(8, 4, 1000, 2, 0)));
        assertFalse(ring.actionableRow(row(8, 4, 4000, 0, 1)));
    }

    @Test public void reusingServiceAndScheduleForSnoozeDoesNotMatchThePreviousRing() {
        assertTrue(ring.sameFiring(new ClockNovemberOccurrence(8, 4, 1000, 0, "fixture")));
        assertFalse(ring.sameFiring(new ClockNovemberOccurrence(8, 4, 4000, 1, "fixture")));
        assertFalse(ring.sameFiring(new ClockNovemberOccurrence(8, 4, 1000, 0, "replacement")));
        assertFalse(ring.sameFiring(null));
    }

    @Test public void snoozeRequiresRetimedSameNativeScheduleAndIncrementedCount() {
        assertTrue(ring.terminal(false, row(8, 4, 4000, 1, 1), 2000));
        assertFalse(ring.terminal(false, row(9, 4, 4000, 1, 1), 2000));
        assertFalse(ring.terminal(false, row(8, 5, 4000, 1, 1), 2000));
        assertFalse(ring.terminal(false, row(8, 4, 4000, 2, 1), 2000));
        assertFalse(ring.terminal(false, row(8, 4, 4000, 1, 0), 2000));
        assertFalse(ring.terminal(false, row(8, 4, 1900, 1, 1), 2000));
        assertFalse(ring.terminal(false, null, 2000));
    }

    @Test public void dismissRequiresConsumedOccurrence() {
        assertTrue(ring.terminal(true, null, 2000));
        assertFalse(ring.terminal(true, row(8, 4, 4000, 1, 1), 2000));
    }

    @Test public void nativeDismissAndSnoozeTakeMutuallyExclusiveServiceBranches() {
        String dismiss = "com.oplus.alarmclock.ALARM_DISMISS_SERVICE_ACTION";
        String snooze = "com.oplus.alarmclock.ALARM_SNOOZE_OR_DISMISS_SERVICE_ACTION";
        ClockNovemberLive.Acceptance before = new ClockNovemberLive.Acceptance(4, 2);
        assertTrue(before.after(dismiss).exactlyOneSince(before, true));
        assertTrue(before.after(snooze).exactlyOneSince(before, false));
        assertFalse(before.after(snooze).exactlyOneSince(before, true));
        assertFalse(before.after(dismiss).exactlyOneSince(before, false));
        assertFalse(before.after(dismiss).after(snooze).exactlyOneSince(before, true));
        assertFalse(before.after(dismiss).after(dismiss).exactlyOneSince(before, true));
        assertFalse(before.after("com.oplus.alarmclock.ALARM_CANCEL_NOTIFICATION_ACTION").exactlyOneSince(before, true));
    }
}
