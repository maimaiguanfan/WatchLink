package io.github.watchlink.mydevices;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Pure-Java encoder for ColorOS MyDevices (com.heytap.mydevices 17.25.15) DeviceModel JSON
 * payloads. Uses a synthetic hash-based device ID and virtual provider authority so WatchLink
 * projects live watch battery/charging/connection state strictly in-memory across hosts with
 * zero WatchLink process wake-up.
 */
public final class MyDevicesCardCodec {
    public static final String VIRTUAL_AUTHORITY = "watchlink.virtual.devices";
    public static final String VIRTUAL_TABLE = "device_info_table_new";
    public static final String VIRTUAL_URI = "content://" + VIRTUAL_AUTHORITY + "/" + VIRTUAL_TABLE;
    public static final String DEVICE_ID_PREFIX = "watchlink_watch_";
    public static final String DEFAULT_WATCH_ICON_URI =
            "android.resource://com.heytap.mydevices/drawable/default_watch";

    public record CardSnapshot(
            String deviceId,
            String watchName,
            String macAddress,
            boolean connected,
            int batteryPercent,
            boolean charging,
            long updatedAtMs) {
        public boolean valid() {
            return deviceId != null && deviceId.startsWith(DEVICE_ID_PREFIX)
                    && watchName != null && !watchName.isEmpty();
        }
    }

    /** Derives a stable, privacy-safe synthetic deviceId from a watch MAC or fallback name. */
    public static String syntheticDeviceId(String rawMac, String fallbackName) {
        String seed = (rawMac != null && !rawMac.trim().isEmpty())
                ? rawMac.trim().toUpperCase(java.util.Locale.ROOT)
                : (fallbackName == null ? "huawei-watch" : fallbackName.trim());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("watchlink:" + seed).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(DEVICE_ID_PREFIX);
            for (int i = 0; i < 6; i++) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 0xff));
            }
            return hex.toString();
        } catch (Exception fallback) {
            return DEVICE_ID_PREFIX + Integer.toHexString(seed.hashCode());
        }
    }

    public static String sanitizeDisplayName(String rawName) {
        if (rawName == null) return "HUAWEI WATCH";
        String cleaned = rawName.replace('\n', ' ').replace('\r', ' ').trim();
        if (cleaned.isEmpty()) return "HUAWEI WATCH";
        return cleaned.length() > 36 ? cleaned.substring(0, 36).trim() : cleaned;
    }

    /**
     * Serializes a watch snapshot to ColorOS MyDevices DeviceModel JSON consumed by
     * DeviceAppScannerImpl / DeviceAppProviderQuery (verified against MyDevices 17.25.15).
     */
    public static String toDeviceDataJson(CardSnapshot snap, String associatedPackage) {
        return toDeviceDataJson(snap, associatedPackage, null);
    }

    public static String toDeviceDataJson(CardSnapshot snap, String associatedPackage, String customIconUri) {
        if (snap == null || !snap.valid()) return "";
        String connectState = snap.connected() ? "CONNECTED" : "DISCONNECTED";
        long ts = snap.updatedAtMs() > 0L ? snap.updatedAtMs() : System.currentTimeMillis();
        String name = sanitizeDisplayName(snap.watchName());
        String mac = snap.macAddress() == null ? "" : snap.macAddress().trim();
        String iconUri = (customIconUri != null && !customIconUri.isEmpty())
                ? customIconUri : DEFAULT_WATCH_ICON_URI;

        StringBuilder batInfo = new StringBuilder("[");
        StringBuilder batInts = new StringBuilder("[");
        if (snap.batteryPercent() >= 0 && snap.batteryPercent() <= 100) {
            batInfo.append("{\"batteryType\":\"SINGLE\",\"value\":")
                    .append(snap.batteryPercent())
                    .append(",\"charge\":")
                    .append(snap.charging())
                    .append('}');
            batInts.append(snap.batteryPercent());
        }
        batInfo.append(']');
        batInts.append(']');

        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        appendStringField(sb, "mDeviceId", snap.deviceId(), false);
        appendStringField(sb, "mDeviceName", name, true);
        appendStringField(sb, "mMacAddress", mac, true);
        appendStringField(sb, "modelId", "OW19W3", true);
        appendStringField(sb, "mDeviceType", "WATCH", true);
        appendStringField(sb, "iconUrl", iconUri, true);
        appendStringField(sb, "mIconUrl", iconUri, true);
        appendStringField(sb, "mConnectState", connectState, true);
        appendStringField(sb, "mAuthority", VIRTUAL_AUTHORITY, true);
        if (associatedPackage != null && !associatedPackage.isEmpty()) {
            appendStringField(sb, "associatedPackageName", associatedPackage, true);
        }
        sb.append(",\"isActive\":true");
        sb.append(",\"cardStyle\":1");
        sb.append(",\"timestamp\":").append(ts);
        sb.append(",\"versionCode\":1");
        sb.append(",\"feature\":0");
        sb.append(",\"linkageVersion\":1");
        sb.append(",\"autoSwitch\":false");
        sb.append(",\"revision\":").append(ts);
        sb.append(",\"connection\":{");
        appendStringField(sb, "connectState", connectState, false);
        appendStringField(sb, "coordinationState", "DEFAULT", true);
        sb.append(",\"lastConnectTime\":").append(snap.connected() ? ts : 0L);
        sb.append(",\"lastDisconnectTime\":").append(snap.connected() ? 0L : ts);
        sb.append('}');
        sb.append(",\"mBatteryInfoList\":").append(batInfo);
        sb.append(",\"mBatteryList\":").append(batInts);
        sb.append('}');
        return sb.toString();
    }

    private static void appendStringField(StringBuilder sb, String key, String value, boolean comma) {
        if (comma) sb.append(',');
        sb.append('"').append(key).append("\":");
        appendJsonString(sb, value == null ? "" : value);
    }

    static void appendJsonString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private MyDevicesCardCodec() {}
}
