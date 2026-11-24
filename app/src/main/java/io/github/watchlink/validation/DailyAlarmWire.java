package io.github.watchlink.validation;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import java.util.UUID;

/** Point-to-point messages between already-running Clock and Health hosts. Receivers verify OS sender identity. */
public final class DailyAlarmWire {
    private static final String PREFIX = BuildConfig.APPLICATION_ID + ".cooperative.DAILY_";
    public static final String OPEN = PREFIX + "OPEN";
    public static final String END = PREFIX + "END";
    public static final String STATUS = PREFIX + "STATUS";
    public static final String HEALTH_READY = PREFIX + "HEALTH_READY";

    public static final long MAX_WINDOW = 10 * 60_000L;
    public static final long TEST_WINDOW = 90_000L;

    public static boolean isRun(String run) {
        if (run == null || run.length() != 36) return false;
        try { return UUID.fromString(run).toString().equals(run); }
        catch (IllegalArgumentException malformed) { return false; }
    }

    public static boolean inWindow(long at, long elapsed, long maxMs) {
        return inWindow(at, elapsed, maxMs, System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    public static boolean inWindow(long at, long elapsed, long maxMs, long wallNow, long monotonicNow) {
        return at > 0 && elapsed >= 0 && maxMs > 0
                && wallNow >= at && wallNow - at < maxMs
                && monotonicNow >= elapsed && monotonicNow - elapsed < maxMs;
    }

    public static Intent route(String action, String pkg) {
        return new Intent(action).setPackage(pkg)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND
                        | Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);
    }

    @RequiresApi(34)
    public static void send(Context sender, Intent message) {
        sender.sendBroadcast(message, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    private DailyAlarmWire() {}
}
