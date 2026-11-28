package io.github.watchlink.xposed;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import androidx.annotation.RequiresApi;
import io.github.watchlink.alarm.probe.DeviceToken;
import io.github.watchlink.wifi.HuaweiWifiSyncCodec;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

/** Worker-only reads of the native cooperative reminder's device capability and switch. */
final class HealthCooperativeReadiness {
    record Result(String kind, String matchingRawDevice) {
        // This short-lived device identifier only scopes the physical receiver. Never log it.
        @Override public String toString() { return kind; }
    }

    record WifiDeviceSnapshot(boolean connected, String rawDevice, String deviceToken, Boolean capability241) {
        static final WifiDeviceSnapshot DISCONNECTED = new WifiDeviceSnapshot(false, "", "", null);

        @Override public String toString() {
            return "WifiDeviceSnapshot(connected=" + connected + ", cap241=" + capability241 + ")";
        }
    }

    record LiveDeviceSnapshot(boolean connected, Boolean bit84, Boolean bit219) {
        static final LiveDeviceSnapshot DISCONNECTED = new LiveDeviceSnapshot(false, null, null);

        @Override public String toString() {
            return "LiveDeviceSnapshot(connected=" + connected + ", bit84=" + bit84 + ", bit219=" + bit219 + ")";
        }
    }

    record ConnectedDevice(Object deviceInfo, String rawMac) {
        @Override public String toString() {
            return "ConnectedDevice(present=" + (deviceInfo != null && rawMac != null && !rawMac.isEmpty()) + ")";
        }
    }

    record WatchDeviceInfo(boolean connected, String rawMac, String deviceName,
                           int productType, String hiLinkDeviceId, String deviceModel,
                           String deviceSubModelId) {
        static final WatchDeviceInfo DISCONNECTED = new WatchDeviceInfo(false, "", "", -1, "", "", "");

        @Override public String toString() {
            return "WatchDeviceInfo(connected=" + connected + ")";
        }
    }
    private final Method manager, connected, identify, expand, deviceNameMethod,
            productTypeMethod, hiLinkIdMethod, deviceModelMethod, deviceSubModelIdMethod;
    private final Object mainDevices;

    @RequiresApi(34)
    HealthCooperativeReadiness(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols)
            throws ReflectiveOperationException {
        if (symbols == null || symbols.deviceCatalogClass() == null || symbols.deviceCatalogClass().isEmpty()) {
            throw new NoSuchMethodException("Missing Health device catalog symbol");
        }
        Class<?> api = installer.target("com.huawei.health.devicemgr.api.DeviceMgrApi");
        Class<?> proxy = installer.target("com.huawei.haf.bundle.AppBundlePluginProxy");
        manager = HealthReminderCatalog.factory(proxy, api, candidate(installer, symbols.deviceCatalogClass()));
        Class<?> device = installer.target("com.huawei.health.devicemgr.business.entity.DeviceInfo");
        Class<?> mode = installer.target("com.huawei.health.devicemgr.api.constant.HwGetDevicesMode");
        mainDevices = mode.getField("CONNECTED_MAIN_DEVICES").get(null);
        connected = installer.method(api, "getDeviceList", List.class, false, mode,
                installer.target("com.huawei.health.devicemgr.business.entity.HwGetDevicesParameter"), String.class);
        identify = installer.method(device, "getDeviceIdentify", String.class, false);
        expand = installer.method(device, "getExpandCapability", String.class, false);
        Method nameM = null;
        try { nameM = installer.method(device, "getDeviceName", String.class, false); }
        catch (ReflectiveOperationException ignored) {}
        deviceNameMethod = nameM;
        Method prodM = null;
        try { prodM = installer.method(device, "getProductType", int.class, false); }
        catch (ReflectiveOperationException ignored) {}
        productTypeMethod = prodM;
        Method hiLinkM = null;
        try { hiLinkM = installer.method(device, "getHiLinkDeviceId", String.class, false); }
        catch (ReflectiveOperationException ignored) {}
        hiLinkIdMethod = hiLinkM;
        Method modelM = null;
        try { modelM = installer.method(device, "getDeviceModel", String.class, false); }
        catch (ReflectiveOperationException ignored) {}
        deviceModelMethod = modelM;
        Method subModelM = null;
        try { subModelM = installer.method(device, "getDeviceSubModelId", String.class, false); }
        catch (ReflectiveOperationException ignored) {}
        deviceSubModelIdMethod = subModelM;
    }

    @RequiresApi(34)
    private static Class<?> candidate(AlarmObserverInstaller installer, String name) {
        try { return installer.target(name); }
        catch (ClassNotFoundException absent) { return null; }
    }

    Result read(Context context, String expectedDeviceToken) {
        return read(context, expectedDeviceToken, false);
    }

    Result readSingle(Context context, String expectedDeviceToken) {
        return read(context, expectedDeviceToken, true);
    }

    private List<?> connectedDevices(Object mode) throws ReflectiveOperationException {
        return (List<?>) connected.invoke(manager.invoke(null), mode, null, "ExternalPhoneService");
    }

    private boolean onlyRouteMatches(String key) throws ReflectiveOperationException {
        Object all = connected.getParameterTypes()[0].getField("CONNECTED_DEVICES").get(null);
        List<?> routes = connectedDevices(all);
        return routes != null && routes.size() == 1 && key != null && key.equals(identify.invoke(routes.get(0)));
    }

    String connectedToken() {
        try {
            List<?> primary = connectedDevices(mainDevices);
            if (primary == null || primary.size() != 1) return null;
            String key = (String) identify.invoke(primary.get(0));
            return onlyRouteMatches(key) ? DeviceToken.of(key) : null;
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** Returns the connected primary wearable DeviceInfo and MAC for in-process Health command dispatch. */
    ConnectedDevice connectedPrimaryDevice() {
        try {
            List<?> devices = connectedDevices(mainDevices);
            if (devices == null || devices.isEmpty()) return null;
            for (Object device : devices) {
                if (device == null) continue;
                String key = (String) identify.invoke(device);
                if (key != null && !key.isEmpty()) {
                    return new ConnectedDevice(device, key);
                }
            }
            return null;
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** Returns primary wearable connection state, raw MAC, and display name for status & device-card sync. */
    WatchDeviceInfo readWatchDeviceInfo() {
        try {
            List<?> devices = connectedDevices(mainDevices);
            if (devices == null || devices.isEmpty()) return WatchDeviceInfo.DISCONNECTED;
            for (Object device : devices) {
                if (device == null) continue;
                String key = (String) identify.invoke(device);
                if (key == null || key.isEmpty()) continue;
                String name = "";
                if (deviceNameMethod != null) {
                    Object rawName = deviceNameMethod.invoke(device);
                    if (rawName instanceof String s) name = sanitizeWatchName(s);
                }
                int productType = -1;
                if (productTypeMethod != null) {
                    Object rawType = productTypeMethod.invoke(device);
                    if (rawType instanceof Integer t) productType = t;
                }
                String hiLinkId = "";
                if (hiLinkIdMethod != null) {
                    Object rawHi = hiLinkIdMethod.invoke(device);
                    if (rawHi instanceof String s) hiLinkId = s.trim();
                }
                String deviceModel = "";
                if (deviceModelMethod != null) {
                    Object rawModel = deviceModelMethod.invoke(device);
                    if (rawModel instanceof String s) deviceModel = s.trim();
                }
                String deviceSubModelId = "";
                if (deviceSubModelIdMethod != null) {
                    Object rawSub = deviceSubModelIdMethod.invoke(device);
                    if (rawSub instanceof String s) deviceSubModelId = s.trim();
                }
                return new WatchDeviceInfo(true, key, name, productType, hiLinkId, deviceModel, deviceSubModelId);
            }
            return WatchDeviceInfo.DISCONNECTED;
        } catch (Exception unavailable) {
            return WatchDeviceInfo.DISCONNECTED;
        }
    }

    static String sanitizeWatchName(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() > 40 ? cleaned.substring(0, 40).trim() : cleaned;
    }

    /** Returns the connected primary wearable snapshot for Wi-Fi sync (including ExpandCapability bit 241). */
    WifiDeviceSnapshot readWifiSnapshot() {
        try {
            List<?> devices = connectedDevices(mainDevices);
            if (devices == null || devices.isEmpty()) return WifiDeviceSnapshot.DISCONNECTED;
            WifiDeviceSnapshot fallback = null;
            for (Object device : devices) {
                if (device == null) continue;
                String key = (String) identify.invoke(device);
                if (key == null || key.isEmpty()) continue;
                String token = DeviceToken.of(key);
                Boolean cap241 = HuaweiWifiSyncCodec.capability241((String) expand.invoke(device));
                WifiDeviceSnapshot snapshot = new WifiDeviceSnapshot(true, key, token, cap241);
                if (Boolean.TRUE.equals(cap241)) return snapshot;
                if (fallback == null) fallback = snapshot;
            }
            return fallback != null ? fallback : WifiDeviceSnapshot.DISCONNECTED;
        } catch (Exception unavailable) {
            return WifiDeviceSnapshot.DISCONNECTED;
        }
    }

    /** Returns the connected primary wearable snapshot for SmartEdge live-window support (ExpandCapability bit 84 / 219). */
    LiveDeviceSnapshot readLiveSnapshot() {
        try {
            List<?> devices = connectedDevices(mainDevices);
            if (devices == null || devices.isEmpty()) return LiveDeviceSnapshot.DISCONNECTED;
            LiveDeviceSnapshot fallback = null;
            for (Object device : devices) {
                if (device == null) continue;
                Object raw = expand.invoke(device);
                String hex = raw instanceof String s ? s : null;
                Boolean bit84 = capabilityBit(hex, 84);
                Boolean bit219 = capabilityBit(hex, 219);
                LiveDeviceSnapshot snap = new LiveDeviceSnapshot(true, bit84, bit219);
                if (Boolean.TRUE.equals(bit84)) return snap;
                if (fallback == null) fallback = snap;
            }
            return fallback != null ? fallback : LiveDeviceSnapshot.DISCONNECTED;
        } catch (Exception unavailable) {
            return LiveDeviceSnapshot.DISCONNECTED;
        }
    }

    /** Returns watch silent-notification capability support (ExpandCapability bit 94) for connected primary wearable. */
    String readSilentPushSupport() {
        try {
            List<?> devices = connectedDevices(mainDevices);
            if (devices == null || devices.isEmpty()) return "WATCH_DISCONNECTED";
            boolean sawFalse = false;
            for (Object device : devices) {
                if (device == null) continue;
                Object raw = expand.invoke(device);
                String hex = raw instanceof String s ? s : null;
                Boolean bit94 = capabilityBit(hex, 94);
                if (Boolean.TRUE.equals(bit94)) return "SUPPORTED_94";
                if (Boolean.FALSE.equals(bit94)) sawFalse = true;
            }
            return sawFalse ? "UNSUPPORTED" : "UNKNOWN";
        } catch (Exception unavailable) {
            return "UNKNOWN";
        }
    }

    /** Returns watch sleep-status capability support (ExpandCapability bit 136) for connected primary wearable. */
    String readSleepWatchSupport() {
        try {
            List<?> devices = connectedDevices(mainDevices);
            if (devices == null || devices.isEmpty()) return "WATCH_DISCONNECTED";
            boolean sawFalse = false;
            for (Object device : devices) {
                if (device == null) continue;
                Object raw = expand.invoke(device);
                String hex = raw instanceof String s ? s : null;
                Boolean bit136 = capabilityBit(hex, 136);
                if (Boolean.TRUE.equals(bit136)) return "SUPPORTED_136";
                if (Boolean.FALSE.equals(bit136)) sawFalse = true;
            }
            return sawFalse ? "UNSUPPORTED" : "UNKNOWN";
        } catch (Exception unavailable) {
            return "UNKNOWN";
        }
    }

    private Result read(Context context, String expectedDeviceToken, boolean requireSingle) {
        String matched = "";
        try {
            List<?> devices = connectedDevices(mainDevices);
            if (devices == null || devices.isEmpty()) return new Result("READY_UNKNOWN", "");
            if (requireSingle && devices.size() != 1) return new Result("READY_DEVICE_MISMATCH", "");
            Object device = devices.get(0);
            String key = (String) identify.invoke(device);
            if (!DeviceToken.of(key).equals(expectedDeviceToken)) return new Result("READY_DEVICE_MISMATCH", "");
            if (requireSingle && !onlyRouteMatches(key)) return new Result("READY_DEVICE_MISMATCH", "");
            matched = key;
            Boolean supports = capability200((String) expand.invoke(device));
            if (supports == null) return new Result("READY_UNKNOWN", matched);
            if (!supports) return new Result("READY_UNSUPPORTED", matched);

            // Health initializes missing rows to true. Restrict the probe to its SELECT.
            String encoded = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
            Uri clockState = Uri.parse("content://com.huawei.health.HwNotificationContentProvider/ClockState");
            try (Cursor cursor = context.getContentResolver().query(clockState, new String[]{"clockValue"},
                    "deviceMac = ?", new String[]{encoded}, null)) {
                if (cursor == null) return new Result("READY_UNKNOWN", matched);
                if (!cursor.moveToFirst()) return new Result("READY_SUPPORTED_UNSET", matched);
                String value = cursor.getString(cursor.getColumnIndexOrThrow("clockValue"));
                if (cursor.moveToNext()) return new Result("READY_UNKNOWN", matched);
                return new Result(switchState(value), matched);
            }
        } catch (Exception unavailable) {
            return new Result("READY_UNKNOWN", matched);
        }
    }

    static String switchState(String value) {
        if ("true".equals(value)) return "READY_SUPPORTED_ON";
        if ("false".equals(value)) return "READY_SUPPORTED_OFF";
        return "READY_UNKNOWN";
    }

    static Boolean capability200(String expanded) {
        return capabilityBit(expanded, 200);
    }

    static Boolean capabilityBit(String expanded, int bitIndex) {
        if (bitIndex < 0 || expanded == null || expanded.isEmpty() || expanded.length() > 65_536) return null;
        String hex = expanded.replace(" ", "");
        if (hex.isEmpty() || (hex.length() & 1) != 0 || !hex.matches("[a-fA-F0-9]+")) return null;
        // Huawei native adapters check byte[index/8] & (1 << index%8), in input byte order (verified against Lesi.e).
        int charPos = (bitIndex / 8) * 2;
        if (hex.length() < charPos + 2) return false;
        int value = (Character.digit(hex.charAt(charPos), 16) << 4)
                | Character.digit(hex.charAt(charPos + 1), 16);
        return (value & (1 << (bitIndex % 8))) != 0;
    }
}
