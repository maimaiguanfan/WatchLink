package io.github.watchlink.xposed;

import org.junit.Test;
import static org.junit.Assert.*;

public class DailyReminderTitleTest {
    @Test public void phoneEditorLabelsFitInFullAndOnlyUnnamedValuesUseTheDefault() {
        assertNull(DailyReminderTitle.forWatch(null));
        assertNull(DailyReminderTitle.forWatch(""));
        assertNull(DailyReminderTitle.forWatch(" \t\u00a0\u3000"));
        String sample = " 早班 🕗 ";
        assertEquals(sample, DailyReminderTitle.forWatch(sample));
        String chinese = "钟".repeat(40);
        assertEquals(chinese, DailyReminderTitle.forWatch(chinese));
    }

    @Test public void oversizedImportedLabelsAreShortenedAtACodePointBoundary() {
        String prefix = "钟".repeat(41);
        assertEquals(prefix + "🕗", DailyReminderTitle.forWatch(prefix + "🕗x"));
        assertEquals(prefix + "z", DailyReminderTitle.forWatch(prefix + "z🕗"));
    }

}
