package io.github.watchlink.setup;

/** Shared RemotePreferences group and keys used across WatchLink UI, Clock, Calendar, and Health. */
public final class WatchLinkPrefs {
    public static final String GROUP = "watchlink_settings_v1";
    public static final String KEY_TIMER_ENABLED = "countdown_enabled";
    public static final String KEY_TIMER_LIVE = "countdown_live_window_enabled";
    public static final String KEY_TIMER_RESTART = "countdown_restart_on_snooze";

    public static boolean timerKey(String key) {
        return KEY_TIMER_ENABLED.equals(key) || KEY_TIMER_LIVE.equals(key) || KEY_TIMER_RESTART.equals(key);
    }

    public static final String KEY_ENABLED = "daily_reminder_enabled";
    public static final String KEY_CALENDAR_ENABLED = "calendar_reminder_enabled";
    public static final String KEY_WIFI_AUTO_SYNC_ENABLED = "wifi_auto_sync_enabled";
    public static final String KEY_LIVE_WINDOW_ENABLED = "live_window_enabled";
    public static final String KEY_GAME_FLASHBACK_ENABLED = "game_flashback_enabled";
    public static final String KEY_GAME_FLASHBACK_LEAD_SEC = "game_flashback_lead_sec";
    public static final String KEY_NOTIFY_MUTE_DND_ENABLED = "notify_mute_dnd_enabled";
    public static final String KEY_NOTIFY_SILENT_PUSH_ENABLED = "notify_silent_push_enabled";
    public static final String KEY_NOTIFY_SILENT_SCREEN_ON_ENABLED = "notify_silent_screen_on_enabled";
    public static final String KEY_NOTIFY_SILENT_FOCUS_ENABLED = "notify_silent_focus_enabled";
    public static final String KEY_NOTIFY_SILENT_GAME_ENABLED = "notify_silent_game_enabled";
    public static final String KEY_NOTIFY_DISCONNECT_STAGE_ENABLED = "notify_disconnect_stage_enabled";
    public static final String KEY_SLEEP_AUTO_DND_ENABLED = "sleep_auto_dnd_enabled";
    public static final String KEY_WAKE_EXIT_DND_ENABLED = "wake_exit_dnd_enabled";
    public static final String KEY_SLEEP_PAUSE_MEDIA_ENABLED = "sleep_pause_media_enabled";
    public static final String KEY_SLEEP_STOP_VIDEO_ENABLED = "sleep_stop_video_enabled";
    public static final String KEY_SLEEP_STOP_VIDEO_CUSTOM_PKGS = "sleep_stop_video_custom_pkgs";
    public static final String KEY_MYDEVICES_CARD_ENABLED = "mydevices_card_enabled";
    public static final String KEY_MYDEVICES_TAP_TARGET = "mydevices_tap_target";
    public static final String KEY_RAW_COMMAND_DEBUG = "raw_command_debug_enabled";
    public static final String KEY_UPDATED_AT = "updated_at";

    public static final String TAP_TARGET_HEALTH = "HEALTH";
    public static final String TAP_TARGET_WATCHLINK = "WATCHLINK";

    public static String normalizeTapTarget(String raw) {
        return TAP_TARGET_WATCHLINK.equals(raw) ? TAP_TARGET_WATCHLINK : TAP_TARGET_HEALTH;
    }

    public static final int DEFAULT_GAME_FLASHBACK_LEAD_SEC = 5;
    public static final int MIN_GAME_FLASHBACK_LEAD_SEC = 0;
    public static final int MAX_GAME_FLASHBACK_LEAD_SEC = 300;

    public static int clampGameFlashbackLeadSec(int leadSec) {
        return Math.max(MIN_GAME_FLASHBACK_LEAD_SEC, Math.min(MAX_GAME_FLASHBACK_LEAD_SEC, leadSec));
    }

    private WatchLinkPrefs() {}
}
