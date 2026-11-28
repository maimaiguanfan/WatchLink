package io.github.watchlink.xposed;

import android.app.BackgroundServiceStartNotAllowedException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.live.GameReviveCoordinator;
import io.github.watchlink.live.LiveWindowPayload;
import io.github.watchlink.live.LiveWindowTracker;
import io.github.watchlink.live.LiveWindowWire;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import io.github.watchlink.timer.TimerCards;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Huawei Health :PhoneService SmartEdge live-window dispatcher.
 * Receives authenticated live-window updates from Channel B (com.oplus.pantanal.ums) and
 * Channel A (com.huawei.health:DaemonService), deduplicates/throttles, and dispatches via
 * unobfuscated HandleIntentService with automatic in-process HwNotificationLiveMgr (oca) fallback.
 */
@RequiresApi(34)
final class HealthLiveWindowBridge {
    static final String DAEMON_SYNC_ACTION = BuildConfig.APPLICATION_ID + ".live.DAEMON_SYNC_V1";

    private static final String HANDLE_INTENT_SERVICE = "com.huawei.hwdevice.phoneprocess.service.HandleIntentService";
    private static final String ACTION_NOTIFICATION_PUSH = "com.huawei.bone.ACTION_NOTIFICATION_PUSH";
    private static final String EXTRA_LIVE_TYPE = "LIVE_NOTIFICATION_PUSH_TYPE";
    private static final String EXTRA_LIVE_TYPE_VAL = "LIVE_NOTIFICATION_PUSH";
    private static final String EXTRA_LIVE_JSON = "LIVE_NOTIFICATION_PUSH_JSON_STRING";
    private static final String DATA_RECEIVE_CALLBACK = "com.huawei.health.devicemgr.business.callback.DataReceiveCallback";

    private static final String TEST_STABLE_KEY = "watchlink:live-self-test";
    private static final long CLEANUP_INTERVAL_MS = 5 * 60_000L;
    private static final long TEST_UPDATE_DELAY_MS = 6_000L;
    private static final long TEST_CLOSE_DELAY_MS = 14_000L;
    private static final long GAME_DONE_ALERT_CLOSE_MS = 3_500L;

    record StatusSnapshot(
            String watchSupport,
            int activeCount,
            String lastResult,
            String lastSource,
            String dispatchMode,
            String testState) {}

    private final Context app;
    private final HostSettings settings;
    private final ScheduledExecutorService worker;
    private final HealthCooperativeReadiness readiness;
    // Only opaque occurrence keys survive a Health restart. A missed CLOSE can then be replayed.
    private final SharedPreferences timerLedger;
    private static final String PUBLISHED = "publishedKeys";
    private final LiveWindowTracker tracker = new LiveWindowTracker();
    private final GameReviveCoordinator gameRevive = new GameReviveCoordinator();
    private final Map<String, ScheduledFuture<?>> reviveTimers = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> doneCloseTimers = new HashMap<>();

    private BroadcastReceiver wireReceiver;
    private Method liveMgrSingleton;
    private Method liveMgrSendJson;
    private boolean handleIntentReady;
    private ScheduledFuture<?> cleanupFuture;
    private ScheduledFuture<?> testUpdateFuture;
    private ScheduledFuture<?> testCloseFuture;
    private LiveWindowPayload activeTestPayload;

    private volatile boolean installed;
    private volatile String lastResult = "NONE";
    private volatile String lastSource = "NONE";
    private volatile String lastDispatchMode = "NONE";
    private volatile String testState = "IDLE";

    HealthLiveWindowBridge(Context app, HostSettings settings, ScheduledExecutorService worker,
                           HealthCooperativeReadiness readiness) {
        this.app = app;
        this.settings = settings;
        this.timerLedger = app.getSharedPreferences(BuildConfig.APPLICATION_ID + ".timer_cards", Context.MODE_PRIVATE);
        this.worker = worker;
        this.readiness = readiness;
    }

    void install(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        handleIntentReady = checkHandleIntentService();
        resolveDirectFallback(installer, symbols);

        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!installed || intent == null || !LiveWindowWire.ACTION.equals(intent.getAction())) return;
                int uid = getSentFromUid();
                String pkg = getSentFromPackage();
                TargetProfile senderProfile = TargetProfile.fromPackage(pkg);
                if (uid < 0 || (senderProfile != TargetProfile.UMS && senderProfile != TargetProfile.HEALTH && senderProfile != TargetProfile.CLOCK)
                        || intent.getComponent() != null
                        || !TargetProfile.HEALTH.packageName.equals(intent.getPackage())
                        || (intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
                    return;
                }
                PendingResult pending = goAsync();
                try {
                    worker.execute(() -> {
                        try {
                            if (!installed || !SenderVerifier.verifyReminderSender(app, senderProfile, uid, pkg)) return;
                            String expectedSource = senderProfile == TargetProfile.CLOCK ? LiveWindowPayload.SOURCE_CLOCK_TIMER
                                    : senderProfile == TargetProfile.UMS
                                    ? LiveWindowPayload.SOURCE_UMS : LiveWindowPayload.SOURCE_DAEMON;
                            LiveWindowPayload payload = LiveWindowWire.fromIntent(intent, expectedSource);
                            if (payload != null) handleIncomingLocked(payload);
                        } catch (RuntimeException | LinkageError err) {
                            ProbeLog.failure("HEALTH_LIVE_DISPATCH", err);
                        } finally {
                            pending.finish();
                        }
                    });
                } catch (RuntimeException shuttingDown) {
                    pending.finish();
                }
            }
        };
        app.registerReceiver(r, new IntentFilter(LiveWindowWire.ACTION), Context.RECEIVER_EXPORTED);
        wireReceiver = r;

        settings.onLiveWindowChanged(enabled -> worker.execute(() -> {
            notifyDaemonState(Boolean.TRUE.equals(enabled), settings.isGameFlashbackEnabled(),
                    isWatchCapableOrUnknown(), settings.isNotifyMuteDndEnabled(),
                    settings.isNotifySilentPushEnabled());
            if (!Boolean.TRUE.equals(enabled)) {
                closeGroupLocked(false);
            }
        }));
        settings.onGameFlashbackChanged(enabled -> worker.execute(() -> {
            notifyDaemonState(settings.isLiveWindowEnabled(), Boolean.TRUE.equals(enabled),
                    isWatchCapableOrUnknown(), settings.isNotifyMuteDndEnabled(),
                    settings.isNotifySilentPushEnabled());
            if (!Boolean.TRUE.equals(enabled)) {
                closeGameFlashbackLocked();
            }
        }));
        settings.onGameFlashbackLeadSecChanged(leadSec -> worker.execute(() -> {
            if (installed && settings.isGameFlashbackEnabled() && leadSec != null) {
                rescheduleGameReviveLocked(leadSec);
            }
        }));
        settings.onNotifyMuteDndChanged(enabled -> worker.execute(() ->
                notifyDaemonState(settings.isLiveWindowEnabled(), settings.isGameFlashbackEnabled(),
                        isWatchCapableOrUnknown(), Boolean.TRUE.equals(enabled),
                        settings.isNotifySilentPushEnabled())));
        settings.onNotifySilentPushChanged(enabled -> worker.execute(() ->
                notifyDaemonState(settings.isLiveWindowEnabled(), settings.isGameFlashbackEnabled(),
                        isWatchCapableOrUnknown(), settings.isNotifyMuteDndEnabled(),
                        Boolean.TRUE.equals(enabled))));

        cleanupFuture = worker.scheduleWithFixedDelay(
                this::expireOrphansLocked, CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        installed = true;
        worker.execute(this::closeForgottenTimers);
        notifyDaemonState(settings.isLiveWindowEnabled(), settings.isGameFlashbackEnabled(),
                isWatchCapableOrUnknown(), settings.isNotifyMuteDndEnabled(),
                settings.isNotifySilentPushEnabled());
        ProbeLog.info("HEALTH_LIVE_READY source=HEALTH");
    }

    boolean isBusy() {
        return activeTestPayload != null;
    }

    void onSettingsFallbackChanged(boolean enabled) {
        notifyDaemonState(enabled, settings.isGameFlashbackEnabled(),
                isWatchCapableOrUnknown(), settings.isNotifyMuteDndEnabled(),
                settings.isNotifySilentPushEnabled());
        if (!enabled) {
            closeGroupLocked(false);
        }
    }

    void onGameFlashbackFallbackChanged(boolean enabled) {
        notifyDaemonState(settings.isLiveWindowEnabled(), enabled,
                isWatchCapableOrUnknown(), settings.isNotifyMuteDndEnabled(),
                settings.isNotifySilentPushEnabled());
        if (!enabled) {
            closeGameFlashbackLocked();
        }
    }

    void onNotifyMuteDndFallbackChanged(boolean enabled) {
        notifyDaemonState(settings.isLiveWindowEnabled(), settings.isGameFlashbackEnabled(),
                isWatchCapableOrUnknown(), enabled, settings.isNotifySilentPushEnabled());
    }

    void onNotifySilentPushFallbackChanged(boolean enabled) {
        notifyDaemonState(settings.isLiveWindowEnabled(), settings.isGameFlashbackEnabled(),
                isWatchCapableOrUnknown(), settings.isNotifyMuteDndEnabled(), enabled);
    }

    void onGameFlashbackLeadSecChanged(int leadSec) {
        if (installed && settings.isGameFlashbackEnabled()) {
            rescheduleGameReviveLocked(leadSec);
        }
    }

    void close() {
        installed = false;
        settings.onLiveWindowChanged(null);
        settings.onGameFlashbackChanged(null);
        settings.onGameFlashbackLeadSecChanged(null);
        settings.onNotifyMuteDndChanged(null);
        settings.onNotifySilentPushChanged(null);
        cancelTestFutures();
        cancelAllReviveTimersLocked();
        gameRevive.clear();
        activeTestPayload = null;
        if (cleanupFuture != null) {
            cleanupFuture.cancel(false);
            cleanupFuture = null;
        }
        if (wireReceiver != null) {
            try { app.unregisterReceiver(wireReceiver); } catch (RuntimeException ignored) {}
            wireReceiver = null;
        }
    }

    StatusSnapshot statusSnapshot() {
        String support = readWatchSupport();
        return new StatusSnapshot(support, tracker.activeCount(), lastResult, lastSource, lastDispatchMode, testState);
    }

    void startLiveTest() {
        cancelTestFutures();
        String support = readWatchSupport();
        if ("WATCH_DISCONNECTED".equals(support) || "UNSUPPORTED".equals(support)) {
            testState = "WATCH_UNAVAILABLE";
            lastResult = "WATCH_DISCONNECTED".equals(support) ? "WATCH_DISCONNECTED" : "UNSUPPORTED_WATCH";
            lastSource = LiveWindowPayload.SOURCE_TEST;
            ProbeLog.info("HEALTH_LIVE_TEST source=TEST result=WATCH_UNAVAILABLE");
            return;
        }

        LiveWindowPayload open = new LiveWindowPayload(
                LiveWindowPayload.SOURCE_TEST,
                TEST_STABLE_KEY,
                BuildConfig.APPLICATION_ID,
                "WatchLink 实况测试",
                "模拟骑手正在配送，预计10分钟（测试）",
                "模拟配送",
                "预计10分钟",
                LiveWindowPayload.EVENT_DELIVERY,
                35,
                LiveWindowPayload.OP_CREATE);

        String mode = dispatchJson(open.toSmartEdgeJson());
        lastDispatchMode = mode;
        lastSource = LiveWindowPayload.SOURCE_TEST;
        if (!isDispatchSuccess(mode)) {
            testState = "FAILED";
            lastResult = "BG_RESTRICTED".equals(mode) ? "BG_RESTRICTED" : "FAILED";
            ProbeLog.info("HEALTH_LIVE_TEST source=TEST result=FAILED");
            return;
        }

        activeTestPayload = open;
        testState = "OPENED";
        lastResult = "OPENED";
        ProbeLog.info("HEALTH_LIVE_TEST source=TEST live_op=CREATE mode=" + mode);

        testUpdateFuture = worker.schedule(() -> {
            if (!installed || activeTestPayload == null) return;
            LiveWindowPayload update = new LiveWindowPayload(
                    LiveWindowPayload.SOURCE_TEST,
                    TEST_STABLE_KEY,
                    BuildConfig.APPLICATION_ID,
                    "WatchLink 实况测试",
                    "模拟骑手即将到达，预计2分钟（测试）",
                    "模拟配送",
                    "预计2分钟",
                    LiveWindowPayload.EVENT_DELIVERY,
                    80,
                    LiveWindowPayload.OP_UPDATE);
            String updMode = dispatchJson(update.toSmartEdgeJson());
            lastDispatchMode = updMode;
            if (isDispatchSuccess(updMode)) {
                activeTestPayload = update;
                testState = "UPDATED";
                lastResult = "UPDATED";
                ProbeLog.info("HEALTH_LIVE_TEST source=TEST live_op=UPDATE mode=" + updMode);
            }
        }, TEST_UPDATE_DELAY_MS, TimeUnit.MILLISECONDS);

        testCloseFuture = worker.schedule(() -> {
            if (!installed || activeTestPayload == null) return;
            finishLiveTestLocked();
        }, TEST_CLOSE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    void cancelLiveTest() {
        cancelTestFutures();
        if (activeTestPayload != null) {
            finishLiveTestLocked();
        } else {
            testState = "IDLE";
        }
    }

    private void finishLiveTestLocked() {
        LiveWindowPayload current = activeTestPayload;
        activeTestPayload = null;
        if (current != null) {
            String mode = dispatchJson(current.withOperation(LiveWindowPayload.OP_CLOSE).toSmartEdgeJson());
            lastDispatchMode = mode;
        }
        testState = "CLOSED";
        lastResult = "CLOSED";
        lastSource = LiveWindowPayload.SOURCE_TEST;
        ProbeLog.info("HEALTH_LIVE_TEST source=TEST live_op=CLOSE result=CLOSED");
    }

    private void cancelTestFutures() {
        if (testUpdateFuture != null) {
            testUpdateFuture.cancel(false);
            testUpdateFuture = null;
        }
        if (testCloseFuture != null) {
            testCloseFuture.cancel(false);
            testCloseFuture = null;
        }
    }

    private void handleIncomingLocked(LiveWindowPayload incoming) {
        boolean timer = LiveWindowPayload.SOURCE_CLOCK_TIMER.equals(incoming.source());
        boolean gameFlashback = incoming.isGameFlashback();
        boolean featureEnabled = timer
                ? (settings.isTimerEnabled() && settings.isTimerLiveWindowEnabled())
                : (gameFlashback ? settings.isGameFlashbackEnabled() : settings.isLiveWindowEnabled());
        if (incoming.operation() != LiveWindowPayload.OP_CLOSE && !featureEnabled) {
            lastResult = "DISABLED";
            lastSource = incoming.source();
            return;
        }
        if (incoming.operation() == LiveWindowPayload.OP_CLOSE) {
            LiveWindowPayload closed = tracker.onRemove(incoming.stableKey());
            if (gameFlashback) {
                cancelReviveTimerLocked(incoming.stableKey());
                GameReviveCoordinator.AlertAction edgeAlert = gameRevive.onRemove(
                        incoming.stableKey(), SystemClock.elapsedRealtime(), settings.gameFlashbackLeadSec());
                if (closed != null) {
                    String mode = dispatchCard(closed);
                    lastDispatchMode = mode;
                    lastSource = closed.source();
                    lastResult = isDispatchSuccess(mode) ? "CLOSED" : ("BG_RESTRICTED".equals(mode) ? "BG_RESTRICTED" : "FAILED");
                    ProbeLog.info("HEALTH_LIVE_DISPATCH source=" + closed.source() + " live_op=CLOSE mode=" + mode);
                }
                if (edgeAlert != null && settings.isGameFlashbackEnabled()) {
                    fireGameReviveAlertLocked(edgeAlert, closed != null ? closed.source() : incoming.source(), true);
                }
                return;
            }
            if (closed == null) {
                if (!timer || !publishedTimerKeys().contains(incoming.stableKey())) return;
                closed = TimerCards.close(incoming.stableKey());
            }
            String mode = dispatchCard(closed);
            lastDispatchMode = mode;
            lastSource = closed.source();
            lastResult = isDispatchSuccess(mode) ? "CLOSED" : ("BG_RESTRICTED".equals(mode) ? "BG_RESTRICTED" : "FAILED");
            ProbeLog.info("HEALTH_LIVE_DISPATCH source=" + closed.source() + " live_op=CLOSE mode=" + mode);
            return;
        }

        String support = readWatchSupport();
        boolean supported = !"UNSUPPORTED".equals(support);
        if (!timer) {
            notifyDaemonState(settings.isLiveWindowEnabled(), settings.isGameFlashbackEnabled(),
                    supported, settings.isNotifyMuteDndEnabled(), settings.isNotifySilentPushEnabled());
        }
        if ("WATCH_DISCONNECTED".equals(support)) {
            lastResult = "WATCH_DISCONNECTED";
            lastSource = incoming.source();
            return;
        }
        if (!supported) {
            lastResult = "UNSUPPORTED_WATCH";
            lastSource = incoming.source();
            return;
        }

        // Prevent duplicate watch cards when both Channel A (DaemonService) and Channel B (UMS) observe the same app.
        if (LiveWindowPayload.SOURCE_UMS.equals(incoming.source())
                && !tracker.isActive(incoming.stableKey())
                && tracker.hasOtherSourceForPackage(incoming.packageName(), incoming.eventType(), incoming.source())) {
            return;
        }

        long nowElapsed = SystemClock.elapsedRealtime();
        LiveWindowPayload toSend = tracker.onUpsert(incoming, nowElapsed);
        if (toSend != null) {
            String mode = dispatchCard(toSend);
            lastDispatchMode = mode;
            lastSource = toSend.source();
            if (isDispatchSuccess(mode)) {
                lastResult = toSend.operation() == LiveWindowPayload.OP_CREATE ? "OPENED" : "UPDATED";
                ProbeLog.info("HEALTH_LIVE_DISPATCH source=" + toSend.source()
                        + " live_op=" + LiveWindowPayload.opName(toSend.operation())
                        + " live_event=" + toSend.eventType()
                        + " mode=" + mode);
            } else {
                if (toSend.operation() == LiveWindowPayload.OP_CREATE) {
                    tracker.onRemove(toSend.stableKey());
                }
                lastResult = "BG_RESTRICTED".equals(mode) ? "BG_RESTRICTED" : "FAILED";
                ProbeLog.info("HEALTH_LIVE_DISPATCH source=" + toSend.source() + " result=" + lastResult);
                return;
            }
        }
        if (gameFlashback) {
            GameReviveCoordinator.UpsertPlan plan = gameRevive.onUpsert(
                    incoming, nowElapsed, settings.gameFlashbackLeadSec());
            applyRevivePlanLocked(incoming.stableKey(), incoming.source(), plan, nowElapsed);
        }
    }

    void closeGroupLocked(boolean timers) {
        List<LiveWindowPayload> closures = tracker.closeSourceGroup(timers);
        for (LiveWindowPayload close : closures) {
            String mode = dispatchCard(close);
            lastDispatchMode = mode;
        }
        if (!closures.isEmpty()) {
            lastResult = "DISABLED";
        }
    }

    void closeGameFlashbackLocked() {
        cancelAllReviveTimersLocked();
        gameRevive.clear();
        List<LiveWindowPayload> closures = tracker.closeGameFlashback();
        for (LiveWindowPayload close : closures) {
            String mode = dispatchCard(close);
            lastDispatchMode = mode;
        }
        if (!closures.isEmpty()) {
            lastResult = "DISABLED";
        }
    }

    private void rescheduleGameReviveLocked(int leadSec) {
        long nowElapsed = SystemClock.elapsedRealtime();
        Map<String, GameReviveCoordinator.UpsertPlan> plans = gameRevive.onLeadSecChanged(nowElapsed, leadSec);
        for (Map.Entry<String, GameReviveCoordinator.UpsertPlan> entry : plans.entrySet()) {
            applyRevivePlanLocked(entry.getKey(), LiveWindowPayload.SOURCE_UMS, entry.getValue(), nowElapsed);
        }
    }

    private void applyRevivePlanLocked(
            String stableKey, String source, GameReviveCoordinator.UpsertPlan plan, long nowElapsed) {
        if (plan == null) return;
        cancelReviveTimerLocked(stableKey);
        if (plan.immediateAlert() != null) {
            fireGameReviveAlertLocked(plan.immediateAlert(), source, false);
            return;
        }
        if (plan.scheduleAtElapsedMs() != null) {
            long delayMs = Math.max(0L, plan.scheduleAtElapsedMs() - nowElapsed);
            long generation = plan.generation();
            ScheduledFuture<?> future = worker.schedule(() -> {
                if (!installed || !settings.isGameFlashbackEnabled()) return;
                reviveTimers.remove(stableKey);
                GameReviveCoordinator.AlertAction alert = gameRevive.onTimerFired(
                        stableKey, generation, SystemClock.elapsedRealtime(), settings.gameFlashbackLeadSec());
                if (alert != null) {
                    fireGameReviveAlertLocked(alert, source, false);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
            reviveTimers.put(stableKey, future);
        }
    }

    private void fireGameReviveAlertLocked(
            GameReviveCoordinator.AlertAction alert, String source, boolean cardAlreadyClosed) {
        if (alert == null || !installed) return;
        String support = readWatchSupport();
        if ("WATCH_DISCONNECTED".equals(support) || "UNSUPPORTED".equals(support)) return;

        boolean useStandalone = cardAlreadyClosed || alert.completionAlert();
        LiveWindowPayload payload = useStandalone
                ? alert.toStandaloneCompletionPayload(source)
                : alert.toInPlaceRemindPayload(source);
        String mode = dispatchJson(payload.toSmartEdgeJson(true));
        lastDispatchMode = mode;
        lastSource = source;
        ProbeLog.info("HEALTH_GAME_REVIVE_ALERT lead_sec=" + settings.gameFlashbackLeadSec()
                + " remaining_sec=" + alert.remainingSec()
                + " standalone=" + useStandalone
                + " mode=" + mode);

        if (useStandalone && isDispatchSuccess(mode)) {
            String doneKey = payload.stableKey();
            ScheduledFuture<?> oldDone = doneCloseTimers.remove(doneKey);
            if (oldDone != null) oldDone.cancel(false);
            ScheduledFuture<?> closeDone = worker.schedule(() -> {
                doneCloseTimers.remove(doneKey);
                if (!installed) return;
                dispatchJson(payload.withOperation(LiveWindowPayload.OP_CLOSE).toSmartEdgeJson(false));
            }, GAME_DONE_ALERT_CLOSE_MS, TimeUnit.MILLISECONDS);
            doneCloseTimers.put(doneKey, closeDone);
        }
    }

    private void cancelReviveTimerLocked(String stableKey) {
        ScheduledFuture<?> future = reviveTimers.remove(stableKey);
        if (future != null) future.cancel(false);
    }

    private void cancelAllReviveTimersLocked() {
        for (ScheduledFuture<?> future : reviveTimers.values()) {
            future.cancel(false);
        }
        reviveTimers.clear();
        for (ScheduledFuture<?> future : doneCloseTimers.values()) {
            future.cancel(false);
        }
        doneCloseTimers.clear();
    }

    private void expireOrphansLocked() {
        if (!installed) return;
        List<LiveWindowPayload> stale = tracker.expireStale(
                SystemClock.elapsedRealtime(), LiveWindowTracker.DEFAULT_ORPHAN_TIMEOUT_MS);
        for (LiveWindowPayload close : stale) {
            if (close.isGameFlashback()) {
                cancelReviveTimerLocked(close.stableKey());
                gameRevive.onRemove(close.stableKey(), 0L, settings.gameFlashbackLeadSec());
            }
            dispatchCard(close);
        }
        closeForgottenTimers();
    }

    private Set<String> publishedTimerKeys() {
        return timerLedger.getStringSet(PUBLISHED, Set.of());
    }

    private void closeForgottenTimers() {
        if (!installed) return;
        for (String key : publishedTimerKeys()) {
            if (!tracker.isActive(key)) dispatchCard(TimerCards.close(key));
        }
    }

    private String dispatchCard(LiveWindowPayload card) {
        if (!LiveWindowPayload.SOURCE_CLOCK_TIMER.equals(card.source())) return dispatchJson(card.toSmartEdgeJson());
        boolean closing = card.operation() == LiveWindowPayload.OP_CLOSE;
        if (closing && "WATCH_DISCONNECTED".equals(readWatchSupport())) return "FAILED";
        String mode = dispatchJson(card.toSmartEdgeJson());
        if (isDispatchSuccess(mode) && (closing || card.operation() == LiveWindowPayload.OP_CREATE)) {
            Set<String> keys = new HashSet<>(publishedTimerKeys());
            boolean changed = closing ? keys.remove(card.stableKey()) : keys.add(card.stableKey());
            if (changed) timerLedger.edit().putStringSet(PUBLISHED, keys).apply();
        }
        return mode;
    }

    private String dispatchJson(String json) {
        if (json == null || json.isEmpty()) return "FAILED";
        String mode = sendViaHandleIntentService(json);
        if ("START_SERVICE".equals(mode)) return mode;
        String fallback = sendDirectLiveMgr(json);
        if ("DIRECT_SDK".equals(fallback)) return fallback;
        return "BG_RESTRICTED".equals(mode) ? "BG_RESTRICTED" : "FAILED";
    }

    private String sendViaHandleIntentService(String json) {
        if (!handleIntentReady) return "FAILED";
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(TargetProfile.HEALTH.packageName, HANDLE_INTENT_SERVICE));
            intent.setAction(ACTION_NOTIFICATION_PUSH);
            intent.putExtra(EXTRA_LIVE_TYPE, EXTRA_LIVE_TYPE_VAL);
            intent.putExtra(EXTRA_LIVE_JSON, json);
            ComponentName started = app.startService(intent);
            return started != null ? "START_SERVICE" : "FAILED";
        } catch (RuntimeException err) {
            if (err instanceof BackgroundServiceStartNotAllowedException) return "BG_RESTRICTED";
            return "FAILED";
        }
    }

    private String sendDirectLiveMgr(String json) {
        Method singleton = liveMgrSingleton;
        Method send = liveMgrSendJson;
        if (singleton == null || send == null) return "FAILED";
        try {
            Object mgr = singleton.invoke(null);
            if (mgr == null) return "FAILED";
            send.invoke(mgr, json);
            return "DIRECT_SDK";
        } catch (Throwable ignored) {
            return "FAILED";
        }
    }

    private static boolean isDispatchSuccess(String mode) {
        return "START_SERVICE".equals(mode) || "DIRECT_SDK".equals(mode);
    }

    private boolean checkHandleIntentService() {
        try {
            ComponentName cn = new ComponentName(TargetProfile.HEALTH.packageName, HANDLE_INTENT_SERVICE);
            ServiceInfo info = app.getPackageManager().getServiceInfo(cn, 0);
            return info != null && !info.exported && TargetProfile.HEALTH.isReminderProcess(info.processName);
        } catch (Exception unavailable) {
            return false;
        }
    }

    private void resolveDirectFallback(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        String className = symbols != null ? symbols.liveNotifyMgrClass() : "";
        if (className == null || className.isEmpty()) return;
        try {
            Class<?> cb = installer.target(DATA_RECEIVE_CALLBACK);
            Class<?> mgrCls = installer.target(className);
            if (!cb.isAssignableFrom(mgrCls)) return;
            Method singleton = null;
            Method send = null;
            for (Method m : mgrCls.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (!Modifier.isPublic(mod) || m.isSynthetic()) continue;
                if (Modifier.isStatic(mod) && m.getReturnType() == mgrCls && m.getParameterCount() == 0) {
                    singleton = m;
                } else if (!Modifier.isStatic(mod) && m.getReturnType() == void.class
                        && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                    send = m;
                }
            }
            if (singleton != null && send != null) {
                liveMgrSingleton = singleton;
                liveMgrSendJson = send;
            }
        } catch (Throwable ignored) {
            liveMgrSingleton = null;
            liveMgrSendJson = null;
        }
    }

    private String readWatchSupport() {
        if (readiness == null) return "UNKNOWN";
        HealthCooperativeReadiness.LiveDeviceSnapshot snap = readiness.readLiveSnapshot();
        if (!snap.connected()) return "WATCH_DISCONNECTED";
        if (Boolean.FALSE.equals(snap.bit84())) return "UNSUPPORTED";
        if (Boolean.TRUE.equals(snap.bit84())) {
            return Boolean.TRUE.equals(snap.bit219()) ? "SUPPORTED_LIVE_LEVEL" : "SUPPORTED_BASIC";
        }
        return "UNKNOWN";
    }

    private boolean isWatchCapableOrUnknown() {
        return !"UNSUPPORTED".equals(readWatchSupport());
    }

    /** Same-package non-exported notification to Health :DaemonService for local fallback sync. */
    private void notifyDaemonState(boolean enabled, boolean gameFlashbackEnabled,
                                   boolean watchSupported, boolean notifyMuteDndEnabled,
                                   boolean notifySilentPushEnabled) {
        try {
            Intent sync = new Intent(DAEMON_SYNC_ACTION)
                    .setPackage(TargetProfile.HEALTH.packageName)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    .putExtra("enabled", enabled)
                    .putExtra("gameFlashbackEnabled", gameFlashbackEnabled)
                    .putExtra("watchSupported", watchSupported)
                    .putExtra("notifyMuteDndEnabled", notifyMuteDndEnabled)
                    .putExtra("notifySilentPushEnabled", notifySilentPushEnabled);
            app.sendBroadcast(sync);
        } catch (RuntimeException ignored) {}
    }
}
