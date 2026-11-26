package io.github.watchlink.live;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveWindowPayloadTest {
    @Test public void encodesDeliveryProgressPayloadAsType3SmartEdgeJson() {
        LiveWindowPayload p = new LiveWindowPayload(
                LiveWindowPayload.SOURCE_UMS,
                "ums:268451900:order-1",
                "com.sankuai.meituan",
                "骑手正在配送",
                "预计 10 分钟送达",
                "美团外卖",
                "预计10分钟",
                LiveWindowPayload.EVENT_DELIVERY,
                35,
                LiveWindowPayload.OP_CREATE);
        String json = p.toSmartEdgeJson();
        Map<String, Object> root = SimpleJson.parseObject(json);
        assertNotNull(root);
        assertEquals("com.sankuai.meituan", root.get("packageName"));
        assertEquals(0L, root.get("notification.live.operation"));
        assertEquals(3L, root.get("notification.live.type"));
        assertEquals("DELIVERY", root.get("notification.live.event"));
        assertEquals("骑手正在配送", root.get("notification.live.titleOverlay"));
        assertEquals("预计 10 分钟送达", root.get("notification.live.contentOverlay"));

        @SuppressWarnings("unchecked")
        Map<String, Object> feature = (Map<String, Object>) root.get("notification.live.feature");
        assertNotNull(feature);
        assertEquals(0L, feature.get("notification.live.feature.progressType"));
        assertEquals(1L, feature.get("notification.live.feature.indicatorType"));
        assertEquals(35L, feature.get("notification.live.feature.progress"));

        @SuppressWarnings("unchecked")
        Map<String, Object> capsule = (Map<String, Object>) root.get("notification.live.capsule");
        assertNotNull(capsule);
        assertEquals(1L, capsule.get("notification.live.capsuleStatus"));
        assertEquals(Boolean.TRUE, capsule.get("notification.live.capsuleRemind"));
        assertEquals("美团外卖", capsule.get("notification.live.capsuleTitle"));
        assertEquals("预计10分钟", capsule.get("notification.live.capsuleContent"));
    }

    @Test public void encodesNoProgressPickupCardAsType1WithTextFeatureAndNeverEmitsOther() {
        LiveWindowPayload p = new LiveWindowPayload(
                LiveWindowPayload.SOURCE_UMS,
                "ums:268451931:inst-9",
                "com.tencent.mm",
                "库迪咖啡",
                "取餐码 A739 · 约2分钟",
                "库迪咖啡",
                "取餐码 A739",
                "OTHER", // WATCH 6 Pro drops OTHER; must normalize to PICK_UP!
                -1,
                LiveWindowPayload.OP_UPDATE);
        assertEquals(LiveWindowPayload.EVENT_PICK_UP, p.eventType());

        Map<String, Object> root = SimpleJson.parseObject(p.toSmartEdgeJson());
        assertNotNull(root);
        assertEquals(1L, root.get("notification.live.type"));
        assertEquals("PICK_UP", root.get("notification.live.event"));
        assertEquals("库迪咖啡", root.get("notification.live.titleOverlay"));
        assertEquals("A739 约2分钟", root.get("notification.live.contentOverlay"));

        @SuppressWarnings("unchecked")
        Map<String, Object> feature = (Map<String, Object>) root.get("notification.live.feature");
        assertNotNull(feature);
        assertEquals("库迪咖啡", feature.get("notification.live.feature.titleText"));
        assertEquals("A739", feature.get("notification.live.feature.contentText"));

        @SuppressWarnings("unchecked")
        Map<String, Object> capsule = (Map<String, Object>) root.get("notification.live.capsule");
        assertNotNull(capsule);
        assertEquals(1L, capsule.get("notification.live.capsuleStatus"));
        assertEquals(Boolean.FALSE, capsule.get("notification.live.capsuleRemind"));
        assertEquals("库迪咖啡", capsule.get("notification.live.capsuleTitle"));
        assertEquals("A739", capsule.get("notification.live.capsuleContent"));

        // Close sets capsuleStatus=0 and op=2.
        Map<String, Object> closeRoot = SimpleJson.parseObject(p.withOperation(LiveWindowPayload.OP_CLOSE).toSmartEdgeJson());
        assertNotNull(closeRoot);
        assertEquals(2L, closeRoot.get("notification.live.operation"));
        @SuppressWarnings("unchecked")
        Map<String, Object> closeCap = (Map<String, Object>) closeRoot.get("notification.live.capsule");
        assertEquals(0L, closeCap.get("notification.live.capsuleStatus"));
        assertEquals(Boolean.FALSE, closeCap.get("notification.live.capsuleRemind"));
    }

    @Test public void compactsPickupLinesAndCapsulesToTenWatchCellsPreservingCode() {
        // Real measured watch truncation baseline: space = 0.5 cell, CJK/ASCII = 1.0 cell, limit = 10.0 cells (20 half-cells).
        assertEquals(20, WatchLiveTextFormatter.halfCells("卡布奇诺 取餐码 A6"));

        // Exact reported case: "卡布奇诺 取餐码 A633" -> removes "取餐码" and fits in 8.5 cells.
        assertEquals("卡布奇诺 A633", WatchLiveTextFormatter.compactPickupLine("卡布奇诺 取餐码 A633"));
        assertTrue(WatchLiveTextFormatter.halfCells("卡布奇诺 A633") <= 20);

        // Long product name must be ellipsized while keeping pickup code A633 intact within 10.0 cells.
        String longProduct = WatchLiveTextFormatter.compactPickupLine("超级大杯冰生椰拿铁少冰 取餐码 A633");
        assertEquals("超级大杯… A633", longProduct);
        assertTrue(WatchLiveTextFormatter.halfCells(longProduct) <= 20);
        assertTrue(longProduct.endsWith("A633"));

        // Combined capsuleTitle + " " + capsuleContent must also stay within 10.0 cells with code intact.
        LiveWindowPayload longCap = new LiveWindowPayload(
                LiveWindowPayload.SOURCE_UMS, "ums:1:cap", "com.tencent.mm",
                "生椰拿铁大杯少冰无糖", "生椰拿铁大杯少冰无糖 取餐码 A633",
                "生椰拿铁大杯少冰无糖", "取餐码 A633",
                LiveWindowPayload.EVENT_PICK_UP, -1, LiveWindowPayload.OP_CREATE);
        assertEquals("生椰拿铁…", longCap.capsuleTitle());
        assertEquals("A633", longCap.capsuleContent());
        assertTrue(WatchLiveTextFormatter.halfCells(longCap.capsuleTitle() + " " + longCap.capsuleContent()) <= 20);
        assertEquals("生椰拿铁… A633", longCap.text());

        // Very long code leaves no room for product name: drop product name, preserve code intact.
        WatchLiveTextFormatter.Pair longCodePair = WatchLiveTextFormatter.compactPickupCapsule("卡布奇诺", "取餐码 ABCD12345");
        assertEquals("", longCodePair.title());
        assertEquals("ABCD12345", longCodePair.content());

        // Express parcel pickup code & travel/movie/game 10-cell formatters.
        // "菜鸟驿站 1-2-34" would be 10.5 cells (21 half-cells) -> ellipsizes prefix to protect full code "1-2-34"!
        String parcel = WatchLiveTextFormatter.compactPickupLine("菜鸟驿站 驿站取件码 1-2-34");
        assertEquals("菜鸟… 1-2-34", parcel);
        assertTrue(WatchLiveTextFormatter.halfCells(parcel) <= 20);
        assertEquals("驿站 1-2-34", WatchLiveTextFormatter.compactPickupLine("驿站 驿站取件码 1-2-34"));
        assertTrue(WatchLiveTextFormatter.halfCells(WatchLiveTextFormatter.compactFlightLine("CA1234", "B12", "登机中")) <= 20);
        assertTrue(WatchLiveTextFormatter.halfCells(WatchLiveTextFormatter.compactTrainLine("G123", "检5B", "检票中")) <= 20);
        assertTrue(WatchLiveTextFormatter.halfCells(WatchLiveTextFormatter.compactMovieLine("流浪地球3超长副标题", "842190", "")) <= 20);
        assertEquals("复活 28秒", WatchLiveTextFormatter.compactGameFlashback("复活", 28, ""));
    }

    @Test public void explicitRemindOverrideAllowsUpdateVibrationButNeverOnClose() {
        LiveWindowPayload update = new LiveWindowPayload(
                LiveWindowPayload.SOURCE_UMS, LiveWindowPayload.GAME_FLASHBACK_KEY_PREFIX + "ums:g", "com.tencent.tmgp.sgame",
                "即将复活 5秒", "还有5秒复活", "即将复活", "5秒",
                LiveWindowPayload.EVENT_QUEUE, -1, LiveWindowPayload.OP_UPDATE);
        assertTrue(update.isGameFlashback());
        @SuppressWarnings("unchecked")
        Map<String, Object> cap = (Map<String, Object>) SimpleJson.parseObject(update.toSmartEdgeJson(true)).get("notification.live.capsule");
        assertEquals(Boolean.TRUE, cap.get("notification.live.capsuleRemind"));

        @SuppressWarnings("unchecked")
        Map<String, Object> closeCap = (Map<String, Object>) SimpleJson.parseObject(
                update.withOperation(LiveWindowPayload.OP_CLOSE).toSmartEdgeJson(true)).get("notification.live.capsule");
        assertEquals(Boolean.FALSE, closeCap.get("notification.live.capsuleRemind"));
    }

    @Test public void deterministicIdAndRedactedToString() {
        int id1 = LiveWindowPayload.stableNotificationId("ums:268451931:abc");
        int id2 = LiveWindowPayload.stableNotificationId("ums:268451931:abc");
        int id3 = LiveWindowPayload.stableNotificationId("ums:268451931:def");
        assertEquals(id1, id2);
        assertNotEquals(id1, id3);
        assertTrue((id1 & 0x40000000) != 0);

        LiveWindowPayload p = new LiveWindowPayload(
                LiveWindowPayload.SOURCE_UMS, "ums:1:secret-id", "com.tencent.mm",
                "私密商家名", "取餐码 Z999", "私密", "Z999",
                LiveWindowPayload.EVENT_PICK_UP, -1, LiveWindowPayload.OP_CREATE);
        String repr = p.toString();
        assertFalse(repr.contains("私密"));
        assertFalse(repr.contains("Z999"));
        assertFalse(repr.contains("secret-id"));
    }
}
