package io.github.watchlink.validation;

import io.github.watchlink.alarm.probe.DeviceToken;
import io.github.watchlink.alarm.probe.NativeUuid;

/** Verified current Clock occurrence metadata. Never puts raw alarm IDs or labels into logs. */
public final class PhoneRingWire {
    public static final long MAX_AGE = 15_000L;

    public record Event(String key, boolean ringing, long at, long elapsed, int snoozeMinutes) {
        public Event {
            if (key == null || !key.matches("[0-9a-f]{64}") || at <= 0 || elapsed < 0
                    || (snoozeMinutes != 0 && !validSnooze(snoozeMinutes)))
                throw new IllegalArgumentException("PHONE_OCCURRENCE_INVALID");
        }

        @Override public String toString() { return ringing ? "ClockRing" : "ClockEnd"; }

        public boolean fresh(long wall, long monotonic) {
            return PhoneRingWire.fresh(at, elapsed, wall, monotonic);
        }
    }

    public static boolean fresh(long at, long elapsed, long now, long monotonic) {
        return at > 0 && elapsed >= 0 && at <= now && now - at < MAX_AGE
                && elapsed <= monotonic && monotonic - elapsed < MAX_AGE;
    }

    public static boolean validSnooze(int minutes) {
        return minutes >= 5 && minutes <= 30 && minutes % 5 == 0;
    }

    public static String occurrence(String uuid, long parent, long schedule, long time, int snoozes) throws Exception {
        String normalized = NativeUuid.normalize(uuid);
        if (normalized == null || parent <= 0 || schedule <= 0 || time <= 0 || snoozes < 0)
            throw new IllegalArgumentException("PHONE_OCCURRENCE_ID");
        return DeviceToken.of("clock-ring/v1/" + normalized + "/" + parent + "/" + schedule + "/" + time + "/" + snoozes);
    }

    private PhoneRingWire() {}
}
