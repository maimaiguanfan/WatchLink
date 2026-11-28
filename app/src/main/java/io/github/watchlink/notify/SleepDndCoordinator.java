package io.github.watchlink.notify;

/**
 * Pure-Java decision policy for watch sleep/wake detection -> phone Do Not Disturb.
 *
 * Verified against Huawei Health 17.0.10.330 (Lnfr.a -> Lnhp.a & Lntn.b):
 *   action == 16 (0x10): watch detected user fell asleep (sleepStatus = 1, sleep model = true)
 *   action == 4  (0x04): watch detected user woke up     (sleepStatus = 0, sleep model = false)
 *
 * "Fall asleep -> enable DND" and "Wake up -> exit DND" are two independent features controlled
 * by separate user switches.
 */
public final class SleepDndCoordinator {
    public static final int ACTION_WAKE_UP = 4;
    public static final int ACTION_FALL_ASLEEP = 16;
    public static final long MEDIA_PAUSE_COOLDOWN_MS = 30_000L;

    public enum Decision {
        ENABLE_DND,
        DISABLE_DND,
        SKIP_ALREADY_DND,
        SKIP_ALREADY_OFF,
        DISABLED,
        IGNORE_ACTION
    }

    public enum MediaPauseDecision {
        PAUSE_MEDIA,
        SKIP_IN_CALL,
        SKIP_NOT_PLAYING,
        SKIP_COOLDOWN,
        DISABLED,
        IGNORE_ACTION
    }

    public static Decision onSleepAction(
            boolean sleepEnableDnd, boolean wakeExitDnd, int action, boolean phoneDndActive) {
        if (action == ACTION_FALL_ASLEEP) {
            if (!sleepEnableDnd) return Decision.DISABLED;
            return phoneDndActive ? Decision.SKIP_ALREADY_DND : Decision.ENABLE_DND;
        }
        if (action == ACTION_WAKE_UP) {
            if (!wakeExitDnd) return Decision.DISABLED;
            return phoneDndActive ? Decision.DISABLE_DND : Decision.SKIP_ALREADY_OFF;
        }
        return Decision.IGNORE_ACTION;
    }

    /**
     * Pure-Java decision for pausing phone media playback when the watch detects fall asleep.
     * Wake-up events never resume playback automatically.
     */
    public static MediaPauseDecision onSleepMediaAction(
            boolean pauseMediaEnabled,
            int action,
            boolean musicActive,
            boolean inCall,
            long nowElapsedMs,
            long lastPausedElapsedMs) {
        if (action != ACTION_FALL_ASLEEP) {
            return MediaPauseDecision.IGNORE_ACTION;
        }
        if (!pauseMediaEnabled) {
            return MediaPauseDecision.DISABLED;
        }
        if (inCall) {
            return MediaPauseDecision.SKIP_IN_CALL;
        }
        if (!musicActive) {
            return MediaPauseDecision.SKIP_NOT_PLAYING;
        }
        if (lastPausedElapsedMs > 0L && nowElapsedMs >= lastPausedElapsedMs
                && (nowElapsedMs - lastPausedElapsedMs) < MEDIA_PAUSE_COOLDOWN_MS) {
            return MediaPauseDecision.SKIP_COOLDOWN;
        }
        return MediaPauseDecision.PAUSE_MEDIA;
    }

    private SleepDndCoordinator() {}
}
