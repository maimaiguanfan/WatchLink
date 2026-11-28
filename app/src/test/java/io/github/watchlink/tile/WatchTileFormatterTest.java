package io.github.watchlink.tile;

import org.junit.Test;
import static org.junit.Assert.*;

public class WatchTileFormatterTest {
    @Test public void formatsConnectedBatteryAndChargingStates() {
        WatchTileFormatter.TilePresentation normal = WatchTileFormatter.format(
                false, true, true, "HUAWEI WATCH 6 Pro", 82, false);
        assertEquals("HUAWEI WATCH 6 Pro", normal.label());
        assertEquals("已连接 · 82%", normal.subtitle());
        assertEquals(WatchTileFormatter.STATE_ACTIVE, normal.state());

        WatchTileFormatter.TilePresentation charging = WatchTileFormatter.format(
                false, true, true, "WATCH 6 Pro", 65, true);
        assertEquals("WATCH 6 Pro", charging.label());
        assertEquals("充电中 · 65%", charging.subtitle());
        assertEquals(WatchTileFormatter.STATE_ACTIVE, charging.state());

        WatchTileFormatter.TilePresentation unknownBattery = WatchTileFormatter.format(
                false, true, true, "", -1, false);
        assertEquals("WatchLink", unknownBattery.label());
        assertEquals("已连接", unknownBattery.subtitle());
        assertEquals(WatchTileFormatter.STATE_ACTIVE, unknownBattery.state());
    }

    @Test public void formatsDisconnectedUnreachableAndRefreshingStates() {
        WatchTileFormatter.TilePresentation disconnected = WatchTileFormatter.format(
                false, true, false, "WATCH 6 Pro", 50, false);
        assertEquals("WATCH 6 Pro", disconnected.label());
        assertEquals("未连接", disconnected.subtitle());
        assertEquals(WatchTileFormatter.STATE_INACTIVE, disconnected.state());

        WatchTileFormatter.TilePresentation unreachable = WatchTileFormatter.format(
                false, false, false, "", -1, false);
        assertEquals("WatchLink", unreachable.label());
        assertEquals("等待运动健康", unreachable.subtitle());
        assertEquals(WatchTileFormatter.STATE_INACTIVE, unreachable.state());

        WatchTileFormatter.TilePresentation refreshing = WatchTileFormatter.format(
                true, true, true, "WATCH 6 Pro", 82, false);
        assertEquals("WATCH 6 Pro", refreshing.label());
        assertEquals("刷新中…", refreshing.subtitle());
        assertEquals(WatchTileFormatter.STATE_ACTIVE, refreshing.state());
    }
}
