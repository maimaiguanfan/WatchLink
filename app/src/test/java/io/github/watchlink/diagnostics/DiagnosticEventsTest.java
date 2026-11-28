package io.github.watchlink.diagnostics;

import java.util.ArrayDeque;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DiagnosticEventsTest {
    @Test public void allowlistsOnlyStructuredMetadataAndStripsUnknownFields() {
        assertEquals("CLOCK_LIVE_ACTION operation=SNOOZE result=VERIFIED", DiagnosticEvents.event(
                "CLOCK_LIVE_ACTION run=private-session operation=SNOOZE label=private result=VERIFIED device=mac"));
        assertEquals("HEALTH_DISPATCH_START_SERVICE mode=START_SERVICE", DiagnosticEvents.event(
                "HEALTH_DISPATCH_START_SERVICE mode=START_SERVICE title=my-alarm"));
        assertEquals("HEALTH_DISPATCH_FALLBACK_DIRECT mode=DIRECT_SDK", DiagnosticEvents.event(
                "HEALTH_DISPATCH_FALLBACK_DIRECT mode=DIRECT_SDK mac=AA:BB:CC"));
        assertEquals("HEALTH_DEVICE_ACTION_INTENT operation=DISMISS", DiagnosticEvents.event(
                "HEALTH_DEVICE_ACTION_INTENT operation=DISMISS id=12345"));
        assertEquals("HEALTH_TEST_STATUS result=SNOOZE action_source=SDK_AND_INTENT", DiagnosticEvents.event(
                "HEALTH_TEST_STATUS result=SNOOZE action_source=SDK_AND_INTENT secret=abc"));
        assertEquals("CLOCK_SYMBOLS_RESOLVED source=CLOCK resolver=DEX_SCAN", DiagnosticEvents.event(
                "CLOCK_SYMBOLS_RESOLVED source=CLOCK resolver=DEX_SCAN class=secret"));
        assertEquals("CALENDAR_SYMBOLS_RESOLVED source=CALENDAR resolver=CACHE", DiagnosticEvents.event(
                "CALENDAR_SYMBOLS_RESOLVED source=CALENDAR resolver=CACHE title=meeting"));
        assertEquals("CALENDAR_LIVE_ACTION operation=SNOOZE ui_mode=LOCK_SCREEN result=APPLIED", DiagnosticEvents.event(
                "CALENDAR_LIVE_ACTION operation=SNOOZE ui_mode=LOCK_SCREEN result=APPLIED title=private"));
        assertEquals("MODULE_HOT_RELOADED source=HEALTH api=102", DiagnosticEvents.event(
                "MODULE_HOT_RELOADED source=HEALTH api=102 path=/data/app/x"));
        assertEquals("HOT_RELOAD_RESULT result=SUCCEEDED", DiagnosticEvents.event(
                "HOT_RELOAD_RESULT result=SUCCEEDED detail=private"));
        assertEquals("HEALTH_DISPATCH_BG_RESTRICTED type=BackgroundServiceStartNotAllowedException",
                DiagnosticEvents.event("HEALTH_DISPATCH_BG_RESTRICTED type=BackgroundServiceStartNotAllowedException"));
        assertEquals("HEALTH_LIVE_DISPATCH source=UMS live_op=CREATE live_event=PICK_UP mode=START_SERVICE",
                DiagnosticEvents.event("HEALTH_LIVE_DISPATCH source=UMS live_op=CREATE live_event=PICK_UP mode=START_SERVICE code=A739"));
        assertEquals("UMS_SYMBOLS_RESOLVED source=UMS resolver=DEX_SCAN",
                DiagnosticEvents.event("UMS_SYMBOLS_RESOLVED source=UMS resolver=DEX_SCAN title=Cotti"));
        assertEquals("HEALTH_NOTIFY_DISCONNECT_STAGED source=HEALTH",
                DiagnosticEvents.event("HEALTH_NOTIFY_DISCONNECT_STAGED source=HEALTH text=secret"));
        assertEquals("HEALTH_NOTIFY_DISCONNECT_FLUSHED source=HEALTH",
                DiagnosticEvents.event("HEALTH_NOTIFY_DISCONNECT_FLUSHED source=HEALTH pkg=com.tencent.mm"));
        assertEquals("HEALTH_NOTIFY_SILENT_PUSH source=HEALTH silent_reason=DISCONNECT_STAGED",
                DiagnosticEvents.event("HEALTH_NOTIFY_SILENT_PUSH source=HEALTH silent_reason=DISCONNECT_STAGED title=x"));
        assertEquals("HEALTH_NOTIFY_SILENT_PUSH source=HEALTH silent_reason=BURST_THROTTLE",
                DiagnosticEvents.event("HEALTH_NOTIFY_SILENT_PUSH source=HEALTH silent_reason=BURST_THROTTLE pkg=secret"));
        assertEquals("HEALTH_BATTERY_PHONE_ALERT source=HEALTH battery_event=PHONE_FULL_LIMIT",
                DiagnosticEvents.event("HEALTH_BATTERY_PHONE_ALERT source=HEALTH battery_event=PHONE_FULL_LIMIT level=90"));
        assertEquals("HEALTH_BATTERY_WATCH_ALERT source=HEALTH battery_event=WATCH_LOW",
                DiagnosticEvents.event("HEALTH_BATTERY_WATCH_ALERT source=HEALTH battery_event=WATCH_LOW name=secret"));
        assertNull(DiagnosticEvents.event("UNKNOWN_EVENT private=anything"));
        assertNull(DiagnosticEvents.event("DAILY_HEALTH_OUTBOUND\nDAILY_HEALTH_DISMISS"));
    }

    @Test public void incomingFramesAreSanitizedAgainAndBoundedByNewestEvent() {
        StringBuilder packet = new StringBuilder("badtime DAILY_HEALTH_OUTBOUND\n1234567890123 secret\n");
        for (int i = 0; i <= DiagnosticEvents.LIMIT; i++)
            packet.append(1700000000000L + i).append(" DAILY_HEALTH_OUTBOUND run=private\n");
        ArrayDeque<String> rows = DiagnosticEvents.read(packet.toString());
        assertEquals(DiagnosticEvents.LIMIT, rows.size());
        assertEquals("1700000000001 DAILY_HEALTH_OUTBOUND", rows.getFirst());
        assertEquals((1700000000000L + DiagnosticEvents.LIMIT) + " DAILY_HEALTH_OUTBOUND", rows.getLast());
        assertTrue(DiagnosticEvents.read("x".repeat(DiagnosticEvents.MAX_TEXT + 1)).isEmpty());
    }
}
