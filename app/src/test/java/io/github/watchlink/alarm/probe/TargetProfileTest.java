package io.github.watchlink.alarm.probe;

import org.junit.Test;
import static org.junit.Assert.*;

public class TargetProfileTest {
    private static final String HEALTH_CERT = "b92825c2bd5d6d6d1e7f39eecd17843b7d9016f611136b75441bc6f4d3f00f05";
    private static final String CLOCK_CERT = "159e6f37c336f2f41272542dbc1dc85721dd567ed4ac68ac13f2e43003ce0dce";
    private static final String CALENDAR_CERT = "4e61b5b0c669ff77214bbc321c85f54e4d86d60c446f14ac4f126d4e66f71cd7";
    private static final String WIRELESS_CERT = "e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e";

    @Test public void authenticatesOnlyExactPackageUidAndOfficialSigner() {
        for (TargetProfile profile : TargetProfile.values()) {
            String cert = switch (profile) {
                case CLOCK, UMS -> CLOCK_CERT;
                case CALENDAR -> CALENDAR_CERT;
                case HEALTH -> HEALTH_CERT;
                case WIRELESS_SETTINGS -> WIRELESS_CERT;
            };
            String otherCert = profile == TargetProfile.HEALTH ? CLOCK_CERT : HEALTH_CERT;
            assertTrue(profile.matchesIdentity(profile.packageName, 42, 42, cert));
            assertFalse(profile.matchesIdentity(profile.packageName, 51, 42, cert));
            assertFalse(profile.matchesIdentity(profile.packageName, -1, -1, cert));
            assertFalse(profile.matchesIdentity("other.app", 42, 42, cert));
            assertFalse(profile.matchesIdentity(profile.packageName, 42, 42, otherCert));
            assertFalse(profile.matchesIdentity(profile.packageName, 42, 42, ""));
            assertFalse(profile.matchesIdentity(profile.packageName, 42, 42, null));
        }
    }

    @Test public void unknownSenderHasNoTargetProfile() {
        assertNull(TargetProfile.fromPackage(null));
        assertNull(TargetProfile.fromPackage("com.android.systemui"));
        assertNull(TargetProfile.fromPackage("com.huawei.health.evil"));
    }

    @Test public void remindersUseOnlyClockMainCalendarMainAndHealthPhoneService() {
        TargetProfile clock = TargetProfile.CLOCK;
        TargetProfile calendar = TargetProfile.CALENDAR;
        TargetProfile health = TargetProfile.HEALTH;
        TargetProfile wireless = TargetProfile.WIRELESS_SETTINGS;
        TargetProfile ums = TargetProfile.UMS;
        assertTrue(clock.isAlertSource());
        assertTrue(calendar.isAlertSource());
        assertFalse(health.isAlertSource());
        assertFalse(wireless.isAlertSource());
        assertFalse(ums.isAlertSource());
        assertTrue(clock.isReminderProcess(clock.packageName));
        assertTrue(calendar.isReminderProcess(calendar.packageName));
        assertFalse(calendar.isReminderProcess(calendar.packageName + ":hlog"));
        assertTrue(wireless.isReminderProcess(wireless.packageName));
        assertFalse(wireless.isReminderProcess(wireless.packageName + ":tzupdate"));
        assertTrue(ums.isReminderProcess(ums.packageName));
        assertFalse(ums.isReminderProcess(ums.packageName + ":remote"));
        assertTrue(health.isReminderProcess(health.packageName + ":PhoneService"));
        assertTrue(health.isHealthDaemonProcess(health.packageName + ":DaemonService"));
        assertFalse(health.isHealthDaemonProcess(health.packageName + ":PhoneService"));
        assertFalse(ums.isHealthDaemonProcess(ums.packageName + ":DaemonService"));
        assertFalse(health.isReminderProcess(health.packageName));
        assertFalse(clock.isReminderProcess(clock.packageName + ":PhoneService"));
        assertFalse(health.isReminderProcess(clock.packageName + ":PhoneService"));
        assertFalse(health.isReminderProcess(health.packageName + ":PhoneServiceHelper"));
        assertFalse(clock.isReminderProcess(null));
    }
}
