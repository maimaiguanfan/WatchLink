package io.github.watchlink.live;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameReviveCoordinatorTest {
    private static LiveWindowPayload gameCard(String key, String title, String text, String capContent) {
        return new LiveWindowPayload(
                LiveWindowPayload.SOURCE_UMS,
                LiveWindowPayload.GAME_FLASHBACK_KEY_PREFIX + key,
                "com.tencent.tmgp.sgame",
                title,
                text,
                "复活",
                capContent,
                LiveWindowPayload.EVENT_QUEUE,
                -1,
                LiveWindowPayload.OP_CREATE);
    }

    @Test public void parsesSecondsAndCompletionPhrases() {
        assertEquals(28, GameReviveCoordinator.parseRemainingSeconds("复活 28秒"));
        assertEquals(15, GameReviveCoordinator.parseRemainingSeconds("复活倒计时 15s"));
        assertEquals(5, GameReviveCoordinator.parseRemainingSeconds("还有 5 秒复活"));
        assertEquals(78, GameReviveCoordinator.parseRemainingSeconds("01:18"));
        assertEquals(-1, GameReviveCoordinator.parseRemainingSeconds("匹配成功"));

        assertTrue(GameReviveCoordinator.isCompletionText("已复活"));
        assertTrue(GameReviveCoordinator.isCompletionText("准备起飞"));
        assertFalse(GameReviveCoordinator.isCompletionText("复活 5秒"));
    }

    @Test public void firesExactlyOnceAtCustomLeadSecBeforeRevive() {
        GameReviveCoordinator coord = new GameReviveCoordinator();
        long t0 = 10_000L;

        // 30s revive with user leadSec = 5s -> scheduled at t0 + 25_000ms.
        GameReviveCoordinator.UpsertPlan p1 = coord.onUpsert(gameCard("ums:g1", "复活 30秒", "复活 30秒", "30秒"), t0, 5);
        assertNull(p1.immediateAlert());
        assertEquals(Long.valueOf(t0 + 25_000L), p1.scheduleAtElapsedMs());

        // Countdown reaches 5s -> triggers immediately once, preserving countdown card (completionAlert=false).
        GameReviveCoordinator.UpsertPlan p2 = coord.onUpsert(gameCard("ums:g1", "复活 5秒", "复活 5秒", "5秒"), t0 + 25_000L, 5);
        assertNotNull(p2.immediateAlert());
        assertFalse(p2.immediateAlert().completionAlert());
        assertEquals(5, p2.immediateAlert().remainingSec());
        assertTrue(WatchLiveTextFormatter.halfCells(p2.immediateAlert().title()) <= WatchLiveTextFormatter.MAX_WATCH_HALF_CELLS);

        // Subsequent ticks 4s -> 1s -> 0s and final close must NOT fire again.
        assertNull(coord.onUpsert(gameCard("ums:g1", "复活 3秒", "复活 3秒", "3秒"), t0 + 27_000L, 5).immediateAlert());
        assertNull(coord.onUpsert(gameCard("ums:g1", "已复活", "已复活", "已复活"), t0 + 30_000L, 5).immediateAlert());
        assertNull(coord.onRemove(LiveWindowPayload.GAME_FLASHBACK_KEY_PREFIX + "ums:g1", t0 + 30_200L, 5));
        assertEquals(0, coord.trackedCount());
    }

    @Test public void timerCallbackFiresWhenUpdatesDropAndIgnoresStaleGenerations() {
        GameReviveCoordinator coord = new GameReviveCoordinator();
        long t0 = 20_000L;

        GameReviveCoordinator.UpsertPlan p1 = coord.onUpsert(gameCard("ums:g2", "起飞 20秒", "起飞 20秒", "20秒"), t0, 8);
        assertEquals(Long.valueOf(t0 + 12_000L), p1.scheduleAtElapsedMs());

        // Second frame updates schedule and advances generation.
        GameReviveCoordinator.UpsertPlan p2 = coord.onUpsert(gameCard("ums:g2", "起飞 18秒", "起飞 18秒", "18秒"), t0 + 2_000L, 8);
        assertTrue(p2.generation() > p1.generation());
        assertNull(coord.onTimerFired(LiveWindowPayload.GAME_FLASHBACK_KEY_PREFIX + "ums:g2", p1.generation(), t0 + 12_000L, 8));

        // Fresh generation fires at t0 + 12_000ms (8s before takeoff).
        GameReviveCoordinator.AlertAction fired = coord.onTimerFired(
                LiveWindowPayload.GAME_FLASHBACK_KEY_PREFIX + "ums:g2", p2.generation(), t0 + 12_000L, 8);
        assertNotNull(fired);
        assertFalse(fired.completionAlert());
        assertEquals(8, fired.remainingSec());
        assertTrue(fired.text().contains("起飞"));
    }

    @Test public void zeroLeadSecFiresOnCompletionOrEdgeCloseOnly() {
        GameReviveCoordinator coord = new GameReviveCoordinator();
        long t0 = 50_000L;
        String key = LiveWindowPayload.GAME_FLASHBACK_KEY_PREFIX + "ums:g3";

        GameReviveCoordinator.UpsertPlan p1 = coord.onUpsert(gameCard("ums:g3", "复活 12秒", "复活 12秒", "12秒"), t0, 0);
        assertEquals(Long.valueOf(t0 + 12_000L), p1.scheduleAtElapsedMs());

        // Card closes 1s before predicted end (within edge tolerance) -> fires completion alert.
        GameReviveCoordinator.AlertAction edge = coord.onRemove(key, t0 + 11_000L, 0);
        assertNotNull(edge);
        assertTrue(edge.completionAlert());
        assertEquals(0, edge.remainingSec());
    }

    @Test public void earlyReturnToGameCancelsWithoutFalseAlert() {
        GameReviveCoordinator coord = new GameReviveCoordinator();
        long t0 = 100_000L;
        String key = LiveWindowPayload.GAME_FLASHBACK_KEY_PREFIX + "ums:g4";

        coord.onUpsert(gameCard("ums:g4", "复活 25秒", "复活 25秒", "25秒"), t0, 5);
        // Player switches back into game after 3 seconds (22s still remaining).
        assertNull(coord.onRemove(key, t0 + 3_000L, 5));
        assertEquals(0, coord.trackedCount());

        // Also test leadSec == 0 with early return (10s remaining -> outside edge tolerance -> null).
        coord.onUpsert(gameCard("ums:g4", "复活 25秒", "复活 25秒", "25秒"), t0, 0);
        assertNull(coord.onRemove(key, t0 + 15_000L, 0));
    }

    @Test public void leadSecGreaterThanReviveSecondsFiresImmediatelyOnce() {
        GameReviveCoordinator coord = new GameReviveCoordinator();
        long t0 = 200_000L;
        String key = LiveWindowPayload.GAME_FLASHBACK_KEY_PREFIX + "ums:short";

        // 4s early-game revive when user set leadSec = 10s -> fires immediately once.
        GameReviveCoordinator.UpsertPlan p1 = coord.onUpsert(gameCard("ums:short", "复活 4秒", "复活 4秒", "4秒"), t0, 10);
        assertNotNull(p1.immediateAlert());
        assertFalse(p1.immediateAlert().completionAlert());
        assertEquals(4, p1.immediateAlert().remainingSec());
        assertNull(p1.scheduleAtElapsedMs());

        // Subsequent ticks and completion must NOT fire a second time.
        assertNull(coord.onUpsert(gameCard("ums:short", "复活 2秒", "复活 2秒", "2秒"), t0 + 2_000L, 10).immediateAlert());
        assertNull(coord.onUpsert(gameCard("ums:short", "已复活", "已复活", "已复活"), t0 + 4_000L, 10).immediateAlert());
        assertNull(coord.onRemove(key, t0 + 4_100L, 10));
    }

    @Test public void changingLeadSecLiveReschedulesOrTriggersImmediately() {
        GameReviveCoordinator coord = new GameReviveCoordinator();
        long t0 = 300_000L;
        String key = LiveWindowPayload.GAME_FLASHBACK_KEY_PREFIX + "ums:live";

        coord.onUpsert(gameCard("ums:live", "复活 12秒", "复活 12秒", "12秒"), t0, 3);
        // After 4s (8s remaining), user changes leadSec from 3s to 10s -> triggers immediately.
        Map<String, GameReviveCoordinator.UpsertPlan> plans = coord.onLeadSecChanged(t0 + 4_000L, 10);
        assertTrue(plans.containsKey(key));
        assertNotNull(plans.get(key).immediateAlert());
        assertEquals(8, plans.get(key).immediateAlert().remainingSec());
    }
}
