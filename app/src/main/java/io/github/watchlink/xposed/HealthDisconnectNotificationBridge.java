package io.github.watchlink.xposed;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.notify.DisconnectNotificationQueue;
import io.github.watchlink.notify.NotificationBurstThrottlePolicy;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Huawei Health :PhoneService short-disconnect notification staging bridge.
 *
 * <p>Hooks unobfuscated {@code IntegratedServicesReceiver.onReceive(Context, Intent)} (plus an
 * optional {@code NotifySendData} send-failure race guard) so ordinary notifications arriving
 * while the watch is briefly disconnected are staged in memory for up to 1 minute (max 5 latest
 * deduplicated notifications). Dismissals on the phone remove matching staged items automatically.
 *
 * <p>Upon watch reconnect (after a short link-settle delay), valid staged notifications are
 * replayed in chronological order with paced spacing: at most ONE notification (the newest
 * non-silent message, pushed last) vibrates, while any additional staged notifications are forced
 * to silent watch delivery ({@code TLV 13 02 00 00}).
 */
@RequiresApi(34)
final class HealthDisconnectNotificationBridge {
    private static final String RECEIVER_CLASS =
            "com.huawei.hwdevice.phoneprocess.receiver.IntegratedServicesReceiver";
    private static final String ACTION_NOTIFICATION_PUSH = "com.huawei.bone.ACTION_NOTIFICATION_PUSH";
    private static final String ACTION_NOTIFICATION_DELETE = "com.huawei.bone.ACTION_NOTIFICATION_DELETE";
    private static final String ACTION_CONNECTION_CHANGED = "com.huawei.bone.action.CONNECTION_STATE_CHANGED";
    private static final String EXTRA_WATCHLINK_REPLAY = "watchlink_disconnect_replay";
    private static final String EXTRA_REMINDER_STATUS = "data_extra_reminder_status";
    private static final int CONNECT_STATE_CONNECTED = 2;
    private static final int CONNECT_STATE_DISCONNECTED = 3;
    private static final int CONNECT_STATE_FAILED = 4;
    private static final long RECONNECT_SETTLE_DELAY_MS = 1_500L;
    private static final long DELIVERY_SPACING_MS = 350L;

    private final Context app;
    private final HostSettings settings;
    private final ScheduledExecutorService worker;
    private final HealthCooperativeReadiness readiness;
    private final HealthSleepDndBridge sleepDndBridge;
    private final DisconnectNotificationQueue<Bundle> queue = new DisconnectNotificationQueue<>();
    private final NotificationBurstThrottlePolicy burstThrottle = new NotificationBurstThrottlePolicy();
    private final ThreadLocal<Bundle> inflightBundle = new ThreadLocal<>();

    private BroadcastReceiver stateReceiver;
    private Method receiverOnReceive;
    private Method deviceConnectStateMethod;
    private volatile WeakReference<Object> receiverRef;
    private volatile boolean installed;
    private volatile boolean hookReady;
    private volatile boolean watchConnected = true;
    private volatile boolean bluetoothEnabled = true;
    private ScheduledFuture<?> pendingFlush;
    private ScheduledFuture<?> pendingStep;

    HealthDisconnectNotificationBridge(Context app, HostSettings settings,
                                       ScheduledExecutorService worker,
                                       HealthCooperativeReadiness readiness,
                                       HealthSleepDndBridge sleepDndBridge) {
        this.app = app;
        this.settings = settings;
        this.worker = worker;
        this.readiness = readiness;
        this.sleepDndBridge = sleepDndBridge;
    }

    void install(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        resolveDeviceStateMethod(installer);
        hookReady = installReceiverHook(installer);
        boolean sendGuardReady = installSendFailureGuard(installer, symbols);
        registerStateReceiver();
        settings.onNotifyDisconnectStageChanged(enabled -> {
            if (!Boolean.TRUE.equals(enabled)) {
                try { worker.execute(this::clearStagedLocked); }
                catch (RuntimeException ignored) { queue.clear(); }
            }
        });
        settings.onNotifyBurstThrottleChanged(enabled -> {
            if (!Boolean.TRUE.equals(enabled)) {
                burstThrottle.clear();
            }
        });
        installed = true;
        try {
            worker.execute(this::refreshInitialStateLocked);
        } catch (RuntimeException ignored) {}
        ProbeLog.info((hookReady ? "HEALTH_NOTIFY_DISCONNECT_READY" : "HEALTH_NOTIFY_DISCONNECT_DEGRADED")
                + " source=HEALTH send_guard=" + sendGuardReady);
    }

    void onSettingsFallbackChanged(boolean enabled) {
        if (!enabled) {
            clearStagedLocked();
        }
    }

    void onBurstThrottleFallbackChanged(boolean enabled) {
        if (!enabled) {
            burstThrottle.clear();
        }
    }

    /**
     * Dispatches a synthetic watch notification (e.g. WatchLink phone battery alert) directly
     * through Health's live IntegratedServicesReceiver when captured, falling back to an explicit
     * package-scoped registered-only broadcast so it works even before the first regular notification.
     */
    void dispatchSyntheticWatchNotification(Intent pushIntent) {
        if (!installed || pushIntent == null) return;
        WeakReference<Object> ref = receiverRef;
        Object receiver = ref == null ? null : ref.get();
        Method onReceive = receiverOnReceive;
        if (receiver != null && onReceive != null) {
            try {
                onReceive.invoke(receiver, app, pushIntent);
                return;
            } catch (Throwable err) {
                ProbeLog.failure("HEALTH_BATTERY_ALERT_DEGRADED", err);
            }
        }
        try {
            pushIntent.setPackage(app.getPackageName());
            pushIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            app.sendBroadcast(pushIntent);
        } catch (RuntimeException err) {
            ProbeLog.failure("HEALTH_BATTERY_ALERT_DEGRADED", err);
        }
    }

    /** Called on worker thread when HealthWatchStatusCollector observes a connection change. */
    void onWatchConnectionObserved(boolean connected) {
        if (!installed) return;
        boolean wasConnected = watchConnected;
        watchConnected = connected;
        if (connected && !wasConnected) {
            scheduleFlushLocked();
        } else if (!connected) {
            cancelPendingFlushLocked();
        }
    }

    void close() {
        installed = false;
        settings.onNotifyDisconnectStageChanged(null);
        settings.onNotifyBurstThrottleChanged(null);
        clearStagedLocked();
        burstThrottle.clear();
        receiverRef = null;
        if (stateReceiver != null) {
            try { app.unregisterReceiver(stateReceiver); } catch (RuntimeException ignored) {}
            stateReceiver = null;
        }
    }

    private void resolveDeviceStateMethod(AlarmObserverInstaller installer) {
        if (installer == null) return;
        try {
            Class<?> deviceInfo = installer.target("com.huawei.health.devicemgr.business.entity.DeviceInfo");
            deviceConnectStateMethod = installer.method(deviceInfo, "getDeviceConnectState", int.class, false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            deviceConnectStateMethod = null;
        }
    }

    private boolean installReceiverHook(AlarmObserverInstaller installer) {
        if (installer == null) return false;
        try {
            Class<?> cls = installer.target(RECEIVER_CLASS);
            Method onReceive = installer.method(cls, "onReceive", void.class, false, Context.class, Intent.class);
            receiverOnReceive = onReceive;
            installer.intercept("health.notify.disconnect.recv", onReceive, chain -> {
                captureReceiver(chain.getThisObject());
                if (!installed) return chain.proceed();
                Object rawIntent = chain.getArg(1);
                if (!(rawIntent instanceof Intent intent)) return chain.proceed();
                String action = intent.getAction();
                if (ACTION_NOTIFICATION_DELETE.equals(action)) {
                    try {
                        String key = extractDeleteKey(intent);
                        if (key != null && !key.isEmpty()) {
                            queue.removeByKey(key);
                        }
                    } catch (RuntimeException ignored) {}
                    return chain.proceed();
                }
                if (!ACTION_NOTIFICATION_PUSH.equals(action)) {
                    return chain.proceed();
                }
                if (intent.getBooleanExtra(EXTRA_WATCHLINK_REPLAY, false)) {
                    return chain.proceed();
                }
                Bundle extras;
                try {
                    extras = intent.getExtras();
                } catch (RuntimeException ignored) {
                    return chain.proceed();
                }
                if (extras == null) {
                    return chain.proceed();
                }
                boolean stageEnabled = settings.isNotifyDisconnectStageEnabled();
                boolean eligibleForStage = stageEnabled && isEligibleBundle(extras);
                if (eligibleForStage && (!watchConnected || !bluetoothEnabled)) {
                    stageBundle(extras);
                    return null;
                }
                maybeApplyBurstThrottle(intent, extras);
                if (!eligibleForStage) {
                    return chain.proceed();
                }
                inflightBundle.set(extras);
                try {
                    return chain.proceed();
                } finally {
                    inflightBundle.remove();
                }
            });
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /**
     * Optional race-window guard on NotifySendData.b(DeviceCommand, int): if a notification enters
     * IntegratedServicesReceiver milliseconds after Bluetooth drops (before CONNECTION_STATE_CHANGED
     * flips watchConnected to false), NotifySendData returns false and we immediately stage it.
     */
    private boolean installSendFailureGuard(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        String className = symbols != null ? symbols.notifySendDataClass() : "";
        if (installer == null || className == null || className.isEmpty()) return false;
        try {
            Class<?> cls = installer.target(className);
            Class<?> devCmd = installer.target("com.huawei.datatype.DeviceCommand");
            Method sendMethod = uniqueSendNotificationMethod(cls, devCmd);
            if (sendMethod == null) return false;
            installer.observeAround("health.notify.disconnect.send", sendMethod,
                    chain -> null,
                    (chain, token, result) -> {
                        if (!installed || !Boolean.FALSE.equals(result)
                                || !settings.isNotifyDisconnectStageEnabled()) {
                            return;
                        }
                        Bundle inflight = inflightBundle.get();
                        if (inflight != null) {
                            inflightBundle.remove();
                            watchConnected = false;
                            stageBundle(inflight);
                        }
                    });
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static Method uniqueSendNotificationMethod(Class<?> cls, Class<?> deviceCommandCls) {
        Method found = null;
        for (Method m : cls.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (!Modifier.isPublic(mod) || Modifier.isStatic(mod) || m.isSynthetic()) continue;
            if (m.getReturnType() != boolean.class || m.getParameterCount() != 2) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p[0] == deviceCommandCls && p[1] == int.class) {
                if (found != null) return null;
                found = m;
            }
        }
        return found;
    }

    private void registerStateReceiver() {
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!installed || intent == null) return;
                    String action = intent.getAction();
                    if (ACTION_CONNECTION_CHANGED.equals(action)) {
                        int state = readConnectStateFromIntent(intent);
                        if (state == CONNECT_STATE_CONNECTED) {
                            watchConnected = true;
                            try { worker.execute(HealthDisconnectNotificationBridge.this::scheduleFlushLocked); }
                            catch (RuntimeException ignored) {}
                        } else if (state == CONNECT_STATE_DISCONNECTED || state == CONNECT_STATE_FAILED) {
                            watchConnected = false;
                            try { worker.execute(HealthDisconnectNotificationBridge.this::cancelPendingFlushLocked); }
                            catch (RuntimeException ignored) {}
                        } else {
                            try { worker.execute(HealthDisconnectNotificationBridge.this::refreshInitialStateLocked); }
                            catch (RuntimeException ignored) {}
                        }
                    } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                        int btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                        if (btState == BluetoothAdapter.STATE_OFF || btState == BluetoothAdapter.STATE_TURNING_OFF) {
                            bluetoothEnabled = false;
                            watchConnected = false;
                            try { worker.execute(HealthDisconnectNotificationBridge.this::cancelPendingFlushLocked); }
                            catch (RuntimeException ignored) {}
                        } else if (btState == BluetoothAdapter.STATE_ON) {
                            bluetoothEnabled = true;
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_CONNECTION_CHANGED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED);
            stateReceiver = r;
        } catch (RuntimeException ignored) {}
    }

    private int readConnectStateFromIntent(Intent intent) {
        Method stateMethod = deviceConnectStateMethod;
        if (stateMethod == null) return -1;
        try {
            @SuppressWarnings("deprecation")
            Object info = intent.getParcelableExtra("deviceinfo");
            if (info == null) return -1;
            Object raw = stateMethod.invoke(info);
            return raw instanceof Integer s ? s : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void refreshInitialStateLocked() {
        if (!installed) return;
        try {
            BluetoothManager bm = app.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            if (adapter != null) {
                bluetoothEnabled = adapter.isEnabled();
            }
        } catch (RuntimeException ignored) {}
        if (readiness != null) {
            boolean connected = readiness.readWatchDeviceInfo().connected();
            boolean wasConnected = watchConnected;
            watchConnected = connected;
            if (connected && !wasConnected) {
                scheduleFlushLocked();
            }
        }
    }

    private void captureReceiver(Object receiver) {
        if (receiver == null) return;
        WeakReference<Object> current = receiverRef;
        if (current == null || current.get() != receiver) {
            receiverRef = new WeakReference<>(receiver);
        }
    }

    private static boolean isEligibleBundle(Bundle extras) {
        String switchChangeType = extras.getString("notificationSwitchChangeType");
        String livePushType = extras.getString("LIVE_NOTIFICATION_PUSH_TYPE");
        boolean wechatCall = extras.getBoolean("data_extra_send_call_wechat", false);
        boolean noWechatCall = extras.getBoolean("data_extra_send_call_no_wechat", false);
        String pkg = extras.getString("packagename");
        if (pkg == null || pkg.isEmpty()) pkg = extras.getString("data_package_name");
        String category = extras.getString("data_category");
        return DisconnectNotificationQueue.isEligibleForStaging(
                switchChangeType, livePushType, wechatCall, noWechatCall, pkg, category);
    }

    private static String extractDeleteKey(Intent intent) {
        String key = intent.getStringExtra("data_extra_noty_key");
        if (key != null && !key.isEmpty()) return key;
        return intent.getStringExtra("notification_key");
    }

    private void maybeApplyBurstThrottle(Intent intent, Bundle extras) {
        if (!settings.isNotifyBurstThrottleEnabled()) return;
        try {
            String switchChangeType = extras.getString("notificationSwitchChangeType");
            String livePushType = extras.getString("LIVE_NOTIFICATION_PUSH_TYPE");
            if ((switchChangeType != null && !switchChangeType.isEmpty())
                    || (livePushType != null && !livePushType.isEmpty())) {
                return;
            }
            String pkg = extras.getString("packagename");
            if (pkg == null || pkg.isEmpty()) pkg = extras.getString("data_package_name");
            String category = extras.getString("data_category");
            boolean wechatCall = extras.getBoolean("data_extra_send_call_wechat", false);
            boolean noWechatCall = extras.getBoolean("data_extra_send_call_no_wechat", false);
            boolean isBatteryAlert = extras.getBoolean(HealthBatteryAlertBridge.EXTRA_WATCHLINK_BATTERY_ALERT, false);
            boolean alreadySilent = extras.getBoolean(HealthSleepDndBridge.EXTRA_WATCHLINK_SILENT_PUSH, false)
                    || extras.getBoolean(HealthSleepDndBridge.EXTRA_WATCHLINK_FORCE_SILENT_STAGED, false)
                    || extras.getInt(EXTRA_REMINDER_STATUS, 15) == 0
                    || (sleepDndBridge != null && sleepDndBridge.wouldBeSilentByMode(extras));
            boolean shouldSilence = burstThrottle.shouldSilenceAndRecord(
                    true,
                    settings.notifyBurstThrottleSec(),
                    pkg,
                    category,
                    wechatCall,
                    noWechatCall,
                    false,
                    isBatteryAlert,
                    alreadySilent,
                    SystemClock.elapsedRealtime());
            if (shouldSilence) {
                intent.putExtra(EXTRA_REMINDER_STATUS, 0);
                intent.putExtra(HealthSleepDndBridge.EXTRA_WATCHLINK_FORCE_SILENT_BURST, true);
            }
        } catch (RuntimeException ignored) {}
    }

    private void stageBundle(Bundle extras) {
        try {
            Bundle copy = new Bundle(extras);
            copy.remove(HealthSleepDndBridge.EXTRA_WATCHLINK_FORCE_SILENT_BURST);
            String notyKey = copy.getString("data_extra_noty_key");
            String altKey = copy.getString("notification_key");
            String pkg = copy.getString("packagename");
            if (pkg == null || pkg.isEmpty()) pkg = copy.getString("data_package_name");
            String title = copy.getString("title");
            String text = copy.getString("text");
            String dedupKey = DisconnectNotificationQueue.dedupKey(notyKey, altKey, pkg, title, text);
            boolean originallySilent = copy.getBoolean(HealthSleepDndBridge.EXTRA_WATCHLINK_SILENT_PUSH, false)
                    || copy.getInt(EXTRA_REMINDER_STATUS, 15) == 0;
            queue.stage(dedupKey, copy, SystemClock.elapsedRealtime(), originallySilent);
            ProbeLog.info("HEALTH_NOTIFY_DISCONNECT_STAGED source=HEALTH");
        } catch (RuntimeException ignored) {}
    }

    private synchronized void scheduleFlushLocked() {
        if (!installed || !settings.isNotifyDisconnectStageEnabled()) return;
        if (queue.isEmpty(SystemClock.elapsedRealtime())) return;
        cancelPendingFlushLocked();
        pendingFlush = worker.schedule(
                this::flushStagedLocked, RECONNECT_SETTLE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelPendingFlushLocked() {
        if (pendingFlush != null) {
            pendingFlush.cancel(false);
            pendingFlush = null;
        }
        if (pendingStep != null) {
            pendingStep.cancel(false);
            pendingStep = null;
        }
    }

    private synchronized void clearStagedLocked() {
        cancelPendingFlushLocked();
        queue.clear();
    }

    private void flushStagedLocked() {
        if (!installed || !settings.isNotifyDisconnectStageEnabled()) return;
        if (!watchConnected || !bluetoothEnabled) return;
        if (readiness != null && !readiness.readWatchDeviceInfo().connected()) {
            watchConnected = false;
            return;
        }
        WeakReference<Object> ref = receiverRef;
        Object receiver = ref == null ? null : ref.get();
        Method onReceive = receiverOnReceive;
        if (receiver == null || onReceive == null) return;

        List<DisconnectNotificationQueue.DeliveryItem<Bundle>> items =
                queue.drainForDelivery(SystemClock.elapsedRealtime());
        if (items.isEmpty()) return;
        deliverNextLocked(receiver, onReceive, items, 0, 0);
    }

    private void deliverNextLocked(
            Object receiver,
            Method onReceive,
            List<DisconnectNotificationQueue.DeliveryItem<Bundle>> items,
            int index,
            int deliveredCount) {
        if (!installed || !settings.isNotifyDisconnectStageEnabled() || index >= items.size()) {
            if (deliveredCount > 0) {
                ProbeLog.info("HEALTH_NOTIFY_DISCONNECT_FLUSHED source=HEALTH");
            }
            return;
        }
        if (!watchConnected || !bluetoothEnabled) {
            long now = SystemClock.elapsedRealtime();
            for (int i = index; i < items.size(); i++) {
                DisconnectNotificationQueue.DeliveryItem<Bundle> rem = items.get(i);
                queue.restageIfFresh(rem.dedupKey(), rem.payload(),
                        rem.stagedAtElapsedMs(), rem.originallySilent(), now);
            }
            if (deliveredCount > 0) {
                ProbeLog.info("HEALTH_NOTIFY_DISCONNECT_FLUSHED source=HEALTH");
            }
            return;
        }

        DisconnectNotificationQueue.DeliveryItem<Bundle> item = items.get(index);
        try {
            Bundle copy = new Bundle(item.payload());
            if (item.forceSilent()) {
                copy.putInt(EXTRA_REMINDER_STATUS, 0);
                copy.putBoolean(HealthSleepDndBridge.EXTRA_WATCHLINK_FORCE_SILENT_STAGED, true);
            }
            Intent replay = new Intent(ACTION_NOTIFICATION_PUSH);
            replay.putExtras(copy);
            replay.putExtra(EXTRA_WATCHLINK_REPLAY, true);
            onReceive.invoke(receiver, app, replay);
            deliveredCount++;
        } catch (Throwable err) {
            ProbeLog.failure("HEALTH_NOTIFY_DISCONNECT_DEGRADED", err);
        }

        int nextIndex = index + 1;
        int nextDelivered = deliveredCount;
        if (nextIndex < items.size()) {
            synchronized (this) {
                pendingStep = worker.schedule(
                        () -> deliverNextLocked(receiver, onReceive, items, nextIndex, nextDelivered),
                        DELIVERY_SPACING_MS,
                        TimeUnit.MILLISECONDS);
            }
        } else if (nextDelivered > 0) {
            ProbeLog.info("HEALTH_NOTIFY_DISCONNECT_FLUSHED source=HEALTH");
        }
    }
}
