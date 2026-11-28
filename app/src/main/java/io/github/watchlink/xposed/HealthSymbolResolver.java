package io.github.watchlink.xposed;

import android.os.Build;
import androidx.annotation.RequiresApi;
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves optional Huawei Health SDK symbols dynamically across ProGuard dictionary shifts.
 * Fast path checks cached or known (.300/.330) names first (<1ms); on a Health update, scans DEX
 * structurally by interfaces, superclass, and log markers ONCE and caches by APK fingerprint.
 */
final class HealthSymbolResolver {
    static final String SCHEMA = "health-v7";
    private static final String D_PLUGIN_PROXY = "Lcom/huawei/haf/bundle/AppBundlePluginProxy;";
    private static final String D_DEVICE_MGR_API = "Lcom/huawei/health/devicemgr/api/DeviceMgrApi;";
    private static final String D_SAMPLE_CMD_API = "Lcom/huawei/health/devicemgr/api/outofprocess/SendSampleCommandApi;";
    private static final String D_UNITE_CHANNEL = "Lcom/huawei/unitedevice/api/UniteChannelInterface;";
    private static final String D_ENGINE_CHANNEL = "Lcom/huawei/unitedevice/api/EngineChannelInterface;";
    private static final String D_MSG_CALLBACK = "Lcom/huawei/devicesdk/callback/MessageReceiveCallback;";
    private static final String D_DMS_PREFIX = "Lcom/huawei/devicesdk/service/DevicesManagementService$";
    private static final String D_HW_DEVICE_MGR = "Lcom/huawei/hwdevice/phoneprocess/framework/hwdevicemgr/dmsmanager/HwDeviceMgrInterface;";
    private static final String D_PARSER_INTERFACE = "Lcom/huawei/hwdevice/phoneprocess/binder/ParserInterface;";
    private static final String D_WIFI_CONNECT_RECEIVER = "Lcom/huawei/hwdevice/phoneprocess/mgr/hwwifisyncmgr/WifiConnectReceiver;";
    private static final String D_DATA_RECEIVE_CALLBACK = "Lcom/huawei/health/devicemgr/business/callback/DataReceiveCallback;";
    private static final String D_BT_PROXY_SLEEP_CALLBACK = "Lcom/huawei/hwdevice/phoneprocess/mgr/btproxy/callback/BtProxySleepCallback;";
    private static final String D_BUNDLE = "Landroid/os/Bundle;";
    private static final String D_STRING = "Ljava/lang/String;";
    private static final String D_CONTEXT = "Landroid/content/Context;";
    private static final Pattern DEX = Pattern.compile("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");

    record Symbols(
            String deviceCatalogClass,
            String sendFacadeClass,
            String receiveCallbackClass,
            String deviceMgrFactoryClass,
            String wifiSyncMgrClass,
            String liveNotifyMgrClass,
            String sampleCommandApiClass,
            String sleepStatusMgrClass,
            String notifySendDataClass,
            String notifyReminderMethod,
            String batteryInfoClass,
            String source) {

        Symbols(String deviceCatalogClass, String sendFacadeClass, String receiveCallbackClass,
                String deviceMgrFactoryClass, String wifiSyncMgrClass, String liveNotifyMgrClass,
                String sampleCommandApiClass, String sleepStatusMgrClass, String notifySendDataClass,
                String notifyReminderMethod, String source) {
            this(deviceCatalogClass, sendFacadeClass, receiveCallbackClass, deviceMgrFactoryClass,
                    wifiSyncMgrClass, liveNotifyMgrClass, sampleCommandApiClass, sleepStatusMgrClass,
                    notifySendDataClass, notifyReminderMethod, "", source);
        }

        Symbols(String deviceCatalogClass, String sendFacadeClass, String receiveCallbackClass,
                String deviceMgrFactoryClass, String wifiSyncMgrClass, String liveNotifyMgrClass,
                String sampleCommandApiClass, String sleepStatusMgrClass, String source) {
            this(deviceCatalogClass, sendFacadeClass, receiveCallbackClass, deviceMgrFactoryClass,
                    wifiSyncMgrClass, liveNotifyMgrClass, sampleCommandApiClass, sleepStatusMgrClass,
                    "", "", "", source);
        }

        Symbols(String deviceCatalogClass, String sendFacadeClass, String receiveCallbackClass,
                String deviceMgrFactoryClass, String wifiSyncMgrClass, String liveNotifyMgrClass,
                String sampleCommandApiClass, String source) {
            this(deviceCatalogClass, sendFacadeClass, receiveCallbackClass, deviceMgrFactoryClass,
                    wifiSyncMgrClass, liveNotifyMgrClass, sampleCommandApiClass, "", "", "", "", source);
        }

        Symbols(String deviceCatalogClass, String sendFacadeClass, String receiveCallbackClass,
                String deviceMgrFactoryClass, String wifiSyncMgrClass, String liveNotifyMgrClass, String source) {
            this(deviceCatalogClass, sendFacadeClass, receiveCallbackClass, deviceMgrFactoryClass,
                    wifiSyncMgrClass, liveNotifyMgrClass, "", "", "", "", "", source);
        }

        Symbols(String deviceCatalogClass, String sendFacadeClass, String receiveCallbackClass,
                String deviceMgrFactoryClass, String wifiSyncMgrClass, String source) {
            this(deviceCatalogClass, sendFacadeClass, receiveCallbackClass, deviceMgrFactoryClass,
                    wifiSyncMgrClass, "", "", "", "", "", "", source);
        }

        Symbols(String deviceCatalogClass, String sendFacadeClass, String receiveCallbackClass,
                String deviceMgrFactoryClass, String source) {
            this(deviceCatalogClass, sendFacadeClass, receiveCallbackClass, deviceMgrFactoryClass,
                    "", "", "", "", "", "", "", source);
        }

        boolean completeSdk() {
            return notEmpty(deviceCatalogClass) && notEmpty(sendFacadeClass) && notEmpty(receiveCallbackClass);
        }

        Map<String, String> toMap() {
            Map<String, String> map = new HashMap<>();
            map.put("deviceCatalogClass", empty(deviceCatalogClass));
            map.put("sendFacadeClass", empty(sendFacadeClass));
            map.put("receiveCallbackClass", empty(receiveCallbackClass));
            map.put("deviceMgrFactoryClass", empty(deviceMgrFactoryClass));
            map.put("wifiSyncMgrClass", empty(wifiSyncMgrClass));
            map.put("liveNotifyMgrClass", empty(liveNotifyMgrClass));
            map.put("sampleCommandApiClass", empty(sampleCommandApiClass));
            map.put("sleepStatusMgrClass", empty(sleepStatusMgrClass));
            map.put("notifySendDataClass", empty(notifySendDataClass));
            map.put("notifyReminderMethod", empty(notifyReminderMethod));
            map.put("batteryInfoClass", empty(batteryInfoClass));
            return map;
        }

        static Symbols fromCache(Map<String, String> map) {
            if (map == null || map.isEmpty()) return null;
            String catalog = map.getOrDefault("deviceCatalogClass", "");
            String send = map.getOrDefault("sendFacadeClass", "");
            String recv = map.getOrDefault("receiveCallbackClass", "");
            String mgr = map.getOrDefault("deviceMgrFactoryClass", "");
            String wifi = map.getOrDefault("wifiSyncMgrClass", "");
            String live = map.getOrDefault("liveNotifyMgrClass", "");
            String sampleCmd = map.getOrDefault("sampleCommandApiClass", "");
            String sleepMgr = map.getOrDefault("sleepStatusMgrClass", "");
            String notifySend = map.getOrDefault("notifySendDataClass", "");
            String notifyRem = map.getOrDefault("notifyReminderMethod", "");
            String batteryInfo = map.getOrDefault("batteryInfoClass", "");
            if (catalog.isEmpty() && send.isEmpty() && recv.isEmpty()
                    && mgr.isEmpty() && wifi.isEmpty() && live.isEmpty()
                    && sampleCmd.isEmpty() && sleepMgr.isEmpty() && notifySend.isEmpty()
                    && batteryInfo.isEmpty()) return null;
            return new Symbols(catalog, send, recv, mgr, wifi, live, sampleCmd, sleepMgr,
                    notifySend, notifyRem, batteryInfo, "CACHE");
        }

        private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }
        private static String empty(String s) { return s == null ? "" : s; }
    }

    static Symbols resolve(AlarmObserverInstaller installer, ApkSymbolCache cache, String... sources) {
        String fp = cache == null ? null : ApkSymbolCache.fingerprint(SCHEMA, sources);
        if (cache != null) {
            Symbols cached = Symbols.fromCache(cache.read(fp));
            if (cached != null) return cached;
        }
        Symbols fast = Build.VERSION.SDK_INT >= 34 ? tryFastPath(installer) : null;
        if (fast != null && fast.completeSdk() && !fast.deviceMgrFactoryClass().isEmpty()
                && !fast.wifiSyncMgrClass().isEmpty() && !fast.liveNotifyMgrClass().isEmpty()
                && !fast.sampleCommandApiClass().isEmpty() && !fast.sleepStatusMgrClass().isEmpty()
                && !fast.notifySendDataClass().isEmpty() && !fast.notifyReminderMethod().isEmpty()
                && !fast.batteryInfoClass().isEmpty()) {
            if (cache != null) cache.write(fp, fast.toMap());
            return fast;
        }
        Symbols scanned = scanApk(sources);
        if (scanned != null && cache != null) {
            cache.write(fp, scanned.toMap());
        }
        return scanned != null ? scanned : fast;
    }

    @RequiresApi(34)
    private static Symbols tryFastPath(AlarmObserverInstaller installer) {
        if (installer == null) return null;
        try {
            Class<?> proxy = installer.target("com.huawei.haf.bundle.AppBundlePluginProxy");
            Class<?> api = installer.target("com.huawei.health.devicemgr.api.DeviceMgrApi");
            Class<?> catalog = HealthReminderCatalog.factory(proxy, api,
                    opt(installer, "eqj"), opt(installer, "eqd")).getDeclaringClass();
            Class<?> send = ReminderMethodShape.implementation(installer::target, List.of(
                    installer.target("com.huawei.unitedevice.api.EngineChannelInterface"),
                    installer.target("com.huawei.unitedevice.api.UniteChannelInterface")), "zck", "zcj");
            String dms = "com.huawei.devicesdk.service.DevicesManagementService";
            Class<?> recv = ReminderMethodShape.implementation(installer::target, List.of(
                    installer.target("com.huawei.devicesdk.callback.MessageReceiveCallback")), dms + "$7", dms + "$8");
            String mgr = "";
            Class<?> nfz = opt(installer, "nfz");
            Class<?> mgrApi = opt(installer,
                    "com.huawei.hwdevice.phoneprocess.framework.hwdevicemgr.dmsmanager.HwDeviceMgrInterface");
            if (nfz != null && mgrApi != null && HealthExternalSender.findManagerFactory(nfz, mgrApi) != null) {
                mgr = nfz.getName();
            }
            String wifiMgr = "";
            Class<?> oai = opt(installer, "oai");
            Class<?> parserApi = opt(installer, "com.huawei.hwdevice.phoneprocess.binder.ParserInterface");
            Class<?> wifiRecv = opt(installer, "com.huawei.hwdevice.phoneprocess.mgr.hwwifisyncmgr.WifiConnectReceiver");
            if (oai != null && parserApi != null && wifiRecv != null && parserApi.isAssignableFrom(oai)
                    && hasFieldOfType(oai, wifiRecv)) {
                wifiMgr = oai.getName();
            }
            String liveMgr = "";
            Class<?> oca = opt(installer, "oca");
            Class<?> dataCb = opt(installer, "com.huawei.health.devicemgr.business.callback.DataReceiveCallback");
            if (oca != null && dataCb != null && dataCb.isAssignableFrom(oca) && hasLiveNotifyShape(oca)) {
                liveMgr = oca.getName();
            }
            String sampleCmd = "";
            Class<?> sampleApi = opt(installer, "com.huawei.health.devicemgr.api.outofprocess.SendSampleCommandApi");
            if (sampleApi != null) {
                try {
                    Class<?> found = HealthReminderCatalog.factory(proxy, sampleApi,
                            opt(installer, "eqe"), opt(installer, "eqk")).getDeclaringClass();
                    sampleCmd = found.getName();
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    sampleCmd = "";
                }
            }
            String sleepMgr = "";
            Class<?> nhp = opt(installer, "nhp");
            Class<?> sleepCb = opt(installer, "com.huawei.hwdevice.phoneprocess.mgr.btproxy.callback.BtProxySleepCallback");
            if (nhp != null && sleepCb != null && hasFieldOfType(nhp, sleepCb) && hasSleepStatusShape(nhp)) {
                sleepMgr = nhp.getName();
            }
            String notifySend = "";
            String notifyRem = "";
            Class<?> ocp = opt(installer, "ocp");
            Class<?> devCmd = opt(installer, "com.huawei.datatype.DeviceCommand");
            if (ocp != null && devCmd != null && hasNotifySendDataShape(ocp, devCmd)) {
                notifySend = ocp.getName();
                try {
                    java.lang.reflect.Method j = ocp.getDeclaredMethod("j", int.class, android.os.Bundle.class);
                    if (j.getReturnType() == String.class) notifyRem = "j";
                } catch (NoSuchMethodException ignored) {}
            }
            String batteryInfo = "";
            Class<?> ncd = opt(installer, "ncd");
            if (ncd != null && hasBatteryInfoShape(ncd)) {
                batteryInfo = ncd.getName();
            }
            return new Symbols(catalog.getName(), send.getName(), recv.getName(), mgr, wifiMgr, liveMgr,
                    sampleCmd, sleepMgr, notifySend, notifyRem, batteryInfo, "FAST_PATH");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError miss) {
            return null;
        }
    }

    private static boolean hasBatteryInfoShape(Class<?> cls) {
        boolean hasReadInt = false;
        boolean hasWriteInt = false;
        boolean hasStaticMap = false;
        for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && Map.class.isAssignableFrom(f.getType())) {
                hasStaticMap = true;
            }
        }
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (!java.lang.reflect.Modifier.isStatic(mod) || m.isSynthetic()) continue;
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == int.class && p.length == 1 && p[0] == String.class) {
                hasReadInt = true;
            } else if (m.getReturnType() == void.class && p.length == 2
                    && p[0] == String.class && p[1] == int.class) {
                hasWriteInt = true;
            }
        }
        return hasStaticMap && hasReadInt && hasWriteInt;
    }

    private static boolean hasNotifySendDataShape(Class<?> cls, Class<?> deviceCommandCls) {
        boolean hasExtend = false;
        boolean hasOldReminder = false;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(mod) || m.isSynthetic()) continue;
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == deviceCommandCls && p.length == 3
                    && p[0] == android.os.Bundle.class && p[1] == int.class && p[2] == deviceCommandCls) {
                hasExtend = true;
            } else if (m.getReturnType() == String.class && p.length == 3
                    && p[0] == int.class && p[1] == android.os.Bundle.class && p[2] == int.class) {
                hasOldReminder = true;
            }
        }
        return hasExtend && hasOldReminder;
    }

    private static boolean hasSleepStatusShape(Class<?> cls) {
        boolean hasSingleton = false;
        int actionMethods = 0;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (!java.lang.reflect.Modifier.isPublic(mod) || m.isSynthetic()) continue;
            if (java.lang.reflect.Modifier.isStatic(mod) && m.getReturnType() == cls && m.getParameterCount() == 0) {
                hasSingleton = true;
            } else if (!java.lang.reflect.Modifier.isStatic(mod) && m.getReturnType() == void.class
                    && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == String.class
                    && m.getParameterTypes()[1] == int.class) {
                actionMethods++;
            }
        }
        return hasSingleton && actionMethods == 1;
    }

    private static boolean hasLiveNotifyShape(Class<?> cls) {
        boolean hasSingleton = false;
        boolean hasSendString = false;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (!java.lang.reflect.Modifier.isPublic(mod) || m.isSynthetic()) continue;
            if (java.lang.reflect.Modifier.isStatic(mod) && m.getReturnType() == cls && m.getParameterCount() == 0) {
                hasSingleton = true;
            } else if (!java.lang.reflect.Modifier.isStatic(mod) && m.getReturnType() == void.class
                    && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                hasSendString = true;
            }
        }
        return hasSingleton && hasSendString;
    }

    private static boolean hasFieldOfType(Class<?> owner, Class<?> fieldType) {
        for (java.lang.reflect.Field f : owner.getDeclaredFields()) {
            if (f.getType() == fieldType) return true;
        }
        return false;
    }

    @RequiresApi(34)
    private static Class<?> opt(AlarmObserverInstaller installer, String name) {
        try { return installer.target(name); }
        catch (ClassNotFoundException | NoClassDefFoundError absent) { return null; }
    }

    static Symbols scanApk(String... sources) {
        if (sources == null || sources.length == 0) return null;
        Set<String> catalogs = new HashSet<>();
        Set<String> sendFacades = new HashSet<>();
        Set<String> recvCallbacks = new HashSet<>();
        Set<String> mgrFactories = new HashSet<>();
        Set<String> wifiSyncMgrs = new HashSet<>();
        Set<String> liveNotifyMgrs = new HashSet<>();
        Set<String> sampleCmdApis = new HashSet<>();
        Set<String> sleepStatusMgrs = new HashSet<>();
        Set<String> notifySendDatas = new HashSet<>();
        Set<String> notifyReminderMethods = new HashSet<>();
        Set<String> batteryInfos = new HashSet<>();
        try {
            for (String source : sources) {
                if (source == null) continue;
                try (ZipFile archive = new ZipFile(source)) {
                    Enumeration<? extends ZipEntry> entries = archive.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (!DEX.matcher(entry.getName()).matches()) continue;
                        try (BufferedInputStream in = new BufferedInputStream(archive.getInputStream(entry))) {
                            DexBackedDexFile dex = DexBackedDexFile.fromInputStream(null, in);
                            for (ClassDef type : dex.getClasses()) {
                                inspectClass(type, catalogs, sendFacades, recvCallbacks,
                                        mgrFactories, wifiSyncMgrs, liveNotifyMgrs, sampleCmdApis,
                                        sleepStatusMgrs, notifySendDatas, notifyReminderMethods, batteryInfos);
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException | LinkageError unreadable) {
            return null;
        }
        return new Symbols(
                unique(catalogs),
                unique(sendFacades),
                unique(recvCallbacks),
                unique(mgrFactories),
                unique(wifiSyncMgrs),
                unique(liveNotifyMgrs),
                unique(sampleCmdApis),
                unique(sleepStatusMgrs),
                unique(notifySendDatas),
                unique(notifyReminderMethods),
                unique(batteryInfos),
                "DEX_SCAN");
    }

    private static void inspectClass(ClassDef type, Set<String> catalogs, Set<String> sendFacades,
                                     Set<String> recvCallbacks, Set<String> mgrFactories,
                                     Set<String> wifiSyncMgrs, Set<String> liveNotifyMgrs,
                                     Set<String> sampleCmdApis, Set<String> sleepStatusMgrs,
                                     Set<String> notifySendDatas, Set<String> notifyReminderMethods,
                                     Set<String> batteryInfos) {
        String desc = type.getType();
        List<String> ifaces = type.getInterfaces();
        if (D_PLUGIN_PROXY.equals(type.getSuperclass()) && ifaces.contains(D_DEVICE_MGR_API)) {
            catalogs.add(toClassName(desc));
        }
        if (D_PLUGIN_PROXY.equals(type.getSuperclass()) && ifaces.contains(D_SAMPLE_CMD_API)) {
            sampleCmdApis.add(toClassName(desc));
        }
        if (!AccessFlags.ABSTRACT.isSet(type.getAccessFlags())
                && ifaces.contains(D_UNITE_CHANNEL) && ifaces.contains(D_ENGINE_CHANNEL)
                && isUniteDeviceServiceFacade(type)) {
            sendFacades.add(toClassName(desc));
        }
        if (desc.startsWith(D_DMS_PREFIX) && ifaces.contains(D_MSG_CALLBACK)) {
            recvCallbacks.add(toClassName(desc));
        }
        if (ifaces.contains(D_PARSER_INTERFACE) && isWifiSyncManager(type)) {
            wifiSyncMgrs.add(toClassName(desc));
        }
        if (ifaces.contains(D_DATA_RECEIVE_CALLBACK) && isLiveNotificationManager(type)) {
            liveNotifyMgrs.add(toClassName(desc));
        }
        if (isBtProxySleepStatusManager(type)) {
            sleepStatusMgrs.add(toClassName(desc));
        }
        String reminderMethod = findNotifySendDataReminderMethod(type);
        if (reminderMethod != null) {
            notifySendDatas.add(toClassName(desc));
            if (!reminderMethod.isEmpty()) notifyReminderMethods.add(reminderMethod);
        }
        if (isDeviceBatteryInfo(type)) {
            batteryInfos.add(toClassName(desc));
        }
        for (Method m : type.getDirectMethods()) {
            int flags = m.getAccessFlags();
            if (!AccessFlags.PUBLIC.isSet(flags) || !AccessFlags.STATIC.isSet(flags)
                    || AccessFlags.SYNTHETIC.isSet(flags)) continue;
            if (D_HW_DEVICE_MGR.equals(m.getReturnType())
                    && m.getParameterTypes().size() == 1
                    && D_CONTEXT.contentEquals(m.getParameterTypes().get(0))) {
                mgrFactories.add(toClassName(desc));
            }
        }
    }

    /** Identifies DeviceBatteryInfo (ncd) via log markers and static battery lookup/config methods. */
    private static boolean isDeviceBatteryInfo(ClassDef type) {
        boolean hasTag = false;
        boolean hasFetchEmpty = false;
        boolean hasConfigEmpty = false;
        for (Method m : type.getDirectMethods()) {
            if (m.getImplementation() == null) continue;
            for (Instruction insn : m.getImplementation().getInstructions()) {
                if (insn instanceof ReferenceInstruction ref && ref.getReference() instanceof StringReference str) {
                    String s = str.getString();
                    if ("DeviceBatteryInfo".equals(s)) hasTag = true;
                    else if ("fetchDeviceBattery id is empty".equals(s)) hasFetchEmpty = true;
                    else if ("configBattery id is empty".equals(s)) hasConfigEmpty = true;
                }
            }
            if (hasTag && hasFetchEmpty && hasConfigEmpty) return true;
        }
        return false;
    }

    /**
     * Identifies NotifySendData (ocp) via HwDeviceMgrInterface field + reminderTlv log markers,
     * returning the packagingReminderHex method name (e.g. "j") or null if not NotifySendData.
     */
    private static String findNotifySendDataReminderMethod(ClassDef type) {
        boolean hasDeviceMgrField = false;
        for (com.android.tools.smali.dexlib2.iface.Field f : type.getInstanceFields()) {
            if (D_HW_DEVICE_MGR.equals(f.getType())) {
                hasDeviceMgrField = true;
                break;
            }
        }
        if (!hasDeviceMgrField) return null;
        boolean hasTag = false;
        boolean hasReminderMarker = false;
        String reminderMethod = "";
        for (Method m : type.getMethods()) {
            if (m.getImplementation() == null) continue;
            boolean isIntBundleString = !AccessFlags.STATIC.isSet(m.getAccessFlags())
                    && D_STRING.equals(m.getReturnType())
                    && m.getParameterTypes().size() == 2
                    && "I".contentEquals(m.getParameterTypes().get(0))
                    && D_BUNDLE.contentEquals(m.getParameterTypes().get(1));
            for (Instruction insn : m.getImplementation().getInstructions()) {
                if (insn instanceof ReferenceInstruction ref && ref.getReference() instanceof StringReference str) {
                    String s = str.getString();
                    if ("NotifySendData".equals(s)) {
                        hasTag = true;
                    } else if (s.contains("reminderTlv(1302000F=vibrate)")) {
                        hasReminderMarker = true;
                    } else if (isIntBundleString && s.contains("packagingReminderHex isReminderStatus")) {
                        reminderMethod = m.getName();
                    }
                }
            }
        }
        return (hasTag && hasReminderMarker) ? reminderMethod : null;
    }

    /** Identifies BtProxySleepStatusManager (nhp) via BtProxySleepCallback field + log markers. */
    private static boolean isBtProxySleepStatusManager(ClassDef type) {
        boolean hasCallbackField = false;
        for (com.android.tools.smali.dexlib2.iface.Field f : type.getInstanceFields()) {
            if (D_BT_PROXY_SLEEP_CALLBACK.equals(f.getType())) {
                hasCallbackField = true;
                break;
            }
        }
        if (!hasCallbackField) return false;
        boolean hasTag = false;
        boolean hasActionLog = false;
        for (Method m : type.getMethods()) {
            if (m.getImplementation() == null) continue;
            for (Instruction insn : m.getImplementation().getInstructions()) {
                if (insn instanceof ReferenceInstruction ref && ref.getReference() instanceof StringReference str) {
                    String s = str.getString();
                    if ("BtProxySleepStatusManager".equals(s)) hasTag = true;
                    else if ("dealDeviceSleepAction action: ".equals(s)) hasActionLog = true;
                }
            }
            if (hasTag && hasActionLog) return true;
        }
        return false;
    }

    /** Identifies HwNotificationLiveMgr (oca) via DataReceiveCallback + SmartEdge tag/package markers. */
    private static boolean isLiveNotificationManager(ClassDef type) {
        boolean hasTag = false;
        boolean hasSmartEdge = false;
        for (Method m : type.getMethods()) {
            if (m.getImplementation() == null) continue;
            for (Instruction insn : m.getImplementation().getInstructions()) {
                if (insn instanceof ReferenceInstruction ref && ref.getReference() instanceof StringReference str) {
                    String s = str.getString();
                    if ("HwNotificationLiveMgr".equals(s)) hasTag = true;
                    else if ("hw.unitedevice.smartedge".equals(s) || "hw.watch.message.smartedge".equals(s)) {
                        hasSmartEdge = true;
                    }
                }
            }
            if (hasTag && hasSmartEdge) return true;
        }
        return false;
    }

    /** Identifies HwWifiSyncMgr (oai) via ParserInterface + WifiConnectReceiver field + ACK markers. */
    private static boolean isWifiSyncManager(ClassDef type) {
        boolean hasReceiverField = false;
        for (com.android.tools.smali.dexlib2.iface.Field f : type.getInstanceFields()) {
            if (D_WIFI_CONNECT_RECEIVER.equals(f.getType())) {
                hasReceiverField = true;
                break;
            }
        }
        boolean hasTag = false;
        boolean hasSuccessMsg = false;
        for (Method m : type.getMethods()) {
            if (m.getImplementation() == null) continue;
            for (Instruction insn : m.getImplementation().getInstructions()) {
                if (insn instanceof ReferenceInstruction ref && ref.getReference() instanceof StringReference str) {
                    String s = str.getString();
                    if ("HwWifiSyncMgr".equals(s)) hasTag = true;
                    else if ("The device received wifi data success".equals(s)) hasSuccessMsg = true;
                }
            }
            if (hasTag && hasSuccessMsg) return true;
        }
        return hasReceiverField && hasTag;
    }

    /** Distinguishes UniteDeviceService (e.g. zcj/zck) from UniteDeviceServiceProxy (e.g. zci). */
    private static boolean isUniteDeviceServiceFacade(ClassDef type) {
        boolean hasServiceTag = false;
        for (Method m : type.getMethods()) {
            if (m.getImplementation() == null) continue;
            for (Instruction insn : m.getImplementation().getInstructions()) {
                if (insn instanceof ReferenceInstruction ref && ref.getReference() instanceof StringReference str) {
                    String s = str.getString();
                    if ("UniteDeviceServiceProxy".equals(s)) return false;
                    if ("UniteDeviceService".equals(s) || "enter UniteDeviceService".equals(s)) hasServiceTag = true;
                }
            }
        }
        return hasServiceTag;
    }

    private static String unique(Set<String> items) {
        return items.size() == 1 ? items.iterator().next() : "";
    }

    private static String toClassName(String descriptor) {
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private HealthSymbolResolver() {}
}
