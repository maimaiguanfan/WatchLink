package io.github.watchlink.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.mydevices.MyDevicesCardCodec;
import io.github.watchlink.mydevices.MyDevicesWire;
import io.github.watchlink.setup.WatchLinkPrefs;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Pure zero-WatchLink-process bridge inside ColorOS MyDevices (com.heytap.mydevices 17.25.15+).
 * Receives live watch snapshots directly from Huawei Health :PhoneService via MyDevicesWire,
 * serves virtual device_data JSON in-memory through DeviceAppProviderQuery (never waking WatchLink),
 * drives IDeviceAppObserver updates, and routes card taps to either Huawei Health or WatchLink
 * according to LSPosed RemotePreferences.
 */
@RequiresApi(34)
final class MyDevicesBridge {
    private static final String VIRTUAL_PKG = BuildConfig.APPLICATION_ID;
    private static final String CACHE_PREFS = BuildConfig.APPLICATION_ID + ".mydevices_card_cache_v1";

    private static final String DEVICE_APP_CONFIG_MGR = "com.heytap.mydevices.core.config.DeviceAppConfigManager";
    private static final String CONFIGURATION_MGR = "com.heytap.mydevices.core.config.ConfigurationManager";
    private static final String APP_AGENT_MGR = "com.heytap.mydevices.core.agent.AppAgentManager";
    private static final String DEVICE_APP_CLASS = "com.oplus.mydevices.domain.entities.config.DeviceApp";
    private static final String DEL_DEVICE_METHOD_CLASS = "com.oplus.mydevices.domain.entities.config.DelDeviceMethod";
    private static final String DEVICE_APP_CONFIG_CLASS = "com.oplus.mydevices.domain.entities.config.DeviceAppConfig";
    private static final String CARD_CONFIG_CLASS = "com.oplus.mydevices.domain.entities.config.CardConfig";
    private static final String DEVICE_APP_OBSERVER_CLASS = "com.oplus.mydevices.domain.ports.scanner.IDeviceAppObserver";
    private static final String HOME_CARD_VIEW_CLASS = "com.oplus.mydevices.deviceui.devicecard.view.homecard.HomeCardView";

    private final Context app;
    private final HostSettings settings;
    private final String[] sources;
    private final SharedPreferences cachePrefs;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "WatchLink-mydevices");
        t.setDaemon(true);
        return t;
    });

    private BroadcastReceiver syncReceiver;
    private volatile boolean installed;
    private volatile MyDevicesCardCodec.CardSnapshot latestSnapshot;
    private volatile Object capturedObserver;
    private volatile boolean observerAddedDevice;
    private volatile String lastObserverDeviceId = "";
    private volatile Object virtualDeviceApp;
    private volatile Object virtualDeviceAppConfig;
    private Method observerOnAdd;
    private Method observerOnUpdated;
    private Method observerOnRemoved;

    private float downX;
    private float downY;
    private boolean trackingTap;
    private long lastTapLaunchMs;

    MyDevicesBridge(Context app, HostSettings settings, String[] sources) {
        this.app = app;
        this.settings = settings;
        this.sources = sources;
        this.cachePrefs = app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        this.latestSnapshot = readCachedSnapshot();
    }

    void install(AlarmObserverInstaller installer) {
        MyDevicesSymbolResolver.Symbols symbols = MyDevicesSymbolResolver.resolve(
                installer, new ApkSymbolCache(app, "mydevices"), sources);
        ProbeLog.info("MYDEVICES_SYMBOLS_RESOLVED source=MY_DEVICES resolver="
                + (symbols != null && symbols.ready() ? symbols.source() : "UNAVAILABLE"));

        boolean supportHook = installSupportHooks(installer);
        boolean configHook = installConfigHooks(installer);
        boolean observerHook = installObserverHook(installer);
        boolean queryHook = symbols != null && symbols.ready() && installProviderQueryHooks(installer, symbols);
        installCardTapHook(installer);

        registerSyncReceiver();
        settings.onMyDevicesCardChanged(enabled -> worker.execute(() -> {
            if (!installed) return;
            if (Boolean.TRUE.equals(enabled)) {
                MyDevicesWire.requestPullFromHealth(app);
                pushObserverStateLocked();
            } else {
                removeFromObserverLocked();
            }
        }));

        installed = true;
        boolean coreReady = supportHook && configHook && observerHook && queryHook;
        ProbeLog.info((coreReady ? "MYDEVICES_CARD_READY" : "MYDEVICES_CARD_DEGRADED") + " source=MY_DEVICES");

        if (settings.isMyDevicesCardEnabled()) {
            worker.schedule(() -> {
                if (installed && settings.isMyDevicesCardEnabled()) {
                    try { MyDevicesWire.requestPullFromHealth(app); } catch (RuntimeException ignored) {}
                    pushObserverStateLocked();
                }
            }, 800L, TimeUnit.MILLISECONDS);
        }
    }

    void close() {
        installed = false;
        settings.onMyDevicesCardChanged(null);
        capturedObserver = null;
        if (syncReceiver != null) {
            try { app.unregisterReceiver(syncReceiver); } catch (RuntimeException ignored) {}
            syncReceiver = null;
        }
        worker.shutdownNow();
    }

    private boolean installSupportHooks(AlarmObserverInstaller installer) {
        try {
            Class<?> mgrCls = installer.target(DEVICE_APP_CONFIG_MGR);
            Class<?> deviceAppCls = installer.target(DEVICE_APP_CLASS);
            Class<?> delMethodCls = installer.target(DEL_DEVICE_METHOD_CLASS);
            Object delNone = Enum.valueOf(delMethodCls.asSubclass(Enum.class), "NONE");
            Constructor<?> ctor = deviceAppCls.getDeclaredConstructor(
                    String.class, String.class, long.class, int.class,
                    boolean.class, boolean.class, Boolean.class, delMethodCls);
            ctor.setAccessible(true);
            virtualDeviceApp = ctor.newInstance(
                    "WatchLink", VIRTUAL_PKG, 1L, 0, false, false, Boolean.FALSE, delNone);

            Method getPkg = deviceAppCls.getMethod("getPackageName");
            Method getSupportApps = mgrCls.getDeclaredMethod("getSupportDeviceApplications");
            installer.intercept("mydevices.support.apps", getSupportApps, chain -> {
                Object result = chain.proceed();
                if (!installed || !settings.isMyDevicesCardEnabled() || !(result instanceof List<?> list)) {
                    return result;
                }
                for (Object item : list) {
                    if (item != null && VIRTUAL_PKG.equals(safeInvoke(getPkg, item))) {
                        return result;
                    }
                }
                List<Object> copy = new ArrayList<>(list);
                copy.add(virtualDeviceApp);
                return copy;
            });

            try {
                Method isSupport = mgrCls.getDeclaredMethod("isSupport", String.class);
                installer.intercept("mydevices.is.support", isSupport, chain -> {
                    if (installed && settings.isMyDevicesCardEnabled()
                            && VIRTUAL_PKG.equals(chain.getArg(0))) {
                        return Boolean.TRUE;
                    }
                    return chain.proceed();
                });
            } catch (NoSuchMethodException ignored) {}
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private boolean installConfigHooks(AlarmObserverInstaller installer) {
        try {
            Class<?> cfgMgrCls = installer.target(CONFIGURATION_MGR);
            Class<?> appCfgCls = installer.target(DEVICE_APP_CONFIG_CLASS);
            Class<?> cardCfgCls = installer.target(CARD_CONFIG_CLASS);
            Constructor<?> cardCtor = cardCfgCls.getDeclaredConstructor(
                    String.class, long.class, int.class, int.class);
            cardCtor.setAccessible(true);
            Object cardCfg = cardCtor.newInstance(VIRTUAL_PKG, 1L, 0, 1);

            Constructor<?> appCfgCtor = appCfgCls.getDeclaredConstructor(
                    String.class, long.class, String.class, cardCfgCls, int.class, String.class, String.class);
            appCfgCtor.setAccessible(true);
            virtualDeviceAppConfig = appCfgCtor.newInstance(
                    VIRTUAL_PKG, (long) BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME,
                    cardCfg, -1, MyDevicesCardCodec.VIRTUAL_AUTHORITY, MyDevicesCardCodec.VIRTUAL_TABLE);

            Method getPkg = appCfgCls.getMethod("getPackageName");
            Method getConfigs = cfgMgrCls.getDeclaredMethod("getAppConfigurations");
            installer.intercept("mydevices.app.configs", getConfigs, chain -> {
                Object result = chain.proceed();
                if (!installed || !settings.isMyDevicesCardEnabled() || !(result instanceof List<?> list)) {
                    return result;
                }
                for (Object item : list) {
                    if (item != null && VIRTUAL_PKG.equals(safeInvoke(getPkg, item))) {
                        return result;
                    }
                }
                List<Object> copy = new ArrayList<>(list);
                copy.add(virtualDeviceAppConfig);
                return copy;
            });
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private boolean installObserverHook(AlarmObserverInstaller installer) {
        try {
            Class<?> agentCls = installer.target(APP_AGENT_MGR);
            Class<?> obsCls = installer.target(DEVICE_APP_OBSERVER_CLASS);
            observerOnAdd = obsCls.getMethod("onDeviceAdd", String.class);
            observerOnUpdated = obsCls.getMethod("onDeviceUpdated", String.class);
            observerOnRemoved = obsCls.getMethod("onDeviceRemoved", String.class, boolean.class, String.class);
            Method addObs = agentCls.getDeclaredMethod("addDeviceObserver", String.class, obsCls);
            installer.observeAround("mydevices.observer.add", addObs, chain -> {
                if (installed && VIRTUAL_PKG.equals(chain.getArg(0)) && chain.getArg(1) != null) {
                    capturedObserver = chain.getArg(1);
                    observerAddedDevice = false;
                    worker.execute(() -> {
                        if (installed && settings.isMyDevicesCardEnabled()) {
                            try { MyDevicesWire.requestPullFromHealth(app); } catch (RuntimeException ignored) {}
                            pushObserverStateLocked();
                        }
                    });
                }
                return null;
            }, (chain, token, result) -> {});
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private boolean installProviderQueryHooks(AlarmObserverInstaller installer,
                                              MyDevicesSymbolResolver.Symbols symbols) {
        try {
            Class<?> queryCls = installer.target(symbols.providerQueryClass());
            Method queryAll = queryCls.getDeclaredMethod("queryAllDevices", String.class);
            Method queryById = queryCls.getDeclaredMethod("queryDeviceById", String.class, String.class);
            installer.intercept("mydevices.provider.all", queryAll, chain -> {
                Object rawUri = chain.getArg(0);
                if (installed && isVirtualUri(rawUri)) {
                    if (!settings.isMyDevicesCardEnabled()) return Collections.emptyList();
                    String json = currentDeviceJson();
                    return json.isEmpty() ? Collections.emptyList() : Collections.singletonList(json);
                }
                return chain.proceed();
            });
            installer.intercept("mydevices.provider.byid", queryById, chain -> {
                Object rawUri = chain.getArg(0);
                if (installed && isVirtualUri(rawUri)) {
                    if (!settings.isMyDevicesCardEnabled()) return "";
                    MyDevicesCardCodec.CardSnapshot snap = latestSnapshot;
                    Object requestedId = chain.getArg(1);
                    if (snap != null && snap.valid()
                            && (requestedId == null || snap.deviceId().equals(requestedId))) {
                        return currentDeviceJson();
                    }
                    return "";
                }
                return chain.proceed();
            });
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private void installCardTapHook(AlarmObserverInstaller installer) {
        try {
            Class<?> homeCardCls = installer.target(HOME_CARD_VIEW_CLASS);
            Method dispatchTouch = homeCardCls.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            int touchSlop = ViewConfiguration.get(app).getScaledTouchSlop();
            installer.intercept("mydevices.card.tap", dispatchTouch, chain -> {
                Object view = chain.getThisObject();
                Object rawEv = chain.getArg(0);
                if (installed && settings.isMyDevicesCardEnabled()
                        && view != null && rawEv instanceof MotionEvent ev
                        && isWatchLinkCardView(view)) {
                    int action = ev.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        downX = ev.getX();
                        downY = ev.getY();
                        trackingTap = true;
                    } else if (action == MotionEvent.ACTION_MOVE && trackingTap) {
                        if (Math.hypot(ev.getX() - downX, ev.getY() - downY) > touchSlop) {
                            trackingTap = false;
                        }
                    } else if (action == MotionEvent.ACTION_CANCEL) {
                        trackingTap = false;
                    } else if (action == MotionEvent.ACTION_UP && trackingTap) {
                        trackingTap = false;
                        long now = System.currentTimeMillis();
                        if (now - lastTapLaunchMs > 600L) {
                            lastTapLaunchMs = now;
                            launchConfiguredTapTarget();
                            return Boolean.TRUE;
                        }
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}
    }

    private void launchConfiguredTapTarget() {
        String target = settings.myDevicesTapTarget();
        String pkg = WatchLinkPrefs.TAP_TARGET_WATCHLINK.equals(target)
                ? BuildConfig.APPLICATION_ID
                : TargetProfile.HEALTH.packageName;
        try {
            Intent launch = app.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(launch);
                ProbeLog.info("MYDEVICES_CARD_TAP source=MY_DEVICES");
            }
        } catch (RuntimeException ignored) {}
    }

    private boolean isWatchLinkCardView(Object view) {
        MyDevicesCardCodec.CardSnapshot snap = latestSnapshot;
        String expectedId = snap != null ? snap.deviceId() : "";
        for (Class<?> c = view.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(view);
                    if (v == null) continue;
                    if (v instanceof String s) {
                        if (s.startsWith(MyDevicesCardCodec.DEVICE_ID_PREFIX)
                                || (!expectedId.isEmpty() && expectedId.equals(s))) {
                            return true;
                        }
                    } else if (v.getClass().getName().contains("QuickCardDeviceData")
                            || v.getClass().getName().contains("DeviceInfo")) {
                        String text = String.valueOf(v);
                        if (text.contains(MyDevicesCardCodec.DEVICE_ID_PREFIX)
                                || text.contains(VIRTUAL_PKG)) {
                            return true;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private void registerSyncReceiver() {
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!installed || intent == null || !MyDevicesWire.ACTION_SYNC.equals(intent.getAction())) return;
                    int uid = getSentFromUid();
                    String pkg = getSentFromPackage();
                    if (uid < 0 || !TargetProfile.HEALTH.packageName.equals(pkg)
                            || intent.getComponent() != null
                            || !TargetProfile.MY_DEVICES.packageName.equals(intent.getPackage())
                            || (intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
                        return;
                    }
                    PendingResult pending = goAsync();
                    try {
                        worker.execute(() -> {
                            try {
                                if (!installed || !SenderVerifier.verifyReminderSender(app, TargetProfile.HEALTH, uid, pkg)) {
                                    return;
                                }
                                MyDevicesCardCodec.CardSnapshot snap = MyDevicesWire.fromSyncIntent(intent);
                                if (snap != null) {
                                    latestSnapshot = snap;
                                    writeCachedSnapshot(snap);
                                    if (settings.isMyDevicesCardEnabled()) {
                                        pushObserverStateLocked();
                                        ProbeLog.info("MYDEVICES_CARD_UPDATED source=MY_DEVICES");
                                    }
                                }
                            } catch (RuntimeException ignored) {
                            } finally {
                                pending.finish();
                            }
                        });
                    } catch (RuntimeException shuttingDown) {
                        pending.finish();
                    }
                }
            };
            app.registerReceiver(r, new IntentFilter(MyDevicesWire.ACTION_SYNC), Context.RECEIVER_EXPORTED);
            syncReceiver = r;
        } catch (RuntimeException ignored) {}
    }

    private void pushObserverStateLocked() {
        Object obs = capturedObserver;
        MyDevicesCardCodec.CardSnapshot snap = latestSnapshot;
        if (obs == null || snap == null || !snap.valid()) return;
        try {
            if (!observerAddedDevice || !snap.deviceId().equals(lastObserverDeviceId)) {
                if (observerOnAdd != null) {
                    observerOnAdd.invoke(obs, snap.deviceId());
                    observerAddedDevice = true;
                    lastObserverDeviceId = snap.deviceId();
                }
            } else if (observerOnUpdated != null) {
                observerOnUpdated.invoke(obs, snap.deviceId());
            }
        } catch (Throwable ignored) {}
    }

    private void removeFromObserverLocked() {
        Object obs = capturedObserver;
        String id = lastObserverDeviceId;
        if (obs == null || id == null || id.isEmpty() || observerOnRemoved == null) return;
        try {
            observerOnRemoved.invoke(obs, id, false, VIRTUAL_PKG);
            observerAddedDevice = false;
        } catch (Throwable ignored) {}
    }

    private String currentDeviceJson() {
        MyDevicesCardCodec.CardSnapshot snap = latestSnapshot;
        if (snap == null || !snap.valid()) return "";
        return MyDevicesCardCodec.toDeviceDataJson(snap, VIRTUAL_PKG);
    }

    private static boolean isVirtualUri(Object rawUri) {
        if (!(rawUri instanceof String uri)) return false;
        return uri.contains(MyDevicesCardCodec.VIRTUAL_AUTHORITY);
    }

    private static Object safeInvoke(Method m, Object target) {
        try { return m.invoke(target); } catch (Throwable ignored) { return null; }
    }

    private MyDevicesCardCodec.CardSnapshot readCachedSnapshot() {
        try {
            String id = cachePrefs.getString("deviceId", "");
            String name = cachePrefs.getString("watchName", "");
            if (id == null || id.isEmpty() || name == null || name.isEmpty()) return null;
            MyDevicesCardCodec.CardSnapshot snap = new MyDevicesCardCodec.CardSnapshot(
                    id, name, cachePrefs.getString("mac", ""),
                    false, cachePrefs.getInt("battery", -1), false,
                    cachePrefs.getLong("updatedAt", 0L));
            return snap.valid() ? snap : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void writeCachedSnapshot(MyDevicesCardCodec.CardSnapshot snap) {
        try {
            cachePrefs.edit()
                    .putString("deviceId", snap.deviceId())
                    .putString("watchName", snap.watchName())
                    .putString("mac", snap.macAddress())
                    .putInt("battery", snap.batteryPercent())
                    .putLong("updatedAt", snap.updatedAtMs())
                    .apply();
        } catch (RuntimeException ignored) {}
    }
}
