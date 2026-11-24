package io.github.watchlink.xposed;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;

public final class WatchCooperativeReportTest {
    private static byte[] report(int action) {
        return new byte[]{8, 10, 1, 4, 0, 0, 0, 17, 2, 1, (byte) action};
    }

    @Test public void returnsOnlyTheTwoObservedSchemaCandidates() {
        assertEquals(1, WatchCooperativeReport.action(report(1)));
        assertEquals(2, WatchCooperativeReport.action(report(2)));
        assertEquals(0, WatchCooperativeReport.action(report(0)));
        assertEquals(0, WatchCooperativeReport.action(report(3)));
    }

    @Test public void rejectsTruncationExtraFieldsAndWrongTags() {
        assertEquals(0, WatchCooperativeReport.action(null));
        byte[] valid = report(2);
        for (int length = 0; length < valid.length; length++)
            assertEquals(0, WatchCooperativeReport.action(Arrays.copyOf(valid, length)));
        assertEquals(0, WatchCooperativeReport.action(Arrays.copyOf(valid, valid.length + 1)));
        for (int i : new int[]{0, 1, 2, 3, 8, 9}) {
            byte[] altered = valid.clone();
            altered[i]++;
            assertEquals(0, WatchCooperativeReport.action(altered));
        }
        valid[4] = (byte) 0x80;
        assertEquals(0, WatchCooperativeReport.action(valid));
    }
}
