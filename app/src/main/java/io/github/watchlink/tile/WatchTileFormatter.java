package io.github.watchlink.tile;

/**
 * Pure-Java formatter for the WatchLink Quick Settings tile label, subtitle, and active state.
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
        String label = (watchName != null && !watchName.trim().isEmpty())
                ? watchName.trim() : "WatchLink";
        if (refreshing) {
            return new TilePresentation(label, "刷新中…", watchConnected ? STATE_ACTIVE : STATE_INACTIVE);
        }
        if (!healthReachable) {
            return new TilePresentation("WatchLink", "等待运动健康", STATE_INACTIVE);
        }
        if (!watchConnected) {
            return new TilePresentation(label, "未连接", STATE_INACTIVE);
        }
        if (batteryPercent >= 0 && batteryPercent <= 100) {
            String sub = charging ? ("充电中 · " + batteryPercent + "%") : ("已连接 · " + batteryPercent + "%");
            return new TilePresentation(label, sub, STATE_ACTIVE);
        }
        return new TilePresentation(label, charging ? "充电中" : "已连接", STATE_ACTIVE);
    }

    private WatchTileFormatter() {}
}
