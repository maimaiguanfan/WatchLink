package io.github.watchlink.timer;

import io.github.watchlink.live.LiveWindowPayload;
import io.github.watchlink.live.LiveWindowTracker;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TimerCardsTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long WALL = Instant.parse("2026-11-26T15:59:00Z").toEpochMilli();
    private static final long NOW = 10_000;
    private final TimerCards cards = new TimerCards();

    private static Countdown running(long id, long left) {
        return new Countdown(id, 100, 600_000, Countdown.Phase.RUNNING, NOW + left);
    }
    private List<LiveWindowPayload> sync(long advance, Countdown... timers) {
        return cards.reconcile(List.of(timers), NOW + advance, WALL + advance, ZONE, false);
    }

    @Test public void formatsMonotonicDeadlineAndCrossesMidnight() {
        var payload = sync(0, running(4, 300_000)).get(0);
        assertEquals("倒计时 · 10 分钟", payload.title());
        assertEquals("预计 明日 00:04 结束 · 剩 5 分钟", payload.text());
        assertEquals(50, payload.progress());
        assertEquals("clock-timer:4:100", payload.stableKey());
        assertFalse(payload.toString().contains(payload.text()));
        assertEquals("即将结束 · 预计 23:59:29", sync(0, running(4, 29_000)).get(0).text());
    }

    @Test public void progressBoundsAndSubminuteTimers() {
        var shortTimer = new Countdown(1, 100, 14_000, Countdown.Phase.RUNNING, NOW + 14_000);
        var p = sync(0, shortTimer).get(0);
        assertEquals(0, p.progress());
        assertTrue(p.title().contains("14 秒"));
        assertEquals(NOW + 14_000, cards.nextUpdate());
        assertTrue(sync(200, shortTimer).isEmpty());
        assertEquals(99, TimerCards.format(shortTimer, NOW + 13_999, WALL, ZONE, 1).progress());
        assertTrue(sync(14_000, shortTimer).get(0).operation() == LiveWindowPayload.OP_CLOSE);
        assertTrue(sync(0, new Countdown(1, 100, 0, Countdown.Phase.PAUSED, 0)).isEmpty());
    }

    @Test public void periodicUpdatesAreInfrequentAndPhaseChangesImmediate() {
        Countdown longTimer = running(1, 450_000);
        sync(0, longTimer);
        assertEquals(NOW + 60_000, cards.nextUpdate());
        assertTrue(sync(59_999, longTimer).isEmpty());
        assertEquals(1, sync(60_000, longTimer).size());
        var paused = new Countdown(1, 100, 600_000, Countdown.Phase.PAUSED, 200_000);
        var change = sync(60_001, paused).get(0);
        assertEquals("已暂停 · 剩余 03:20", change.text());
        assertEquals(Long.MAX_VALUE, cards.nextUpdate());
        assertTrue(sync(600_000, paused).isEmpty());
        assertEquals(1, sync(600_001, running(1, 700_001)).size());
        assertEquals(NOW + 630_001, cards.nextUpdate());
    }

    @Test public void threeEarliestRunningTimersTakePrecedenceAndRemovedCardClosesBeforePromotion() {
        var paused = new Countdown(4, 100, 600_000, Countdown.Phase.PAUSED, 60_000);
        var a = running(1, 200_000); var b = running(2, 80_000); var c = running(3, 110_000);
        var first = sync(0, paused, a, b, c);
        assertEquals(List.of(b.key(), c.key(), a.key()), first.stream().map(LiveWindowPayload::stableKey).toList());
        var changes = sync(1, paused, a, c);
        assertEquals(List.of(b.key(), paused.key()), changes.stream().map(LiveWindowPayload::stableKey).toList());
        assertEquals(LiveWindowPayload.OP_CLOSE, changes.get(0).operation());
        assertEquals(LiveWindowPayload.OP_CREATE, changes.get(1).operation());
        assertEquals(3, cards.reconcile(List.of(paused, a, c), NOW, WALL, ZONE, true).size());
    }

    @Test public void orphanedPauseRecreatesOnResumeAndQuickUpdatesStaySilent() {
        var tracker = new LiveWindowTracker();
        var paused = new Countdown(1, 100, 600_000, Countdown.Phase.PAUSED, 200_000);
        var create = tracker.onUpsert(sync(0, running(1, 200_000)).get(0), NOW);
        assertTrue(create.toSmartEdgeJson().contains("\"notification.live.capsuleRemind\":false"));
        assertTrue(create.toSmartEdgeJson().contains("\"notification.live.type\":3"));
        var update = tracker.onUpsert(sync(1, paused).get(0), NOW + 1);
        assertEquals(LiveWindowPayload.OP_UPDATE, update.operation());
        assertTrue(update.toSmartEdgeJson().contains("\"notification.live.capsuleRemind\":false"));
        assertEquals(1, tracker.expireStale(NOW + 2_000_000, LiveWindowTracker.DEFAULT_ORPHAN_TIMEOUT_MS).size());
        var resumed = sync(2_000_000, running(1, 2_200_000)).get(0);
        assertEquals(LiveWindowPayload.OP_CREATE, tracker.onUpsert(resumed, NOW + 2_000_000).operation());
    }

    @Test public void switchingFeaturesOnlyClosesTheirOwnCards() {
        for (boolean timers : new boolean[]{false, true}) {
            var tracker = new LiveWindowTracker();
            var clock = cards.reconcile(List.of(running(1, 50_000)), NOW, WALL, ZONE, true).get(0);
            var delivery = new LiveWindowPayload("UMS", "ums:food", "example.food", "配送", "途中", "", "",
                    "DELIVERY", 1, LiveWindowPayload.OP_CREATE);
            tracker.onUpsert(clock, NOW);
            tracker.onUpsert(delivery, NOW);
            assertEquals(timers ? clock.stableKey() : delivery.stableKey(), tracker.closeSourceGroup(timers).get(0).stableKey());
            assertEquals(1, tracker.activeCount());
            assertTrue(tracker.isActive(timers ? delivery.stableKey() : clock.stableKey()));
        }
    }
}
