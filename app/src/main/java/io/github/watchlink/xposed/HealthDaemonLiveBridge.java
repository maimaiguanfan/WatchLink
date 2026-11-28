package io.github.watchlink.xposed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import androidx.annotation.RequiresApi;
import io.github.libxposed.api.XposedInterface;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.live.LiveWindowPayload;
import io.github.watchlink.live.LiveWindowWire;
import io.github.watchlink.live.PantanalFluidParser;
import io.github.watchlink.notify.NotificationDndPolicy;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Channel A live-notification observer inside com.huawei.health:DaemonService.
 * Hooks unobfuscated NotificationPushListener to capture Android 16/17 promoted/live notifications,
 * forward them to :PhoneService via authenticated LiveWindowWire, and suppress duplicate ordinary
 * notification vibration on the watch in-place (zero cross-process race).
 */
@RequiresApi(34)
final class HealthDaemonLiveBridge {
    private static final String LISTENER_CLASS = "com.huawei.bone.ui.setting.NotificationPushListener";
    private static final String EXTRA_REMINDER_STATUS = "data_extra_reminder_status";
    private static final int REMINDER_STATUS_SILENT = 0;
    private static final int MAX_TRACKED_KEYS = 32;
    private static final long DND_MUTE_LOG_INTERVAL_MS = 10_000L;

    private final XposedInterface module;
    private final ClassLoader loader;
    private final Context app;
    private final SharedPreferences remotePrefs;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Set<String> activeKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> activeGameKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, String> appLabelCache = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "WatchLink-daemon-live");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean closed = new AtomicBoolean();

    private HostSettings settings;
    private BroadcastReceiver syncReceiver;
    private PhoneModeObserver modeObserver;
    private Method isPromotedMethod;
    private Method shortCriticalMethod;
    private volatile WeakReference<NotificationListenerService> listenerRef;
    private volatile long lastDndMuteLogElapsedMs;
    private volatile boolean watchSupported = true;
    private volatile boolean active;

    HealthDaemonLiveBridge(XposedInterface module, ClassLoader loader, Context app, SharedPreferences remotePrefs) {
        this.module = module;
        this.loader = loader;
        this.app = app;
        this.remotePrefs = remotePrefs;
    }

    void install() {
        if (closed.get() || app == null || loader == null) return;
        if (!TargetProfile.HEALTH.packageName.equals(app.getPackageName())
                || !SenderVerifier.trustedReminderTarget(app, TargetProfile.HEALTH)) {
            return;
        }
        try {
            SharedPreferences local = app.getSharedPreferences(
                    BuildConfig.APPLICATION_ID + ".health_daemon_settings_v1", Context.MODE_PRIVATE);
            HostSettings hostSettings = new HostSettings(remotePrefs, local);
            settings = hostSettings;

            resolveOptionalNotificationMethods();
            registerSyncReceiver(hostSettings);
            PhoneModeObserver observer = new PhoneModeObserver(app, worker);
            observer.start();
            modeObserver = observer;

            Class<?> listenerCls = Class.forName(LISTENER_CLASS, false, loader);
            Method onPosted = listenerCls.getDeclaredMethod("onNotificationPosted", StatusBarNotification.class);
            Method onRemoved = listenerCls.getDeclaredMethod("onNotificationRemoved", StatusBarNotification.class);
            try {
                Method onConnected = listenerCls.getDeclaredMethod("onListenerConnected");
                handles.add(buildHook(onConnected, "health.daemon.live.connected").intercept(chain -> {
                    captureListener(chain.getThisObject());
                    if (active) {
                        try { worker.execute(observer::refreshAll); } catch (RuntimeException ignored) {}
                    }
                    return chain.proceed();
                }));
            } catch (NoSuchMethodException ignored) {}

            handles.add(buildHook(onPosted, "health.daemon.live.posted").intercept(chain -> {
                captureListener(chain.getThisObject());
                if (active) {
                    Object arg = chain.getArg(0);
                    if (arg instanceof StatusBarNotification sbn) {
                        Notification n = sbn.getNotification();
                        if (TargetProfile.HEALTH.packageName.equals(sbn.getPackageName())
                                && n != null && n.extras != null
                                && n.extras.getBoolean(
                                        HealthBatteryAlertBridge.EXTRA_WATCHLINK_PHONE_LOCAL_BATTERY_ALERT, false)) {
                            // Never echo WatchLink's local watch-battery phone notification back to the watch.
                            return null;
                        }
                        String category = n == null ? null : n.category;
                        boolean silentOnDnd = hostSettings.isNotifyMuteDndEnabled();
                        boolean silentOnFocus = hostSettings.isNotifySilentFocusEnabled();
                        boolean dndActive = observer.isDndActive() || isListenerDndActive(chain.getThisObject());
                        boolean focusActive = observer.isFocusActive();
                        boolean holdLiveForQuiet = NotificationDndPolicy.evaluateSilentReason(
                                silentOnDnd, silentOnFocus, false, false, false,
                                dndActive, focusActive, false, false, false,
                                sbn.getPackageName(), category) != null;

                        boolean regularEnabled = hostSettings.isLiveWindowEnabled();
                        boolean gameEnabled = hostSettings.isGameFlashbackEnabled();
                        if (watchSupported && (regularEnabled || gameEnabled)) {
                            LiveWindowPayload payload = extractLivePayload(sbn);
                            if (payload != null) {
                                boolean game = payload.isGameFlashback();
                                // Game flashback penetrates game-foreground silent mode and DND/Focus quiet hold.
                                boolean allowed = game ? gameEnabled : (regularEnabled && !holdLiveForQuiet);
                                if (allowed) {
                                    if (activeKeys.size() < MAX_TRACKED_KEYS || activeKeys.contains(sbn.getKey())) {
                                        activeKeys.add(sbn.getKey());
                                        if (game) {
                                            activeGameKeys.add(sbn.getKey());
                                        } else {
                                            activeGameKeys.remove(sbn.getKey());
                                        }
                                        forwardAsync(payload, false);
                                        // Suppress duplicate ordinary watch notification in-place.
                                        return null;
                                    }
                                } else if (!holdLiveForQuiet) {
                                    closeIfTracked(sbn);
                                }
                            } else {
                                // A notification ID can be reused for an unrelated card.
                                closeIfTracked(sbn);
                            }
                        }
                    }
                }
                return chain.proceed();
            }));

            Method buildPushBundle = uniquePushBundleMethod(listenerCls);
            if (buildPushBundle != null) {
                handles.add(buildHook(buildPushBundle, "health.daemon.notify.bundle").intercept(chain -> {
                    Object res = chain.proceed();
                    if (active && res instanceof Bundle out && chain.getArg(1) instanceof StatusBarNotification sbn) {
                        boolean silentOnDnd = hostSettings.isNotifyMuteDndEnabled();
                        boolean silentOnFocus = hostSettings.isNotifySilentFocusEnabled();
                        boolean silentOnGame = hostSettings.isNotifySilentGameEnabled();
                        boolean silentOnScreenOn = hostSettings.isNotifySilentScreenOnEnabled();
                        boolean silentOnPhoneSilent = hostSettings.isNotifySilentPushEnabled();
                        if (silentOnDnd || silentOnFocus || silentOnGame || silentOnScreenOn || silentOnPhoneSilent) {
                            Notification n = sbn.getNotification();
                            String category = n == null ? null : n.category;
                            boolean dndActive = observer.isDndActive() || isListenerDndActive(chain.getThisObject());
                            boolean phoneSilent = silentOnPhoneSilent
                                    && isPhoneNotificationSilent(chain.getThisObject(), sbn);
                            String reason = NotificationDndPolicy.evaluateSilentReason(
                                    silentOnDnd, silentOnFocus, silentOnGame, silentOnScreenOn, silentOnPhoneSilent,
                                    dndActive, observer.isFocusActive(), observer.isGameForeground(),
                                    observer.isScreenUnlocked(), phoneSilent,
                                    sbn.getPackageName(), category);
                            if (reason != null) {
                                out.putInt(EXTRA_REMINDER_STATUS, REMINDER_STATUS_SILENT);
                                out.putBoolean(HealthSleepDndBridge.EXTRA_WATCHLINK_SILENT_PUSH, true);
                                out.putString(HealthSleepDndBridge.EXTRA_WATCHLINK_SILENT_REASON, reason);
                                logSilentPushThrottled(reason);
                            }
                        }
                    }
                    return res;
                }));
            }

            handles.add(buildHook(onRemoved, "health.daemon.live.removed").intercept(chain -> {
                if (active) {
                    Object arg = chain.getArg(0);
                    if (arg instanceof StatusBarNotification sbn) {
                        closeIfTracked(sbn);
                    }
                }
                return chain.proceed();
            }));

            hostSettings.onLiveWindowChanged(enabled -> {
                if (!Boolean.TRUE.equals(enabled)) activeKeys.removeIf(k -> !activeGameKeys.contains(k));
            });
            hostSettings.onGameFlashbackChanged(enabled -> {
                if (!Boolean.TRUE.equals(enabled)) {
                    activeKeys.removeAll(activeGameKeys);
                    activeGameKeys.clear();
                }
            });

            active = true;
            ProbeLog.info("DAEMON_LIVE_READY source=DAEMON");
        } catch (Throwable err) {
            ProbeLog.failure("DAEMON_LIVE_READY", err);
            close();
        }
    }

    void close() {
        if (!closed.compareAndSet(false, true)) return;
        active = false;
        activeKeys.clear();
        activeGameKeys.clear();
        listenerRef = null;
        if (modeObserver != null) {
            try { modeObserver.close(); } catch (RuntimeException ignored) {}
            modeObserver = null;
        }
        if (syncReceiver != null) {
            try { app.unregisterReceiver(syncReceiver); } catch (RuntimeException ignored) {}
            syncReceiver = null;
        }
        if (settings != null) {
            try { settings.close(); } catch (RuntimeException ignored) {}
            settings = null;
        }
        for (XposedInterface.HookHandle h : handles) {
            try { h.unhook(); } catch (RuntimeException ignored) {}
        }
        handles.clear();
        worker.shutdownNow();
    }

    private void registerSyncReceiver(HostSettings hostSettings) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (HealthLiveWindowBridge.DAEMON_SYNC_ACTION.equals(action)) {
                    boolean enabled = intent.getBooleanExtra("enabled", false);
                    boolean gameEnabled = intent.getBooleanExtra("gameFlashbackEnabled", false);
                    watchSupported = intent.getBooleanExtra("watchSupported", true);
                    hostSettings.setLocalLiveWindowFallback(enabled);
                    hostSettings.setLocalGameFlashbackFallback(gameEnabled);
                    if (intent.hasExtra("notifyMuteDndEnabled")) {
                        hostSettings.setLocalNotifyMuteDndFallback(
                                intent.getBooleanExtra("notifyMuteDndEnabled", false));
                    }
                    if (intent.hasExtra("notifySilentPushEnabled")) {
                        hostSettings.setLocalNotifySilentPushFallback(
                                intent.getBooleanExtra("notifySilentPushEnabled", false));
                    }
                    if (intent.hasExtra("notifySilentScreenOnEnabled")) {
                        hostSettings.setLocalNotifySilentScreenOnFallback(
                                intent.getBooleanExtra("notifySilentScreenOnEnabled", false));
                    }
                    if (intent.hasExtra("notifySilentFocusEnabled")) {
                        hostSettings.setLocalNotifySilentFocusFallback(
                                intent.getBooleanExtra("notifySilentFocusEnabled", false));
                    }
                    if (intent.hasExtra("notifySilentGameEnabled")) {
                        hostSettings.setLocalNotifySilentGameFallback(
                                intent.getBooleanExtra("notifySilentGameEnabled", false));
                    }
                    if (!enabled) activeKeys.removeIf(k -> !activeGameKeys.contains(k));
                    if (!gameEnabled) {
                        activeKeys.removeAll(activeGameKeys);
                        activeGameKeys.clear();
                    }
                } else if (HealthSleepDndBridge.DAEMON_DND_ACTION.equals(action)) {
                    int filter = intent.getIntExtra(
                            HealthSleepDndBridge.EXTRA_FILTER,
                            NotificationDndPolicy.INTERRUPTION_FILTER_UNKNOWN);
                    if (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            || filter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                        try {
                            worker.execute(() -> applyListenerInterruptionFilter(filter));
                        } catch (RuntimeException ignored) {}
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(HealthLiveWindowBridge.DAEMON_SYNC_ACTION);
        filter.addAction(HealthSleepDndBridge.DAEMON_DND_ACTION);
        app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED);
        syncReceiver = r;
    }

    private void applyListenerInterruptionFilter(int filter) {
        if (!active) return;
        WeakReference<NotificationListenerService> ref = listenerRef;
        NotificationListenerService listener = ref == null ? null : ref.get();
        if (listener == null) {
            ProbeLog.info("DAEMON_DND_REQUEST source=DAEMON dnd_result=FAILED");
            return;
        }
        try {
            listener.requestInterruptionFilter(filter);
            PhoneModeObserver observer = modeObserver;
            if (observer != null) observer.refreshDnd();
            ProbeLog.info("DAEMON_DND_REQUEST source=DAEMON dnd_result="
                    + (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY ? "ENABLE_DND" : "DISABLE_DND"));
        } catch (RuntimeException err) {
            ProbeLog.failure("DAEMON_DND_REQUEST", err);
        }
    }

    private void captureListener(Object self) {
        if (!(self instanceof NotificationListenerService nls)) return;
        WeakReference<NotificationListenerService> existing = listenerRef;
        if (existing == null || existing.get() != nls) {
            listenerRef = new WeakReference<>(nls);
        }
    }

    private static boolean isListenerDndActive(Object listenerObj) {
        if (!(listenerObj instanceof NotificationListenerService nls)) return false;
        try {
            return NotificationDndPolicy.isPhoneDndActive(nls.getCurrentInterruptionFilter(), 0);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isPhoneNotificationSilent(Object listenerObj, StatusBarNotification sbn) {
        if (!(listenerObj instanceof NotificationListenerService nls) || sbn == null) return false;
        String key = sbn.getKey();
        if (key == null || key.isEmpty()) return false;
        try {
            NotificationListenerService.RankingMap map = nls.getCurrentRanking();
            if (map == null) return false;
            NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
            if (!map.getRanking(key, ranking)) return false;
            NotificationChannel ch = ranking.getChannel();
            boolean hasChannel = ch != null;
            int chImp = hasChannel ? ch.getImportance() : NotificationDndPolicy.IMPORTANCE_UNSPECIFIED;
            boolean hasSound = hasChannel && ch.getSound() != null && !Uri.EMPTY.equals(ch.getSound());
            boolean shouldVibrate = hasChannel && ch.shouldVibrate();
            return NotificationDndPolicy.isPhoneNotificationSilent(
                    ranking.getImportance(), ranking.isAmbient(), hasChannel, chImp, hasSound, shouldVibrate);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Method uniquePushBundleMethod(Class<?> listenerCls) {
        Method found = null;
        for (Method m : listenerCls.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isStatic(mod) || m.isSynthetic()) continue;
            if (m.getReturnType() != Bundle.class || m.getParameterCount() != 2) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p[0] == Bundle.class && p[1] == StatusBarNotification.class) {
                if (found != null) return null;
                found = m;
            }
        }
        return found;
    }

    private void logSilentPushThrottled(String reason) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastDndMuteLogElapsedMs >= DND_MUTE_LOG_INTERVAL_MS) {
            lastDndMuteLogElapsedMs = now;
            ProbeLog.info("DAEMON_NOTIFY_SILENT_PUSH source=DAEMON silent_reason=" + reason);
        }
    }

    private void resolveOptionalNotificationMethods() {
        try { isPromotedMethod = Notification.class.getMethod("isPromotedOngoing"); }
        catch (Throwable ignored) {}
        try { shortCriticalMethod = Notification.class.getMethod("getShortCriticalText"); }
        catch (Throwable ignored) {}
    }

    private LiveWindowPayload extractLivePayload(StatusBarNotification sbn) {
        try {
            if (TargetProfile.CLOCK.packageName.equals(sbn.getPackageName())) return null;
            Notification n = sbn.getNotification();
            if (n == null) return null;
            Bundle extras = n.extras;
            boolean promoted = callBool(isPromotedMethod, n);
            String template = extras == null ? null : extras.getString(Notification.EXTRA_TEMPLATE);
            Object rawOplus = extras == null ? null : extras.get("oplusLiveAlertAppConfig");
            String oplusConfig = rawOplus instanceof String s ? s : null;
            boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            String pkg = sbn.getPackageName();
            if (!promoted
                    && (template == null || !template.endsWith("ProgressStyle"))
                    && (oplusConfig == null || oplusConfig.isEmpty())
                    && !ongoing) {
                return null;
            }
            String label = cachedAppLabel(pkg);
            String title = charSeq(extras, Notification.EXTRA_TITLE);
            String text = charSeq(extras, Notification.EXTRA_TEXT);
            String bigText = charSeq(extras, Notification.EXTRA_BIG_TEXT);
            String subText = charSeq(extras, Notification.EXTRA_SUB_TEXT);
            String shortCrit = callCharSeq(shortCriticalMethod, n);
            int progress = extras == null ? -1 : extras.getInt(Notification.EXTRA_PROGRESS, -1);
            int progressMax = extras == null ? 0 : extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
            boolean indet = extras != null && extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
            return PantanalFluidParser.parsePromotedNotification(
                    sbn.getKey(), pkg, label, n.flags, promoted, template, oplusConfig,
                    title, text, bigText, subText, shortCrit, progress, progressMax, indet);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void closeIfTracked(StatusBarNotification sbn) {
        String key = sbn.getKey();
        if (key == null || !activeKeys.remove(key)) return;
        boolean game = activeGameKeys.remove(key);
        String stableKey = (game ? LiveWindowPayload.GAME_FLASHBACK_KEY_PREFIX : "") + "daemon:" + key.trim();
        forwardAsync(new LiveWindowPayload(LiveWindowPayload.SOURCE_DAEMON, stableKey,
                sbn.getPackageName(), "", "", "", "",
                game ? LiveWindowPayload.EVENT_QUEUE : LiveWindowPayload.EVENT_PICK_UP,
                -1, LiveWindowPayload.OP_CLOSE), true);
    }

    private void forwardAsync(LiveWindowPayload payload, boolean removal) {
        try {
            worker.execute(() -> {
                if (!active) return;
                try {
                    LiveWindowWire.sendToHealth(app, payload);
                    ProbeLog.info((removal ? "DAEMON_LIVE_REMOVE" : "DAEMON_LIVE_FORWARD")
                            + " source=DAEMON live_event=" + payload.eventType());
                } catch (RuntimeException err) {
                    ProbeLog.failure("DAEMON_LIVE_FORWARD", err);
                }
            });
        } catch (RuntimeException ignored) {}
    }

    private String cachedAppLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String cached = appLabelCache.get(pkg);
        if (cached != null) return cached;
        if (appLabelCache.size() > 64) appLabelCache.clear();
        String label = "";
        try {
            PackageManager pm = app.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(info);
            if (cs != null) label = cs.toString().trim();
        } catch (Throwable ignored) {}
        appLabelCache.put(pkg, label);
        return label;
    }

    private static boolean callBool(Method method, Object target) {
        if (method == null) return false;
        try { return Boolean.TRUE.equals(method.invoke(target)); }
        catch (Throwable ignored) { return false; }
    }

    private static String callCharSeq(Method method, Object target) {
        if (method == null) return "";
        try {
            Object v = method.invoke(target);
            return v instanceof CharSequence cs ? cs.toString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String charSeq(Bundle extras, String key) {
        if (extras == null) return "";
        CharSequence cs = extras.getCharSequence(key);
        return cs == null ? "" : cs.toString();
    }

    private XposedInterface.HookBuilder buildHook(Method m, String id) {
        XposedInterface.HookBuilder b = module.hook(m);
        if (module.getApiVersion() >= XposedInterface.API_102) b.setId(id);
        return b;
    }
}
