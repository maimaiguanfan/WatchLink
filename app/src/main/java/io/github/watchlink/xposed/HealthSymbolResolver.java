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
    static final String SCHEMA = "health-v4";
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
            String source) {

        Symbols(String deviceCatalogClass, String sendFacadeClass, String receiveCallbackClass,
                String deviceMgrFactoryClass, String wifiSyncMgrClass, String liveNotifyMgrClass, String source) {
            this(deviceCatalogClass, sendFacadeClass, receiveCallbackClass, deviceMgrFactoryClass,
                    wifiSyncMgrClass, liveNotifyMgrClass, "", source);
        }

        Symbols(String deviceCatalogClass, String sendFacadeClass, String receiveCallbackClass,
                String deviceMgrFactoryClass, String wifiSyncMgrClass, String source) {
            this(deviceCatalogClass, sendFacadeClass, receiveCallbackClass, deviceMgrFactoryClass,
                    wifiSyncMgrClass, "", "", source);
        }

        Symbols(String deviceCatalogClass, String sendFacadeClass, String receiveCallbackClass,
                String deviceMgrFactoryClass, String source) {
            this(deviceCatalogClass, sendFacadeClass, receiveCallbackClass, deviceMgrFactoryClass, "", "", "", source);
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
            if (catalog.isEmpty() && send.isEmpty() && recv.isEmpty()
                    && mgr.isEmpty() && wifi.isEmpty() && live.isEmpty() && sampleCmd.isEmpty()) return null;
            return new Symbols(catalog, send, recv, mgr, wifi, live, sampleCmd, "CACHE");
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
                && !fast.sampleCommandApiClass().isEmpty()) {
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
            return new Symbols(catalog.getName(), send.getName(), recv.getName(), mgr, wifiMgr, liveMgr, sampleCmd, "FAST_PATH");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError miss) {
            return null;
        }
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
                                        mgrFactories, wifiSyncMgrs, liveNotifyMgrs, sampleCmdApis);
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
                "DEX_SCAN");
    }

    private static void inspectClass(ClassDef type, Set<String> catalogs, Set<String> sendFacades,
                                     Set<String> recvCallbacks, Set<String> mgrFactories,
                                     Set<String> wifiSyncMgrs, Set<String> liveNotifyMgrs,
                                     Set<String> sampleCmdApis) {
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
