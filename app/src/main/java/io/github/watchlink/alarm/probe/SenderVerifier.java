package io.github.watchlink.alarm.probe;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.security.MessageDigest;

/** Receiver-side authentication using OS-supplied UID, package name, and APK signing cert. */
public final class SenderVerifier {
    private SenderVerifier() {}

    /** Verifies the installed package matches the official signing certificate. */
    public static boolean trustedReminderTarget(Context context, TargetProfile profile) {
        return authenticatedPackage(context, profile, profile.packageName, null) != null;
    }

    /** Verifies an incoming IPC sender against the installed package UID and official signer. */
    public static boolean verifyReminderSender(Context context, TargetProfile profile, int uid, String pkg) {
        return authenticatedPackage(context, profile, pkg, uid) != null;
    }

    private static PackageInfo authenticatedPackage(Context context, TargetProfile profile, String pkg, Integer uid) {
        if (Build.VERSION.SDK_INT < 34 || profile == null || !profile.packageName.equals(pkg)) return null;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.applicationInfo == null || info.signingInfo == null) return null;
            Signature[] signers = info.signingInfo.getApkContentsSigners();
            if (signers == null || signers.length != 1) return null;
            int installedUid = info.applicationInfo.uid;
            return profile.matchesIdentity(pkg, uid == null ? installedUid : uid, installedUid,
                    certificateHash(signers[0])) ? info : null;
        } catch (PackageManager.NameNotFoundException | RuntimeException | java.security.NoSuchAlgorithmException unavailable) {
            return null;
        }
    }

    private static String certificateHash(Signature signer) throws java.security.NoSuchAlgorithmException {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray());
        StringBuilder hex = new StringBuilder(64);
        for (byte value : hash) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }
}
