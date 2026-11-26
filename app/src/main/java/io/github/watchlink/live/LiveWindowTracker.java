package io.github.watchlink.live;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java stateful tracker for active live windows.
 * Handles CREATE / UPDATE / CLOSE lifecycle, content deduplication, rapid-update throttling,
 * active-set reconciliation, max concurrency cap, and orphan expiration.
 */
public final class LiveWindowTracker {
    public static final int MAX_ACTIVE = 8;
    public static final long MIN_UPDATE_INTERVAL_MS = 2_500L;
    public static final long DEFAULT_ORPHAN_TIMEOUT_MS = 30 * 60_000L;

    private static final class Entry {
        LiveWindowPayload payload;
        String fingerprint;
        long lastSentElapsedMs;

        Entry(LiveWindowPayload payload, String fingerprint, long lastSentElapsedMs) {
            this.payload = payload;
            this.fingerprint = fingerprint;
            this.lastSentElapsedMs = lastSentElapsedMs;
        }
    }

    private final Map<String, Entry> active = new LinkedHashMap<>();

    public synchronized int activeCount() {
        return active.size();
    }

    public synchronized boolean isActive(String stableKey) {
        return stableKey != null && active.containsKey(stableKey);
    }

    /**
     * Evaluates an incoming payload and returns the outbound CREATE/UPDATE payload to emit,
     * or {@code null} if suppressed by deduplication, throttling, or concurrency cap.
     */
    public synchronized LiveWindowPayload onUpsert(LiveWindowPayload incoming, long nowElapsedMs) {
        if (incoming == null || !incoming.isValidForOpenOrUpdate()) return null;
        String key = incoming.stableKey();
        String fp = incoming.contentFingerprint();
        Entry existing = active.get(key);
        if (existing == null) {
            if (active.size() >= MAX_ACTIVE) return null;
            LiveWindowPayload created = incoming.withOperation(LiveWindowPayload.OP_CREATE);
            active.put(key, new Entry(created, fp, nowElapsedMs));
            return created;
        }
        if (fp.equals(existing.fingerprint)) {
            // Identical content: refresh liveness timestamp without re-sending over Bluetooth.
            existing.lastSentElapsedMs = Math.max(existing.lastSentElapsedMs, nowElapsedMs);
            return null;
        }
        boolean majorChange = LiveWindowPayload.SOURCE_CLOCK_TIMER.equals(incoming.source())
                || !incoming.eventType().equals(existing.payload.eventType())
                || !incoming.title().equals(existing.payload.title())
                || Math.abs(incoming.progress() - existing.payload.progress()) >= 5
                || (incoming.progress() == 100 && existing.payload.progress() != 100);
        if (!majorChange && nowElapsedMs - existing.lastSentElapsedMs < MIN_UPDATE_INTERVAL_MS) {
            return null;
        }
        LiveWindowPayload updated = incoming.withOperation(LiveWindowPayload.OP_UPDATE);
        existing.payload = updated;
        existing.fingerprint = fp;
        existing.lastSentElapsedMs = nowElapsedMs;
        return updated;
    }

    /** Removes a key if active and returns its OP_CLOSE payload (or null if not active). */
    public synchronized LiveWindowPayload onRemove(String stableKey) {
        if (stableKey == null || stableKey.isEmpty()) return null;
        Entry removed = active.remove(stableKey);
        return removed == null ? null : removed.payload.withOperation(LiveWindowPayload.OP_CLOSE);
    }

    /** Closes any active entries matching {@code keyPrefix} that are no longer in {@code presentKeys}. */
    public synchronized List<LiveWindowPayload> reconcilePrefix(String keyPrefix, Set<String> presentKeys) {
        List<LiveWindowPayload> closures = new ArrayList<>();
        Iterator<Map.Entry<String, Entry>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> entry = it.next();
            String key = entry.getKey();
            if (key.startsWith(keyPrefix) && (presentKeys == null || !presentKeys.contains(key))) {
                closures.add(entry.getValue().payload.withOperation(LiveWindowPayload.OP_CLOSE));
                it.remove();
            }
        }
        return closures;
    }

    /** Closes any active entries untouched longer than {@code maxAgeMs}. */
    public synchronized List<LiveWindowPayload> expireStale(long nowElapsedMs, long maxAgeMs) {
        List<LiveWindowPayload> closures = new ArrayList<>();
        Iterator<Map.Entry<String, Entry>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> entry = it.next();
            if (nowElapsedMs - entry.getValue().lastSentElapsedMs >= maxAgeMs) {
                closures.add(entry.getValue().payload.withOperation(LiveWindowPayload.OP_CLOSE));
                it.remove();
            }
        }
        return closures;
    }

    public synchronized List<LiveWindowPayload> closeSourceGroup(boolean timers) {
        List<LiveWindowPayload> closures = new ArrayList<>();
        active.values().removeIf(entry -> {
            if (LiveWindowPayload.SOURCE_CLOCK_TIMER.equals(entry.payload.source()) != timers) return false;
            closures.add(entry.payload.withOperation(LiveWindowPayload.OP_CLOSE));
            return true;
        });
        return closures;
    }

    /** Closes and removes all active live windows (e.g., when the user disables the feature). */
    public synchronized List<LiveWindowPayload> closeAll() {
        List<LiveWindowPayload> closures = new ArrayList<>(active.size());
        for (Entry entry : active.values()) {
            closures.add(entry.payload.withOperation(LiveWindowPayload.OP_CLOSE));
        }
        active.clear();
        return closures;
    }

    /** Returns true if another channel already tracks an active card for this concrete app package and event. */
    public synchronized boolean hasOtherSourceForPackage(String packageName, String eventType, String excludeSource) {
        if (packageName == null || packageName.isEmpty()
                || LiveWindowPayload.DEFAULT_PACKAGE.equals(packageName)
                || "com.tencent.mm".equals(packageName)) {
            return false;
        }
        for (Entry entry : active.values()) {
            LiveWindowPayload p = entry.payload;
            if (!p.source().equals(excludeSource)
                    && packageName.equals(p.packageName())
                    && eventType.equals(p.eventType())) {
                return true;
            }
        }
        return false;
    }
}
