package io.github.watchlink.mydevices;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.TargetProfile;

/**
 * Authenticated dual-host IPC wire directly between Huawei Health (:PhoneService) and ColorOS
 * MyDevices (com.heytap.mydevices). Never wakes WatchLink's own process.
 */
public final class MyDevicesWire {
    public static final String ACTION_SYNC = BuildConfig.APPLICATION_ID + ".mydevices.SYNC_V1";
    public static final String ACTION_PULL = BuildConfig.APPLICATION_ID + ".mydevices.PULL_V1";

    public static final String EXTRA_DEVICE_ID = "deviceId";
    public static final String EXTRA_WATCH_NAME = "watchName";
    public static final String EXTRA_MAC = "mac";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_BATTERY = "battery";
    public static final String EXTRA_CHARGING = "charging";
    public static final String EXTRA_UPDATED_AT = "updatedAt";
    public static final String EXTRA_ICON_PNG = "iconPng";
    public static final String EXTRA_ICON_HASH = "iconHash";

    @RequiresApi(34)
    public static void sendSnapshotToMyDevices(Context healthApp, MyDevicesCardCodec.CardSnapshot snap) {
        sendSnapshotToMyDevices(healthApp, snap, null, null);
    }

    @RequiresApi(34)
    public static void sendSnapshotToMyDevices(Context healthApp, MyDevicesCardCodec.CardSnapshot snap,
                                               byte[] iconPng, String iconHash) {
        if (healthApp == null || snap == null || !snap.valid()) return;
        Intent intent = new Intent(ACTION_SYNC)
                .setPackage(TargetProfile.MY_DEVICES.packageName)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                .putExtra(EXTRA_DEVICE_ID, snap.deviceId())
                .putExtra(EXTRA_WATCH_NAME, snap.watchName())
                .putExtra(EXTRA_MAC, snap.macAddress())
                .putExtra(EXTRA_CONNECTED, snap.connected())
                .putExtra(EXTRA_BATTERY, snap.batteryPercent())
                .putExtra(EXTRA_CHARGING, snap.charging())
                .putExtra(EXTRA_UPDATED_AT, snap.updatedAtMs());
        if (iconPng != null && iconPng.length > 0 && iconPng.length <= 220 * 1024
                && iconHash != null && !iconHash.isEmpty()) {
            intent.putExtra(EXTRA_ICON_PNG, iconPng);
            intent.putExtra(EXTRA_ICON_HASH, iconHash);
        }
        healthApp.sendBroadcast(intent, null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    @RequiresApi(34)
    public static void requestPullFromHealth(Context myDevicesApp) {
        if (myDevicesApp == null) return;
        Intent intent = new Intent(ACTION_PULL)
                .setPackage(TargetProfile.HEALTH.packageName)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        myDevicesApp.sendBroadcast(intent, null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    public static MyDevicesCardCodec.CardSnapshot fromSyncIntent(Intent intent) {
        if (intent == null || !ACTION_SYNC.equals(intent.getAction())) return null;
        String id = intent.getStringExtra(EXTRA_DEVICE_ID);
        String name = MyDevicesCardCodec.sanitizeDisplayName(intent.getStringExtra(EXTRA_WATCH_NAME));
        String mac = intent.getStringExtra(EXTRA_MAC);
        boolean connected = intent.getBooleanExtra(EXTRA_CONNECTED, false);
        int battery = intent.getIntExtra(EXTRA_BATTERY, -1);
        if (battery < -1 || battery > 100) battery = -1;
        boolean charging = connected && intent.getBooleanExtra(EXTRA_CHARGING, false);
        long updatedAt = Math.max(0L, intent.getLongExtra(EXTRA_UPDATED_AT, 0L));
        MyDevicesCardCodec.CardSnapshot snap = new MyDevicesCardCodec.CardSnapshot(
                id, name, mac == null ? "" : mac, connected, battery, charging, updatedAt);
        return snap.valid() ? snap : null;
    }

    private MyDevicesWire() {}
}
