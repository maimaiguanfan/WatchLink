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
 * Dynamically resolves obfuscated symbols inside ColorOS MyDevices (com.heytap.mydevices 17.25.15+):
 * specifically DeviceAppProviderQuery (aa.bn in 17.25.15), which exposes unobfuscated methods
 * queryAllDevices(String) -> List<String> and queryDeviceById(String, String) -> String.
 */
final class MyDevicesSymbolResolver {
    static final String SCHEMA = "mydevices-v1";
    private static final String D_STRING = "Ljava/lang/String;";
    private static final String D_LIST = "Ljava/util/List;";
    private static final Pattern DEX = Pattern.compile("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");

    record Symbols(String providerQueryClass, String source) {
        boolean ready() {
            return providerQueryClass != null && !providerQueryClass.isEmpty();
        }

        Map<String, String> toMap() {
            Map<String, String> map = new HashMap<>();
            map.put("providerQueryClass", providerQueryClass == null ? "" : providerQueryClass);
            return map;
        }

        static Symbols fromCache(Map<String, String> map) {
            if (map == null || map.isEmpty()) return null;
            String cls = map.getOrDefault("providerQueryClass", "");
            return cls.isEmpty() ? null : new Symbols(cls, "CACHE");
        }
    }

    static Symbols resolve(AlarmObserverInstaller installer, ApkSymbolCache cache, String... sources) {
        String fp = cache == null ? null : ApkSymbolCache.fingerprint(SCHEMA, sources);
        if (cache != null) {
            Symbols cached = Symbols.fromCache(cache.read(fp));
            if (cached != null) return cached;
        }
        Symbols fast = Build.VERSION.SDK_INT >= 34 ? tryFastPath(installer) : null;
        if (fast != null && fast.ready()) {
            if (cache != null) cache.write(fp, fast.toMap());
            return fast;
        }
        Symbols scanned = scanApk(sources);
        if (scanned != null && scanned.ready() && cache != null) {
            cache.write(fp, scanned.toMap());
        }
        return scanned != null ? scanned : fast;
    }

    @RequiresApi(34)
    private static Symbols tryFastPath(AlarmObserverInstaller installer) {
        if (installer == null) return null;
        try {
            Class<?> cls = installer.target("aa.bn");
            if (hasProviderQueryShape(cls)) {
                return new Symbols(cls.getName(), "FAST_PATH");
            }
        } catch (ClassNotFoundException | LinkageError ignored) {}
        return null;
    }

    private static boolean hasProviderQueryShape(Class<?> cls) {
        try {
            java.lang.reflect.Method all = cls.getDeclaredMethod("queryAllDevices", String.class);
            java.lang.reflect.Method byId = cls.getDeclaredMethod("queryDeviceById", String.class, String.class);
            return List.class.isAssignableFrom(all.getReturnType())
                    && byId.getReturnType() == String.class;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    static Symbols scanApk(String... sources) {
        if (sources == null || sources.length == 0) return null;
        Set<String> matches = new HashSet<>();
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
                                if (isDeviceAppProviderQuery(type)) {
                                    matches.add(toClassName(type.getType()));
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException | LinkageError unreadable) {
            return null;
        }
        return matches.size() == 1 ? new Symbols(matches.iterator().next(), "DEX_SCAN") : null;
    }

    private static boolean isDeviceAppProviderQuery(ClassDef type) {
        boolean hasAll = false;
        boolean hasById = false;
        boolean hasTag = false;
        for (Method m : type.getMethods()) {
            if (AccessFlags.STATIC.isSet(m.getAccessFlags())) continue;
            String name = m.getName();
            if ("queryAllDevices".equals(name)
                    && D_LIST.equals(m.getReturnType())
                    && m.getParameterTypes().size() == 1
                    && D_STRING.contentEquals(m.getParameterTypes().get(0))) {
                hasAll = true;
            } else if ("queryDeviceById".equals(name)
                    && D_STRING.equals(m.getReturnType())
                    && m.getParameterTypes().size() == 2
                    && D_STRING.contentEquals(m.getParameterTypes().get(0))
                    && D_STRING.contentEquals(m.getParameterTypes().get(1))) {
                hasById = true;
            }
            if (m.getImplementation() != null) {
                for (Instruction insn : m.getImplementation().getInstructions()) {
                    if (insn instanceof ReferenceInstruction ref && ref.getReference() instanceof StringReference str) {
                        if ("DeviceAppProviderQuery".equals(str.getString())) {
                            hasTag = true;
                        }
                    }
                }
            }
        }
        return hasAll && hasById && hasTag;
    }

    private static String toClassName(String descriptor) {
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private MyDevicesSymbolResolver() {}
}
