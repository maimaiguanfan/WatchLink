package io.github.watchlink.xposed;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
import java.lang.reflect.Method;
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
    private static final int MAX_TRACKED_KEYS = 32;

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
    private Method isPromotedMethod;
    private Method shortCriticalMethod;
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

            Class<?> listenerCls = Class.forName(LISTENER_CLASS, false, loader);
            Method onPosted = listenerCls.getDeclaredMethod("onNotificationPosted", StatusBarNotification.class);
            Method onRemoved = listenerCls.getDeclaredMethod("onNotificationRemoved", StatusBarNotification.class);

            handles.add(buildHook(onPosted, "health.daemon.live.posted").intercept(chain -> {
                boolean regularEnabled = hostSettings.isLiveWindowEnabled();
                boolean gameEnabled = hostSettings.isGameFlashbackEnabled();
                if (active && watchSupported && (regularEnabled || gameEnabled)) {
                    Object arg = chain.getArg(0);
                    if (arg instanceof StatusBarNotification sbn) {
                        LiveWindowPayload payload = extractLivePayload(sbn);
                        if (payload != null) {
                            boolean allowed = payload.isGameFlashback() ? gameEnabled : regularEnabled;
                            if (allowed) {
                                if (activeKeys.size() < MAX_TRACKED_KEYS || activeKeys.contains(sbn.getKey())) {
                                    activeKeys.add(sbn.getKey());
                                    if (payload.isGameFlashback()) {
                                        activeGameKeys.add(sbn.getKey());
                                    } else {
                                        activeGameKeys.remove(sbn.getKey());
                                    }
                                    forwardAsync(payload, false);
                                    // Suppress duplicate ordinary watch notification in-place.
                                    return null;
                                }
                            } else {
                                closeIfTracked(sbn);
                            }
                        } else {
                            // A notification ID can be reused for an unrelated card.
                            closeIfTracked(sbn);
                        }
                    }
                }
                return chain.proceed();
            }));

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
                if (!HealthLiveWindowBridge.DAEMON_SYNC_ACTION.equals(intent.getAction())) return;
                boolean enabled = intent.getBooleanExtra("enabled", false);
                boolean gameEnabled = intent.getBooleanExtra("gameFlashbackEnabled", false);
                watchSupported = intent.getBooleanExtra("watchSupported", true);
                hostSettings.setLocalLiveWindowFallback(enabled);
                hostSettings.setLocalGameFlashbackFallback(gameEnabled);
                if (!enabled) activeKeys.removeIf(k -> !activeGameKeys.contains(k));
                if (!gameEnabled) {
                    activeKeys.removeAll(activeGameKeys);
                    activeGameKeys.clear();
                }
            }
        };
        app.registerReceiver(r, new IntentFilter(HealthLiveWindowBridge.DAEMON_SYNC_ACTION), Context.RECEIVER_NOT_EXPORTED);
        syncReceiver = r;
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
