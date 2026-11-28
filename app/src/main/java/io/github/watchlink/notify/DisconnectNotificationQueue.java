package io.github.watchlink.notify;

import io.github.watchlink.alarm.probe.TargetProfile;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java bounded staging queue for phone notifications that arrive during brief watch
 * disconnects. Retains at most {@link #MAX_STAGED} newest unique notifications within
 * {@link #TTL_MS} (1 minute). On reconnect delivery, at most 1 notification is allowed to
 * vibrate (specifically the newest non-silent notification, pushed last so watch shade order
 * stays chronological with the latest message on top); all remaining staged notifications are
 * marked {@code forceSilent = true} for silent watch delivery (TLV 13 02 00 00).
 */
public final class DisconnectNotificationQueue<T> {
    public static final long TTL_MS = 60_000L;
    public static final int MAX_STAGED = 5;

    public record DeliveryItem<T>(
            String dedupKey,
            T payload,
            long stagedAtElapsedMs,
            boolean originallySilent,
            boolean forceSilent) {}

    private record Entry<T>(
            String dedupKey,
            T payload,
            long stagedAtElapsedMs,
            boolean originallySilent) {}

    private final long ttlMs;
    private final int maxStaged;
    private final LinkedHashMap<String, Entry<T>> entries = new LinkedHashMap<>();

    public DisconnectNotificationQueue() {
        this(TTL_MS, MAX_STAGED);
    }

    public DisconnectNotificationQueue(long ttlMs, int maxStaged) {
        if (ttlMs <= 0L) throw new IllegalArgumentException("ttlMs must be positive");
        if (maxStaged <= 0) throw new IllegalArgumentException("maxStaged must be positive");
        this.ttlMs = ttlMs;
        this.maxStaged = maxStaged;
    }

    /**
     * Evaluates whether an incoming ACTION_NOTIFICATION_PUSH bundle represents an ordinary user
     * notification eligible for short-disconnect staging. Excludes switch-change control intents,
     * SmartEdge live notifications, VoIP/call ringing, alarms, and system clock reminders.
     */
    public static boolean isEligibleForStaging(
            String switchChangeType,
            String livePushType,
            boolean wechatCall,
            boolean noWechatCall,
            String packageName,
            String category) {
        if (switchChangeType != null && !switchChangeType.isEmpty()) return false;
        if ("LIVE_NOTIFICATION_PUSH".equals(livePushType)) return false;
        if (wechatCall || noWechatCall) return false;
        if (TargetProfile.CLOCK.packageName.equals(packageName)) return false;
        if (NotificationDndPolicy.CATEGORY_CALL.equals(category)
                || "imcall".equals(category)
                || NotificationDndPolicy.CATEGORY_ALARM.equals(category)) {
            return false;
        }
        return true;
    }

    /**
     * Derives a stable deduplication key from Huawei Health notification bundle fields.
     * Prefers Android's StatusBarNotification key when present so updates replace in-place
     * and phone-side removals match accurately.
     */
    public static String dedupKey(
            String notyKey,
            String altKey,
            String packageName,
            String title,
            String text) {
        if (notyKey != null && !notyKey.isEmpty()) return notyKey;
        if (altKey != null && !altKey.isEmpty()) return altKey;
        String pkg = packageName == null ? "" : packageName;
        String t = title == null ? "" : title;
        String body = text == null ? "" : text;
        return pkg + "|" + t + "|" + body;
    }

    /**
     * Stages a notification, pruning expired entries first. If {@code dedupKey} is already
     * staged, replaces it and refreshes its position/timestamp. If capacity exceeds
     * {@code maxStaged}, evicts the oldest entry.
     */
    public synchronized int stage(String dedupKey, T payload, long nowElapsedMs, boolean originallySilent) {
        if (dedupKey == null || dedupKey.isEmpty() || payload == null) return entries.size();
        pruneExpiredLocked(nowElapsedMs);
        entries.remove(dedupKey);
        entries.put(dedupKey, new Entry<>(dedupKey, payload, nowElapsedMs, originallySilent));
        while (entries.size() > maxStaged) {
            Iterator<Map.Entry<String, Entry<T>>> it = entries.entrySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        return entries.size();
    }

    /**
     * Re-stages an unsent item (e.g. if the watch disconnects mid-flush), preserving its
     * original timestamp so the 1-minute TTL is never artificially extended.
     */
    public synchronized boolean restageIfFresh(
            String dedupKey, T payload, long originalStagedAtElapsedMs, boolean originallySilent, long nowElapsedMs) {
        if (dedupKey == null || dedupKey.isEmpty() || payload == null) return false;
        pruneExpiredLocked(nowElapsedMs);
        if (isExpired(originalStagedAtElapsedMs, nowElapsedMs)) return false;
        if (entries.containsKey(dedupKey)) return false;
        entries.put(dedupKey, new Entry<>(dedupKey, payload, originalStagedAtElapsedMs, originallySilent));
        while (entries.size() > maxStaged) {
            Iterator<Map.Entry<String, Entry<T>>> it = entries.entrySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        return true;
    }

    /** Removes a staged notification when dismissed on the phone before watch reconnect. */
    public synchronized boolean removeByKey(String dedupKey) {
        if (dedupKey == null || dedupKey.isEmpty()) return false;
        return entries.remove(dedupKey) != null;
    }

    /** Clears all staged notifications (e.g., when toggle is disabled or bridge closes). */
    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size(long nowElapsedMs) {
        pruneExpiredLocked(nowElapsedMs);
        return entries.size();
    }

    public synchronized boolean isEmpty(long nowElapsedMs) {
        pruneExpiredLocked(nowElapsedMs);
        return entries.isEmpty();
    }

    /**
     * Drains all non-expired staged notifications in chronological order (oldest -> newest)
     * and assigns vibration flags:
     * - At most ONE notification is allowed to vibrate ({@code forceSilent = false}), chosen as
     *   the newest notification that was not already originally silent.
     * - All other staged notifications are marked {@code forceSilent = true}.
     * - If all staged notifications were already originally silent, all remain silent.
     */
    public synchronized List<DeliveryItem<T>> drainForDelivery(long nowElapsedMs) {
        pruneExpiredLocked(nowElapsedMs);
        if (entries.isEmpty()) return List.of();
        List<Entry<T>> snapshot = new ArrayList<>(entries.values());
        entries.clear();

        int vibratingIndex = -1;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            if (!snapshot.get(i).originallySilent()) {
                vibratingIndex = i;
                break;
            }
        }

        List<DeliveryItem<T>> out = new ArrayList<>(snapshot.size());
        for (int i = 0; i < snapshot.size(); i++) {
            Entry<T> e = snapshot.get(i);
            boolean forceSilent = (i != vibratingIndex);
            out.add(new DeliveryItem<>(e.dedupKey(), e.payload(), e.stagedAtElapsedMs(),
                    e.originallySilent(), forceSilent));
        }
        return out;
    }

    private void pruneExpiredLocked(long nowElapsedMs) {
        Iterator<Map.Entry<String, Entry<T>>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry<T> e = it.next().getValue();
            if (isExpired(e.stagedAtElapsedMs(), nowElapsedMs)) {
                it.remove();
            }
        }
    }

    private boolean isExpired(long stagedAtElapsedMs, long nowElapsedMs) {
        long age = nowElapsedMs - stagedAtElapsedMs;
        return age < 0L || age > ttlMs;
    }
}
