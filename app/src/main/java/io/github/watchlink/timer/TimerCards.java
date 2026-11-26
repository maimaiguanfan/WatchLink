package io.github.watchlink.timer;

import io.github.watchlink.live.LiveWindowPayload;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Tracks only Clock's three visible timers. The host supplies both clocks for each snapshot. */
public final class TimerCards {
    private static final int LIMIT = 3;
    private record Entry(Countdown timer, long nextUpdate) {}
    private Map<String, Entry> visible = new LinkedHashMap<>();

    public List<LiveWindowPayload> reconcile(Collection<Countdown> snapshot, long elapsed, long wall,
                                              ZoneId zone, boolean replay) {
        Map<String, Entry> next = new LinkedHashMap<>();
        List<LiveWindowPayload> updates = new ArrayList<>();
        snapshot.stream().filter(Countdown::valid)
                .filter(timer -> timer.remaining(elapsed) > 0)
                .sorted(Comparator.comparing(Countdown::phase).thenComparingLong(Countdown::value)
                        .thenComparingLong(Countdown::id))
                .limit(LIMIT).forEach(timer -> {
                    Entry previous = visible.get(timer.key());
                    if (!replay && previous != null && previous.timer.equals(timer) && elapsed < previous.nextUpdate) {
                        next.put(timer.key(), previous);
                        return;
                    }
                    int op = previous == null ? LiveWindowPayload.OP_CREATE : LiveWindowPayload.OP_UPDATE;
                    updates.add(format(timer, elapsed, wall, zone, op));
                    long left = timer.remaining(elapsed);
                    long due = Long.MAX_VALUE;
                    if (timer.phase() == Countdown.Phase.RUNNING) {
                        long delay = left < 30_000 ? left : Math.min(left, left >= 120_000 ? 60_000 : 30_000);
                        due = elapsed + delay;
                    }
                    next.put(timer.key(), new Entry(timer, due));
                });
        List<LiveWindowPayload> result = new ArrayList<>();
        for (String key : visible.keySet()) {
            if (!next.containsKey(key)) result.add(close(key));
        }
        visible = next;
        result.addAll(updates);
        return result;
    }

    public long nextUpdate() {
        return visible.values().stream().mapToLong(Entry::nextUpdate).min().orElse(Long.MAX_VALUE);
    }

    public static LiveWindowPayload close(String key) {
        return new LiveWindowPayload(LiveWindowPayload.SOURCE_CLOCK_TIMER, key, "com.coloros.alarmclock",
                "倒计时", "", "倒计时", "", LiveWindowPayload.EVENT_PICK_UP, 0, LiveWindowPayload.OP_CLOSE);
    }

    static LiveWindowPayload format(Countdown timer, long elapsed, long wall, ZoneId zone, int op) {
        long left = timer.remaining(elapsed);
        long seconds = (left + 999) / 1_000;
        String text, capsule;
        if (timer.phase() == Countdown.Phase.PAUSED) {
            capsule = "已暂停";
            text = capsule + " · 剩余 " + String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        } else {
            ZonedDateTime now = Instant.ofEpochMilli(wall).atZone(zone);
            ZonedDateTime end = Instant.ofEpochMilli(wall + left).atZone(zone);
            long days = end.toLocalDate().toEpochDay() - now.toLocalDate().toEpochDay();
            String day = days == 0 ? "" : days == 1 ? "明日 " : end.format(DateTimeFormatter.ofPattern("MM-dd "));
            String time = day + end.format(DateTimeFormatter.ofPattern(left < 60_000 ? "HH:mm:ss" : "HH:mm"));
            capsule = time + " 结束";
            text = left < 60_000 ? "即将结束 · 预计 " + time : "预计 " + capsule + " · 剩 " + ((seconds + 59) / 60) + " 分钟";
        }
        int progress = timer.duration() <= 0 ? 0
                : (int) Math.max(0, Math.min(99, 100.0 * (timer.duration() - left) / timer.duration()));
        return new LiveWindowPayload(LiveWindowPayload.SOURCE_CLOCK_TIMER, timer.key(), "com.coloros.alarmclock",
                "倒计时 · " + durationText(timer.duration()), text, "倒计时", capsule, LiveWindowPayload.EVENT_PICK_UP, progress, op);
    }

    private static String durationText(long ms) {
        long seconds = (ms + 999) / 1_000;
        List<String> parts = new ArrayList<>(3);
        if (seconds >= 3600) parts.add(seconds / 3600 + " 小时");
        if (seconds / 60 % 60 > 0) parts.add(seconds / 60 % 60 + " 分钟");
        if (seconds % 60 > 0 || parts.isEmpty()) parts.add(seconds % 60 + " 秒");
        return String.join(" ", parts);
    }
}
