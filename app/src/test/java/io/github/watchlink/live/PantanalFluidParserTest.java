package io.github.watchlink.live;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class PantanalFluidParserTest {
    @Test public void parsesCottiCoffeeMiniProgramPickupCodeCard() {
        String bizJson = """
                {
                  "initData": "{\\"appName\\":\\"库迪咖啡\\",\\"pickupCode\\":\\"A739\\",\\"waitTime\\":\\"约3分钟\\",\\"launchPackage\\":\\"com.tencent.mm\\",\\"serviceInstanceId\\":\\"wx-cotti-1\\"}"
                }
                """;
        LiveWindowPayload p = PantanalFluidParser.parseDecisionRow(
                268451931L, "wx-cotti-1", "com.oplus.exsystemservice",
                "LIFE", "pickup", bizJson, "");
        assertNotNull(p);
        assertEquals(LiveWindowPayload.SOURCE_UMS, p.source());
        assertEquals("com.tencent.mm", p.packageName());
        assertEquals(LiveWindowPayload.EVENT_PICK_UP, p.eventType());
        assertEquals("库迪咖啡", p.title());
        assertEquals("A739 约3分钟", p.text());
        assertEquals("库迪咖啡", p.capsuleTitle());
        assertEquals("A739", p.capsuleContent());
        assertFalse(p.text().contains("取餐码"));
        assertFalse(p.capsuleContent().contains("取餐码"));
        assertEquals(-1, p.progress());
    }

    @Test public void excludesColorOsClockTimerAndStopwatchCards() {
        // Real verified serviceId for ColorOS 17 Clock Timer (268451854) & Stopwatch (268451943).
        assertNull(PantanalFluidParser.parseDecisionRow(
                268451854L, "timer-1", "com.coloros.alarmclock",
                "TOOL", "timer", "{\"timer_time\":600000,\"title\":\"倒计时\"}", ""));
        assertNull(PantanalFluidParser.parseDecisionRow(
                268451943L, "sw-1", "com.coloros.alarmclock",
                "TOOL", "stopwatch", "{\"stopwatch_time\":1234,\"title\":\"秒表\"}", ""));
        assertNull(PantanalFluidParser.parsePushData(
                20010, 1, "268451854", "clock_timer_card", "timer",
                "{\"ui_data\":{\"timer_runing\":true,\"title\":\"10:00\"}}", "", ""));
        // Channel A promoted notification from Clock is also excluded!
        assertNull(PantanalFluidParser.parsePromotedNotification(
                "0|com.coloros.alarmclock|1|timer|0", "com.coloros.alarmclock", "时钟",
                0x2, true, "android.app.Notification$ProgressStyle", null,
                "倒计时", "剩余 9 分 50 秒", "", "", "09:50", 10, 100, false));
    }

    @Test public void parsesMeituanElemePinHaoFanAndTaxiSchemas() {
        // Meituan
        LiveWindowPayload mt = PantanalFluidParser.parseDecisionRow(
                268451901L, "mt-1", "com.oplus.sceneservice", "LIFE", "takeout",
                "{\"launchPackage\":\"com.sankuai.meituan\",\"mainContent\":\"骑手已取餐\",\"subContent\":\"距您800米，预计8分钟\",\"capsuleContent1\":\"配送中\",\"capsuleContent2\":\"8分钟\",\"progress\":6,\"progressCount\":10}",
                "");
        assertNotNull(mt);
        assertEquals(LiveWindowPayload.EVENT_DELIVERY, mt.eventType());
        assertEquals("com.sankuai.meituan", mt.packageName());
        assertEquals("骑手已取餐", mt.title());
        assertEquals(60, mt.progress());
        assertEquals("8分钟", mt.capsuleContent());

        // Eleme
        LiveWindowPayload ele = PantanalFluidParser.parsePushData(
                30001, 77, "eleme_takeout", "takeout_card", "eleme",
                "{\"ui_data\":{\"launchPackage\":\"me.ele\",\"fluidTitle\":\"饿了么配送中\",\"fluidContent\":\"骑手正赶往您的位置\",\"capsuleTitle\":\"饿了么\",\"capsuleContent\":\"5分钟\",\"currentProgress\":75}}",
                "", "");
        assertNotNull(ele);
        assertEquals(LiveWindowPayload.EVENT_DELIVERY, ele.eventType());
        assertEquals("me.ele", ele.packageName());
        assertEquals(75, ele.progress());

        // PinHaoFan
        LiveWindowPayload phf = PantanalFluidParser.parseDecisionRow(
                268451902L, "phf-1", "com.oplus.sceneservice", "LIFE", "takeout",
                "{\"smallCardTitle\":\"拼好饭配送中\",\"smallCardDescription\":\"预计12分钟送达\",\"capsuleTextLeft\":\"拼好饭\",\"capsuleTextRight\":\"12分钟\",\"smallCardProgress\":40}",
                "");
        assertNotNull(phf);
        assertEquals(LiveWindowPayload.EVENT_DELIVERY, phf.eventType());
        assertEquals(40, phf.progress());

        // Taxi
        LiveWindowPayload taxi = PantanalFluidParser.parseDecisionRow(
                268451910L, "taxi-1", "com.oplus.sceneservice", "TRAVEL", "taxi",
                "{\"launchPackage\":\"com.sdu.didi.psnger\",\"plateNumber\":\"沪A·D12345\",\"statusText\":\"司机距您3分钟\"}",
                "");
        assertNotNull(taxi);
        assertEquals(LiveWindowPayload.EVENT_TAXI, taxi.eventType());
        assertEquals("com.sdu.didi.psnger", taxi.packageName());
        assertTrue(taxi.text().contains("沪A·D12345"));
    }

    @Test public void parsesChannelAPromotedNotificationAndSkipsOrdinaryOnes() {
        // Ordinary notification without promoted/ProgressStyle/oplusLiveAlert -> ignored (returns null).
        assertNull(PantanalFluidParser.parsePromotedNotification(
                "0|com.tencent.mm|1|msg|0", "com.tencent.mm", "微信",
                0, false, null, null, "好友", "你好", "", "", "", -1, 0, false));

        // Promoted ongoing delivery notification with ProgressStyle -> parsed!
        LiveWindowPayload live = PantanalFluidParser.parsePromotedNotification(
                "0|com.sankuai.meituan|9|order|0", "com.sankuai.meituan", "美团",
                0x2, true, "android.app.Notification$ProgressStyle", null,
                "美团外卖", "骑手正在配送，预计 5 分钟", "", "", "5分钟", 80, 100, false);
        assertNotNull(live);
        assertEquals(LiveWindowPayload.SOURCE_DAEMON, live.source());
        assertEquals(LiveWindowPayload.EVENT_DELIVERY, live.eventType());
        assertEquals(80, live.progress());
        assertEquals("美团", live.capsuleTitle());
        assertEquals("5分钟", live.capsuleContent());
    }

    @Test public void ignoresDeviceModesBookmarksAndUnknownActivitiesAcrossAllCapturePaths() {
        for (String title : List.of("已切换为响铃模式", "振动模式", "免打扰已开启", "已收藏：司机已到达",
                "外卖优惠券", "更新下载", "导航中")) {
            // A popular app, a live layout and even a progress bar are insufficient.
            String json = "{\"fluidTitle\":\"" + title + "\",\"fluidContent\":\"查看详情\",\"currentProgress\":25}";
            assertNull(title, PantanalFluidParser.parseDecisionRow(1, "1", "com.sankuai.meituan",
                    "", "", json, ""));
            assertNull(title, PantanalFluidParser.parsePushData(1, 1, "widget", "", "", json, "me.ele", ""));
            assertNull(title, PantanalFluidParser.parsePromotedNotification("n", "com.sdu.didi.psnger", "打车",
                    2, true, "ProgressStyle", null, title, "查看详情", "", "", "", 25, 100, false));
        }
        // A saved post can itself mention a pickup code or contain nested order information.
        assertNull(PantanalFluidParser.parseDecisionRow(1, "1", "com.oplus.pantanal.ums", "", "",
                "{\"title\":\"帖子已收藏\",\"pickupCode\":\"A1\"}", ""));
        assertNull(PantanalFluidParser.parsePromotedNotification("n", "com.heytap.speechassist", "小布助手",
                2, true, "", null, "取餐码 A1", "", "", "", "", 0, 0, false));
        assertNull(PantanalFluidParser.parsePushData(1, 1, "", "collection_card", "",
                "{\"mainContent\":\"骑手已到达\"}", "", ""));
        assertNull(PantanalFluidParser.parseDecisionRow(1, "1", "", "", "",
                "{\"orderCode\":\"x\",\"title\":\"订单优惠\"}", ""));
    }

    @Test public void acceptsSpecificEvidenceWithoutRelyingOnAnAppOrGenericLayout() {
        LiveWindowPayload pickup = PantanalFluidParser.parsePushData(1, 2, "", "", "",
                "{\"pickupCode\":103,\"appName\":\"咖啡店\"}", "", "");
        assertNotNull(pickup);
        assertEquals("103", pickup.capsuleContent());

        LiveWindowPayload cappuccino = PantanalFluidParser.parsePushData(1, 4, "", "", "",
                "{\"fluidTitle\":\"卡布奇诺 取餐码 A633\",\"fluidContent\":\"卡布奇诺 取餐码 A633\",\"capsuleTitle\":\"卡布奇诺\",\"capsuleContent\":\"取餐码 A633\"}",
                "com.tencent.mm", "");
        assertNotNull(cappuccino);
        assertEquals(LiveWindowPayload.EVENT_PICK_UP, cappuccino.eventType());
        assertEquals("卡布奇诺 A633", cappuccino.title());
        assertEquals("卡布奇诺 A633", cappuccino.text());
        assertEquals("卡布奇诺", cappuccino.capsuleTitle());
        assertEquals("A633", cappuccino.capsuleContent());

        LiveWindowPayload taxi = PantanalFluidParser.parsePushData(1, 3, "", "taxi_card", "",
                "{\"mainContent\":\"还有 3 分钟\"}", "", "");
        assertNotNull(taxi);
        assertEquals(LiveWindowPayload.EVENT_TAXI, taxi.eventType());

        LiveWindowPayload queue = PantanalFluidParser.parseDecisionRow(1, "q", "", "", "",
                "{\"queueNo\":7,\"waitTime\":\"约 10 分钟\"}", "");
        assertNotNull(queue);
        assertEquals(LiveWindowPayload.EVENT_QUEUE, queue.eventType());

        LiveWindowPayload nativePickup = PantanalFluidParser.parsePromotedNotification("n", "example.app", "咖啡店",
                2, true, "", null, "订单更新", "请到柜台", "", "", "取餐码 81", -1, 0, false);
        assertNotNull(nativePickup);
        assertEquals(LiveWindowPayload.EVENT_PICK_UP, nativePickup.eventType());
        LiveWindowPayload ride = PantanalFluidParser.parsePromotedNotification("ride", "example.app", "",
                2, true, "", null, "滴滴打车", "约 3 分钟", "", "", "", -1, 0, false);
        assertNotNull(ride);
        assertEquals(LiveWindowPayload.EVENT_TAXI, ride.eventType());
        assertNull(PantanalFluidParser.parsePromotedNotification("n", "example.app", "取餐码",
                2, true, "", null, "", "查看详情", "", "", "", -1, 0, false));
    }

    @Test public void nativeLiveStylesNeedAnOngoingEventUnlessAlreadyPromoted() {
        for (boolean vendorStyle : List.of(false, true)) {
            String template = vendorStyle ? null : "android.app.Notification$ProgressStyle";
            String config = vendorStyle ? "{}" : null;
            assertNull(PantanalFluidParser.parsePromotedNotification("n", "example.app", "外卖",
                    0, false, template, config, "配送中", "预计送达", "", "", "", 50, 100, false));
            assertNotNull(PantanalFluidParser.parsePromotedNotification("n", "example.app", "外卖",
                    2, false, template, config, "配送中", "预计送达", "", "", "", 50, 100, false));
        }
    }

    @Test public void trackerDeduplicatesThrottlesReconcilesAndExpiresOrphans() {
        LiveWindowTracker tracker = new LiveWindowTracker();
        LiveWindowPayload base = new LiveWindowPayload(
                LiveWindowPayload.SOURCE_UMS, "ums:1:a", "com.sankuai.meituan",
                "配送中", "预计10分钟", "美团", "10分钟",
                LiveWindowPayload.EVENT_DELIVERY, 30, LiveWindowPayload.OP_CREATE);

        LiveWindowPayload created = tracker.onUpsert(base, 1_000L);
        assertNotNull(created);
        assertEquals(LiveWindowPayload.OP_CREATE, created.operation());
        assertEquals(1, tracker.activeCount());

        // Identical duplicate suppressed.
        assertNull(tracker.onUpsert(base, 1_500L));

        // Minor text tweak within 2.5s throttled.
        LiveWindowPayload minorFast = new LiveWindowPayload(
                LiveWindowPayload.SOURCE_UMS, "ums:1:a", "com.sankuai.meituan",
                "配送中", "预计9分钟", "美团", "9分钟",
                LiveWindowPayload.EVENT_DELIVERY, 32, LiveWindowPayload.OP_CREATE);
        assertNull(tracker.onUpsert(minorFast, 2_000L));

        // Progress jump >= 5% bypasses throttle and emits OP_UPDATE.
        LiveWindowPayload major = new LiveWindowPayload(
                LiveWindowPayload.SOURCE_UMS, "ums:1:a", "com.sankuai.meituan",
                "配送中", "预计7分钟", "美团", "7分钟",
                LiveWindowPayload.EVENT_DELIVERY, 45, LiveWindowPayload.OP_CREATE);
        LiveWindowPayload updated = tracker.onUpsert(major, 2_200L);
        assertNotNull(updated);
        assertEquals(LiveWindowPayload.OP_UPDATE, updated.operation());
        assertEquals(45, updated.progress());

        // Reconcile when decision row disappears emits OP_CLOSE.
        List<LiveWindowPayload> closed = tracker.reconcilePrefix("ums:", Set.of());
        assertEquals(1, closed.size());
        assertEquals(LiveWindowPayload.OP_CLOSE, closed.get(0).operation());
        assertEquals(0, tracker.activeCount());

        // Orphan expiration closes stale entries after timeout.
        assertNotNull(tracker.onUpsert(base, 10_000L));
        assertTrue(tracker.expireStale(10_000L + 60_000L, LiveWindowTracker.DEFAULT_ORPHAN_TIMEOUT_MS).isEmpty());
        List<LiveWindowPayload> expired = tracker.expireStale(
                10_000L + LiveWindowTracker.DEFAULT_ORPHAN_TIMEOUT_MS, LiveWindowTracker.DEFAULT_ORPHAN_TIMEOUT_MS);
        assertEquals(1, expired.size());
        assertEquals(LiveWindowPayload.OP_CLOSE, expired.get(0).operation());
        assertEquals(0, tracker.activeCount());
    }

    @Test public void parsesFlightTrainExpressParcelMovieAndGameFlashbackWithinTenCells() {
        // Flight
        LiveWindowPayload flight = PantanalFluidParser.parseDecisionRow(
                268452100L, "fl-1", "com.oplus.sceneservice", "TRAVEL", "flight",
                "{\"flightNo\":\"CA1234\",\"boardingGate\":\"B12\",\"statusText\":\"正在登机\"}", "");
        assertNotNull(flight);
        assertFalse(flight.isGameFlashback());
        assertEquals(LiveWindowPayload.EVENT_TAXI, flight.eventType());
        assertEquals("CA1234 B12", flight.text());
        assertTrue(WatchLiveTextFormatter.halfCells(flight.text()) <= WatchLiveTextFormatter.MAX_WATCH_HALF_CELLS);

        // Train
        LiveWindowPayload train = PantanalFluidParser.parseDecisionRow(
                268452101L, "tr-1", "com.oplus.sceneservice", "TRAVEL", "train",
                "{\"trainNo\":\"G123\",\"checkPort\":\"5B\",\"statusText\":\"开始检票\"}", "");
        assertNotNull(train);
        assertEquals(LiveWindowPayload.EVENT_TAXI, train.eventType());
        assertEquals("G123 5B", train.text());
        assertTrue(WatchLiveTextFormatter.halfCells(train.text()) <= WatchLiveTextFormatter.MAX_WATCH_HALF_CELLS);

        // Express delivery in transit
        LiveWindowPayload express = PantanalFluidParser.parseDecisionRow(
                268452102L, "ex-1", "com.oplus.sceneservice", "LIFE", "express",
                "{\"fluidTitle\":\"顺丰快递派送中\",\"fluidContent\":\"快递员正在派件，预计30分钟送达\",\"capsuleTitle\":\"顺丰\",\"capsuleContent\":\"派件中\"}", "");
        assertNotNull(express);
        assertEquals(LiveWindowPayload.EVENT_DELIVERY, express.eventType());

        // Parcel pickup code strips boilerplate and protects code 1-2-3456
        LiveWindowPayload parcel = PantanalFluidParser.parseDecisionRow(
                268452103L, "pc-1", "com.oplus.sceneservice", "LIFE", "parcel_pickup",
                "{\"appName\":\"菜鸟驿站\",\"parcelCode\":\"1-2-3456\"}", "");
        assertNotNull(parcel);
        assertEquals(LiveWindowPayload.EVENT_PICK_UP, parcel.eventType());
        assertEquals("1-2-3456", parcel.capsuleContent());
        assertFalse(parcel.text().contains("取件码"));
        assertTrue(WatchLiveTextFormatter.halfCells(parcel.text()) <= WatchLiveTextFormatter.MAX_WATCH_HALF_CELLS);

        // Movie ticket
        LiveWindowPayload movie = PantanalFluidParser.parseDecisionRow(
                268452104L, "mv-1", "com.oplus.sceneservice", "LIFE", "movie",
                "{\"movieName\":\"流浪地球3\",\"ticketCode\":\"842190\"}", "");
        assertNotNull(movie);
        assertEquals(LiveWindowPayload.EVENT_PICK_UP, movie.eventType());
        assertEquals("842190", movie.capsuleContent());
        assertTrue(WatchLiveTextFormatter.halfCells(movie.text()) <= WatchLiveTextFormatter.MAX_WATCH_HALF_CELLS);

        // Game Flashback via UMS Push
        LiveWindowPayload gameUms = PantanalFluidParser.parsePushData(
                50001, 9, "game_flashback", "game_revive_card", "gamespace",
                "{\"launchPackage\":\"com.tencent.tmgp.sgame\",\"extra_title\":\"王者荣耀\",\"extra_content\":\"复活倒计时 28秒\"}",
                "com.oplus.games", "");
        assertNotNull(gameUms);
        assertTrue(gameUms.isGameFlashback());
        assertEquals(LiveWindowPayload.EVENT_QUEUE, gameUms.eventType());
        assertEquals("复活 28秒", gameUms.text());
        assertEquals("28秒", gameUms.capsuleContent());

        // Game Flashback via Channel A ongoing game assistant notification
        LiveWindowPayload gameDaemon = PantanalFluidParser.parsePromotedNotification(
                "0|com.oplus.games|8|fb|0", "com.oplus.games", "游戏助手",
                0x2, false, null, null,
                "和平精英", "起飞倒计时 15秒", "", "", "", -1, 0, false);
        assertNotNull(gameDaemon);
        assertTrue(gameDaemon.isGameFlashback());
        assertEquals(LiveWindowPayload.EVENT_QUEUE, gameDaemon.eventType());
        assertEquals("起飞 15秒", gameDaemon.text());
    }

    @Test public void trackerSeparatesGameFlashbackFromRegularLiveWindowsAndAllowsOneSecondTicks() {
        LiveWindowTracker tracker = new LiveWindowTracker();
        LiveWindowPayload regular = new LiveWindowPayload(
                LiveWindowPayload.SOURCE_UMS, "ums:1:r", "com.sankuai.meituan",
                "配送中", "预计10分钟", "美团", "10分钟",
                LiveWindowPayload.EVENT_DELIVERY, 30, LiveWindowPayload.OP_CREATE);
        LiveWindowPayload game1 = new LiveWindowPayload(
                LiveWindowPayload.SOURCE_UMS, LiveWindowPayload.GAME_FLASHBACK_KEY_PREFIX + "ums:g", "com.tencent.tmgp.sgame",
                "复活 10秒", "复活 10秒", "复活", "10秒",
                LiveWindowPayload.EVENT_QUEUE, -1, LiveWindowPayload.OP_CREATE);
        LiveWindowPayload game2 = new LiveWindowPayload(
                LiveWindowPayload.SOURCE_UMS, LiveWindowPayload.GAME_FLASHBACK_KEY_PREFIX + "ums:g", "com.tencent.tmgp.sgame",
                "复活 10秒", "复活 9秒", "复活", "9秒",
                LiveWindowPayload.EVENT_QUEUE, -1, LiveWindowPayload.OP_CREATE);

        assertNotNull(tracker.onUpsert(regular, 1_000L));
        assertNotNull(tracker.onUpsert(game1, 1_000L));
        // 1-second countdown update passes game throttle (900ms) even with same title.
        LiveWindowPayload tick = tracker.onUpsert(game2, 2_000L);
        assertNotNull(tick);
        assertEquals(LiveWindowPayload.OP_UPDATE, tick.operation());

        // Closing regular live windows leaves game flashback active.
        List<LiveWindowPayload> closedRegular = tracker.closeSourceGroup(false);
        assertEquals(1, closedRegular.size());
        assertFalse(closedRegular.get(0).isGameFlashback());
        assertEquals(1, tracker.activeCount());

        // Closing game flashback closes only the game card.
        List<LiveWindowPayload> closedGame = tracker.closeGameFlashback();
        assertEquals(1, closedGame.size());
        assertTrue(closedGame.get(0).isGameFlashback());
        assertEquals(0, tracker.activeCount());
    }
}
