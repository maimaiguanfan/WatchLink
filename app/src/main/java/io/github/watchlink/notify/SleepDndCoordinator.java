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

    public enum Decision {
        ENABLE_DND,
        DISABLE_DND,
        SKIP_ALREADY_DND,
        SKIP_ALREADY_OFF,
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

    private SleepDndCoordinator() {}
}
