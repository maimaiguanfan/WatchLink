package io.github.watchlink.notify;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class SleepVideoStopPolicyTest {
    @Test public void parsesAndValidatesCustomPackagesSafely() {
        assertTrue(SleepVideoStopPolicy.isValidPackageName("com.ss.android.ugc.aweme"));
        assertTrue(SleepVideoStopPolicy.isValidPackageName("tv.danmaku.bili"));
        assertFalse(SleepVideoStopPolicy.isValidPackageName(""));
        assertFalse(SleepVideoStopPolicy.isValidPackageName("singleword"));
        assertFalse(SleepVideoStopPolicy.isValidPackageName("com.bad pkg"));
        assertFalse(SleepVideoStopPolicy.isValidPackageName("com.bad;rm -rf"));

        List<String> parsed = SleepVideoStopPolicy.parseCustomPackages(
                " com.example.live, invalid ; com.example.drama\ncom.example.live ");
        assertEquals(List.of("com.example.live", "com.example.drama"), parsed);

        String added = SleepVideoStopPolicy.addCustomPackage("com.example.live", "com.example.short");
        assertEquals("com.example.live\ncom.example.short", added);
        assertEquals("com.example.short", SleepVideoStopPolicy.removeCustomPackage(added, "com.example.live"));

        Set<String> all = SleepVideoStopPolicy.combinedTargetPackages("com.example.short");
        assertTrue(all.contains("com.ss.android.ugc.aweme"));
        assertTrue(all.contains("tv.danmaku.bili"));
        assertTrue(all.contains("com.phoenix.read"));
        assertTrue(all.contains("com.example.short"));
    }

    @Test public void evaluatesFallAsleepVideoStopWithoutInterruptingCallsOrUnrelatedMusic() {
        assertEquals(SleepVideoStopPolicy.Decision.DISABLED,
                SleepVideoStopPolicy.evaluate(false, SleepDndCoordinator.ACTION_FALL_ASLEEP,
                        false, true, false, true, true, true));
        assertEquals(SleepVideoStopPolicy.Decision.IGNORE_ACTION,
                SleepVideoStopPolicy.evaluate(true, SleepDndCoordinator.ACTION_WAKE_UP,
                        false, true, false, true, true, true));
        assertEquals(SleepVideoStopPolicy.Decision.SKIP_IN_CALL,
                SleepVideoStopPolicy.evaluate(true, SleepDndCoordinator.ACTION_FALL_ASLEEP,
                        true, true, false, true, true, true));
        assertEquals(SleepVideoStopPolicy.Decision.STOP_MATCHED_SESSION,
                SleepVideoStopPolicy.evaluate(true, SleepDndCoordinator.ACTION_FALL_ASLEEP,
                        false, true, true, false, true, true));
        assertEquals(SleepVideoStopPolicy.Decision.SKIP_NO_TARGET,
                SleepVideoStopPolicy.evaluate(true, SleepDndCoordinator.ACTION_FALL_ASLEEP,
                        false, false, true, true, true, true));
        assertEquals(SleepVideoStopPolicy.Decision.STOP_SCREEN_ON_VIDEO,
                SleepVideoStopPolicy.evaluate(true, SleepDndCoordinator.ACTION_FALL_ASLEEP,
                        false, false, false, true, false, true));
        assertEquals(SleepVideoStopPolicy.Decision.STOP_ACTIVE_AUDIO_VIDEO,
                SleepVideoStopPolicy.evaluate(true, SleepDndCoordinator.ACTION_FALL_ASLEEP,
                        false, false, false, false, true, true));
        assertEquals(SleepVideoStopPolicy.Decision.SKIP_NO_TARGET,
                SleepVideoStopPolicy.evaluate(true, SleepDndCoordinator.ACTION_FALL_ASLEEP,
                        false, false, false, false, false, true));
    }
}
