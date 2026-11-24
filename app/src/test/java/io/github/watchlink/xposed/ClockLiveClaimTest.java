package io.github.watchlink.xposed;

import io.github.watchlink.validation.ClockActionWire.Command;
import static org.junit.Assert.*;
import org.junit.Test;

public class ClockLiveClaimTest {
    private final Command snooze = new Command("f057ea1c-9ab7-486b-90c6-3302ba1408b9",
            "a".repeat(64), "SNOOZE", 1200, 900);
    private final Command dismiss = new Command("6f370730-c189-482a-94b4-e4cec82dcc82",
            "a".repeat(64), "DISMISS", 1200, 900);

    @Test public void remoteMarkerIsConsumedBeforeNativeDismissCanProcessAnotherSchedule() {
        ThreadLocal<Command> marker = new ThreadLocal<>();
        ClockLiveClaim first = new ClockLiveClaim();
        ClockLiveClaim other = new ClockLiveClaim();
        marker.set(dismiss);
        assertTrue(first.reserve(dismiss));
        assertTrue(first.enter(ClockLiveClaim.takeDispatch(marker), "DISMISS"));
        // While the outer native D still runs, it can process a different schedule, then return.
        Command nested = ClockLiveClaim.takeDispatch(marker);
        assertNull(nested);
        assertTrue(other.enter(nested, "SNOOZE"));
        assertFalse(other.enteredByWatch());
        assertTrue(first.proves(true));
        assertNull(ClockLiveClaim.takeDispatch(marker));
    }

    @Test public void singleMatchedNativeBranchCanProveOnlyOneActionAndReturnOneFinalReceipt() {
        ClockLiveClaim c = new ClockLiveClaim();
        assertFalse(c.proves(true));
        assertTrue(c.reserve(snooze));
        assertTrue(c.alreadyReceived(snooze));
        assertFalse(c.reserve(dismiss));
        assertFalse(c.enter(dismiss, "DISMISS"));
        assertTrue(c.enter(snooze, "SNOOZE"));
        assertTrue(c.enteredByWatch());
        assertFalse(c.enter(snooze, "SNOOZE"));
        assertTrue(c.proves(false));
        assertFalse(c.proves(true));
        assertEquals(snooze, c.takeResult());
        assertNull(c.takeResult());
    }

    @Test public void phoneWinsIfItsTransitionStartsWhileWatchRequestIsBeingPrepared() {
        ClockLiveClaim c = new ClockLiveClaim();
        assertTrue(c.reserve(dismiss));
        assertTrue(c.enter(null, "SNOOZE"));
        assertFalse(c.enter(dismiss, "DISMISS"));
        assertFalse(c.proves(true));
        c = new ClockLiveClaim();
        assertTrue(c.enter(null, "SNOOZE"));
        assertFalse(c.reserve(dismiss));
    }

    @Test public void repeatedNativeSnoozeAfterWatchEntryStillAllowsExactReadback() {
        ClockLiveClaim c = new ClockLiveClaim();
        assertTrue(c.reserve(snooze));
        assertTrue(c.enter(snooze, "SNOOZE"));
        assertTrue(c.enter(null, "SNOOZE"));
        assertEquals("SAME_ACTION", c.nativeOverlap());
        assertTrue(c.proves(false));
        assertFalse(c.proves(true));
        ClockNovemberOccurrence original = new ClockNovemberOccurrence(5, 2, 1000, 0, "fixture");
        ClockInventory.Schedule rescheduled = new ClockInventory.Schedule();
        rescheduled.id = 5; rescheduled.parent = 2; rescheduled.state = 1; rescheduled.time = 301000;
        rescheduled.snoozeCount = 1;
        assertTrue(original.terminal(false, rescheduled, 1100));
        rescheduled.snoozeCount = 2;
        assertFalse(original.terminal(false, rescheduled, 1100));
    }

    @Test public void phoneAlreadyApplyingSameActionStillBlocksWatchCommand() {
        ClockLiveClaim c = new ClockLiveClaim();
        assertTrue(c.reserve(snooze));
        assertTrue(c.enter(null, "SNOOZE"));
        assertFalse(c.enter(snooze, "SNOOZE"));
        assertFalse(c.proves(false));
    }

    @Test public void overlappingOppositeNativeActionRemainsAvailableAndInvalidatesAttribution() {
        ClockLiveClaim c = new ClockLiveClaim();
        assertTrue(c.reserve(dismiss));
        assertTrue(c.enter(dismiss, "DISMISS"));
        assertTrue(c.proves(true));
        assertTrue(c.enter(null, "SNOOZE"));
        assertEquals("CONFLICT", c.nativeOverlap());
        assertFalse(c.proves(true));
    }
}
