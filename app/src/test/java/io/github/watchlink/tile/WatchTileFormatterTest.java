package io.github.watchlink.tile;

import org.junit.Test;
import static org.junit.Assert.*;

public class WatchTileFormatterTest {
    @Test public void showsBatteryDirectlyInTitleWithInactiveStateAndNoWatchName() {
        var normal = WatchTileFormatter.format(false, true, true, "HUAWEI WATCH 6 Pro", 86, false);
        assertEquals("86%", normal.label());
        assertEquals("已连接", normal.subtitle());
        assertEquals(WatchTileFormatter.STATE_INACTIVE, normal.state());
        assertFalse(normal.label().contains("HUAWEI"));
        assertFalse(normal.subtitle().contains("HUAWEI"));

        var charging = WatchTileFormatter.format(false, true, true, "HUAWEI WATCH 6 Pro", 52, true);
        assertEquals("⚡52%", charging.label());
        assertEquals("充电中", charging.subtitle());
        assertEquals(WatchTileFormatter.STATE_INACTIVE, charging.state());
    }

    @Test public void handlesDisconnectedUnreachableRefreshingAndUnknownBattery() {
        var unknownBat = WatchTileFormatter.format(false, true, true, "HUAWEI WATCH 6 Pro", -1, false);
        assertEquals("已连接", unknownBat.label());
        assertEquals("电量待同步", unknownBat.subtitle());
        assertEquals(WatchTileFormatter.STATE_INACTIVE, unknownBat.state());

        var disconnected = WatchTileFormatter.format(false, true, false, "HUAWEI WATCH 6 Pro", 80, false);
        assertEquals("手表", disconnected.label());
        assertEquals("未连接", disconnected.subtitle());
        assertEquals(WatchTileFormatter.STATE_INACTIVE, disconnected.state());

        var unreachable = WatchTileFormatter.format(false, false, false, "", -1, false);
        assertEquals("手表", unreachable.label());
        assertEquals("等待运动健康", unreachable.subtitle());
        assertEquals(WatchTileFormatter.STATE_INACTIVE, unreachable.state());

        var refreshingWithBat = WatchTileFormatter.format(true, true, true, "HUAWEI WATCH 6 Pro", 75, false);
        assertEquals("75%", refreshingWithBat.label());
        assertEquals("刷新中…", refreshingWithBat.subtitle());
        assertEquals(WatchTileFormatter.STATE_INACTIVE, refreshingWithBat.state());

        var refreshingCold = WatchTileFormatter.format(true, false, false, "", -1, false);
        assertEquals("手表", refreshingCold.label());
        assertEquals("刷新中…", refreshingCold.subtitle());
        assertEquals(WatchTileFormatter.STATE_INACTIVE, refreshingCold.state());
    }
}
