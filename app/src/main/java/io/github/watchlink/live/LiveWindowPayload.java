package io.github.watchlink.live;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Normalized, sanitized live-window payload and WATCH 6 Pro SmartEdge (5.55.2) JSON encoder.
 * Pure Java so both Android hosts and JVM unit tests verify exact wire formatting.
 */
public record LiveWindowPayload(
        String source,
        String stableKey,
        String packageName,
        String title,
        String text,
        String capsuleTitle,
        String capsuleContent,
        String eventType,
        int progress,
        int operation) {

    public static final int OP_CREATE = 0;
    public static final int OP_UPDATE = 1;
    public static final int OP_CLOSE = 2;

    public static final String SOURCE_UMS = "UMS";
    public static final String SOURCE_DAEMON = "DAEMON";
    public static final String SOURCE_CLOCK_TIMER = "CLOCK_TIMER";
    public static final String CLOCK_TIMER_KEY_PREFIX = "clock-timer:";
    public static final String SOURCE_TEST = "TEST";

    // Verified against Huawei Health 17.0.10.330 Lctp.b & live WATCH 6 Pro testing (OTHER is dropped!).
    public static final String EVENT_DELIVERY = "DELIVERY";
    public static final String EVENT_TAXI = "TAXI";
    public static final String EVENT_PICK_UP = "PICK_UP";
    public static final String EVENT_QUEUE = "QUEUE";

    public static final String DEFAULT_PACKAGE = "com.oplus.pantanal.ums";

    private static final int PROGRESS_COLOR = 0xFF317AF7;
    private static final int PROGRESS_BG_COLOR = 0x19000000;
    private static final int MAX_TITLE_LEN = 48;
    private static final int MAX_TEXT_LEN = 120;
    private static final int MAX_CAPSULE_TITLE_LEN = 20;
    private static final int MAX_CAPSULE_CONTENT_LEN = 24;
    private static final int MAX_KEY_LEN = 160;

    public LiveWindowPayload {
        source = normalizeSource(source);
        stableKey = sanitizeKey(stableKey);
        packageName = normalizePackage(packageName);
        title = sanitizeText(title, MAX_TITLE_LEN);
        text = sanitizeText(text, MAX_TEXT_LEN);
        if (title.isEmpty() && !text.isEmpty()) {
            title = truncate(text, MAX_TITLE_LEN);
        }
        capsuleTitle = sanitizeText(capsuleTitle, MAX_CAPSULE_TITLE_LEN);
        if (capsuleTitle.isEmpty()) capsuleTitle = truncate(title, MAX_CAPSULE_TITLE_LEN);
        capsuleContent = sanitizeText(capsuleContent, MAX_CAPSULE_CONTENT_LEN);
        if (capsuleContent.isEmpty()) capsuleContent = truncate(text.isEmpty() ? title : text, MAX_CAPSULE_CONTENT_LEN);
        eventType = normalizeEvent(eventType);
        progress = progress < 0 ? -1 : Math.min(100, progress);
        operation = (operation == OP_CREATE || operation == OP_UPDATE || operation == OP_CLOSE)
                ? operation : OP_UPDATE;
        if (EVENT_PICK_UP.equals(eventType) && !SOURCE_CLOCK_TIMER.equals(source) && operation != OP_CLOSE) {
            title = WatchLiveTextFormatter.compactPickupLine(title);
            text = WatchLiveTextFormatter.compactPickupLine(text);
            WatchLiveTextFormatter.Pair cap = WatchLiveTextFormatter.compactPickupCapsule(capsuleTitle, capsuleContent);
            capsuleTitle = cap.title();
            capsuleContent = cap.content();
            if (title.isEmpty() && !text.isEmpty()) title = text;
            if (capsuleContent.isEmpty() && !text.isEmpty()) capsuleContent = text;
        }
    }

    public boolean isValidForOpenOrUpdate() {
        return !stableKey.isEmpty() && (!title.isEmpty() || !text.isEmpty());
    }

    public LiveWindowPayload withOperation(int newOp) {
        return new LiveWindowPayload(
                source, stableKey, packageName, title, text,
                capsuleTitle, capsuleContent, eventType, progress, newOp);
    }

    public LiveWindowPayload withStableKey(String newKey) {
        return new LiveWindowPayload(
                source, newKey, packageName, title, text,
                capsuleTitle, capsuleContent, eventType, progress, operation);
    }

    public int notificationId() {
        return stableNotificationId(stableKey);
    }

    public String notificationKey() {
        return "0|" + packageName + "|" + notificationId() + "|watchlink-live|0";
    }

    /** Returns an opaque content fingerprint for deduplication (never contains raw text). */
    public String contentFingerprint() {
        String raw = packageName + "\n" + eventType + "\n" + title + "\n" + text + "\n"
                + capsuleTitle + "\n" + capsuleContent + "\n" + progress;
        return sha256Hex(raw).substring(0, 24);
    }

    /** Encodes the verified SmartEdge JSON consumed by Huawei Health HandleIntentService / HwNotificationLiveMgr. */
    public String toSmartEdgeJson() {
        int nativeId = notificationId();
        String key = notificationKey();
        boolean hasProgress = progress >= 0;
        int liveType = hasProgress ? 3 : 1;

        StringBuilder sb = new StringBuilder(384);
        sb.append('{');
        field(sb, "packageName", packageName); sb.append(',');
        fieldInt(sb, "notificationId", nativeId); sb.append(',');
        field(sb, "notificationKey", key); sb.append(',');
        field(sb, "title", title); sb.append(',');
        field(sb, "text", text); sb.append(',');
        fieldInt(sb, "notification.live.operation", operation); sb.append(',');
        fieldLong(sb, "notification.live.keepTime", 0L); sb.append(',');
        fieldInt(sb, "notification.live.type", liveType); sb.append(',');
        field(sb, "notification.live.event", eventType); sb.append(',');
        field(sb, "notification.live.titleOverlay", title); sb.append(',');
        field(sb, "notification.live.contentOverlay", text); sb.append(',');
        fieldBool(sb, "notification.live.externalEnable", false); sb.append(',');
        field(sb, "notification.live.externalTitle", ""); sb.append(',');
        field(sb, "notification.live.externalBody", ""); sb.append(',');

        SimpleJson.appendQuoted(sb, "notification.live.feature");
        sb.append(":{");
        if (hasProgress) {
            fieldInt(sb, "notification.live.feature.progressType", 0); sb.append(',');
            fieldInt(sb, "notification.live.feature.indicatorType", 1); sb.append(',');
            fieldInt(sb, "notification.live.feature.progress", progress); sb.append(',');
            fieldInt(sb, "notification.live.feature.progressColor", PROGRESS_COLOR); sb.append(',');
            fieldInt(sb, "notification.live.feature.progressBgColor", PROGRESS_BG_COLOR);
        } else {
            field(sb, "notification.live.feature.titleText", capsuleTitle); sb.append(',');
            field(sb, "notification.live.feature.contentText", capsuleContent);
        }
        sb.append("},");

        SimpleJson.appendQuoted(sb, "notification.live.capsule");
        sb.append(":{");
        fieldInt(sb, "notification.live.capsuleStatus", operation == OP_CLOSE ? 0 : 1); sb.append(',');
        fieldInt(sb, "notification.live.capsuleType", 1); sb.append(',');
        field(sb, "notification.live.capsuleTitle", capsuleTitle); sb.append(',');
        field(sb, "notification.live.capsuleContent", capsuleContent); sb.append(',');
        fieldBool(sb, "notification.live.capsuleRemind", operation == OP_CREATE && !SOURCE_CLOCK_TIMER.equals(source));
        sb.append("}}");
        return sb.toString();
    }

    /** Never emits raw text or titles in toString() to prevent accidental log leaks. */
    @Override public String toString() {
        return "LiveWindowPayload(source=" + source
                + ", event=" + eventType
                + ", op=" + operation
                + ", progress=" + progress + ")";
    }

    public static String normalizeEvent(String raw) {
        if (raw == null) return EVENT_PICK_UP;
        return switch (raw.trim().toUpperCase(Locale.US)) {
            case EVENT_DELIVERY -> EVENT_DELIVERY;
            case EVENT_TAXI -> EVENT_TAXI;
            case EVENT_QUEUE -> EVENT_QUEUE;
            case EVENT_PICK_UP -> EVENT_PICK_UP;
            default -> EVENT_PICK_UP;
        };
    }

    public static String normalizeSource(String raw) {
        if (raw == null) return SOURCE_UMS;
        return switch (raw.trim().toUpperCase(Locale.US)) {
            case SOURCE_DAEMON -> SOURCE_DAEMON;
            case SOURCE_CLOCK_TIMER -> SOURCE_CLOCK_TIMER;
            case SOURCE_TEST -> SOURCE_TEST;
            default -> SOURCE_UMS;
        };
    }

    public static String normalizePackage(String pkg) {
        if (pkg != null) {
            String trimmed = pkg.trim();
            if (trimmed.length() <= 96 && trimmed.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
                return trimmed;
            }
        }
        return DEFAULT_PACKAGE;
    }

    public static int stableNotificationId(String key) {
        byte[] hash = sha256Bytes(key == null ? "" : key);
        return 0x40000000
                | ((hash[0] & 0x3F) << 24)
                | ((hash[1] & 0xFF) << 16)
                | ((hash[2] & 0xFF) << 8)
                | (hash[3] & 0xFF);
    }

    public static String opName(int op) {
        return switch (op) {
            case OP_CREATE -> "CREATE";
            case OP_CLOSE -> "CLOSE";
            default -> "UPDATE";
        };
    }

    private static String sanitizeKey(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.length() > MAX_KEY_LEN) {
            return "h:" + sha256Hex(trimmed).substring(0, 32);
        }
        return trimmed;
    }

    static String sanitizeText(String raw, int maxLen) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxLen));
        boolean prevSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') c = ' ';
            if (c < 0x20 || c == 0x7F) continue;
            if (c == ' ') {
                if (sb.length() == 0 || prevSpace) continue;
                prevSpace = true;
            } else {
                prevSpace = false;
            }
            sb.append(c);
            if (sb.length() >= maxLen) break;
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s == null ? "" : s;
        return s.substring(0, maxLen).trim();
    }

    private static void field(StringBuilder sb, String key, String value) {
        SimpleJson.appendQuoted(sb, key);
        sb.append(':');
        SimpleJson.appendQuoted(sb, value);
    }

    private static void fieldInt(StringBuilder sb, String key, int value) {
        SimpleJson.appendQuoted(sb, key);
        sb.append(':').append(value);
    }

    private static void fieldLong(StringBuilder sb, String key, long value) {
        SimpleJson.appendQuoted(sb, key);
        sb.append(':').append(value);
    }

    private static void fieldBool(StringBuilder sb, String key, boolean value) {
        SimpleJson.appendQuoted(sb, key);
        sb.append(':').append(value);
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256Hex(String value) {
        byte[] hash = sha256Bytes(value);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
