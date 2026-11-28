package io.github.watchlink.xposed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.notify.BatteryAlertPolicy;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Bidirectional Phone <-> Watch charging & battery alert bridge inside Huawei Health :PhoneService.
 *
 * <ul>
 *   <li>Phone -> Watch: listens to sticky {@code ACTION_BATTERY_CHANGED} and ColorOS charging-limit
 *       system settings ({@code regular_charge_protection_*}), pushing native 5.2.1 notifications
 *       through {@code IntegratedServicesReceiver} so enabled silent-push rules (DND/Focus/Game/ScreenOn)
 *       apply automatically.</li>
 *   <li>Watch -> Phone: listens to {@link HealthWatchStatusCollector} snapshots and posts local
 *       phone notifications on an independent channel, tagged to prevent :DaemonService echo-back
 *       and deduplicated against Huawei Health's native full-battery alert.</li>
 * </ul>
 */
@RequiresApi(34)
final class HealthBatteryAlertBridge {
    static final String EXTRA_WATCHLINK_BATTERY_ALERT = "watchlink_battery_alert";
    static final String EXTRA_WATCHLINK_PHONE_LOCAL_BATTERY_ALERT = "watchlink_local_battery_alert";

    private static final String ACTION_NOTIFICATION_PUSH = "com.huawei.bone.ACTION_NOTIFICATION_PUSH";
    private static final int MSG_TYPE_OTHER_APP = 0x7F;
    private static final int TITLE_TYPE_NORMAL = 3;
    private static final int TEXT_TYPE_NORMAL = 1;
    private static final int REMINDER_STATUS_NORMAL = 15;

    private static final String CHANNEL_ID = "watchlink_battery_alerts";
    private static final String CHANNEL_NAME = "WatchLink 电量提醒";
    private static final int NOTIFY_ID_WATCH_FULL = 0x574C4201;
    private static final int NOTIFY_ID_WATCH_LOW = 0x574C4202;

    // Verified on PLK110 ColorOS 17 Settings.System.
    private static final String SYS_REGULAR_LIMIT_SWITCH = "regular_charge_protection_switch_state";
    private static final String SYS_REGULAR_LIMIT_LEVEL = "regular_charge_protection_open_power_level";
    private static final String SYS_REGULAR_LIMIT_STATUS = "regular_charge_protection_status";
    private static final String SYS_CHARGE_PROTECTION_STATE = "charge_protection_current_state";
    private static final String SYS_SMART_CHARGE_STATUS = "smart_charge_protection_status";
    private static final String SYS_BYPASS_CHARGE_STATE = "bypass_charge_state";

    private static final String STATUS_PUSH_MGR_CLASS =
            "com.huawei.hwdevice.phoneprocess.mgr.hwdevicestatusmgr.HwDeviceStatusPushManager";
    private static final String STATUS_BEAN_CLASS =
            "com.huawei.hwdevice.phoneprocess.mgr.hwdevicestatusmgr.HwDeviceStatusPushManager$b";

    private final Context app;
    private final HostSettings settings;
    private final ScheduledExecutorService worker;
    private final Consumer<Intent> watchNotificationDispatcher;
    private final BatteryAlertPolicy policy = new BatteryAlertPolicy();
    private final AtomicInteger watchNotifySeq = new AtomicInteger(1);

    private BroadcastReceiver batteryReceiver;
    private ContentObserver chargeSettingsObserver;
    private ScheduledFuture<?> pendingSustainCheck;
    private volatile boolean installed;
    private volatile boolean watchConnected;
    private volatile Intent lastBatteryIntent;

    HealthBatteryAlertBridge(Context app,
                             HostSettings settings,
                             ScheduledExecutorService worker,
                             Consumer<Intent> watchNotificationDispatcher) {
        this.app = app;
        this.settings = settings;
        this.worker = worker;
        this.watchNotificationDispatcher = watchNotificationDispatcher;
    }

    void install(AlarmObserverInstaller installer) {
        installNativeWatchFullDedupHook(installer);
        registerBatteryReceiver();
        registerChargeSettingsObserver();
        installed = true;
        ProbeLog.info("HEALTH_BATTERY_ALERT_READY source=HEALTH");
    }

    void close() {
        installed = false;
        cancelSustainCheckLocked();
        policy.resetAll();
        if (batteryReceiver != null) {
            try { app.unregisterReceiver(batteryReceiver); } catch (RuntimeException ignored) {}
            batteryReceiver = null;
        }
        if (chargeSettingsObserver != null) {
            try { app.getContentResolver().unregisterContentObserver(chargeSettingsObserver); }
            catch (RuntimeException ignored) {}
            chargeSettingsObserver = null;
        }
        lastBatteryIntent = null;
    }

    /** Called on worker thread whenever HealthWatchStatusCollector publishes a watch status update. */
    void onWatchStatusObserved(HealthWatchStatusCollector.WatchStatusSnapshot snap) {
        if (!installed || snap == null) return;
        watchConnected = snap.connected();
        if (!snap.connected() || snap.batteryPercent() < 0) return;

        BatteryAlertPolicy.WatchAlert alert = policy.onWatchSample(
                snap.connected(),
                snap.batteryPercent(),
                snap.charging(),
                SystemClock.elapsedRealtime(),
                settings.isBatteryWatchFullPhoneEnabled(),
                settings.isBatteryWatchLowPhoneEnabled(),
                settings.batteryWatchLowThreshold());
        if (alert != null) {
            postPhoneNotificationForWatch(alert, snap.watchName());
        }
    }

    private void registerBatteryReceiver() {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!installed || intent == null) return;
                    if (!Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) return;
                    lastBatteryIntent = intent;
                    try {
                        worker.execute(() -> evaluatePhoneBatteryLocked(intent));
                    } catch (RuntimeException ignored) {}
                }
            };
            Intent sticky = app.registerReceiver(receiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            batteryReceiver = receiver;
            if (sticky != null) {
                lastBatteryIntent = sticky;
                try {
                    worker.execute(() -> evaluatePhoneBatteryLocked(sticky));
                } catch (RuntimeException ignored) {}
            }
        } catch (RuntimeException err) {
            ProbeLog.failure("HEALTH_BATTERY_ALERT_DEGRADED", err);
        }
    }

    private void registerChargeSettingsObserver() {
        try {
            ContentResolver cr = app.getContentResolver();
            if (cr == null) return;
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) {
                    if (!installed) return;
                    try {
                        worker.execute(() -> {
                            Intent current = readStickyBatteryIntent();
                            if (current != null) evaluatePhoneBatteryLocked(current);
                        });
                    } catch (RuntimeException ignored) {}
                }
            };
            for (String key : new String[]{
                    SYS_REGULAR_LIMIT_SWITCH,
                    SYS_REGULAR_LIMIT_LEVEL,
                    SYS_REGULAR_LIMIT_STATUS,
                    SYS_CHARGE_PROTECTION_STATE,
                    SYS_SMART_CHARGE_STATUS,
                    SYS_BYPASS_CHARGE_STATE
            }) {
                Uri uri = Settings.System.getUriFor(key);
                if (uri != null) {
                    cr.registerContentObserver(uri, false, observer);
                }
            }
            chargeSettingsObserver = observer;
        } catch (RuntimeException ignored) {}
    }

    private void installNativeWatchFullDedupHook(AlarmObserverInstaller installer) {
        if (installer == null) return;
        try {
            Class<?> mgrCls = installer.target(STATUS_PUSH_MGR_CLASS);
            Class<?> devInfoCls = installer.target("com.huawei.health.devicemgr.business.entity.DeviceInfo");
            Class<?> beanCls = installer.target(STATUS_BEAN_CLASS);
            // Native showFullBatteryNotify is private boolean e(DeviceInfo, HwDeviceStatusPushManager$b)
            // in 17.0.10.330; only hook if exact signature matches to remain safe across versions.
            Method fullNotify = null;
            try {
                Method m = mgrCls.getDeclaredMethod("e", devInfoCls, beanCls);
                if (!Modifier.isStatic(m.getModifiers()) && m.getReturnType() == boolean.class) {
                    fullNotify = m;
                }
            } catch (NoSuchMethodException ignored) {}
            if (fullNotify != null) {
                installer.observeAround("health.battery.native_full", fullNotify,
                        chain -> null,
                        (chain, token, result) -> {
                            if (installed && Boolean.TRUE.equals(result)) {
                                policy.markNativeWatchFullNotified(SystemClock.elapsedRealtime());
                            }
                        });
            }
        } catch (Throwable ignored) {
            // Optional deduplication hook; watch-full still functions cleanly without it.
        }
    }

    private Intent readStickyBatteryIntent() {
        try {
            Intent fresh = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (fresh != null) {
                lastBatteryIntent = fresh;
                return fresh;
            }
        } catch (RuntimeException ignored) {}
        return lastBatteryIntent;
    }

    private void evaluatePhoneBatteryLocked(Intent intent) {
        if (!installed || intent == null) return;
        int rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (rawLevel < 0 || scale <= 0) return;
        int level = Math.max(0, Math.min(100, (rawLevel * 100) / scale));
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryAlertPolicy.STATUS_UNKNOWN);
        int health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
        boolean overheat = health == BatteryManager.BATTERY_HEALTH_OVERHEAT;

        ContentResolver cr = app.getContentResolver();
        boolean bypassCharging = readSystemInt(cr, SYS_BYPASS_CHARGE_STATE, 0) > 0;
        boolean smartChargeProtecting = readSystemInt(cr, SYS_SMART_CHARGE_STATUS, 0) > 0;
        boolean regularLimitEnabled = readSystemInt(cr, SYS_REGULAR_LIMIT_SWITCH, 0) > 0;
        int regularLimitLevel = readSystemInt(cr, SYS_REGULAR_LIMIT_LEVEL, -1);
        boolean regularLimitActive = readSystemInt(cr, SYS_REGULAR_LIMIT_STATUS, 0) > 0;
        boolean chargeProtectionActive = readSystemInt(cr, SYS_CHARGE_PROTECTION_STATE, 0) > 0;

        long now = SystemClock.elapsedRealtime();
        BatteryAlertPolicy.PhoneSample sample = new BatteryAlertPolicy.PhoneSample(
                level, plugged, status, overheat,
                bypassCharging, smartChargeProtecting,
                regularLimitEnabled, regularLimitLevel,
                regularLimitActive, chargeProtectionActive,
                now);

        BatteryAlertPolicy.PhoneAlert alert = policy.onPhoneSample(
                sample,
                settings.isBatteryPhoneFullWatchEnabled(),
                settings.isBatteryPhoneLowWatchEnabled(),
                settings.batteryPhoneLowThreshold());

        long pendingDelay = policy.pendingSustainCheckDelayMs(now);
        if (pendingDelay > 0L) {
            scheduleSustainCheckLocked(pendingDelay + 500L);
        } else {
            cancelSustainCheckLocked();
        }

        if (alert != null) {
            dispatchPhoneAlertToWatch(alert);
        }
    }

    private synchronized void scheduleSustainCheckLocked(long delayMs) {
        cancelSustainCheckLocked();
        if (!installed) return;
        try {
            pendingSustainCheck = worker.schedule(() -> {
                if (!installed) return;
                Intent current = readStickyBatteryIntent();
                if (current != null) evaluatePhoneBatteryLocked(current);
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {}
    }

    private synchronized void cancelSustainCheckLocked() {
        if (pendingSustainCheck != null) {
            pendingSustainCheck.cancel(false);
            pendingSustainCheck = null;
        }
    }

    private void dispatchPhoneAlertToWatch(BatteryAlertPolicy.PhoneAlert alert) {
        if (!installed || watchNotificationDispatcher == null) return;
        // Skip pushing when watch is disconnected and short-disconnect staging is disabled.
        if (!watchConnected && !settings.isNotifyDisconnectStageEnabled()) return;

        String title;
        String text;
        String eventTag;
        if (alert.fullAlert()) {
            if (alert.fullKind() == BatteryAlertPolicy.PhoneFullKind.STANDARD_100) {
                title = "手机已充满";
                text = "当前电量 " + alert.level() + "%，可拔下充电器";
                eventTag = "PHONE_FULL_100";
            } else {
                title = "手机已达充电上限";
                text = "当前电量 " + alert.level() + "%（充电保护上限）";
                eventTag = "PHONE_FULL_LIMIT";
            }
        } else {
            title = "手机电量较低";
            text = "当前电量 " + alert.level() + "%，请及时充电";
            eventTag = "PHONE_LOW";
        }

        int seq = watchNotifySeq.getAndIncrement();
        long nowWall = System.currentTimeMillis();
        Bundle extras = new Bundle();
        extras.putInt("type", MSG_TYPE_OTHER_APP);
        extras.putString("packagename", BuildConfig.APPLICATION_ID);
        extras.putString("data_package_name", BuildConfig.APPLICATION_ID);
        extras.putInt("title_type", TITLE_TYPE_NORMAL);
        extras.putString("title", title);
        extras.putInt("text_type", TEXT_TYPE_NORMAL);
        extras.putString("text", text);
        extras.putString("data_category", "status");
        extras.putInt("data_noty_id", 0x574C0000 + (seq & 0xFFFF));
        extras.putString("data_extra_noty_key", "watchlink:battery:" + eventTag + ":" + seq);
        extras.putLong("data_extra_noty_when", nowWall);
        extras.putLong("data_extra_noty_posttime", nowWall);
        extras.putInt("data_extra_reminder_status", REMINDER_STATUS_NORMAL);
        extras.putBoolean(EXTRA_WATCHLINK_BATTERY_ALERT, true);

        Intent push = new Intent(ACTION_NOTIFICATION_PUSH);
        push.putExtras(extras);
        try {
            watchNotificationDispatcher.accept(push);
            ProbeLog.info("HEALTH_BATTERY_PHONE_ALERT source=HEALTH battery_event=" + eventTag);
        } catch (RuntimeException err) {
            ProbeLog.failure("HEALTH_BATTERY_ALERT_DEGRADED", err);
        }
    }

    private void postPhoneNotificationForWatch(BatteryAlertPolicy.WatchAlert alert, String watchName) {
        if (!installed) return;
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
            if (existing == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("WatchLink 手表充满与低电量提醒");
                nm.createNotificationChannel(channel);
            }
            String displayName = (watchName != null && !watchName.trim().isEmpty())
                    ? watchName.trim() : "华为手表";
            String title;
            String text;
            int notifyId;
            String eventTag;
            if (alert.fullAlert()) {
                title = "手表已充满";
                text = displayName + " 电量已达 " + alert.level() + "%";
                notifyId = NOTIFY_ID_WATCH_FULL;
                eventTag = "WATCH_FULL";
            } else {
                title = "手表电量较低";
                text = displayName + " 当前电量 " + alert.level() + "%，请及时充电";
                notifyId = NOTIFY_ID_WATCH_LOW;
                eventTag = "WATCH_LOW";
            }

            Bundle extras = new Bundle();
            extras.putBoolean(EXTRA_WATCHLINK_PHONE_LOCAL_BATTERY_ALERT, true);

            Notification.Builder builder = new Notification.Builder(app, CHANNEL_ID)
                    .setSmallIcon(app.getApplicationInfo().icon)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .addExtras(extras);

            try {
                Intent launch = app.getPackageManager().getLaunchIntentForPackage(TargetProfile.HEALTH.packageName);
                if (launch != null) {
                    PendingIntent pi = PendingIntent.getActivity(
                            app, notifyId, launch,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    builder.setContentIntent(pi);
                }
            } catch (RuntimeException ignored) {}

            nm.notify(notifyId, builder.build());
            ProbeLog.info("HEALTH_BATTERY_WATCH_ALERT source=HEALTH battery_event=" + eventTag);
        } catch (RuntimeException err) {
            ProbeLog.failure("HEALTH_BATTERY_ALERT_DEGRADED", err);
        }
    }

    private static int readSystemInt(ContentResolver cr, String key, int fallback) {
        if (cr == null) return fallback;
        try {
            return Settings.System.getInt(cr, key, fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
