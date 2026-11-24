package io.github.watchlink.validation;

import static io.github.watchlink.BuildConfig.APPLICATION_ID;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import androidx.annotation.RequiresApi;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;

/** Contract for Huawei Health's native ExternalPhoneService cooperative alarm endpoint. */
public final class HuaweiReminderService {
    public static final String HEALTH = TargetProfile.HEALTH.packageName;
    public static final String SERVICE = "com.huawei.hwservicesmgr.ExternalPhoneService";
    public static final String ACTION = "com.huawei.synergy.NOTIFY_MSG";
    public static final String DEVICE_ACTION = "alarm_clock_msg_from_device";
    public static final String PERMISSION = "com.huawei.health.permission.AUTH_STATE_REQ";
    public static final String ALARM_TITLE = "手机闹钟";
    public static final String TEST_TITLE = "WatchLink 测试";
    public static final int SNOOZE_MINUTES = 5;

    public record HealthEndpoint(boolean trusted, boolean service, boolean stopped) {
        public boolean ready() { return trusted && service && !stopped; }
    }

    public static ComponentName serviceComponent() {
        return new ComponentName(HEALTH, SERVICE);
    }

    public static HealthEndpoint inspectHealthEndpoint(Context context) {
        PackageManager pm = context.getPackageManager();
        boolean trusted = SenderVerifier.trustedReminderTarget(context, TargetProfile.HEALTH);
        boolean service = false;
        try {
            ResolveInfo resolved = pm.resolveService(new Intent(ACTION).setPackage(HEALTH), 0);
            ServiceInfo info = resolved == null ? null : resolved.serviceInfo;
            service = enabled(info) && info.exported && HEALTH.equals(info.packageName)
                    && SERVICE.equals(info.name) && PERMISSION.equals(info.permission);
        } catch (SecurityException ignored) { /* Service cannot be resolved by this caller. */ }
        return new HealthEndpoint(trusted, service, trusted && stopped(pm));
    }

    private static boolean stopped(PackageManager pm) {
        try {
            return (pm.getApplicationInfo(HEALTH, 0).flags & ApplicationInfo.FLAG_STOPPED) != 0;
        } catch (PackageManager.NameNotFoundException | SecurityException unreadable) {
            return false;
        }
    }

    private static boolean enabled(ServiceInfo info) {
        return info != null && info.enabled && info.applicationInfo != null && info.applicationInfo.enabled;
    }

    public static String dailyIdentifier(String run, int id, int operation) {
        return APPLICATION_ID + ".daily-" + run + "-" + id + "-" + operation;
    }

    /** Builds the native ExternalPhoneService OPEN request (8/9) for Health-hosted self-dispatch. */
    @RequiresApi(29)
    public static Intent openIntent(String run, int reminder, long at, String title, int snoozeMinutes) {
        if (!DailyAlarmWire.isRun(run) || reminder <= 0 || title == null || title.isEmpty()
                || (snoozeMinutes != 0 && !PhoneRingWire.validSnooze(snoozeMinutes))) {
            throw new IllegalArgumentException("HEALTH_OPEN_INVALID");
        }
        Bundle attributes = new Bundle();
        attributes.putInt("notification_id", reminder);
        attributes.putInt("type", 0);
        attributes.putString("title", title);
        if (at > 0) attributes.putLong("time", at);
        if (PhoneRingWire.validSnooze(snoozeMinutes)) attributes.putInt("snooze_delay", snoozeMinutes);
        return new Intent(ACTION)
                .setComponent(serviceComponent())
                .setIdentifier(dailyIdentifier(run, reminder, snoozeMinutes / 5))
                .putExtra("attribute", attributes);
    }

    /** Builds the native ExternalPhoneService CLOSE request (8/10 type=1) for Health-hosted self-dispatch. */
    @RequiresApi(29)
    public static Intent closeIntent(String run, int reminder) {
        if (!DailyAlarmWire.isRun(run) || reminder <= 0) {
            throw new IllegalArgumentException("HEALTH_CLOSE_INVALID");
        }
        Bundle attributes = new Bundle();
        attributes.putInt("notification_id", reminder);
        attributes.putInt("type", 1);
        return new Intent(ACTION)
                .setComponent(serviceComponent())
                .setIdentifier(dailyIdentifier(run, reminder, 7))
                .putExtra("attribute", attributes);
    }

    private HuaweiReminderService() {}
}
