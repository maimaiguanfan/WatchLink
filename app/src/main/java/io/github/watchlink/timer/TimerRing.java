package io.github.watchlink.timer;

import io.github.watchlink.validation.DailyAlarmWire;
import java.util.UUID;

/** One validated Clock alert. A fresh run token and a disjoint watch ID bind every reply. */
public record TimerRing(long id, long firstStart, String run, int reminder, long wall, long elapsed) {
    public static final String TYPE = "TIMER";
    public static final String TYPE_EXTRA = "reminderKind";
    private static final int ID_BASE = 0x30000000;

    public static TimerRing start(long id, long firstStart, long wall, long elapsed) {
        UUID token = UUID.randomUUID();
        return new TimerRing(id, firstStart, token.toString(), ID_BASE | (token.hashCode() & 0x0fffffff), wall, elapsed);
    }

    public static boolean timerNotice(int reminder) { return (reminder & 0xf0000000) == ID_BASE; }

    public boolean accepts(String type, String replyRun, int notice, long at, long seen, long now, long monotonic) {
        return TYPE.equals(type) && run.equals(replyRun) && reminder == notice
                && at >= wall && seen >= elapsed
                && DailyAlarmWire.inWindow(at, seen, 8_000, now, monotonic)
                && DailyAlarmWire.inWindow(wall, elapsed, DailyAlarmWire.MAX_WINDOW, now, monotonic);
    }

    @Override public String toString() { return "TimerRing"; }
}
