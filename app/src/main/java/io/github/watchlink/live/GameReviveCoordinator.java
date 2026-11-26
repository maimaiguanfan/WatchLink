package io.github.watchlink.live;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java state machine for Game Flashback revive/takeoff countdown reminders.
 * Schedules exactly one watch vibration alert {@code leadSec} seconds before countdown end
 * (configurable 0..30s; 0s = at revive completion), while preventing duplicate alerts on
 * short early-game revives and preventing false alerts when the player returns early.
 */
public final class GameReviveCoordinator {
    public static final int DEFAULT_LEAD_SEC = 5;
    public static final int MIN_LEAD_SEC = 0;
    public static final int MAX_LEAD_SEC = 30;

    static final long EDGE_CLOSE_EARLY_TOLERANCE_MS = 2_500L;
    static final long EDGE_CLOSE_LATE_TOLERANCE_MS = 2_000L;

    private static final Pattern SEC_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,3})\\s*(?:秒|s|S|sec)(?![A-Za-z0-9])");
    private static final Pattern MMSS_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)");

    public record AlertAction(
            String stableKey,
            String packageName,
            String eventType,
            String title,
            String text,
            String capsuleTitle,
            String capsuleContent,
            int remainingSec,
            boolean completionAlert) {

        /** Builds a watch SmartEdge update payload that keeps the live countdown card while vibrating. */
        public LiveWindowPayload toInPlaceRemindPayload(String source) {
            return new LiveWindowPayload(
                    source,
                    stableKey,
                    packageName,
                    title,
                    text,
                    capsuleTitle,
                    capsuleContent,
                    eventType,
                    -1,
                    LiveWindowPayload.OP_UPDATE);
        }

        /** Builds a short-lived completion alert card when the countdown card has already closed. */
        public LiveWindowPayload toStandaloneCompletionPayload(String source) {
            return new LiveWindowPayload(
                    source,
                    stableKey + ":done",
                    packageName,
                    title,
                    text,
                    capsuleTitle,
                    capsuleContent,
                    eventType,
                    -1,
                    LiveWindowPayload.OP_CREATE);
        }
    }

    public record UpsertPlan(AlertAction immediateAlert, Long scheduleAtElapsedMs, long generation) {}

    private static final class Session {
        final String stableKey;
        final String source;
        final String packageName;
        final String eventType;
        final String actionLabel;
        final int initialRemainingSec;
        final boolean shortReviveFallback;
        int lastRemainingSec;
        long estimatedEndElapsedMs;
        long generation;
        boolean alertFired;

        Session(String stableKey, String source, String packageName, String eventType,
                String actionLabel, int initialRemainingSec, long estimatedEndElapsedMs,
                boolean shortReviveFallback) {
            this.stableKey = stableKey;
            this.source = source;
            this.packageName = packageName;
            this.eventType = eventType;
            this.actionLabel = actionLabel;
            this.initialRemainingSec = initialRemainingSec;
            this.lastRemainingSec = initialRemainingSec;
            this.estimatedEndElapsedMs = estimatedEndElapsedMs;
            this.shortReviveFallback = shortReviveFallback;
        }
    }

    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public static int clampLeadSec(int leadSec) {
        return Math.max(MIN_LEAD_SEC, Math.min(MAX_LEAD_SEC, leadSec));
    }

    /** Extracts countdown remaining seconds from candidate strings, or {@code -1} if absent. */
    public static int parseRemainingSeconds(String... candidates) {
        if (candidates == null) return -1;
        for (String raw : candidates) {
            if (raw == null || raw.isEmpty()) continue;
            Matcher sec = SEC_PATTERN.matcher(raw);
            if (sec.find()) {
                try {
                    int value = Integer.parseInt(sec.group(1));
                    if (value >= 0 && value <= 600) return value;
                } catch (NumberFormatException ignored) {}
            }
            Matcher mmss = MMSS_PATTERN.matcher(raw);
            if (mmss.find()) {
                try {
                    int min = Integer.parseInt(mmss.group(1));
                    int s = Integer.parseInt(mmss.group(2));
                    if (s < 60 && min <= 10) {
                        return min * 60 + s;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    /** Returns true when text explicitly announces revive/takeoff completion. */
    public static boolean isCompletionText(String... candidates) {
        if (candidates == null) return false;
        for (String raw : candidates) {
            if (raw == null || raw.isEmpty()) continue;
            String s = raw.trim().toLowerCase(Locale.ROOT);
            if (s.contains("已复活") || s.contains("复活完成") || s.contains("立刻复活")
                    || s.contains("准备起飞") || s.contains("已起飞") || s.contains("立即进场")
                    || s.contains("revived")) {
                return true;
            }
        }
        return false;
    }

    /** Infers the short watch action word ("复活" / "起飞" / "闪回") from card text. */
    public static String inferActionLabel(String... candidates) {
        if (candidates != null) {
            for (String raw : candidates) {
                if (raw == null) continue;
                if (raw.contains("复活")) return "复活";
                if (raw.contains("起飞") || raw.contains("跳伞") || raw.contains("航线")) return "起飞";
            }
        }
        return "复活";
    }

    public synchronized int trackedCount() {
        return sessions.size();
    }

    public synchronized UpsertPlan onUpsert(LiveWindowPayload payload, long nowElapsedMs, int rawLeadSec) {
        if (payload == null || !payload.isGameFlashback() || payload.operation() == LiveWindowPayload.OP_CLOSE) {
            return new UpsertPlan(null, null, 0L);
        }
        int leadSec = clampLeadSec(rawLeadSec);
        String key = payload.stableKey();
        int remainingSec = parseRemainingSeconds(payload.capsuleContent(), payload.text(), payload.title());
        boolean completedText = isCompletionText(payload.capsuleContent(), payload.text(), payload.title());
        String actionLabel = inferActionLabel(payload.title(), payload.text(), payload.capsuleTitle(), payload.capsuleContent());

        Session session = sessions.get(key);
        if (session == null) {
            if (remainingSec <= 0) {
                // Static game flashback card without countdown seconds -> do not arm revive timer.
                return new UpsertPlan(null, null, 0L);
            }
            // If total revive time is already <= leadSec (e.g. 4s early-game revive with 5s lead),
            // OP_CREATE already vibrated; fall back to firing once at completion (0s) unless <= 1s.
            boolean shortFallback = leadSec > 0 && remainingSec <= leadSec;
            long endMs = nowElapsedMs + remainingSec * 1_000L;
            session = new Session(key, payload.source(), payload.packageName(), payload.eventType(),
                    actionLabel, remainingSec, endMs, shortFallback);
            if (shortFallback && remainingSec <= 1) {
                session.alertFired = true;
            }
            sessions.put(key, session);
            return scheduleNextLocked(session, nowElapsedMs, leadSec);
        }

        if (session.alertFired) {
            if (remainingSec >= 0) {
                session.lastRemainingSec = remainingSec;
                session.estimatedEndElapsedMs = nowElapsedMs + remainingSec * 1_000L;
            }
            return new UpsertPlan(null, null, session.generation);
        }

        int effLead = session.shortReviveFallback ? 0 : leadSec;

        if (completedText || (remainingSec == 0 && session.lastRemainingSec > 0)) {
            session.lastRemainingSec = 0;
            session.alertFired = true;
            session.generation++;
            return new UpsertPlan(buildAlert(session, 0, true), null, session.generation);
        }

        if (remainingSec > 0) {
            session.lastRemainingSec = remainingSec;
            session.estimatedEndElapsedMs = nowElapsedMs + remainingSec * 1_000L;
            if (effLead > 0 && remainingSec <= effLead) {
                session.alertFired = true;
                session.generation++;
                return new UpsertPlan(buildAlert(session, remainingSec, false), null, session.generation);
            }
            return scheduleNextLocked(session, nowElapsedMs, leadSec);
        }

        return new UpsertPlan(null, null, session.generation);
    }

    /** Called when the scheduled wake-up fires at {@code triggerElapsedMs}. */
    public synchronized AlertAction onTimerFired(String stableKey, long generation, long nowElapsedMs, int rawLeadSec) {
        Session session = sessions.get(stableKey);
        if (session == null || session.generation != generation || session.alertFired) {
            return null;
        }
        int leadSec = clampLeadSec(rawLeadSec);
        int effLead = session.shortReviveFallback ? 0 : leadSec;
        long remainingMs = Math.max(0L, session.estimatedEndElapsedMs - nowElapsedMs);
        int displayRemainingSec = effLead > 0
                ? Math.max(1, (int) Math.round(remainingMs / 1_000.0))
                : 0;
        session.alertFired = true;
        return buildAlert(session, displayRemainingSec, effLead == 0);
    }

    /**
     * Recomputes schedules for all unfired active sessions when the user edits {@code leadSec}.
     * Returns a map of {@code stableKey -> UpsertPlan}.
     */
    public synchronized Map<String, UpsertPlan> onLeadSecChanged(long nowElapsedMs, int rawLeadSec) {
        Map<String, UpsertPlan> plans = new LinkedHashMap<>();
        int leadSec = clampLeadSec(rawLeadSec);
        for (Session session : sessions.values()) {
            if (session.alertFired) continue;
            long remainingMs = Math.max(0L, session.estimatedEndElapsedMs - nowElapsedMs);
            int remainingSec = (int) Math.ceil(remainingMs / 1_000.0);
            int effLead = session.shortReviveFallback ? 0 : leadSec;
            if (effLead > 0 && remainingSec > 0 && remainingSec <= effLead) {
                session.alertFired = true;
                session.generation++;
                plans.put(session.stableKey, new UpsertPlan(buildAlert(session, remainingSec, false), null, session.generation));
            } else {
                plans.put(session.stableKey, scheduleNextLocked(session, nowElapsedMs, leadSec));
            }
        }
        return plans;
    }

    /**
     * Called when a game flashback card closes. Cancels tracking; only emits a completion alert
     * if {@code effLead == 0} and closure falls right around the predicted countdown end.
     */
    public synchronized AlertAction onRemove(String stableKey, long nowElapsedMs, int rawLeadSec) {
        Session session = sessions.remove(stableKey);
        if (session == null || session.alertFired || session.estimatedEndElapsedMs <= 0L) {
            return null;
        }
        int leadSec = clampLeadSec(rawLeadSec);
        int effLead = session.shortReviveFallback ? 0 : leadSec;
        if (effLead == 0
                && nowElapsedMs >= session.estimatedEndElapsedMs - EDGE_CLOSE_EARLY_TOLERANCE_MS
                && nowElapsedMs <= session.estimatedEndElapsedMs + EDGE_CLOSE_LATE_TOLERANCE_MS) {
            session.alertFired = true;
            return buildAlert(session, 0, true);
        }
        return null;
    }

    public synchronized void clear() {
        sessions.clear();
    }

    private UpsertPlan scheduleNextLocked(Session session, long nowElapsedMs, int leadSec) {
        if (session.alertFired) {
            return new UpsertPlan(null, null, session.generation);
        }
        int effLead = session.shortReviveFallback ? 0 : leadSec;
        long triggerAt = session.estimatedEndElapsedMs - effLead * 1_000L;
        session.generation++;
        return new UpsertPlan(null, Math.max(nowElapsedMs, triggerAt), session.generation);
    }

    private static AlertAction buildAlert(Session session, int remainingSec, boolean completion) {
        String label = session.actionLabel;
        String shortLine;
        String detailText;
        String capTitle;
        String capContent;
        if (completion || remainingSec <= 0) {
            shortLine = "已" + label;
            detailText = "起飞".equals(label) ? "准备起飞" : "已复活 准备进场";
            capTitle = shortLine;
            capContent = "准备进场";
        } else {
            shortLine = WatchLiveTextFormatter.compactGameFlashback("即将" + label, remainingSec, "");
            detailText = "还有" + remainingSec + "秒" + label;
            capTitle = "即将" + label;
            capContent = remainingSec + "秒";
        }
        return new AlertAction(
                session.stableKey,
                session.packageName,
                session.eventType,
                WatchLiveTextFormatter.fitWithEllipsis(shortLine, WatchLiveTextFormatter.MAX_WATCH_HALF_CELLS),
                WatchLiveTextFormatter.fitWithEllipsis(detailText, WatchLiveTextFormatter.MAX_WATCH_HALF_CELLS),
                capTitle,
                capContent,
                Math.max(0, remainingSec),
                completion);
    }
}
