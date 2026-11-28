package io.github.watchlink.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.libxposed.api.XposedInterface;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.mydevices.MyDevicesCardCodec;
import io.github.watchlink.mydevices.MyDevicesWire;
import io.github.watchlink.setup.WatchLinkPrefs;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Pure zero-WatchLink-process bridge inside ColorOS MyDevices main process (com.heytap.mydevices 17.25.15+).
 * Installs core hooks synchronously during onPackageReady BEFORE DeviceApplication.attachBaseContext()/onCreate()
 * runs Koin initialization, so DeviceAppConfigManager, ConfigurationManager, AppAgentManager,
 * DeviceAppProviderQuery, and DeviceIconConverter see WatchLink's virtual watch device and official
 * Huawei Health watch PNG immediately on cold start without racing.
 */
@RequiresApi(34)
final class MyDevicesBridge {
    private static final String VIRTUAL_PKG = BuildConfig.APPLICATION_ID;
    private static final String CACHE_PREFS = BuildConfig.APPLICATION_ID + ".mydevices_card_cache_v1";

    private static final String DEVICE_APP_CONFIG_MGR = "com.heytap.mydevices.core.config.DeviceAppConfigManager";
    private static final String CONFIGURATION_MGR = "com.heytap.mydevices.core.config.ConfigurationManager";
    private static final String APP_AGENT_MGR = "com.heytap.mydevices.core.agent.AppAgentManager";
    private static final String DEVICE_APP_SCANNER = "com.heytap.mydevices.core.device.DeviceAppScannerImpl";
    private static final String DEVICE_APP_CLASS = "com.oplus.mydevices.domain.entities.config.DeviceApp";
    private static final String DEL_DEVICE_METHOD_CLASS = "com.oplus.mydevices.domain.entities.config.DelDeviceMethod";
    private static final String DEVICE_APP_CONFIG_CLASS = "com.oplus.mydevices.domain.entities.config.DeviceAppConfig";
    private static final String CARD_CONFIG_CLASS = "com.oplus.mydevices.domain.entities.config.CardConfig";
    private static final String DEVICE_APP_OBSERVER_CLASS = "com.oplus.mydevices.domain.ports.scanner.IDeviceAppObserver";
    private static final String DEVICE_INFO_KT_CLASS = "com.oplus.mydevices.domain.entities.device.DeviceInfoKt";
    private static final String DEVICE_ICON_CONVERTER_CLASS = "com.heytap.mydevices.core.converter.product.DeviceIconConverter";
    private static final String IDEVICE_CLASS = "com.oplus.mydevices.domain.ports.device.IDevice";
    private static final String ICON_CACHE_SUBDIR = "device_image";
    private static final String FILE_PROVIDER_URI_PREFIX = "content://com.oplus.mydevices.fileprovider/deviceIcon/";
    private static final String ICON_FILE_PREFIX = "watchlink_watch_icon_";

    private final XposedInterface module;
    private final ClassLoader loader;
    private final SharedPreferences earlyRemotePrefs;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "WatchLink-mydevices");
        t.setDaemon(true);
        return t;
    });

    private volatile Context app;
    private volatile HostSettings settings;
    private volatile SharedPreferences cachePrefs;
    private BroadcastReceiver syncReceiver;
    private volatile boolean active = true;
    private volatile boolean supportHookReady;
    private volatile boolean configHookReady;
    private volatile boolean observerHookReady;
    private volatile boolean queryHookReady;

    private volatile MyDevicesCardCodec.CardSnapshot latestSnapshot;
    private volatile File customIconFile;
    private volatile String customIconHash = "";
    private volatile Object capturedObserver;
    private volatile Object capturedConfigManager;
    private volatile Field configManagerMapField;
    private volatile boolean observerAddedDevice;
    private volatile String lastObserverDeviceId = "";
    private volatile Object virtualDeviceApp;
    private volatile Object virtualDeviceAppConfig;
    private volatile long lastQueryLogElapsedMs;
    private volatile long lastIconPullElapsedMs;
    private Method observerOnAdd;
    private Method observerOnUpdated;
    private Method observerOnRemoved;

    private MyDevicesBridge(XposedInterface module, ClassLoader loader, SharedPreferences earlyRemotePrefs) {
        this.module = module;
        this.loader = loader;
        this.earlyRemotePrefs = earlyRemotePrefs;
    }

    /**
     * Installs all core MyDevices hooks synchronously during onPackageReady before
     * DeviceApplication.attachBaseContext()/onCreate() initializes Koin & ConfigurationManager.
     */
    static MyDevicesBridge installEarlyHook(XposedInterface module, ClassLoader loader, SharedPreferences remotePrefs) {
        MyDevicesBridge bridge = new MyDevicesBridge(module, loader, remotePrefs);
        bridge.installEarlyHooksSync();
        return bridge;
    }

    boolean isEarlyHookReady() {
        return supportHookReady && configHookReady && observerHookReady;
    }

    private void installEarlyHooksSync() {
        supportHookReady = installSupportHooks();
        configHookReady = installConfigHooks();
        observerHookReady = installObserverHooks();
        Class<?> queryCls = resolveProviderQueryByReflection();
        if (queryCls != null) {
            queryHookReady = installProviderQueryHooks(queryCls);
        }
        installPluginLookupHook();
        installIconConverterHooks();
        ProbeLog.info("MYDEVICES_EARLY_HOOK source=MY_DEVICES result="
                + (supportHookReady && configHookReady && observerHookReady && queryHookReady ? "APPLIED" : "UNAVAILABLE"));
    }

    void attachContext(Context hostContext, HostSettings hostSettings,
                       AlarmObserverInstaller installer, String[] sources) {
        if (!active) return;
        this.app = hostContext.getApplicationContext();
        this.settings = hostSettings;
        this.cachePrefs = this.app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        restoreCachedIconFile();
        if (latestSnapshot == null) {
            latestSnapshot = readCachedSnapshot();
        }

        if (!queryHookReady && installer != null && sources != null) {
            MyDevicesSymbolResolver.Symbols symbols = MyDevicesSymbolResolver.resolve(
                    installer, new ApkSymbolCache(this.app, "mydevices"), sources);
            ProbeLog.info("MYDEVICES_SYMBOLS_RESOLVED source=MY_DEVICES resolver="
                    + (symbols != null && symbols.ready() ? symbols.source() : "UNAVAILABLE"));
            if (symbols != null && symbols.ready()) {
                try {
                    queryHookReady = installProviderQueryHooks(loadClass(symbols.providerQueryClass()));
                } catch (Throwable ignored) {}
            }
        } else {
            ProbeLog.info("MYDEVICES_SYMBOLS_RESOLVED source=MY_DEVICES resolver=FAST_PATH");
        }

        registerSyncReceiver();
        hostSettings.onMyDevicesCardChanged(enabled -> worker.execute(() -> {
            if (!active) return;
            syncConfigMap(Boolean.TRUE.equals(enabled));
            if (Boolean.TRUE.equals(enabled)) {
                requestPullSafe();
                pushObserverStateLocked();
            } else {
                removeFromObserverLocked();
            }
        }));

        boolean ready = supportHookReady && configHookReady && observerHookReady && queryHookReady;
        ProbeLog.info((ready ? "MYDEVICES_CARD_READY" : "MYDEVICES_CARD_DEGRADED") + " source=MY_DEVICES");

        if (isCardEnabled()) {
            syncConfigMap(true);
            worker.schedule(() -> {
                if (active && isCardEnabled()) {
                    requestPullSafe();
                    pushObserverStateLocked();
                }
            }, 600L, TimeUnit.MILLISECONDS);
        }
    }

    void close() {
        active = false;
        HostSettings s = settings;
        if (s != null) s.onMyDevicesCardChanged(null);
        capturedObserver = null;
        capturedConfigManager = null;
        Context ctx = app;
        if (syncReceiver != null && ctx != null) {
            try { ctx.unregisterReceiver(syncReceiver); } catch (RuntimeException ignored) {}
            syncReceiver = null;
        }
        synchronized (handles) {
            for (XposedInterface.HookHandle h : handles) {
                try { h.unhook(); } catch (RuntimeException ignored) {}
            }
            handles.clear();
        }
        worker.shutdownNow();
    }

    private boolean isCardEnabled() {
        HostSettings s = settings;
        if (s != null) return s.isMyDevicesCardEnabled();
        if (earlyRemotePrefs != null) {
            try {
                return earlyRemotePrefs.getBoolean(WatchLinkPrefs.KEY_MYDEVICES_CARD_ENABLED, false);
            } catch (RuntimeException ignored) {}
        }
        return false;
    }

    private boolean installSupportHooks() {
        try {
            Class<?> mgrCls = loadClass(DEVICE_APP_CONFIG_MGR);
            Class<?> deviceAppCls = loadClass(DEVICE_APP_CLASS);
            Class<?> delMethodCls = loadClass(DEL_DEVICE_METHOD_CLASS);
            Object delNone = Enum.valueOf(delMethodCls.asSubclass(Enum.class), "NONE");
            Constructor<?> ctor = deviceAppCls.getDeclaredConstructor(
                    String.class, String.class, long.class, int.class,
                    boolean.class, boolean.class, Boolean.class, delMethodCls);
            ctor.setAccessible(true);
            virtualDeviceApp = ctor.newInstance(
                    "WatchLink", VIRTUAL_PKG, 1L, 0, false, false, Boolean.FALSE, delNone);

            Method getPkg = deviceAppCls.getMethod("getPackageName");
            Method cacheListMethod = findCacheListMethod(mgrCls);
            boolean hookedCache = false;
            if (cacheListMethod != null) {
                hook("mydevices.support.cache", cacheListMethod, chain -> {
                    Object result = chain.proceed();
                    if (!active || !isCardEnabled() || !(result instanceof List<?> list)) {
                        return result;
                    }
                    for (Object item : list) {
                        if (item != null && VIRTUAL_PKG.equals(safeInvoke(getPkg, item))) {
                            return result;
                        }
                    }
                    if (result instanceof CopyOnWriteArrayList<?>) {
                        CopyOnWriteArrayList<Object> copy = new CopyOnWriteArrayList<>(list);
                        copy.add(virtualDeviceApp);
                        return copy;
                    }
                    List<Object> copy = new ArrayList<>(list);
                    copy.add(virtualDeviceApp);
                    return copy;
                });
                hookedCache = true;
            }

            try {
                Method isSupport = mgrCls.getDeclaredMethod("isSupport", String.class);
                hook("mydevices.is.support", isSupport, chain -> {
                    if (active && isCardEnabled() && VIRTUAL_PKG.equals(chain.getArg(0))) {
                        return Boolean.TRUE;
                    }
                    return chain.proceed();
                });
                hookedCache = true;
            } catch (NoSuchMethodException ignored) {}

            try {
                Method getDeviceApp = mgrCls.getDeclaredMethod("getDeviceApp", String.class);
                hook("mydevices.get.device.app", getDeviceApp, chain -> {
                    if (active && isCardEnabled() && VIRTUAL_PKG.equals(chain.getArg(0))) {
                        return virtualDeviceApp;
                    }
                    return chain.proceed();
                });
            } catch (NoSuchMethodException ignored) {}

            return hookedCache;
        } catch (Throwable unavailable) {
            ProbeLog.failure("MYDEVICES_CARD_DEGRADED", unavailable);
            return false;
        }
    }

    private boolean installConfigHooks() {
        try {
            Class<?> cfgMgrCls = loadClass(CONFIGURATION_MGR);
            Class<?> appCfgCls = loadClass(DEVICE_APP_CONFIG_CLASS);
            Class<?> cardCfgCls = loadClass(CARD_CONFIG_CLASS);
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

            configManagerMapField = findMapField(cfgMgrCls);

            Method getPkg = appCfgCls.getMethod("getPackageName");
            Method getConfigs = cfgMgrCls.getDeclaredMethod("getAppConfigurations");
            hook("mydevices.app.configs", getConfigs, chain -> {
                capturedConfigManager = chain.getThisObject();
                Object result = chain.proceed();
                if (!active || !isCardEnabled() || !(result instanceof List<?> list)) {
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

            try {
                Method getOrParse = cfgMgrCls.getDeclaredMethod("getOrParseCardXmlConfig", String.class);
                hook("mydevices.app.config.pkg", getOrParse, chain -> {
                    capturedConfigManager = chain.getThisObject();
                    if (active && isCardEnabled() && VIRTUAL_PKG.equals(chain.getArg(0))) {
                        return virtualDeviceAppConfig;
                    }
                    return chain.proceed();
                });
            } catch (NoSuchMethodException ignored) {}

            try {
                Method queryAppConfig = cfgMgrCls.getDeclaredMethod("queryAppConfig");
                hook("mydevices.app.config.query", queryAppConfig, chain -> {
                    Object mgr = chain.getThisObject();
                    capturedConfigManager = mgr;
                    if (active && isCardEnabled()) {
                        putInConfigMap(mgr, true);
                    }
                    Object res = chain.proceed();
                    if (active && isCardEnabled()) {
                        putInConfigMap(mgr, true);
                    }
                    return res;
                });
            } catch (NoSuchMethodException ignored) {}

            return true;
        } catch (Throwable unavailable) {
            ProbeLog.failure("MYDEVICES_CARD_DEGRADED", unavailable);
            return false;
        }
    }

    private boolean installObserverHooks() {
        try {
            Class<?> agentCls = loadClass(APP_AGENT_MGR);
            Class<?> obsCls = loadClass(DEVICE_APP_OBSERVER_CLASS);
            observerOnAdd = obsCls.getMethod("onDeviceAdd", String.class);
            observerOnUpdated = obsCls.getMethod("onDeviceUpdated", String.class);
            observerOnRemoved = obsCls.getMethod("onDeviceRemoved", String.class, boolean.class, String.class);

            Method addObs = agentCls.getDeclaredMethod("addDeviceObserver", String.class, obsCls);
            hook("mydevices.observer.add", addObs, chain -> {
                Object res = chain.proceed();
                if (active && VIRTUAL_PKG.equals(chain.getArg(0)) && chain.getArg(1) != null) {
                    capturedObserver = chain.getArg(1);
                    observerAddedDevice = false;
                    ProbeLog.info("MYDEVICES_OBSERVER_CAPTURED source=MY_DEVICES");
                    worker.execute(() -> {
                        if (active && isCardEnabled()) {
                            requestPullSafe();
                            pushObserverStateLocked();
                        }
                    });
                }
                return res;
            });

            try {
                Method removeObs = agentCls.getDeclaredMethod("removeDeviceObserver", String.class);
                hook("mydevices.observer.remove", removeObs, chain -> {
                    if (active && VIRTUAL_PKG.equals(chain.getArg(0))) {
                        capturedObserver = null;
                        observerAddedDevice = false;
                    }
                    return chain.proceed();
                });
            } catch (NoSuchMethodException ignored) {}
            return true;
        } catch (Throwable unavailable) {
            ProbeLog.failure("MYDEVICES_CARD_DEGRADED", unavailable);
            return false;
        }
    }

    private Class<?> resolveProviderQueryByReflection() {
        try {
            Class<?> scannerCls = loadClass(DEVICE_APP_SCANNER);
            for (Method m : scannerCls.getDeclaredMethods()) {
                Class<?> ret = m.getReturnType();
                if (ret == null || ret.isPrimitive() || ret == Object.class || ret.getName().startsWith("java.")) {
                    continue;
                }
                try {
                    Method qa = ret.getDeclaredMethod("queryAllDevices", String.class);
                    Method qb = ret.getDeclaredMethod("queryDeviceById", String.class, String.class);
                    if (List.class.isAssignableFrom(qa.getReturnType()) && qb.getReturnType() == String.class) {
                        return ret;
                    }
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean installProviderQueryHooks(Class<?> queryCls) {
        try {
            Method queryAll = queryCls.getDeclaredMethod("queryAllDevices", String.class);
            Method queryById = queryCls.getDeclaredMethod("queryDeviceById", String.class, String.class);
            hook("mydevices.provider.all", queryAll, chain -> {
                Object rawUri = chain.getArg(0);
                if (active && isVirtualUri(rawUri)) {
                    if (!isCardEnabled()) return Collections.emptyList();
                    String json = currentDeviceJson();
                    if (json.isEmpty()) {
                        worker.execute(this::requestPullSafe);
                        return Collections.emptyList();
                    }
                    logQueryServedThrottled();
                    return Collections.singletonList(json);
                }
                return chain.proceed();
            });
            hook("mydevices.provider.byid", queryById, chain -> {
                Object rawUri = chain.getArg(0);
                if (active && isVirtualUri(rawUri)) {
                    if (!isCardEnabled()) return "";
                    MyDevicesCardCodec.CardSnapshot snap = ensureSnapshot();
                    Object requestedId = chain.getArg(1);
                    if (snap != null && snap.valid()
                            && (requestedId == null || snap.deviceId().equals(requestedId))) {
                        logQueryServedThrottled();
                        return currentDeviceJson();
                    }
                    worker.execute(this::requestPullSafe);
                    return "";
                }
                return chain.proceed();
            });
            return true;
        } catch (Throwable unavailable) {
            ProbeLog.failure("MYDEVICES_CARD_DEGRADED", unavailable);
            return false;
        }
    }

    private void installPluginLookupHook() {
        try {
            Class<?> ktCls = loadClass(DEVICE_INFO_KT_CLASS);
            Class<?> iDeviceCls = loadClass(IDEVICE_CLASS);
            Method getPkg = iDeviceCls.getMethod("getPackageName");
            Method getId = iDeviceCls.getMethod("getId");
            Method lookup = ktCls.getDeclaredMethod("getPluginLookupPackage", iDeviceCls);
            hook("mydevices.icon.plugin.pkg", lookup, chain -> {
                if (active && isCardEnabled() && isVirtualDevice(chain.getArg(0), getPkg, getId)) {
                    return TargetProfile.MY_DEVICES.packageName;
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}
    }

    private void installIconConverterHooks() {
        try {
            Class<?> converterCls = loadClass(DEVICE_ICON_CONVERTER_CLASS);
            Class<?> iDeviceCls = loadClass(IDEVICE_CLASS);
            Method getPkg = iDeviceCls.getMethod("getPackageName");
            Method getId = iDeviceCls.getMethod("getId");

            try {
                Method getIconFile = converterCls.getDeclaredMethod("getIconFileByRawUri", iDeviceCls);
                hook("mydevices.icon.file", getIconFile, chain -> {
                    File icon = currentCustomIconFile();
                    if (active && isCardEnabled() && icon != null && isVirtualDevice(chain.getArg(0), getPkg, getId)) {
                        return icon;
                    }
                    return chain.proceed();
                });
            } catch (NoSuchMethodException ignored) {}

            try {
                Method convertUri = converterCls.getDeclaredMethod("convertIconUri", iDeviceCls);
                hook("mydevices.icon.uri", convertUri, chain -> {
                    File icon = currentCustomIconFile();
                    if (active && isCardEnabled() && icon != null && isVirtualDevice(chain.getArg(0), getPkg, getId)) {
                        return customIconContentUri(icon);
                    }
                    return chain.proceed();
                });
            } catch (NoSuchMethodException ignored) {}

            try {
                Method getBitmap = converterCls.getDeclaredMethod("getDeviceBitmapIcon", iDeviceCls, int.class);
                hook("mydevices.icon.bitmap", getBitmap, chain -> {
                    File icon = currentCustomIconFile();
                    if (active && isCardEnabled() && icon != null && isVirtualDevice(chain.getArg(0), getPkg, getId)) {
                        try {
                            Bitmap bmp = BitmapFactory.decodeFile(icon.getAbsolutePath());
                            if (bmp != null) return bmp;
                        } catch (Throwable ignored) {}
                    }
                    return chain.proceed();
                });
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {}
    }

    private static boolean isVirtualDevice(Object dev, Method getPkg, Method getId) {
        if (dev == null) return false;
        Object pkg = safeInvoke(getPkg, dev);
        if (VIRTUAL_PKG.equals(pkg)) return true;
        Object id = safeInvoke(getId, dev);
        return id instanceof String s && s.startsWith(MyDevicesCardCodec.DEVICE_ID_PREFIX);
    }

    private File currentCustomIconFile() {
        File f = customIconFile;
        return (f != null && f.isFile() && f.length() > 64) ? f : null;
    }

    /**
     * Exact FileProvider content URI matching res/xml/file_paths.xml in MyDevices 17.25.15:
     * <cache-path name="deviceIcon" path="device_image/" /> -> content://com.oplus.mydevices.fileprovider/deviceIcon/<file>.
     * Required by QuickCardDeviceDataConverter.b() (which checks content:// + cacheDir/device_image/<file>).
     */
    private static String customIconContentUri(File icon) {
        return FILE_PROVIDER_URI_PREFIX + icon.getName();
    }

    private File iconCacheDir(Context ctx) {
        File base = ctx.getCacheDir();
        if (base == null) return null;
        File dir = new File(base, ICON_CACHE_SUBDIR);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        return dir;
    }

    private static String detectIconExtension(byte[] bytes) {
        if (bytes != null && bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return ".webp";
        }
        return ".png";
    }

    private void persistCustomIcon(byte[] pngBytes, String hash) {
        Context ctx = app;
        if (ctx == null || pngBytes == null || pngBytes.length <= 64 || pngBytes.length > 220 * 1024
                || hash == null || hash.isEmpty() || !hash.matches("[A-Za-z0-9_-]{4,32}")) {
            return;
        }
        if (hash.equals(customIconHash) && currentCustomIconFile() != null) return;
        File dir = iconCacheDir(ctx);
        if (dir == null || !dir.isDirectory()) return;
        String ext = detectIconExtension(pngBytes);
        String fileName = ICON_FILE_PREFIX + hash + ext;
        File target = new File(dir, fileName);
        File tmp = new File(dir, ICON_FILE_PREFIX + hash + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(pngBytes);
            out.flush();
        } catch (Exception ignored) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            return;
        }
        File[] existing = dir.listFiles();
        if (existing != null) {
            for (File old : existing) {
                if (old.isFile() && old.getName().startsWith(ICON_FILE_PREFIX)
                        && !old.getName().equals(target.getName())) {
                    old.delete();
                }
            }
        }
        customIconFile = target;
        customIconHash = hash;
        ProbeLog.info("MYDEVICES_ICON_SAVED source=MY_DEVICES");
        SharedPreferences prefs = cachePrefs;
        if (prefs != null) {
            try {
                prefs.edit()
                        .putString("iconHash", hash)
                        .putString("iconFileName", fileName)
                        .apply();
            } catch (RuntimeException ignored) {}
        }
    }

    private void restoreCachedIconFile() {
        Context ctx = app;
        SharedPreferences prefs = cachePrefs;
        if (ctx == null || prefs == null) return;
        try {
            String hash = prefs.getString("iconHash", "");
            if (hash == null || !hash.matches("[A-Za-z0-9_-]{4,32}")) return;
            File dir = iconCacheDir(ctx);
            if (dir == null) return;
            String savedName = prefs.getString("iconFileName", "");
            File f = (savedName != null && savedName.startsWith(ICON_FILE_PREFIX) && !savedName.contains("/"))
                    ? new File(dir, savedName)
                    : new File(dir, ICON_FILE_PREFIX + hash + ".webp");
            if (!f.isFile() || f.length() <= 64) {
                f = new File(dir, ICON_FILE_PREFIX + hash + ".png");
            }
            if (f.isFile() && f.length() > 64) {
                customIconFile = f;
                customIconHash = hash;
            }
        } catch (RuntimeException ignored) {}
    }

    private void registerSyncReceiver() {
        Context ctx = app;
        if (ctx == null) return;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!active || intent == null || !MyDevicesWire.ACTION_SYNC.equals(intent.getAction())) return;
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
                                if (!active || !SenderVerifier.verifyReminderSender(ctx, TargetProfile.HEALTH, uid, pkg)) {
                                    return;
                                }
                                MyDevicesCardCodec.CardSnapshot snap = MyDevicesWire.fromSyncIntent(intent);
                                byte[] iconPng = intent.getByteArrayExtra(MyDevicesWire.EXTRA_ICON_PNG);
                                String iconHash = intent.getStringExtra(MyDevicesWire.EXTRA_ICON_HASH);
                                if (iconPng != null && iconHash != null) {
                                    persistCustomIcon(iconPng, iconHash);
                                }
                                if (snap != null) {
                                    latestSnapshot = snap;
                                    writeCachedSnapshot(snap);
                                    if (isCardEnabled()) {
                                        syncConfigMap(true);
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
            ctx.registerReceiver(r, new IntentFilter(MyDevicesWire.ACTION_SYNC), Context.RECEIVER_EXPORTED);
            syncReceiver = r;
        } catch (RuntimeException ignored) {}
    }

    private void syncConfigMap(boolean enabled) {
        Object mgr = capturedConfigManager;
        if (mgr != null) putInConfigMap(mgr, enabled);
    }

    @SuppressWarnings("unchecked")
    private void putInConfigMap(Object mgr, boolean enabled) {
        Field f = configManagerMapField;
        if (mgr == null || f == null || virtualDeviceAppConfig == null) return;
        try {
            Object raw = f.get(mgr);
            if (raw instanceof Map<?, ?> map) {
                Map<Object, Object> m = (Map<Object, Object>) map;
                if (enabled) m.put(VIRTUAL_PKG, virtualDeviceAppConfig);
                else m.remove(VIRTUAL_PKG);
            }
        } catch (Throwable ignored) {}
    }

    private void requestPullSafe() {
        Context ctx = app;
        if (ctx != null && active && isCardEnabled()) {
            try { MyDevicesWire.requestPullFromHealth(ctx); } catch (RuntimeException ignored) {}
        }
    }

    private void pushObserverStateLocked() {
        Object obs = capturedObserver;
        MyDevicesCardCodec.CardSnapshot snap = ensureSnapshot();
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

    private MyDevicesCardCodec.CardSnapshot ensureSnapshot() {
        MyDevicesCardCodec.CardSnapshot snap = latestSnapshot;
        if (snap == null && cachePrefs != null) {
            snap = readCachedSnapshot();
            if (snap != null) latestSnapshot = snap;
        }
        return snap;
    }

    private String currentDeviceJson() {
        MyDevicesCardCodec.CardSnapshot snap = ensureSnapshot();
        if (snap == null || !snap.valid()) return "";
        File icon = currentCustomIconFile();
        if (icon == null) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastIconPullElapsedMs >= 10_000L) {
                lastIconPullElapsedMs = now;
                try { worker.execute(this::requestPullSafe); } catch (RuntimeException ignored) {}
            }
        }
        String iconUri = icon != null ? customIconContentUri(icon) : null;
        return MyDevicesCardCodec.toDeviceDataJson(snap, VIRTUAL_PKG, iconUri);
    }

    private void logQueryServedThrottled() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastQueryLogElapsedMs >= 5_000L) {
            lastQueryLogElapsedMs = now;
            ProbeLog.info("MYDEVICES_QUERY_SERVED source=MY_DEVICES");
        }
    }

    private void hook(String id, Method method, XposedInterface.Hooker hooker) {
        XposedInterface.HookBuilder builder = module.hook(method);
        if (module.getApiVersion() >= XposedInterface.API_102) {
            builder.setId(id);
        }
        XposedInterface.HookHandle handle = builder.intercept(hooker);
        synchronized (handles) {
            handles.add(handle);
        }
    }

    private Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Method findCacheListMethod(Class<?> mgrCls) {
        Method found = null;
        for (Method m : mgrCls.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isStatic(mod) || m.isSynthetic()) continue;
            if (m.getParameterCount() != 0) continue;
            if (CopyOnWriteArrayList.class.isAssignableFrom(m.getReturnType())) {
                if (found != null) return null;
                found = m;
            }
        }
        return found;
    }

    private static Field findMapField(Class<?> cfgMgrCls) {
        for (Field f : cfgMgrCls.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && Map.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private static boolean isVirtualUri(Object rawUri) {
        if (!(rawUri instanceof String uri)) return false;
        return uri.contains(MyDevicesCardCodec.VIRTUAL_AUTHORITY);
    }

    private static Object safeInvoke(Method m, Object target) {
        try { return m.invoke(target); } catch (Throwable ignored) { return null; }
    }

    private MyDevicesCardCodec.CardSnapshot readCachedSnapshot() {
        SharedPreferences prefs = cachePrefs;
        if (prefs == null) return null;
        try {
            String id = prefs.getString("deviceId", "");
            String name = prefs.getString("watchName", "");
            if (id == null || id.isEmpty() || name == null || name.isEmpty()) return null;
            MyDevicesCardCodec.CardSnapshot snap = new MyDevicesCardCodec.CardSnapshot(
                    id, name, prefs.getString("mac", ""),
                    false, prefs.getInt("battery", -1), false,
                    prefs.getLong("updatedAt", 0L));
            return snap.valid() ? snap : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void writeCachedSnapshot(MyDevicesCardCodec.CardSnapshot snap) {
        SharedPreferences prefs = cachePrefs;
        if (prefs == null) return;
        try {
            prefs.edit()
                    .putString("deviceId", snap.deviceId())
                    .putString("watchName", snap.watchName())
                    .putString("mac", snap.macAddress())
                    .putInt("battery", snap.batteryPercent())
                    .putLong("updatedAt", snap.updatedAtMs())
                    .apply();
        } catch (RuntimeException ignored) {}
    }
}
