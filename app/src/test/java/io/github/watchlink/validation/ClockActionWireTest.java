package io.github.watchlink.validation;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClockActionWireTest {
    private static final String RUN = "596ac455-8d9b-4d99-a180-99fa57c8da46";
    private static final String KEY = "a".repeat(64);

    @Test public void expiresOnEitherClockAndRejectsRollback() {
        var command = new ClockActionWire.Command(RUN, KEY, "SNOOZE", 10_000, 5_000);
        assertTrue(command.fresh(17_999, 12_999));
        assertFalse(command.fresh(18_000, 5_000));
        assertFalse(command.fresh(10_000, 13_000));
        assertFalse(command.fresh(9_999, 5_000));
        assertFalse(command.fresh(10_000, 4_999));
    }

    @Test public void rejectsInvalidIdentitiesAndActionsWithoutLeakingKey() {
        assertThrows(IllegalArgumentException.class, () -> new ClockActionWire.Command("", KEY, "DISMISS", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ClockActionWire.Command(RUN, "01:28", "DISMISS", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ClockActionWire.Command(RUN, KEY, "DISABLE", 1, 0));
        var command = new ClockActionWire.Command(RUN, KEY, "DISMISS", 1, 0);
        assertFalse(command.toString().contains(KEY));
    }
}
