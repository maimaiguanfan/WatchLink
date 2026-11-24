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
            + "CLOCK_SYMBOLS_RESOLVED HEALTH_SYMBOLS_RESOLVED "
            + "ALARM_INSTALL_OWNER_MISMATCH ALARM_INSTALL_WORKER_FAILED "
            + "REMINDER_APPLICATION_CALLBACK_UNAVAILABLE REMINDER_APPLICATION_CONTEXT_FALLBACK REMINDER_APPLICATION_START_FAILED "
            + "REMINDER_ADAPTER_READY REMINDER_ADAPTER_UNAVAILABLE "
            + "REMOTE_PREFS_READY REMOTE_PREFS_UNAVAILABLE REMOTE_PREFS_SAVED REMOTE_PREFS_READ_FAILED REMOTE_PREFS_WRITE_FAILED "
            + "SCOPE_REQUEST_FAILED SCOPE_READ_FAILED SCOPE_HOST_READ_FAILED REMINDER_SCOPE_WITNESS_UNAVAILABLE "
            + "REMINDER_CLOCK_ACTION_RECEIVER_UNAVAILABLE REMINDER_CLOCK_ACTIONS_UNAVAILABLE "
            + "REMINDER_CLOCK_ACTION_SHAPE_UNAVAILABLE CLOCK_ACTION_UNREGISTER_FAILED "
            + "CLOCK_HOST_CONTROL_FAILED HEALTH_HOST_CONTROL_FAILED "
            + "DAILY_CLOCK_TITLE_UNAVAILABLE DAILY_CLOCK_OPEN_SENT DAILY_CLOCK_OPEN_FAILED "
            + "DAILY_CLOCK_END_UNCONFIRMED DAILY_CLOCK_EVENT_FAILED DAILY_CLOCK_QUEUE_FULL "
            + "DAILY_CLOCK_RING_CHANGED DAILY_CLOCK_HEALTH_OUTBOUND DAILY_CLOCK_HEALTH_UNANSWERED "
            + "DAILY_CLOCK_STATUS "
            + "HEALTH_SDK_OBSERVER_READY HEALTH_SDK_OBSERVER_DEGRADED HEALTH_SERVICE_WITNESS_UNAVAILABLE "
            + "HEALTH_DISPATCH_START_SERVICE HEALTH_DISPATCH_BG_RESTRICTED HEALTH_DISPATCH_DENIED "
            + "HEALTH_DISPATCH_FALLBACK_DIRECT HEALTH_DISPATCH_FAILED "
            + "HEALTH_DEVICE_ACTION_INTENT HEALTH_SDK_ACTION HEALTH_ACTION_DUAL_CONFIRMED "
            + "DAILY_HEALTH_READY DAILY_HEALTH_UNAVAILABLE DAILY_HEALTH_SERVICE_RECEIVED "
            + "DAILY_HEALTH_OUTBOUND DAILY_HEALTH_DISMISS DAILY_HEALTH_SNOOZE "
            + "DAILY_HEALTH_CLOSE_OUTBOUND DAILY_HEALTH_CONTROL_UNAVAILABLE DAILY_HEALTH_REPORT_UNAVAILABLE "
            + "HEALTH_TEST_REQUESTED HEALTH_TEST_STATUS HEALTH_TEST_FINISHED "
            + "CLOCK_LIVE_ACTION CLOCK_ACTION_TERMINAL CLOCK_ACTION_BINDING_UNAVAILABLE "
            + "CLOCK_ACTION_NATIVE_SNOOZE_UNAVAILABLE CLOCK_ACTION_SCHEDULE_CHANGED CLOCK_ACTION_SERVICE_CHANGED "
            + "CLOCK_RING_BOUND CLOCK_DISMISS_BOUND CLOCK_SNOOZE_BOUND CLOCK_TERMINAL_UNCONFIRMED "
            + "CLOCK_RING_SNAPSHOT_FAILED CLOCK_RING_SNAPSHOT_UNCONFIRMED CLOCK_RING_QUEUE_FULL "
            + "ALARM_BEFORE_FAILED ALARM_AFTER_FAILED ALARM_CAPTURE_FAILED ALARM_HOOK_ROLLBACK_FAILED");

    private static final Set<String> SAFE_FIELDS = words(
            "source=CLOCK source=HEALTH mode=START_SERVICE mode=DIRECT_SDK "
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
