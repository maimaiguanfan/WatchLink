package io.github.watchlink.xposed;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public final class CooperativeSessionMatchTest {
    private static final String WATCH = "fixture";
    private static final int ID = 0x0180ff04;
    private static byte[] open() { return new byte[]{8,9,1,4,1,(byte)128,(byte)255,4}; }
    private static byte[] reply(int action) { return new byte[]{8,10,1,4,1,(byte)128,(byte)255,4,2,1,(byte)action}; }

    @Test public void bothActionsRequireObservedOpenAndOnlyTheFirstAnswerIsAccepted() {
        for (int button = 1; button <= 2; button++) {
            CooperativeSessionMatch match = new CooperativeSessionMatch(WATCH, ID, false);
            assertNull(match.observe(true, WATCH, reply(button)));
            assertNull(match.observe(true, WATCH, open()));
            assertEquals("OUTBOUND", match.observe(false, WATCH, open()));
            assertNull(match.observe(false, WATCH, open()));
            assertNull(match.observe(false, WATCH, reply(button)));
            assertEquals(button == 1 ? "SNOOZE" : "DISMISS", match.observe(true, WATCH, reply(button)));
            assertNull(match.observe(true, WATCH, reply(3-button)));
        }
    }

    @Test public void unrelatedFramesCannotArmOrCompleteTrial() {
        CooperativeSessionMatch match = new CooperativeSessionMatch(WATCH, ID, false);
        assertNull(match.observe(false, "other", open()));
        for (int i = 0; i < 8; i++) {
            byte[] wrong = open(); wrong[i]++;
            assertNull(match.observe(false, WATCH, wrong));
        }
        assertNull(match.observe(true, WATCH, reply(1)));
        assertEquals("OUTBOUND", match.observe(false, WATCH, open()));
        assertNull(match.observe(true, "other", reply(1)));
        assertNull(match.observe(true, WATCH, reply(3)));
        assertNull(match.observe(true, WATCH, Arrays.copyOf(reply(1), 10)));
        assertNull(match.observe(true, WATCH, Arrays.copyOf(reply(1), 12)));
        for (int i = 0; i < 10; i++) {
            byte[] wrong = reply(1); wrong[i]++;
            assertNull(match.observe(true, WATCH, wrong));
        }
        assertEquals("SNOOZE", match.observe(true, WATCH, reply(1)));
    }

    @Test public void closeWindowRecognizesOnlyItsOwnOutboundCancelOnce() {
        CooperativeSessionMatch match = new CooperativeSessionMatch(WATCH, ID, true);
        byte[] cancel = reply(1);
        assertNull(match.observe(false, WATCH, open()));
        assertNull(match.observe(true, WATCH, cancel));
        assertNull(match.observe(true, WATCH, reply(2)));
        assertNull(match.observe(false, "other", cancel));
        assertNull(match.observe(false, WATCH, reply(2)));
        assertNull(match.observe(false, WATCH, Arrays.copyOf(cancel, 10)));
        assertNull(match.observe(false, WATCH, Arrays.copyOf(cancel, 12)));
        for (int i = 0; i < cancel.length; i++) {
            byte[] changed = cancel.clone();
            changed[i] ^= 0x40;
            assertNull(match.observe(false, WATCH, changed));
        }
        assertEquals("CLOSE_OUTBOUND", match.observe(false, WATCH, cancel));
        assertNull(match.observe(false, WATCH, cancel));
    }
}
