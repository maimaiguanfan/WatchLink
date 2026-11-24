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
    static final String SCHEMA = "health-v1";
    private static final String D_PLUGIN_PROXY = "Lcom/huawei/haf/bundle/AppBundlePluginProxy;";
    private static final String D_DEVICE_MGR_API = "Lcom/huawei/health/devicemgr/api/DeviceMgrApi;";
    private static final String D_UNITE_CHANNEL = "Lcom/huawei/unitedevice/api/UniteChannelInterface;";
    private static final String D_ENGINE_CHANNEL = "Lcom/huawei/unitedevice/api/EngineChannelInterface;";
    private static final String D_MSG_CALLBACK = "Lcom/huawei/devicesdk/callback/MessageReceiveCallback;";
    private static final String D_DMS_PREFIX = "Lcom/huawei/devicesdk/service/DevicesManagementService$";
    private static final String D_HW_DEVICE_MGR = "Lcom/huawei/hwdevice/phoneprocess/framework/hwdevicemgr/dmsmanager/HwDeviceMgrInterface;";
    private static final String D_CONTEXT = "Landroid/content/Context;";
    private static final Pattern DEX = Pattern.compile("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");

    record Symbols(
            String deviceCatalogClass,
            String sendFacadeClass,
            String receiveCallbackClass,
            String deviceMgrFactoryClass,
            String source) {

        boolean completeSdk() {
            return notEmpty(deviceCatalogClass) && notEmpty(sendFacadeClass) && notEmpty(receiveCallbackClass);
        }

        Map<String, String> toMap() {
            Map<String, String> map = new HashMap<>();
            map.put("deviceCatalogClass", empty(deviceCatalogClass));
            map.put("sendFacadeClass", empty(sendFacadeClass));
            map.put("receiveCallbackClass", empty(receiveCallbackClass));
            map.put("deviceMgrFactoryClass", empty(deviceMgrFactoryClass));
            return map;
        }

        static Symbols fromCache(Map<String, String> map) {
            if (map == null || map.isEmpty()) return null;
            String catalog = map.getOrDefault("deviceCatalogClass", "");
            String send = map.getOrDefault("sendFacadeClass", "");
            String recv = map.getOrDefault("receiveCallbackClass", "");
            String mgr = map.getOrDefault("deviceMgrFactoryClass", "");
            if (catalog.isEmpty() && send.isEmpty() && recv.isEmpty() && mgr.isEmpty()) return null;
            return new Symbols(catalog, send, recv, mgr, "CACHE");
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
        if (fast != null && fast.completeSdk() && !fast.deviceMgrFactoryClass().isEmpty()) {
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
            return new Symbols(catalog.getName(), send.getName(), recv.getName(), mgr, "FAST_PATH");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError miss) {
            return null;
        }
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
                                inspectClass(type, catalogs, sendFacades, recvCallbacks, mgrFactories);
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
                "DEX_SCAN");
    }

    private static void inspectClass(ClassDef type, Set<String> catalogs, Set<String> sendFacades,
                                     Set<String> recvCallbacks, Set<String> mgrFactories) {
        String desc = type.getType();
        List<String> ifaces = type.getInterfaces();
        if (D_PLUGIN_PROXY.equals(type.getSuperclass()) && ifaces.contains(D_DEVICE_MGR_API)) {
            catalogs.add(toClassName(desc));
        }
        if (!AccessFlags.ABSTRACT.isSet(type.getAccessFlags())
                && ifaces.contains(D_UNITE_CHANNEL) && ifaces.contains(D_ENGINE_CHANNEL)
                && isUniteDeviceServiceFacade(type)) {
            sendFacades.add(toClassName(desc));
        }
        if (desc.startsWith(D_DMS_PREFIX) && ifaces.contains(D_MSG_CALLBACK)) {
            recvCallbacks.add(toClassName(desc));
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
