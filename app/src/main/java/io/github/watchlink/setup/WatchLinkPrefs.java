package io.github.watchlink.setup;

/** Shared RemotePreferences group and keys used across WatchLink UI, Clock, Calendar, and Health. */
public final class WatchLinkPrefs {
    public static final String GROUP = "watchlink_settings_v1";
    public static final String KEY_ENABLED = "daily_reminder_enabled";
    public static final String KEY_CALENDAR_ENABLED = "calendar_reminder_enabled";
    public static final String KEY_WIFI_AUTO_SYNC_ENABLED = "wifi_auto_sync_enabled";
    public static final String KEY_LIVE_WINDOW_ENABLED = "live_window_enabled";
    public static final String KEY_UPDATED_AT = "updated_at";

    private WatchLinkPrefs() {}
}
