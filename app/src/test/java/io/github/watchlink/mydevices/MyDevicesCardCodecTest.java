package io.github.watchlink.mydevices;

import io.github.watchlink.setup.WatchLinkPrefs;
import org.junit.Test;
import static org.junit.Assert.*;

public class MyDevicesCardCodecTest {
    @Test public void derivesStableSyntheticIdWithoutExposingRawMac() {
        String id1 = MyDevicesCardCodec.syntheticDeviceId("AA:BB:CC:DD:EE:FF", "WATCH 6 Pro");
        String id2 = MyDevicesCardCodec.syntheticDeviceId("aa:bb:cc:dd:ee:ff", "WATCH 6 Pro");
        assertTrue(id1.startsWith(MyDevicesCardCodec.DEVICE_ID_PREFIX));
        assertEquals(id1, id2);
        assertFalse(id1.toUpperCase().contains("AABBCC"));
    }

    @Test public void encodesColorOsMyDevicesJsonPayload() {
        String id = MyDevicesCardCodec.syntheticDeviceId("11:22:33:44:55:66", "HUAWEI WATCH 6 Pro");
        MyDevicesCardCodec.CardSnapshot snap = new MyDevicesCardCodec.CardSnapshot(
                id, "HUAWEI WATCH \"6\" Pro", "11:22:33:44:55:66", true, 88, true, 1_700_000_000_000L);
        String json = MyDevicesCardCodec.toDeviceDataJson(snap, "io.github.maimaiguanfan.watchlink");
        assertTrue(json.startsWith("{") && json.endsWith("}"));
        assertTrue(json.contains("\"deviceId\":\"" + id + "\""));
        assertTrue(json.contains("\"name\":\"HUAWEI WATCH \\\"6\\\" Pro\""));
        assertTrue(json.contains("\"type\":\"WATCH\""));
        assertTrue(json.contains("\"mConnectState\":\"CONNECTED\""));
        assertTrue(json.contains("\"mAuthority\":\"" + MyDevicesCardCodec.VIRTUAL_AUTHORITY + "\""));
        assertTrue(json.contains("\"associatedPackageName\":\"io.github.maimaiguanfan.watchlink\""));
        assertTrue(json.contains("\"mBatteryInfoList\":[{\"batteryType\":\"SINGLE\",\"value\":88,\"charge\":true}]"));
        assertTrue(json.contains("\"mBatteryList\":[{\"batteryType\":\"SINGLE\",\"value\":88,\"charge\":true}]"));

        MyDevicesCardCodec.CardSnapshot disconnected = new MyDevicesCardCodec.CardSnapshot(
                id, "HUAWEI WATCH 6 Pro", "", false, -1, false, 1_700_000_001_000L);
        String offJson = MyDevicesCardCodec.toDeviceDataJson(disconnected, "com.huawei.health");
        assertTrue(offJson.contains("\"mConnectState\":\"DISCONNECTED\""));
        assertTrue(offJson.contains("\"mBatteryInfoList\":[]"));
    }

    @Test public void normalizesTapTargetSafely() {
        assertEquals(WatchLinkPrefs.TAP_TARGET_WATCHLINK, WatchLinkPrefs.normalizeTapTarget("WATCHLINK"));
        assertEquals(WatchLinkPrefs.TAP_TARGET_HEALTH, WatchLinkPrefs.normalizeTapTarget("HEALTH"));
        assertEquals(WatchLinkPrefs.TAP_TARGET_HEALTH, WatchLinkPrefs.normalizeTapTarget(""));
        assertEquals(WatchLinkPrefs.TAP_TARGET_HEALTH, WatchLinkPrefs.normalizeTapTarget(null));
    }
}
