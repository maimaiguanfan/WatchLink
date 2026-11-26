package io.github.watchlink.timer;

import io.github.watchlink.live.LiveWindowPayload;

/** Clock deadlines and the first start of an occurrence use elapsedRealtime milliseconds. */
public record Countdown(long id, long firstStart, long duration, Phase phase, long value) {
    public enum Phase { RUNNING, PAUSED }

    public boolean valid() {
        return id > 0 && firstStart > 0 && duration > 0 && phase != null
                && (phase == Phase.RUNNING ? value > 0 : value >= 0 && value <= duration);
    }

    public long remaining(long elapsed) {
        return phase == Phase.PAUSED ? value : Math.max(0, value - elapsed);
    }

    public String key() { return key(id, firstStart); }

    public static String key(long id, long firstStart) {
        return LiveWindowPayload.CLOCK_TIMER_KEY_PREFIX + id + ":" + firstStart;
    }
}
