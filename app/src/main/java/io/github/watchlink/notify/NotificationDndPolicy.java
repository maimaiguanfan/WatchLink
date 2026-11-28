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

    public static final String REASON_DND = "DND";
    public static final String REASON_FOCUS = "FOCUS";
    public static final String REASON_GAME = "GAME";
    public static final String REASON_SCREEN_ON = "SCREEN_ON";
    public static final String REASON_PHONE_SILENT = "PHONE_SILENT";

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
     * Returns true when the phone screen is actively on and unlocked (user is actively using
     * the phone; lock-screen wakeups on incoming notifications are excluded).
     */
    public static boolean isScreenUnlocked(boolean interactive, boolean keyguardLocked) {
        return interactive && !keyguardLocked;
    }

    /**
     * Evaluates whether ColorOS Zen Space (deep or light zen) or Focus Mode is active.
     * Verified against ColorOS 17 com.oneplus.brickmode (op_breath_mode_status,
     * op_light_breath_mode_status) plus legacy focusmode_switch / focusmode_switch_new.
     */
    public static boolean isFocusOrZenSpaceActive(
            int secureBreathMode,
            int globalBreathMode,
            int secureLightBreathMode,
            int focusSwitch,
            int focusSwitchNew) {
        return secureBreathMode > 0
                || globalBreathMode > 0
                || secureLightBreathMode > 0
                || focusSwitch > 0
                || focusSwitchNew > 0;
    }

    /**
     * Evaluates whether ColorOS Game Space currently has a game in the foreground.
     * Verified live on PLK110 ColorOS 17 (debug_gamemode_value and gamemode_delayed flip 0 -> 1).
     */
    public static boolean isGameForegroundActive(int debugGameModeValue, int gameModeDelayed) {
        return debugGameModeValue > 0 || gameModeDelayed > 0;
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
     * Evaluates all independent silent-push switches and returns the matched reason constant
     * (DND / FOCUS / GAME / SCREEN_ON / PHONE_SILENT), or null when the notification should
     * remain normal (vibrating). Phone alarms, system clock notifications, and call notifications
     * are never silenced.
     */
    public static String evaluateSilentReason(
            boolean silentOnDndEnabled,
            boolean silentOnFocusEnabled,
            boolean silentOnGameEnabled,
            boolean silentOnScreenOnEnabled,
            boolean silentOnPhoneSilentEnabled,
            boolean phoneDndActive,
            boolean focusActive,
            boolean gameForeground,
            boolean screenUnlocked,
            boolean phoneNotificationSilent,
            String packageName,
            String category) {
        if (TargetProfile.CLOCK.packageName.equals(packageName)) {
            return null;
        }
        if (CATEGORY_ALARM.equals(category) || CATEGORY_CALL.equals(category)) {
            return null;
        }
        if (silentOnDndEnabled && phoneDndActive) {
            return REASON_DND;
        }
        if (silentOnFocusEnabled && focusActive) {
            return REASON_FOCUS;
        }
        if (silentOnGameEnabled && gameForeground) {
            return REASON_GAME;
        }
        if (silentOnScreenOnEnabled && screenUnlocked) {
            return REASON_SCREEN_ON;
        }
        if (silentOnPhoneSilentEnabled && phoneNotificationSilent) {
            return REASON_PHONE_SILENT;
        }
        return null;
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
        return evaluateSilentReason(
                silentOnDndEnabled, false, false, false, silentOnPhoneSilentEnabled,
                phoneDndActive, false, false, false, phoneNotificationSilent,
                packageName, category) != null;
    }

    private NotificationDndPolicy() {}
}
