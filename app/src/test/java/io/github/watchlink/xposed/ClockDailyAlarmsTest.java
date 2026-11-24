package io.github.watchlink.xposed;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClockDailyAlarmsTest {
    @Test public void notificationSequenceStaysWithinDedicatedHealthHostedRange() {
        int first = ClockDailyAlarms.MIN_NOTICE;
        assertEquals(first, ClockDailyAlarms.nextNotice(first - 1));
        assertEquals(first + 1, ClockDailyAlarms.nextNotice(first));
        assertEquals(0, ClockDailyAlarms.nextNotice(ClockDailyAlarms.MAX_NOTICE));
        assertEquals(0, ClockDailyAlarms.nextNotice(0x20000000));
        assertEquals(0, ClockDailyAlarms.nextNotice(0x30000000));
        assertEquals(0, ClockDailyAlarms.nextNotice(7));
        assertEquals(0, ClockDailyAlarms.nextNotice(Integer.MAX_VALUE));
    }

    @Test public void statusMustPostdateThisOccurrenceAndBeFreshOnBothClocks() {
        assertTrue(ClockDailyAlarms.freshStatus(15_004, 1_004, 15_099, 1_099, 1_000));
        assertFalse(ClockDailyAlarms.freshStatus(14_999, 999, 15_099, 1_099, 1_000));
        assertFalse(ClockDailyAlarms.freshStatus(15_100, 1_004, 15_099, 1_000, 1_000));
        assertFalse(ClockDailyAlarms.freshStatus(15_004, 1_004, 23_004, 1_099, 1_000));
    }
}
