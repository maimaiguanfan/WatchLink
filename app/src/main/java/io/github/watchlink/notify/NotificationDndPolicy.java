package io.github.watchlink.notify;

import io.github.watchlink.alarm.probe.TargetProfile;

/**
 * Pure-Java policy deciding whether phone Do Not Disturb (DND) is active, whether a phone
 * notification is silent / silent-no-vibrate, and whether WatchLink should mark the outgoing
 * Huawei Health 5.2.1 notification for silent watch delivery (data_extra_reminder_status = 0 ->
 * TLV 13 02 00 00: arrives in watch notification shade without vibration).
 */
public final class NotificationDndPolicy {
    public static final int INTERRUPTION_FILTER_UNKNOWN = 0;
    public static final int INTERRUPTION_FILTER_ALL = 1;
    public static final int INTERRUPTION_FILTER_PRIORITY = 2;
    public static final int INTERRUPTION_FILTER_NONE = 3;
    public static final int INTERRUPTION_FILTER_ALARMS = 4;

    public static final int IMPORTANCE_UNSPECIFIED = -1000;
    public static final int IMPORTANCE_NONE = 0;
    public static final int IMPORTANCE_MIN = 1;
    public static final int IMPORTANCE_LOW = 2;
    public static final int IMPORTANCE_DEFAULT = 3;

    public static final String CATEGORY_ALARM = "alarm";
    public static final String CATEGORY_CALL = "call";

    /**
     * Evaluates whether Do Not Disturb is currently enabled on the phone.
     * Prefers Android's NotificationManager interruption filter when known, falling back to
     * Settings.Global "zen_mode" (> 0) only when the filter is unknown.
     */
    public static boolean isPhoneDndActive(int interruptionFilter, int zenMode) {
        if (interruptionFilter == INTERRUPTION_FILTER_ALL) {
            return false;
        }
        if (interruptionFilter == INTERRUPTION_FILTER_PRIORITY
                || interruptionFilter == INTERRUPTION_FILTER_NONE
                || interruptionFilter == INTERRUPTION_FILTER_ALARMS) {
            return true;
        }
        return zenMode > 0;
    }

    /**
     * Evaluates whether a phone notification is silent or silent-without-vibration based on
     * Android's NotificationListenerService.Ranking and NotificationChannel attributes.
     */
    public static boolean isPhoneNotificationSilent(
            int rankingImportance,
            boolean ambient,
            boolean hasChannel,
            int channelImportance,
            boolean channelHasSound,
            boolean channelShouldVibrate) {
        if (ambient) {
            return true;
        }
        if (rankingImportance >= IMPORTANCE_NONE && rankingImportance <= IMPORTANCE_LOW) {
            return true;
        }
        if (hasChannel) {
            if (channelImportance >= IMPORTANCE_NONE && channelImportance <= IMPORTANCE_LOW) {
                return true;
            }
            if (!channelHasSound && !channelShouldVibrate) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when WatchLink should rewrite data_extra_reminder_status = 0 so the watch
     * receives the notification silently in its notification list without vibrating.
     * Phone alarms, system clock notifications, and call notifications are never silenced.
     */
    public static boolean shouldPushSilentlyToWatch(
            boolean silentOnDndEnabled,
            boolean silentOnPhoneSilentEnabled,
            boolean phoneDndActive,
            boolean phoneNotificationSilent,
            String packageName,
            String category) {
        if (TargetProfile.CLOCK.packageName.equals(packageName)) {
            return false;
        }
        if (CATEGORY_ALARM.equals(category) || CATEGORY_CALL.equals(category)) {
            return false;
        }
        if (silentOnDndEnabled && phoneDndActive) {
            return true;
        }
        return silentOnPhoneSilentEnabled && phoneNotificationSilent;
    }

    private NotificationDndPolicy() {}
}
