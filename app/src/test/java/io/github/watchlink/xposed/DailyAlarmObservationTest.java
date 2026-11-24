package io.github.watchlink.xposed;

import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.validation.HuaweiReminderService;
import org.junit.Test;
import static org.junit.Assert.*;

public class DailyAlarmObservationTest {
    private static final byte[] OPEN = {8, 9, 1, 4, 0, 0, 0, 7};
    private static final byte[] DISMISS = {8, 10, 1, 4, 0, 0, 0, 7, 2, 1, 2};
    private static final byte[] SNOOZE = {8, 10, 1, 4, 0, 0, 0, 7, 2, 1, 1};
    private final DailyAlarmObservation windows = new DailyAlarmObservation();

    private static String id(String run, int reminder) {
        return HuaweiReminderService.dailyIdentifier(run, reminder, 1);
    }

    private void armSdk() {
        assertEquals("READY", windows.arm("first", 7, 1000, 500, "watch", id("first", 7), 1002, 502, true));
    }

    @Test public void sdkActionThenMatchingServiceIntentUpgradesToDualConfirmedWithoutDuplicatingAction() {
        for (boolean snooze : new boolean[]{false, true}) {
            DailyAlarmObservation session = new DailyAlarmObservation();
            assertEquals("READY", session.arm("first", 7, 1000, 500, "watch", id("first", 7), 1000, 500, true));
            assertNull(session.observe(true, "watch", DISMISS, 1001, 501));
            assertNotNull(session.serviceReceived(7, id("first", 7), 1001, 501));
            assertNull(session.observe(false, "other", OPEN, 1001, 501));
            assertEquals("OUTBOUND", session.observe(false, "watch", OPEN, 1004, 504).kind());
            assertNull(session.observe(true, "other", DISMISS, 1005, 505));

            DailyAlarmObservation.Evidence primary = session.observe(true, "watch", snooze ? SNOOZE : DISMISS, 1006, 506);
            assertEquals(snooze ? "SNOOZE" : "DISMISS", primary.kind());
            assertEquals("SDK_ONLY", primary.actionSource());

            // Subsequent ExternalPhoneService alarm_clock_msg_from_device confirms dual agreement without re-emitting action.
            DailyAlarmObservation.Evidence confirm = session.serviceAction(7, snooze ? 1 : 2, 1008, 508);
            assertNotNull(confirm);
            assertEquals("ACTION_DUAL_CONFIRMED", confirm.kind());
            assertEquals("SDK_AND_INTENT", confirm.actionSource());

            // Duplicate or opposite replies are ignored.
            assertNull(session.serviceAction(7, snooze ? 1 : 2, 1009, 509));
            assertNull(session.serviceAction(7, snooze ? 2 : 1, 1010, 510));
            assertNull(session.observe(true, "watch", SNOOZE, 1011, 511));
        }
    }

    @Test public void degradedSdkModeDeliversActionFromExternalPhoneServiceIntentDirectly() {
        DailyAlarmObservation session = new DailyAlarmObservation();
        assertEquals("READY", session.arm("degraded", 7, 1000, 500, null, id("degraded", 7), 1001, 501, false));
        assertNull(session.serviceAction(7, 2, 1002, 502)); // Not dispatched yet.
        assertEquals("SERVICE_RECEIVED", session.serviceReceived(7, id("degraded", 7), 1003, 503).kind());

        DailyAlarmObservation.Evidence action = session.serviceAction(7, 2, 1005, 505);
        assertNotNull(action);
        assertEquals("DISMISS", action.kind());
        assertEquals("INTENT_ONLY", action.actionSource());
        assertNull(session.serviceAction(7, 1, 1006, 506));
    }

    @Test public void directSdkFallbackEnablesOutboundAndReplyWithoutStartServiceReceipt() {
        armSdk();
        windows.markDirectDispatched("first", 7);
        assertEquals("OUTBOUND", windows.observe(false, "watch", OPEN, 1005, 505).kind());
        DailyAlarmObservation.Evidence reply = windows.observe(true, "watch", SNOOZE, 1008, 508);
        assertNotNull(reply);
        assertEquals("SNOOZE", reply.kind());
        assertEquals("SDK_ONLY", reply.actionSource());
    }

    @Test public void phoneEndBeforeSdkCloseKeepsOnlyCloseObservation() {
        armSdk();
        assertNotNull(windows.serviceReceived(7, id("first", 7), 1001, 501));
        assertEquals("OUTBOUND", windows.observe(false, "watch", OPEN, 1002, 502).kind());
        assertTrue(windows.shouldClose("first", 7, 1050, 550));
        windows.end("first", 7, 1050, 550);
        assertNull(windows.observe(true, "watch", DISMISS, 1052, 552));
        assertNull(windows.serviceAction(7, 2, 1052, 552));
        assertEquals("CLOSE_OUTBOUND", windows.observe(false, "watch", SNOOZE, 1053, 553).kind());
        assertFalse(windows.shouldClose("first", 7, 1054, 554));
        assertNull(windows.observe(false, "watch", SNOOZE, 1054, 554));
        assertFalse(windows.active(6050, 5550));
    }

    @Test public void refusesOverlapReplayAndAbortReleasesSlot() {
        armSdk();
        assertEquals("UNAVAILABLE", windows.arm("overlap", 8, 1005, 505, "watch", id("overlap", 8), 1005, 505, true));
        windows.abort("first", 7);
        assertFalse(windows.active(1006, 506));
        assertEquals("READY", windows.arm("next", 9, 1007, 507, "watch", id("next", 9), 1007, 507, true));
    }

    @Test public void staleOrMissingWatchFailsClosedWhenSdkAvailable() {
        assertEquals("UNAVAILABLE", windows.arm("stale", 7, 1000, 500, "watch", id("stale", 7), 16000, 505, true));
        assertEquals("UNAVAILABLE", windows.arm("no-watch", 8, 990, 490, null, id("no-watch", 8), 1000, 500, true));
    }

    @Test public void calendarSourceRoutesRepliesBackToCalendarAndIsolatesCrossSourceEnd() {
        DailyAlarmObservation session = new DailyAlarmObservation();
        assertEquals("READY", session.arm(TargetProfile.CALENDAR, "cal-run", 7, 1000, 500,
                "watch", id("cal-run", 7), 1001, 501, true));
        DailyAlarmObservation.Evidence recv = session.serviceReceived(7, id("cal-run", 7), 1002, 502);
        assertNotNull(recv);
        assertEquals(TargetProfile.CALENDAR, recv.source());
        assertEquals("OUTBOUND", session.observe(false, "watch", OPEN, 1003, 503).kind());

        // A stray Clock END cannot terminate an active Calendar session.
        assertFalse(session.shouldClose(TargetProfile.CLOCK, "cal-run", 7, 1004, 504));
        session.end(TargetProfile.CLOCK, "cal-run", 7, 1004, 504);

        DailyAlarmObservation.Evidence reply = session.observe(true, "watch", DISMISS, 1005, 505);
        assertNotNull(reply);
        assertEquals(TargetProfile.CALENDAR, reply.source());
        assertEquals("DISMISS", reply.kind());

        assertTrue(session.shouldClose(TargetProfile.CALENDAR, "cal-run", 7, 1006, 506));
        session.end(TargetProfile.CALENDAR, "cal-run", 7, 1006, 506);
        assertNull(session.serviceAction(7, 1, 1007, 507));
    }
}
