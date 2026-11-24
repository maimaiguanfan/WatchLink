package io.github.watchlink.alarm.probe;

import org.junit.Test;
import static org.junit.Assert.*;

public class TargetProfileTest {
    private static final String HEALTH_CERT = "b92825c2bd5d6d6d1e7f39eecd17843b7d9016f611136b75441bc6f4d3f00f05";
    private static final String CLOCK_CERT = "159e6f37c336f2f41272542dbc1dc85721dd567ed4ac68ac13f2e43003ce0dce";

    @Test public void authenticatesOnlyExactPackageUidAndOfficialSigner() {
        for (TargetProfile profile : TargetProfile.values()) {
            String cert = profile == TargetProfile.CLOCK ? CLOCK_CERT : HEALTH_CERT;
            String otherCert = profile == TargetProfile.CLOCK ? HEALTH_CERT : CLOCK_CERT;
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

    @Test public void remindersUseOnlyClockMainAndHealthPhoneService() {
        TargetProfile clock = TargetProfile.CLOCK;
        TargetProfile health = TargetProfile.HEALTH;
        assertTrue(clock.isReminderProcess(clock.packageName));
        assertTrue(health.isReminderProcess(health.packageName + ":PhoneService"));
        assertFalse(health.isReminderProcess(health.packageName));
        assertFalse(clock.isReminderProcess(clock.packageName + ":PhoneService"));
        assertFalse(health.isReminderProcess(clock.packageName + ":PhoneService"));
        assertFalse(health.isReminderProcess(health.packageName + ":PhoneServiceHelper"));
        assertFalse(clock.isReminderProcess(null));
    }
}
