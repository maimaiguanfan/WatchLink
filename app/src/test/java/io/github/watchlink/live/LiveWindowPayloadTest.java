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
        assertEquals("取餐码 A739 · 约2分钟", root.get("notification.live.contentOverlay"));

        @SuppressWarnings("unchecked")
        Map<String, Object> feature = (Map<String, Object>) root.get("notification.live.feature");
        assertNotNull(feature);
        assertEquals("库迪咖啡", feature.get("notification.live.feature.titleText"));
        assertEquals("取餐码 A739", feature.get("notification.live.feature.contentText"));

        @SuppressWarnings("unchecked")
        Map<String, Object> capsule = (Map<String, Object>) root.get("notification.live.capsule");
        assertNotNull(capsule);
        assertEquals(1L, capsule.get("notification.live.capsuleStatus"));
        assertEquals(Boolean.FALSE, capsule.get("notification.live.capsuleRemind"));

        // Close sets capsuleStatus=0 and op=2.
        Map<String, Object> closeRoot = SimpleJson.parseObject(p.withOperation(LiveWindowPayload.OP_CLOSE).toSmartEdgeJson());
        assertNotNull(closeRoot);
        assertEquals(2L, closeRoot.get("notification.live.operation"));
        @SuppressWarnings("unchecked")
        Map<String, Object> closeCap = (Map<String, Object>) closeRoot.get("notification.live.capsule");
        assertEquals(0L, closeCap.get("notification.live.capsuleStatus"));
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
