package io.github.watchlink.alarm.probe;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Trusted host package identities and process names for live alarm and calendar reminders. */
public enum TargetProfile {
    CLOCK("com.coloros.alarmclock",
            certificates("159e6f37c336f2f41272542dbc1dc85721dd567ed4ac68ac13f2e43003ce0dce",
                    "64aafaf1d5bc9155a9e417a849e4f8eda1d0d1341667c28ed7c443c76f820b9a")),
    CALENDAR("com.coloros.calendar",
            certificates("4e61b5b0c669ff77214bbc321c85f54e4d86d60c446f14ac4f126d4e66f71cd7",
                    "7e18a2f30df7f24934d856fca0ba721bc94c728898f30cccbbab398606c921ac")),
    HEALTH("com.huawei.health",
            certificates("b92825c2bd5d6d6d1e7f39eecd17843b7d9016f611136b75441bc6f4d3f00f05"));

    public final String packageName;
    private final Set<String> signerSha256;

    TargetProfile(String packageName, Set<String> signerSha256) {
        this.packageName = packageName;
        this.signerSha256 = signerSha256;
    }

    private static Set<String> certificates(String... hashes) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(hashes)));
    }

    public boolean matchesIdentity(String pkg, int uid, int installedUid, String cert) {
        return packageName.equals(pkg) && uid >= 0 && uid == installedUid && signerSha256.contains(cert);
    }

    public static TargetProfile fromPackage(String packageName) {
        for (TargetProfile profile : values()) {
            if (profile.packageName.equals(packageName)) return profile;
        }
        return null;
    }

    public boolean isAlertSource() {
        return this == CLOCK || this == CALENDAR;
    }

    /** Clock and Calendar run in their main processes; Huawei Health handles watch alarms in :PhoneService. */
    public boolean isReminderProcess(String processName) {
        return (this == HEALTH ? packageName + ":PhoneService" : packageName).equals(processName);
    }

    /** Huawei Health hosts NotificationPushListener in :DaemonService for notification pictures and VoIP. */
    public boolean isDaemonProcess(String processName) {
        return this == HEALTH && (packageName + ":DaemonService").equals(processName);
    }

    public boolean isAnyHostedProcess(String processName) {
        return isReminderProcess(processName) || isDaemonProcess(processName);
    }
}
