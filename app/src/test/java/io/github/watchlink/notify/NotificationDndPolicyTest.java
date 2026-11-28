package io.github.watchlink.notify;

import io.github.watchlink.alarm.probe.TargetProfile;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationDndPolicyTest {
    @Test public void detectsDndFromInterruptionFilterAndZenModeFallback() {
        assertFalse(NotificationDndPolicy.isPhoneDndActive(
                NotificationDndPolicy.INTERRUPTION_FILTER_ALL, 0));
        assertFalse(NotificationDndPolicy.isPhoneDndActive(
                NotificationDndPolicy.INTERRUPTION_FILTER_ALL, 1));
        assertTrue(NotificationDndPolicy.isPhoneDndActive(
                NotificationDndPolicy.INTERRUPTION_FILTER_PRIORITY, 0));
        assertTrue(NotificationDndPolicy.isPhoneDndActive(
                NotificationDndPolicy.INTERRUPTION_FILTER_NONE, 0));
        assertTrue(NotificationDndPolicy.isPhoneDndActive(
                NotificationDndPolicy.INTERRUPTION_FILTER_ALARMS, 0));
        assertFalse(NotificationDndPolicy.isPhoneDndActive(
                NotificationDndPolicy.INTERRUPTION_FILTER_UNKNOWN, 0));
        assertTrue(NotificationDndPolicy.isPhoneDndActive(
                NotificationDndPolicy.INTERRUPTION_FILTER_UNKNOWN, 1));
    }

    @Test public void detectsSilentAndNoVibratePhoneNotifications() {
        // IMPORTANCE_LOW / IMPORTANCE_MIN are always silent.
        assertTrue(NotificationDndPolicy.isPhoneNotificationSilent(
                NotificationDndPolicy.IMPORTANCE_LOW, false, true, NotificationDndPolicy.IMPORTANCE_LOW, true, true));
        assertTrue(NotificationDndPolicy.isPhoneNotificationSilent(
                NotificationDndPolicy.IMPORTANCE_MIN, false, false, NotificationDndPolicy.IMPORTANCE_UNSPECIFIED, false, false));

        // Ambient flag marks silent.
        assertTrue(NotificationDndPolicy.isPhoneNotificationSilent(
                NotificationDndPolicy.IMPORTANCE_DEFAULT, true, true, NotificationDndPolicy.IMPORTANCE_DEFAULT, true, true));

        // High/default importance channel configured with NO sound and NO vibration -> silent.
        assertTrue(NotificationDndPolicy.isPhoneNotificationSilent(
                NotificationDndPolicy.IMPORTANCE_DEFAULT, false, true, NotificationDndPolicy.IMPORTANCE_DEFAULT, false, false));

        // Normal vibrating or sounding channel -> NOT silent.
        assertFalse(NotificationDndPolicy.isPhoneNotificationSilent(
                NotificationDndPolicy.IMPORTANCE_DEFAULT, false, true, NotificationDndPolicy.IMPORTANCE_DEFAULT, false, true));
        assertFalse(NotificationDndPolicy.isPhoneNotificationSilent(
                NotificationDndPolicy.IMPORTANCE_DEFAULT, false, true, NotificationDndPolicy.IMPORTANCE_DEFAULT, true, false));
        assertFalse(NotificationDndPolicy.isPhoneNotificationSilent(
                NotificationDndPolicy.IMPORTANCE_DEFAULT, false, false, NotificationDndPolicy.IMPORTANCE_UNSPECIFIED, false, false));
    }

    @Test public void routesDndAndPhoneSilentNotificationsToSilentWatchPush() {
        // Both toggles off -> never silent push.
        assertFalse(NotificationDndPolicy.shouldPushSilentlyToWatch(
                false, false, true, true, "com.tencent.mm", "msg"));

        // DND toggle on + DND active -> silent push even for normal vibrating phone notifications.
        assertTrue(NotificationDndPolicy.shouldPushSilentlyToWatch(
                true, false, true, false, "com.tencent.mm", "msg"));
        assertFalse(NotificationDndPolicy.shouldPushSilentlyToWatch(
                true, false, false, true, "com.tencent.mm", "msg"));

        // Phone-silent toggle on + phone notification silent -> silent push even when DND is off.
        assertTrue(NotificationDndPolicy.shouldPushSilentlyToWatch(
                false, true, false, true, "com.tencent.mm", "msg"));
        assertFalse(NotificationDndPolicy.shouldPushSilentlyToWatch(
                false, true, false, false, "com.tencent.mm", "msg"));
    }

    @Test public void neverSilencesClockAlarmsOrCalls() {
        assertFalse(NotificationDndPolicy.shouldPushSilentlyToWatch(
                true, true, true, true, TargetProfile.CLOCK.packageName, "msg"));
        assertFalse(NotificationDndPolicy.shouldPushSilentlyToWatch(
                true, true, true, true, "com.example.clock", NotificationDndPolicy.CATEGORY_ALARM));
        assertFalse(NotificationDndPolicy.shouldPushSilentlyToWatch(
                true, true, true, true, "com.tencent.mm", NotificationDndPolicy.CATEGORY_CALL));
    }
}
