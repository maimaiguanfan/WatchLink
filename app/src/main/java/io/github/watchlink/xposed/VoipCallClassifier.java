package io.github.watchlink.xposed;

import android.app.Notification;
import android.app.PendingIntent;
import android.os.Build;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies third-party VoIP notifications using 100% public Android APIs (CallStyle extras and
 * CATEGORY_CALL action titles), avoiding Android 17 hidden-API reflection on PendingIntent.getIntent().
 */
final class VoipCallClassifier {
    static final String REJECT_TOKEN = "wl_voip_reject";

    private static final Set<String> EXCLUDED_PACKAGES = Set.of(
            "com.tencent.mm",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.oneplus.dialer",
            "com.oplus.dialer",
            "com.samsung.android.dialer",
            "com.android.phone",
            "com.android.contacts",
            "com.huawei.contacts",
            "com.hihonor.contacts",
            "com.coloros.alarmclock",
            "com.coloros.calendar",
            "com.huawei.health");

    private static final String[] REJECT_KEYWORDS = {
            "拒绝", "拒接", "挂断", "忽略", "结束通话",
            "decline", "reject", "hang up", "hangup", "end call", "dismiss", "ignore"
    };

    private static final String[] ANSWER_KEYWORDS = {
            "接听", "接受", "语音接听", "视频接听",
            "answer", "accept", "pick up"
    };

    enum Kind {
        NONE,
        INCOMING_CALL_STYLE,
        INCOMING_CATEGORY_CALL,
        ONGOING
    }

    record ActionSpec<T>(String title, int semanticAction, T intent) {}

    record Classified<T>(Kind kind, T rejectIntent) {
        boolean isIncoming() {
            return (kind == Kind.INCOMING_CALL_STYLE || kind == Kind.INCOMING_CATEGORY_CALL)
                    && rejectIntent != null;
        }
    }

    record Decision(Kind kind, PendingIntent rejectIntent) {
        static final Decision NONE = new Decision(Kind.NONE, null);
        static final Decision ONGOING = new Decision(Kind.ONGOING, null);

        boolean isIncoming() {
            return (kind == Kind.INCOMING_CALL_STYLE || kind == Kind.INCOMING_CATEGORY_CALL)
                    && rejectIntent != null;
        }
    }

    static boolean isEligibleVoipPackage(String pkg) {
        return pkg != null && !pkg.isEmpty() && !EXCLUDED_PACKAGES.contains(pkg);
    }

    static Decision classify(Notification notification) {
        if (notification == null) return Decision.NONE;
        Bundle extras = notification.extras;
        PendingIntent declineIntent = null;
        PendingIntent hangUpIntent = null;
        String template = null;
        if (extras != null) {
            template = extras.getString(Notification.EXTRA_TEMPLATE);
            if (Build.VERSION.SDK_INT >= 33) {
                declineIntent = extras.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent.class);
                hangUpIntent = extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent.class);
            } else if (Build.VERSION.SDK_INT >= 31) {
                @SuppressWarnings("deprecation")
                PendingIntent dec = extras.getParcelable(Notification.EXTRA_DECLINE_INTENT);
                @SuppressWarnings("deprecation")
                PendingIntent hang = extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT);
                declineIntent = dec;
                hangUpIntent = hang;
            }
        }

        List<ActionSpec<PendingIntent>> specs = new ArrayList<>();
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                if (action == null) continue;
                String title = action.title == null ? "" : action.title.toString();
                int semantic = Build.VERSION.SDK_INT >= 28 ? action.getSemanticAction() : 0;
                specs.add(new ActionSpec<>(title, semantic, action.actionIntent));
            }
        }
        Classified<PendingIntent> res = classifyMetadata(
                notification.category,
                template,
                notification.flags,
                notification.fullScreenIntent != null,
                declineIntent,
                hangUpIntent,
                specs);
        return new Decision(res.kind(), res.rejectIntent());
    }

    /** Pure metadata classifier testable on JVM without Android stubs. */
    static <T> Classified<T> classifyMetadata(String category, String template, int flags, boolean hasFullScreenIntent,
                                              T declineIntent, T hangUpIntent,
                                              List<ActionSpec<T>> actions) {
        if (declineIntent != null) {
            return new Classified<>(Kind.INCOMING_CALL_STYLE, declineIntent);
        }
        if (hangUpIntent != null) {
            return new Classified<>(Kind.ONGOING, null);
        }
        boolean isCallLike = "call".equals(category)
                || (template != null && template.contains("CallStyle"));
        if (!isCallLike || actions == null || actions.isEmpty()) {
            return new Classified<>(Kind.NONE, null);
        }

        T matchedReject = null;
        boolean matchedAnswer = false;
        for (ActionSpec<T> action : actions) {
            if (action == null) continue;
            if (isRejectAction(action) && action.intent() != null && matchedReject == null) {
                matchedReject = action.intent();
            } else if (isAnswerAction(action)) {
                matchedAnswer = true;
            }
        }

        if (matchedReject != null && matchedAnswer) {
            return new Classified<>(Kind.INCOMING_CATEGORY_CALL, matchedReject);
        }
        boolean ongoingFlag = (flags & 0x00000002) != 0; // Notification.FLAG_ONGOING_EVENT
        if (matchedReject != null && hasFullScreenIntent && !ongoingFlag) {
            return new Classified<>(Kind.INCOMING_CATEGORY_CALL, matchedReject);
        }
        if (matchedReject != null && !matchedAnswer) {
            return new Classified<>(Kind.ONGOING, null);
        }
        return new Classified<>(Kind.NONE, null);
    }

    private static boolean isRejectAction(ActionSpec<?> action) {
        // Notification.Action.SEMANTIC_ACTION_DELETE == 4
        if (action.semanticAction() == 4) return true;
        String lower = normalize(action.title());
        if (lower.isEmpty()) return false;
        for (String keyword : REJECT_KEYWORDS) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    private static boolean isAnswerAction(ActionSpec<?> action) {
        String lower = normalize(action.title());
        if (lower.isEmpty()) return false;
        for (String keyword : ANSWER_KEYWORDS) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private VoipCallClassifier() {}
}
