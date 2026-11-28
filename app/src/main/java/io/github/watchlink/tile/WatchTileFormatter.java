package io.github.watchlink.tile;

/**
 * Pure-Java formatter for the WatchLink Quick Settings tile.
 * Always keeps the tile in inactive visual state and displays battery percentage directly
 * in the title without showing the watch name.
 */
public final class WatchTileFormatter {
    public static final int STATE_INACTIVE = 1;
    public static final int STATE_ACTIVE = 2;

    public record TilePresentation(String label, String subtitle, int state) {}

    public static TilePresentation format(
            boolean refreshing,
            boolean healthReachable,
            boolean watchConnected,
            String watchName,
            int batteryPercent,
            boolean charging) {
        boolean hasBattery = batteryPercent >= 0 && batteryPercent <= 100;
        String batteryLabel = hasBattery ? ((charging ? "⚡" : "") + batteryPercent + "%") : "";
        if (refreshing) {
            String label = (watchConnected && hasBattery) ? batteryLabel : "手表";
            return new TilePresentation(label, "刷新中…", STATE_INACTIVE);
        }
        if (!healthReachable) {
            return new TilePresentation("手表", "等待运动健康", STATE_INACTIVE);
        }
        if (!watchConnected) {
            return new TilePresentation("手表", "未连接", STATE_INACTIVE);
        }
        if (hasBattery) {
            return new TilePresentation(batteryLabel, charging ? "充电中" : "已连接", STATE_INACTIVE);
        }
        return new TilePresentation("已连接", charging ? "充电中" : "电量待同步", STATE_INACTIVE);
    }

    private WatchTileFormatter() {}
}
