package io.github.watchlink.validation;

import static io.github.watchlink.BuildConfig.APPLICATION_ID;

import java.util.UUID;

/** Value object for one watch-originated DISMISS/SNOOZE command executed inside the Clock process. */
public final class ClockActionWire {
    public static final String PERMISSION = APPLICATION_ID + ".permission.CONTROL_RINGING";
    private static final long MAX_AGE_MS = 8_000;

    public record Command(String run, String key, String action, long at, long elapsed) {
        public Command {
            if (run == null || run.length() != 36 || !UUID.fromString(run).toString().equals(run)
                    || key == null || !key.matches("[0-9a-f]{64}")
                    || !("DISMISS".equals(action) || "SNOOZE".equals(action))
                    || at <= 0 || elapsed < 0)
                throw new IllegalArgumentException("CLOCK_ACTION_INVALID");
        }

        public boolean fresh(long wall, long monotonic) {
            return wall >= at && wall - at < MAX_AGE_MS
                    && monotonic >= elapsed && monotonic - elapsed < MAX_AGE_MS;
        }

        @Override public String toString() { return "ClockAction(" + action + ")"; }
    }

    private ClockActionWire() {}
}
