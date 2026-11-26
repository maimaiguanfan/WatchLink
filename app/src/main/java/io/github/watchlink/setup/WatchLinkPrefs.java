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
    public static final String KEY_RAW_COMMAND_DEBUG = "raw_command_debug_enabled";
    public static final String KEY_UPDATED_AT = "updated_at";

    public static final int DEFAULT_GAME_FLASHBACK_LEAD_SEC = 5;
    public static final int MIN_GAME_FLASHBACK_LEAD_SEC = 0;
    public static final int MAX_GAME_FLASHBACK_LEAD_SEC = 30;

    public static int clampGameFlashbackLeadSec(int leadSec) {
        return Math.max(MIN_GAME_FLASHBACK_LEAD_SEC, Math.min(MAX_GAME_FLASHBACK_LEAD_SEC, leadSec));
    }

    private WatchLinkPrefs() {}
}
