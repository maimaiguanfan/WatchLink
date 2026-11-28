package io.github.watchlink.notify;

import org.junit.Test;
import static org.junit.Assert.*;

public class SleepDndCoordinatorTest {
    @Test public void fallAsleepSwitchOperatesIndependently() {
        assertEquals(SleepDndCoordinator.Decision.DISABLED,
                SleepDndCoordinator.onSleepAction(false, true, SleepDndCoordinator.ACTION_FALL_ASLEEP, false));
        assertEquals(SleepDndCoordinator.Decision.ENABLE_DND,
                SleepDndCoordinator.onSleepAction(true, false, SleepDndCoordinator.ACTION_FALL_ASLEEP, false));
        assertEquals(SleepDndCoordinator.Decision.SKIP_ALREADY_DND,
                SleepDndCoordinator.onSleepAction(true, false, SleepDndCoordinator.ACTION_FALL_ASLEEP, true));
    }

    @Test public void wakeUpSwitchOperatesIndependently() {
        assertEquals(SleepDndCoordinator.Decision.DISABLED,
                SleepDndCoordinator.onSleepAction(true, false, SleepDndCoordinator.ACTION_WAKE_UP, true));
        assertEquals(SleepDndCoordinator.Decision.DISABLE_DND,
                SleepDndCoordinator.onSleepAction(false, true, SleepDndCoordinator.ACTION_WAKE_UP, true));
        assertEquals(SleepDndCoordinator.Decision.SKIP_ALREADY_OFF,
                SleepDndCoordinator.onSleepAction(false, true, SleepDndCoordinator.ACTION_WAKE_UP, false));
    }

    @Test public void bothSwitchesEnabledHandleFullSleepCycle() {
        assertEquals(SleepDndCoordinator.Decision.ENABLE_DND,
                SleepDndCoordinator.onSleepAction(true, true, SleepDndCoordinator.ACTION_FALL_ASLEEP, false));
        assertEquals(SleepDndCoordinator.Decision.DISABLE_DND,
                SleepDndCoordinator.onSleepAction(true, true, SleepDndCoordinator.ACTION_WAKE_UP, true));
    }

    @Test public void ignoresUnknownSleepActionCodes() {
        assertEquals(SleepDndCoordinator.Decision.IGNORE_ACTION,
                SleepDndCoordinator.onSleepAction(true, true, 99, false));
    }

    @Test public void pausesMediaOnlyOnFallAsleepWhenPlayingAndNotInCall() {
        assertEquals(SleepDndCoordinator.MediaPauseDecision.IGNORE_ACTION,
                SleepDndCoordinator.onSleepMediaAction(true, SleepDndCoordinator.ACTION_WAKE_UP, true, false, 60_000L, 0L));
        assertEquals(SleepDndCoordinator.MediaPauseDecision.DISABLED,
                SleepDndCoordinator.onSleepMediaAction(false, SleepDndCoordinator.ACTION_FALL_ASLEEP, true, false, 60_000L, 0L));
        assertEquals(SleepDndCoordinator.MediaPauseDecision.SKIP_IN_CALL,
                SleepDndCoordinator.onSleepMediaAction(true, SleepDndCoordinator.ACTION_FALL_ASLEEP, true, true, 60_000L, 0L));
        assertEquals(SleepDndCoordinator.MediaPauseDecision.SKIP_NOT_PLAYING,
                SleepDndCoordinator.onSleepMediaAction(true, SleepDndCoordinator.ACTION_FALL_ASLEEP, false, false, 60_000L, 0L));
        assertEquals(SleepDndCoordinator.MediaPauseDecision.PAUSE_MEDIA,
                SleepDndCoordinator.onSleepMediaAction(true, SleepDndCoordinator.ACTION_FALL_ASLEEP, true, false, 60_000L, 0L));
        assertEquals(SleepDndCoordinator.MediaPauseDecision.SKIP_COOLDOWN,
                SleepDndCoordinator.onSleepMediaAction(true, SleepDndCoordinator.ACTION_FALL_ASLEEP, true, false, 70_000L, 60_000L));
        assertEquals(SleepDndCoordinator.MediaPauseDecision.PAUSE_MEDIA,
                SleepDndCoordinator.onSleepMediaAction(true, SleepDndCoordinator.ACTION_FALL_ASLEEP, true, false, 90_000L, 60_000L));
    }
}
