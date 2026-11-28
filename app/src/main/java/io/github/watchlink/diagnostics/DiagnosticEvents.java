package io.github.watchlink.diagnostics;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Only fixed event names and enumerated fields can enter a diagnostic export. */
final class DiagnosticEvents {
    static final int LIMIT = 120;
    static final int MAX_TEXT = LIMIT * 192;

    private static Set<String> words(String values) {
        return new HashSet<>(Arrays.asList(values.split(" ")));
    }

    private static final Set<String> EVENTS = words(
            "OWNER_STARTED MODULE_LOADED MODULE_HOT_RELOADING MODULE_HOT_RELOADED MODULE_HOT_RELOAD_DEFERRED "
            + "HOT_RELOAD_REQUESTED HOT_RELOAD_RESULT "
            + "CLOCK_SYMBOLS_RESOLVED CALENDAR_SYMBOLS_RESOLVED HEALTH_SYMBOLS_RESOLVED UMS_SYMBOLS_RESOLVED "
            + "ALARM_INSTALL_OWNER_MISMATCH ALARM_INSTALL_WORKER_FAILED "
            + "REMINDER_APPLICATION_CALLBACK_UNAVAILABLE REMINDER_APPLICATION_CONTEXT_FALLBACK REMINDER_APPLICATION_START_FAILED "
            + "REMINDER_ADAPTER_READY REMINDER_ADAPTER_UNAVAILABLE "
            + "REMOTE_PREFS_READY REMOTE_PREFS_UNAVAILABLE REMOTE_PREFS_SAVED REMOTE_PREFS_READ_FAILED REMOTE_PREFS_WRITE_FAILED "
            + "SCOPE_REQUEST_FAILED SCOPE_READ_FAILED SCOPE_HOST_READ_FAILED REMINDER_SCOPE_WITNESS_UNAVAILABLE "
            + "REMINDER_CLOCK_ACTION_RECEIVER_UNAVAILABLE REMINDER_CLOCK_ACTIONS_UNAVAILABLE "
            + "REMINDER_CALENDAR_ACTIONS_UNAVAILABLE "
            + "REMINDER_CLOCK_ACTION_SHAPE_UNAVAILABLE CLOCK_ACTION_UNREGISTER_FAILED "
            + "CLOCK_HOST_CONTROL_FAILED CALENDAR_HOST_CONTROL_FAILED HEALTH_HOST_CONTROL_FAILED "
            + "DAILY_CLOCK_TITLE_UNAVAILABLE DAILY_CLOCK_OPEN_SENT DAILY_CLOCK_OPEN_FAILED "
            + "DAILY_CLOCK_END_UNCONFIRMED DAILY_CLOCK_EVENT_FAILED DAILY_CLOCK_QUEUE_FULL "
            + "DAILY_CLOCK_RING_CHANGED DAILY_CLOCK_HEALTH_OUTBOUND DAILY_CLOCK_HEALTH_UNANSWERED "
            + "DAILY_CLOCK_STATUS "
            + "DAILY_CALENDAR_OPEN_SENT DAILY_CALENDAR_OPEN_FAILED DAILY_CALENDAR_END_UNCONFIRMED "
            + "DAILY_CALENDAR_EVENT_FAILED DAILY_CALENDAR_QUEUE_FULL "
            + "DAILY_CALENDAR_HEALTH_OUTBOUND DAILY_CALENDAR_HEALTH_UNANSWERED DAILY_CALENDAR_STATUS "
            + "CALENDAR_LIVE_ACTION CALENDAR_ACTION_TERMINAL "
            + "HEALTH_SDK_OBSERVER_READY HEALTH_SDK_OBSERVER_DEGRADED HEALTH_SERVICE_WITNESS_UNAVAILABLE "
            + "HEALTH_WIFI_ACK_HOOK_READY HEALTH_WIFI_ACK_HOOK_DEGRADED "
            + "WIFI_SYNC_ONE_FAILED WIFI_SYNC_RESULT "
            + "WIFI_1B_HOOK_UNAVAILABLE WIFI_1B_CALLBACK_UNAVAILABLE "
            + "WIFI_1B_QUERY_SENT WIFI_1B_QUERY_FAILED WIFI_1B_QUERY_RESULT WIFI_1B_REPLY_FAILED "
            + "HEALTH_LIVE_READY HEALTH_LIVE_DISPATCH HEALTH_LIVE_TEST "
            + "DAEMON_LIVE_READY DAEMON_LIVE_FORWARD DAEMON_LIVE_REMOVE "
            + "DAEMON_NOTIFY_DND_MUTED DAEMON_NOTIFY_SILENT_PUSH DAEMON_DND_REQUEST "
            + "HEALTH_SLEEP_DND_READY HEALTH_SLEEP_DND_DEGRADED HEALTH_SLEEP_ACTION HEALTH_SLEEP_DND_APPLIED HEALTH_SLEEP_MEDIA_PAUSED "
            + "HEALTH_NOTIFY_SILENT_READY HEALTH_NOTIFY_SILENT_DEGRADED HEALTH_NOTIFY_SILENT_PUSH "
            + "HEALTH_WATCH_STATUS_READY HEALTH_WATCH_STATUS_DEGRADED "
            + "UMS_LIVE_READY UMS_LIVE_FORWARD UMS_LIVE_REMOVE "
            + "HEALTH_DISPATCH_START_SERVICE HEALTH_DISPATCH_BG_RESTRICTED HEALTH_DISPATCH_DENIED "
            + "HEALTH_DISPATCH_FALLBACK_DIRECT HEALTH_DISPATCH_FAILED "
            + "HEALTH_DEVICE_ACTION_INTENT HEALTH_SDK_ACTION HEALTH_ACTION_DUAL_CONFIRMED "
            + "DAILY_HEALTH_READY DAILY_HEALTH_UNAVAILABLE DAILY_HEALTH_SERVICE_RECEIVED "
            + "DAILY_HEALTH_OUTBOUND DAILY_HEALTH_DISMISS DAILY_HEALTH_SNOOZE "
            + "DAILY_HEALTH_CLOSE_OUTBOUND DAILY_HEALTH_CONTROL_UNAVAILABLE DAILY_HEALTH_REPORT_UNAVAILABLE "
            + "HEALTH_TEST_REQUESTED HEALTH_TEST_STATUS HEALTH_TEST_FINISHED "
            + "CLOCK_LIVE_ACTION CLOCK_ACTION_TERMINAL CLOCK_ACTION_BINDING_UNAVAILABLE "
            + "CLOCK_ACTION_NATIVE_SNOOZE_UNAVAILABLE CLOCK_ACTION_SCHEDULE_CHANGED CLOCK_ACTION_SERVICE_CHANGED "
            + "TIMER_CLOCK_UNAVAILABLE TIMER_CLOCK_RING TIMER_CLOCK_END TIMER_CLOCK_PLAYBACK_ENDED TIMER_CLOCK_ACTIONS_UNAVAILABLE TIMER_CLOCK_ACTION TIMER_CLOCK_READY CLOCK_RING_BOUND CLOCK_DISMISS_BOUND CLOCK_SNOOZE_BOUND CLOCK_TERMINAL_UNCONFIRMED "
            + "CLOCK_RING_SNAPSHOT_FAILED CLOCK_RING_SNAPSHOT_UNCONFIRMED CLOCK_RING_QUEUE_FULL "
            + "ALARM_BEFORE_FAILED ALARM_AFTER_FAILED ALARM_CAPTURE_FAILED ALARM_HOOK_ROLLBACK_FAILED");

    private static final Set<String> SAFE_FIELDS = words(
            "source=CLOCK source=CALENDAR source=HEALTH source=WIRELESS_SETTINGS source=UMS source=CLOCK_TIMER source=DAEMON source=TEST "
            + "mode=START_SERVICE mode=DIRECT_SDK "
            + "live_op=CREATE live_op=UPDATE live_op=CLOSE "
            + "live_event=DELIVERY live_event=TAXI live_event=PICK_UP live_event=QUEUE "
            + "sleep_action=FALL_ASLEEP sleep_action=WAKE_UP "
            + "silent_reason=DND silent_reason=FOCUS silent_reason=GAME silent_reason=SCREEN_ON silent_reason=PHONE_SILENT "
            + "media_result=PAUSED media_result=SKIP_IN_CALL media_result=SKIP_NOT_PLAYING media_result=SKIP_COOLDOWN media_result=FAILED "
            + "dnd_result=ENABLE_DND dnd_result=DISABLE_DND dnd_result=SKIP_ALREADY_DND dnd_result=SKIP_ALREADY_OWNED "
            + "dnd_result=SKIP_NOT_OWNED dnd_result=SKIP_EXPIRED dnd_result=SKIP_ALREADY_OFF "
            + "dnd_result=NO_POLICY_ACCESS dnd_result=DAEMON_FALLBACK dnd_result=FAILED "
            + "ui_mode=LOCK_SCREEN ui_mode=TOP_WINDOW ui_mode=FLUID_CARD ui_mode=LISTENER "
            + "api=101 api=102 "
            + "resolver=CACHE resolver=FAST_PATH resolver=DEX_SCAN resolver=DEGRADED resolver=UNAVAILABLE "
            + "operation=DISMISS operation=SNOOZE "
            + "action_source=SDK_AND_INTENT action_source=SDK_ONLY action_source=INTENT_ONLY "
            + "result=VERIFIED result=APPLIED result=REJECTED result=UNCERTAIN "
            + "result=SUCCEEDED result=UNSUPPORTED result=IN_PROGRESS result=PROCESS_DIED result=DEFERRED "
            + "result=SENDING result=SENT result=SENT_DIRECT result=SERVICE_RECEIVED result=OUTBOUND "
            + "result=SNOOZE result=DISMISS result=CLOSE_OUTBOUND result=CLOSED result=DISABLED "
            + "result=WATCH_UNAVAILABLE result=HEALTH_UNANSWERED result=BUSY result=BACKGROUND_RESTRICTED "
            + "result=DENIED result=FAILED result=UNAVAILABLE "
            + "result=READY_SUPPORTED_ON result=READY_SUPPORTED_UNSET result=READY_SUPPORTED_OFF "
            + "result=READY_UNSUPPORTED result=READY_UNKNOWN result=READY_DEVICE_MISMATCH "
            + "overlap=true overlap=false readback=true readback=false "
            + "local_clock_control=true local_clock_control=false "
            + "local_calendar_control=true local_calendar_control=false "
            + "remote_prefs=true remote_prefs=false sdk_observer=true sdk_observer=false "
            + "type=SecurityException type=IllegalStateException type=BackgroundServiceStartNotAllowedException "
            + "type=NoSuchMethodException type=ClassNotFoundException type=UnsupportedOperationException "
            + "type=IOException type=IllegalArgumentException type=RejectedExecutionException");

    static String event(String raw) {
        if (raw == null || raw.length() > 512 || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) return null;
        int space = raw.indexOf(' ');
        String name = space < 0 ? raw : raw.substring(0, space);
        if (!EVENTS.contains(name)) return null;
        StringBuilder safe = new StringBuilder(name);
        if (space >= 0) for (String field : raw.substring(space + 1).split(" ")) {
            if (SAFE_FIELDS.contains(field)) safe.append(' ').append(field);
        }
        return safe.length() > 170 ? null : safe.toString();
    }

    static void append(ArrayDeque<String> rows, String line) {
        if (rows.size() == LIMIT) rows.removeFirst();
        rows.addLast(line);
    }

    static ArrayDeque<String> read(String input) {
        ArrayDeque<String> safe = new ArrayDeque<>();
        if (input == null || input.length() > MAX_TEXT) return safe;
        for (String line : input.split("\n")) {
            int space = line.indexOf(' ');
            if (space != 13 || line.length() > 192) continue;
            String stamp = line.substring(0, space);
            if (!stamp.matches("[0-9]{13}")) continue;
            String event = event(line.substring(space + 1));
            if (event != null) append(safe, stamp + " " + event);
        }
        return safe;
    }

    private DiagnosticEvents() {}
}
