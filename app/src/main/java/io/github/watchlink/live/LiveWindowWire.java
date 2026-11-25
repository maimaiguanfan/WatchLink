package io.github.watchlink.live;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.validation.DailyAlarmWire;

/**
 * Authenticated host-to-host IPC carrying normalized live-window events from
 * Channel B (com.oplus.pantanal.ums) and Channel A (com.huawei.health:DaemonService)
 * directly to com.huawei.health:PhoneService without waking WatchLink.
 */
public final class LiveWindowWire {
    public static final String ACTION = BuildConfig.APPLICATION_ID + ".live.EVENT_V1";
    public static final long MAX_AGE_MS = 15_000L;

    private static final String EXTRA_SOURCE = "source";
    private static final String EXTRA_KEY = "stableKey";
    private static final String EXTRA_PKG = "pkg";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_TEXT = "text";
    private static final String EXTRA_CAP_TITLE = "capsuleTitle";
    private static final String EXTRA_CAP_CONTENT = "capsuleContent";
    private static final String EXTRA_EVENT = "eventType";
    private static final String EXTRA_PROGRESS = "progress";
    private static final String EXTRA_OP = "operation";
    private static final String EXTRA_AT = "at";
    private static final String EXTRA_ELAPSED = "elapsed";

    private LiveWindowWire() {}

    @RequiresApi(34)
    public static void sendToHealth(Context sender, LiveWindowPayload payload) {
        if (sender == null || payload == null || payload.stableKey().isEmpty()) return;
        Intent intent = DailyAlarmWire.route(ACTION, TargetProfile.HEALTH.packageName)
                .putExtra(EXTRA_SOURCE, payload.source())
                .putExtra(EXTRA_KEY, payload.stableKey())
                .putExtra(EXTRA_PKG, payload.packageName())
                .putExtra(EXTRA_TITLE, payload.title())
                .putExtra(EXTRA_TEXT, payload.text())
                .putExtra(EXTRA_CAP_TITLE, payload.capsuleTitle())
                .putExtra(EXTRA_CAP_CONTENT, payload.capsuleContent())
                .putExtra(EXTRA_EVENT, payload.eventType())
                .putExtra(EXTRA_PROGRESS, payload.progress())
                .putExtra(EXTRA_OP, payload.operation())
                .putExtra(EXTRA_AT, System.currentTimeMillis())
                .putExtra(EXTRA_ELAPSED, SystemClock.elapsedRealtime());
        DailyAlarmWire.send(sender, intent);
    }

    public static LiveWindowPayload fromIntent(Intent intent, String expectedSource) {
        if (intent == null || !ACTION.equals(intent.getAction())) return null;
        long at = intent.getLongExtra(EXTRA_AT, 0L);
        long elapsed = intent.getLongExtra(EXTRA_ELAPSED, -1L);
        if (!DailyAlarmWire.inWindow(at, elapsed, MAX_AGE_MS)) return null;

        String key = intent.getStringExtra(EXTRA_KEY);
        if (key == null || key.trim().isEmpty()) return null;
        int op = intent.getIntExtra(EXTRA_OP, -1);
        if (op != LiveWindowPayload.OP_CREATE && op != LiveWindowPayload.OP_UPDATE && op != LiveWindowPayload.OP_CLOSE) {
            return null;
        }
        String pkg = intent.getStringExtra(EXTRA_PKG);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String text = intent.getStringExtra(EXTRA_TEXT);
        String capTitle = intent.getStringExtra(EXTRA_CAP_TITLE);
        String capContent = intent.getStringExtra(EXTRA_CAP_CONTENT);
        String event = intent.getStringExtra(EXTRA_EVENT);
        int progress = intent.getIntExtra(EXTRA_PROGRESS, -1);
        String src = expectedSource != null ? expectedSource : intent.getStringExtra(EXTRA_SOURCE);
        LiveWindowPayload payload = new LiveWindowPayload(
                src, key, pkg, title, text, capTitle, capContent, event, progress, op);
        if (op != LiveWindowPayload.OP_CLOSE && !payload.isValidForOpenOrUpdate()) return null;
        return payload;
    }
}
