package io.github.watchlink.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.watchlink.alarm.probe.TargetProfile;
import java.util.List;
import org.junit.Test;

public class DisconnectNotificationQueueTest {

    @Test
    public void singleMessageVibratesNormally() {
        DisconnectNotificationQueue<String> q = new DisconnectNotificationQueue<>();
        q.stage("k1", "msg1", 10_000L, false);

        List<DisconnectNotificationQueue.DeliveryItem<String>> out = q.drainForDelivery(25_000L);
        assertEquals(1, out.size());
        assertEquals("msg1", out.get(0).payload());
        assertFalse(out.get(0).forceSilent());
        assertTrue(q.isEmpty(25_000L));
    }

    @Test
    public void multipleMessagesOnlyNewestVibratesOthersForcedSilent() {
        DisconnectNotificationQueue<String> q = new DisconnectNotificationQueue<>();
        q.stage("k1", "msg1", 1_000L, false);
        q.stage("k2", "msg2", 2_000L, false);
        q.stage("k3", "msg3", 3_000L, false);

        List<DisconnectNotificationQueue.DeliveryItem<String>> out = q.drainForDelivery(20_000L);
        assertEquals(3, out.size());
        assertEquals("msg1", out.get(0).payload());
        assertTrue(out.get(0).forceSilent());
        assertEquals("msg2", out.get(1).payload());
        assertTrue(out.get(1).forceSilent());
        assertEquals("msg3", out.get(2).payload());
        assertFalse(out.get(2).forceSilent());
    }

    @Test
    public void maxFiveMessagesEvictsOldestAndKeepsLatestFive() {
        DisconnectNotificationQueue<String> q = new DisconnectNotificationQueue<>();
        for (int i = 1; i <= 7; i++) {
            q.stage("k" + i, "msg" + i, i * 1_000L, false);
        }

        List<DisconnectNotificationQueue.DeliveryItem<String>> out = q.drainForDelivery(10_000L);
        assertEquals(5, out.size());
        assertEquals("msg3", out.get(0).payload());
        assertEquals("msg4", out.get(1).payload());
        assertEquals("msg5", out.get(2).payload());
        assertEquals("msg6", out.get(3).payload());
        assertEquals("msg7", out.get(4).payload());
        for (int i = 0; i < 4; i++) {
            assertTrue("Expected silent for index " + i, out.get(i).forceSilent());
        }
        assertFalse(out.get(4).forceSilent());
    }

    @Test
    public void ttlOneMinuteExpiresStaleMessages() {
        DisconnectNotificationQueue<String> q = new DisconnectNotificationQueue<>();
        q.stage("k1", "old1", 1_000L, false);
        q.stage("k2", "fresh2", 50_000L, false);

        // At t = 61_001ms: k1 age is 60_001ms (> 60_000ms expired), k2 age is 11_001ms (fresh).
        List<DisconnectNotificationQueue.DeliveryItem<String>> out = q.drainForDelivery(61_001L);
        assertEquals(1, out.size());
        assertEquals("fresh2", out.get(0).payload());
        assertFalse(out.get(0).forceSilent());
    }

    @Test
    public void exactOneMinuteBoundaryStillValid() {
        DisconnectNotificationQueue<String> q = new DisconnectNotificationQueue<>();
        q.stage("k1", "boundary", 10_000L, false);

        List<DisconnectNotificationQueue.DeliveryItem<String>> out = q.drainForDelivery(70_000L);
        assertEquals(1, out.size());
        assertEquals("boundary", out.get(0).payload());
    }

    @Test
    public void duplicateKeyReplacesAndMovesToTail() {
        DisconnectNotificationQueue<String> q = new DisconnectNotificationQueue<>();
        q.stage("k1", "v1", 1_000L, false);
        q.stage("k2", "other", 2_000L, false);
        q.stage("k1", "v2", 5_000L, false);

        List<DisconnectNotificationQueue.DeliveryItem<String>> out = q.drainForDelivery(10_000L);
        assertEquals(2, out.size());
        assertEquals("other", out.get(0).payload());
        assertTrue(out.get(0).forceSilent());
        assertEquals("v2", out.get(1).payload());
        assertFalse(out.get(1).forceSilent());
    }

    @Test
    public void removeByKeyDropsDismissedNotification() {
        DisconnectNotificationQueue<String> q = new DisconnectNotificationQueue<>();
        q.stage("k1", "msg1", 1_000L, false);
        q.stage("k2", "msg2", 2_000L, false);
        assertTrue(q.removeByKey("k2"));
        assertFalse(q.removeByKey("k999"));

        List<DisconnectNotificationQueue.DeliveryItem<String>> out = q.drainForDelivery(10_000L);
        assertEquals(1, out.size());
        assertEquals("msg1", out.get(0).payload());
        assertFalse(out.get(0).forceSilent());
    }

    @Test
    public void originallySilentItemsDoNotStealSingleVibrateSlot() {
        DisconnectNotificationQueue<String> q = new DisconnectNotificationQueue<>();
        q.stage("k1", "normal1", 1_000L, false);
        q.stage("k2", "normal2", 2_000L, false);
        q.stage("k3", "silent3", 3_000L, true);

        List<DisconnectNotificationQueue.DeliveryItem<String>> out = q.drainForDelivery(10_000L);
        assertEquals(3, out.size());
        assertTrue(out.get(0).forceSilent());   // normal1 -> forced silent
        assertFalse(out.get(1).forceSilent());  // normal2 -> single vibrating push
        assertTrue(out.get(2).forceSilent());   // silent3 -> remains silent
    }

    @Test
    public void allOriginallySilentProducesZeroVibratingItems() {
        DisconnectNotificationQueue<String> q = new DisconnectNotificationQueue<>();
        q.stage("k1", "s1", 1_000L, true);
        q.stage("k2", "s2", 2_000L, true);

        List<DisconnectNotificationQueue.DeliveryItem<String>> out = q.drainForDelivery(10_000L);
        assertEquals(2, out.size());
        assertTrue(out.get(0).forceSilent());
        assertTrue(out.get(1).forceSilent());
    }

    @Test
    public void restageIfFreshPreservesOriginalTtl() {
        DisconnectNotificationQueue<String> q = new DisconnectNotificationQueue<>();
        assertTrue(q.restageIfFresh("k1", "msg1", 10_000L, false, 40_000L));
        assertFalse(q.restageIfFresh("k2", "expired", 10_000L, false, 70_001L));
        // k1 was also pruned at 70_001L because its original timestamp was 10_000L!
        assertTrue(q.isEmpty(70_001L));
    }

    @Test
    public void eligibilityExcludesControlLiveCallAlarmAndClock() {
        assertTrue(DisconnectNotificationQueue.isEligibleForStaging(
                null, null, false, false, "com.tencent.mm", "msg"));
        assertFalse(DisconnectNotificationQueue.isEligibleForStaging(
                "notificationSwitchChangeType", null, false, false, "com.tencent.mm", "msg"));
        assertFalse(DisconnectNotificationQueue.isEligibleForStaging(
                null, "LIVE_NOTIFICATION_PUSH", false, false, "com.sankuai.meituan", "msg"));
        assertFalse(DisconnectNotificationQueue.isEligibleForStaging(
                null, null, true, false, "com.tencent.mm", "msg"));
        assertFalse(DisconnectNotificationQueue.isEligibleForStaging(
                null, null, false, true, "com.whatsapp", "msg"));
        assertFalse(DisconnectNotificationQueue.isEligibleForStaging(
                null, null, false, false, "com.tencent.mm", "call"));
        assertFalse(DisconnectNotificationQueue.isEligibleForStaging(
                null, null, false, false, "com.tencent.mm", "imcall"));
        assertFalse(DisconnectNotificationQueue.isEligibleForStaging(
                null, null, false, false, "com.tencent.mm", "alarm"));
        assertFalse(DisconnectNotificationQueue.isEligibleForStaging(
                null, null, false, false, TargetProfile.CLOCK.packageName, "msg"));
    }

    @Test
    public void dedupKeyPrefersNotyKeyThenAltKeyThenContentFallback() {
        assertEquals("sbn|1", DisconnectNotificationQueue.dedupKey("sbn|1", "alt", "pkg", "t", "b"));
        assertEquals("alt", DisconnectNotificationQueue.dedupKey("", "alt", "pkg", "t", "b"));
        assertEquals("pkg|t|b", DisconnectNotificationQueue.dedupKey(null, null, "pkg", "t", "b"));
    }
}
