package io.github.watchlink.xposed;

import android.os.Build;
import androidx.annotation.RequiresApi;
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
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
 * Resolves obfuscated ColorOS Pantanal UMS card push/remove strategy symbols dynamically across
 * UMS updates using unobfuscated {@code CardCreator} and {@code CardIdentify} anchors.
 */
final class UmsSymbolResolver {
    static final String SCHEMA = "ums-v1";
    static final String CARD_IDENTIFY = "com.oplus.pantanal.ums.cardservice.valueobject.CardIdentify";
    private static final String FAST_STRATEGY_CLASS = "com.oplus.pantanal.ums.cardservice.valueobject.strategy.g";
    private static final String D_CARD_CREATOR = "Lcom/oplus/pantanal/ums/cardservice/domain/CardCreator;";
    private static final String D_CARD_IDENTIFY = "Lcom/oplus/pantanal/ums/cardservice/valueobject/CardIdentify;";
    private static final String D_STRATEGY_PREFIX = "Lcom/oplus/pantanal/ums/cardservice/valueobject/strategy/";
    private static final Pattern DEX = Pattern.compile("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");

    record Symbols(
            String strategyClass,
            String pushMethod,
            String removeMethod,
            String source) {

        boolean complete() {
            return notEmpty(strategyClass) && notEmpty(pushMethod) && notEmpty(removeMethod);
        }

        Map<String, String> toMap() {
            Map<String, String> map = new HashMap<>();
            map.put("strategyClass", empty(strategyClass));
            map.put("pushMethod", empty(pushMethod));
            map.put("removeMethod", empty(removeMethod));
            return map;
        }

        static Symbols fromCache(Map<String, String> map) {
            if (map == null || map.isEmpty()) return null;
            String cls = map.getOrDefault("strategyClass", "");
            String push = map.getOrDefault("pushMethod", "");
            String rem = map.getOrDefault("removeMethod", "");
            if (cls.isEmpty() || push.isEmpty() || rem.isEmpty()) return null;
            return new Symbols(cls, push, rem, "CACHE");
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
        if (fast != null && fast.complete()) {
            if (cache != null) cache.write(fp, fast.toMap());
            return fast;
        }
        Symbols scanned = scanApk(sources);
        if (scanned != null && scanned.complete() && cache != null) {
            cache.write(fp, scanned.toMap());
        }
        return scanned != null ? scanned : fast;
    }

    @RequiresApi(34)
    private static Symbols tryFastPath(AlarmObserverInstaller installer) {
        if (installer == null) return null;
        try {
            Class<?> strategy = installer.target(FAST_STRATEGY_CLASS);
            Class<?> cardId = installer.target(CARD_IDENTIFY);
            String push = "";
            String remove = "";
            for (java.lang.reflect.Method m : strategy.getDeclaredMethods()) {
                if (m.isSynthetic() || java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != void.class) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[0] == cardId && p[1] == byte[].class) {
                    push = m.getName();
                } else if (p.length == 1 && p[0] == cardId) {
                    remove = m.getName();
                }
            }
            if (push.isEmpty() || remove.isEmpty()) return null;
            return new Symbols(strategy.getName(), push, remove, "FAST_PATH");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError miss) {
            return null;
        }
    }

    static Symbols scanApk(String... sources) {
        if (sources == null || sources.length == 0) return null;
        Set<String> matches = new HashSet<>();
        Map<String, String[]> methodsByClass = new HashMap<>();
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
                                inspectClass(type, matches, methodsByClass);
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException | LinkageError unreadable) {
            return null;
        }
        if (matches.size() != 1) return null;
        String cls = matches.iterator().next();
        String[] pair = methodsByClass.get(cls);
        return pair == null ? null : new Symbols(cls, pair[0], pair[1], "DEX_SCAN");
    }

    private static void inspectClass(ClassDef type, Set<String> matches, Map<String, String[]> methodsByClass) {
        String desc = type.getType();
        if (!desc.startsWith(D_STRATEGY_PREFIX) || AccessFlags.ABSTRACT.isSet(type.getAccessFlags())) return;
        boolean hasCardCreatorCtor = false;
        for (Method m : type.getDirectMethods()) {
            if ("<init>".equals(m.getName()) && m.getParameterTypes().size() == 1
                    && D_CARD_CREATOR.contentEquals(m.getParameterTypes().get(0))) {
                hasCardCreatorCtor = true;
                break;
            }
        }
        if (!hasCardCreatorCtor) return;

        Set<String> pushMethods = new HashSet<>();
        Set<String> removeMethods = new HashSet<>();
        for (Method m : type.getVirtualMethods()) {
            int flags = m.getAccessFlags();
            if (AccessFlags.SYNTHETIC.isSet(flags) || !"V".equals(m.getReturnType())) continue;
            List<? extends CharSequence> params = m.getParameterTypes();
            if (params.size() == 3
                    && D_CARD_IDENTIFY.contentEquals(params.get(0))
                    && "[B".contentEquals(params.get(1))) {
                pushMethods.add(m.getName());
            } else if (params.size() == 1 && D_CARD_IDENTIFY.contentEquals(params.get(0))) {
                removeMethods.add(m.getName());
            }
        }
        if (pushMethods.size() == 1 && removeMethods.size() == 1) {
            String className = desc.substring(1, desc.length() - 1).replace('/', '.');
            matches.add(className);
            methodsByClass.put(className, new String[]{
                    pushMethods.iterator().next(),
                    removeMethods.iterator().next()
            });
        }
    }

    private UmsSymbolResolver() {}
}
