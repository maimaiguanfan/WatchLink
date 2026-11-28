package io.github.watchlink.notify;

import io.github.watchlink.alarm.probe.TargetProfile;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure-Java fixed-window per-app burst throttling policy.
 *
 * <p>Within {@code windowSec} seconds after a real vibrating notification from a given package,
 * subsequent notifications from the same package are downgraded to silent watch delivery
 * ({@code TLV 13 02 00 00}). Calls, alarms/timers, short-disconnect replays, WatchLink battery
 * alerts, and notifications that are already silent are never throttled and never arm the window.
 */
public final class NotificationBurstThrottlePolicy {
    static final int MAX_TRACKED_PACKAGES = 128;

    private final LinkedHashMap<String, Long> lastVibrateByPkg = new LinkedHashMap<>();

    public synchronized boolean shouldSilenceAndRecord(
            boolean enabled,
            int windowSec,
            String packageName,
            String category,
            boolean wechatCall,
            boolean noWechatCall,
            boolean isReplay,
            boolean isBatteryAlert,
            boolean alreadySilent,
            long nowElapsedMs) {
        if (!enabled || windowSec <= 0) {
            return false;
        }
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        if (isExempt(packageName, category, wechatCall, noWechatCall, isReplay, isBatteryAlert)) {
            return false;
        }
        if (alreadySilent) {
            return false;
        }

        long windowMs = windowSec * 1_000L;
        Long last = lastVibrateByPkg.get(packageName);
        if (last != null && nowElapsedMs >= last && (nowElapsedMs - last) < windowMs) {
            return true;
        }

        // Fixed window: record the vibrating notification timestamp and let it vibrate.
        lastVibrateByPkg.remove(packageName);
        lastVibrateByPkg.put(packageName, nowElapsedMs);
        pruneIfNeededLocked(nowElapsedMs, windowMs);
        return false;
    }

    public static boolean isExempt(
            String packageName,
            String category,
            boolean wechatCall,
            boolean noWechatCall,
            boolean isReplay,
            boolean isBatteryAlert) {
        if (isReplay || isBatteryAlert || wechatCall || noWechatCall) {
            return true;
        }
        if (TargetProfile.CLOCK.packageName.equals(packageName)) {
            return true;
        }
        return NotificationDndPolicy.CATEGORY_ALARM.equals(category)
                || NotificationDndPolicy.CATEGORY_CALL.equals(category);
    }

    public synchronized void clear() {
        lastVibrateByPkg.clear();
    }

    public synchronized int trackedCount() {
        return lastVibrateByPkg.size();
    }

    private void pruneIfNeededLocked(long nowElapsedMs, long windowMs) {
        if (lastVibrateByPkg.size() <= MAX_TRACKED_PACKAGES) {
            return;
        }
        Iterator<Map.Entry<String, Long>> it = lastVibrateByPkg.entrySet().iterator();
        while (it.hasNext()) {
            long ts = it.next().getValue();
            if (nowElapsedMs < ts || (nowElapsedMs - ts) >= windowMs) {
                it.remove();
            }
        }
        while (lastVibrateByPkg.size() > MAX_TRACKED_PACKAGES) {
            Iterator<String> oldest = lastVibrateByPkg.keySet().iterator();
            if (!oldest.hasNext()) break;
            oldest.next();
            oldest.remove();
        }
    }
}
