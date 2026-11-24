package io.github.watchlink.xposed;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
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
 * Structurally resolves Huawei Health :DaemonService symbols (NotificationPushListener, its inner
 * BroadcastReceiver, Notification Picture encoder helper, and MMKV SharedPreferenceManager) across
 * ProGuard shifts. Scans once per APK fingerprint and caches via ApkSymbolCache.
 */
final class HealthDaemonSymbolResolver {
    static final String SCHEMA = "health-daemon-v1";
    static final String NOTIFICATION_PUSH_LISTENER = "com.huawei.bone.ui.setting.NotificationPushListener";
    static final String SHARED_PREF_MANAGER = "health.compact.a.SharedPreferenceManager";

    private static final String D_NPL = "Lcom/huawei/bone/ui/setting/NotificationPushListener;";
    private static final String D_NPL_INNER = "Lcom/huawei/bone/ui/setting/NotificationPushListener$";
    private static final String D_BROADCAST_RECEIVER = "Landroid/content/BroadcastReceiver;";
    private static final String D_SHARED_PREF = "Lhealth/compact/a/SharedPreferenceManager;";
    private static final String D_MMKV = "Lcom/tencent/mmkv/MMKV;";
    private static final String D_BUNDLE = "Landroid/os/Bundle;";
    private static final String D_STRING = "Ljava/lang/String;";
    private static final String D_NOTIFICATION = "Landroid/app/Notification;";
    private static final String D_CONTEXT = "Landroid/content/Context;";
    private static final String D_BITMAP = "Landroid/graphics/Bitmap;";
    private static final String D_INTENT = "Landroid/content/Intent;";
    private static final String D_SBN = "Landroid/service/notification/StatusBarNotification;";
    private static final String D_MSG_STYLE_MSG = "Landroidx/core/app/NotificationCompat$MessagingStyle$Message;";
    private static final Pattern DEX = Pattern.compile("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");

    record Symbols(
            String pictureStyleMethod,
            String pictureHelperClass,
            String pictureNextFileMethod,
            String pictureSaveBitmapMethod,
            String voipGateMethod,
            String voipReceiverClass,
            String voipRejectIntentMethod,
            String voipRejectSbnMethod,
            String spGetBoolMethod,
            String spGetIntMethod,
            String source) {

        boolean pictureReady() {
            return notEmpty(pictureStyleMethod)
                    && notEmpty(pictureHelperClass)
                    && notEmpty(pictureNextFileMethod)
                    && notEmpty(pictureSaveBitmapMethod)
                    && notEmpty(spGetBoolMethod)
                    && notEmpty(spGetIntMethod);
        }

        boolean voipReady() {
            return notEmpty(voipGateMethod)
                    && notEmpty(voipReceiverClass)
                    && notEmpty(voipRejectIntentMethod)
                    && notEmpty(spGetBoolMethod);
        }

        boolean anyReady() {
            return pictureReady() || voipReady();
        }

        Map<String, String> toMap() {
            Map<String, String> map = new HashMap<>();
            map.put("pictureStyleMethod", empty(pictureStyleMethod));
            map.put("pictureHelperClass", empty(pictureHelperClass));
            map.put("pictureNextFileMethod", empty(pictureNextFileMethod));
            map.put("pictureSaveBitmapMethod", empty(pictureSaveBitmapMethod));
            map.put("voipGateMethod", empty(voipGateMethod));
            map.put("voipReceiverClass", empty(voipReceiverClass));
            map.put("voipRejectIntentMethod", empty(voipRejectIntentMethod));
            map.put("voipRejectSbnMethod", empty(voipRejectSbnMethod));
            map.put("spGetBoolMethod", empty(spGetBoolMethod));
            map.put("spGetIntMethod", empty(spGetIntMethod));
            return map;
        }

        static Symbols fromCache(Map<String, String> map) {
            if (map == null || map.isEmpty()) return null;
            Symbols s = new Symbols(
                    map.getOrDefault("pictureStyleMethod", ""),
                    map.getOrDefault("pictureHelperClass", ""),
                    map.getOrDefault("pictureNextFileMethod", ""),
                    map.getOrDefault("pictureSaveBitmapMethod", ""),
                    map.getOrDefault("voipGateMethod", ""),
                    map.getOrDefault("voipReceiverClass", ""),
                    map.getOrDefault("voipRejectIntentMethod", ""),
                    map.getOrDefault("voipRejectSbnMethod", ""),
                    map.getOrDefault("spGetBoolMethod", ""),
                    map.getOrDefault("spGetIntMethod", ""),
                    "CACHE");
            return s.anyReady() ? s : null;
        }

        private static boolean notEmpty(String value) { return value != null && !value.isEmpty(); }
        private static String empty(String value) { return value == null ? "" : value; }
    }

    static Symbols resolve(ApkSymbolCache cache, String... sources) {
        String fp = cache == null ? null : ApkSymbolCache.fingerprint(SCHEMA, sources);
        if (cache != null) {
            Symbols cached = Symbols.fromCache(cache.read(fp));
            if (cached != null) return cached;
        }
        Symbols scanned = scanApk(sources);
        if (scanned != null && scanned.anyReady() && cache != null) {
            cache.write(fp, scanned.toMap());
        }
        return scanned;
    }

    static Symbols scanApk(String... sources) {
        if (sources == null || sources.length == 0) return null;
        Set<String> pictureStyleMethods = new HashSet<>();
        Set<String> pictureHelperDescs = new HashSet<>();
        Map<String, Set<String>> helperNextFiles = new HashMap<>();
        Map<String, Set<String>> helperSaveBitmaps = new HashMap<>();
        Set<String> voipGateMethods = new HashSet<>();
        Set<String> voipReceiverClasses = new HashSet<>();
        Set<String> voipRejectIntentMethods = new HashSet<>();
        Set<String> voipRejectSbnMethods = new HashSet<>();
        Set<String> spGetBoolMethods = new HashSet<>();
        Set<String> spGetIntMethods = new HashSet<>();

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
                                String desc = type.getType();
                                if (D_NPL.equals(desc)) {
                                    inspectListener(type, pictureStyleMethods, pictureHelperDescs, voipGateMethods);
                                } else if (desc.startsWith(D_NPL_INNER) && D_BROADCAST_RECEIVER.equals(type.getSuperclass())) {
                                    inspectReceiver(type, voipReceiverClasses, voipRejectIntentMethods, voipRejectSbnMethods);
                                } else if (D_SHARED_PREF.equals(desc)) {
                                    inspectSharedPrefs(type, spGetBoolMethods, spGetIntMethods);
                                } else {
                                    inspectPictureHelperCandidate(type, helperNextFiles, helperSaveBitmaps);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException | LinkageError unreadable) {
            return null;
        }

        String helperDesc = unique(pictureHelperDescs);
        String helperClass = helperDesc.isEmpty() ? "" : toClassName(helperDesc);
        String nextFile = helperDesc.isEmpty() ? "" : unique(helperNextFiles.get(helperDesc));
        String saveBitmap = helperDesc.isEmpty() ? "" : unique(helperSaveBitmaps.get(helperDesc));
        return new Symbols(
                unique(pictureStyleMethods),
                helperClass,
                nextFile,
                saveBitmap,
                unique(voipGateMethods),
                unique(voipReceiverClasses),
                unique(voipRejectIntentMethods),
                unique(voipRejectSbnMethods),
                unique(spGetBoolMethods),
                unique(spGetIntMethods),
                "DEX_SCAN");
    }

    private static void inspectListener(ClassDef type, Set<String> pictureStyleMethods,
                                        Set<String> pictureHelperDescs, Set<String> voipGateMethods) {
        for (Method m : type.getMethods()) {
            if (AccessFlags.STATIC.isSet(m.getAccessFlags()) || m.getImplementation() == null) continue;
            List<? extends CharSequence> params = m.getParameterTypes();
            if ("V".equals(m.getReturnType())
                    && params.size() == 4
                    && D_BUNDLE.contentEquals(params.get(0))
                    && D_STRING.contentEquals(params.get(1))
                    && D_NOTIFICATION.contentEquals(params.get(2))
                    && D_STRING.contentEquals(params.get(3))) {
                boolean extractsStyle = false;
                String helperOwner = null;
                for (Instruction insn : m.getImplementation().getInstructions()) {
                    if (insn instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference mr) {
                        if ("extractStyleFromNotification".equals(mr.getName())) {
                            extractsStyle = true;
                        } else if ("V".equals(mr.getReturnType())
                                && mr.getParameterTypes().size() == 3
                                && D_CONTEXT.contentEquals(mr.getParameterTypes().get(0))
                                && D_BUNDLE.contentEquals(mr.getParameterTypes().get(1))
                                && D_MSG_STYLE_MSG.contentEquals(mr.getParameterTypes().get(2))) {
                            helperOwner = mr.getDefiningClass();
                        }
                    }
                }
                if (extractsStyle && helperOwner != null) {
                    pictureStyleMethods.add(m.getName());
                    pictureHelperDescs.add(helperOwner);
                }
            } else if ("Z".equals(m.getReturnType())
                    && params.size() == 3
                    && D_BUNDLE.contentEquals(params.get(0))
                    && D_NOTIFICATION.contentEquals(params.get(1))
                    && "Z".contentEquals(params.get(2))) {
                if (containsAllStrings(m, "SUPPORT_NOTIFY_VOIP_TYPE", "this phone is not HuaweiSeaOverlayPhone.")) {
                    voipGateMethods.add(m.getName());
                }
            }
        }
    }

    private static void inspectReceiver(ClassDef type, Set<String> receiverClasses,
                                        Set<String> intentMethods, Set<String> sbnMethods) {
        boolean matched = false;
        for (Method m : type.getMethods()) {
            if (AccessFlags.STATIC.isSet(m.getAccessFlags()) || m.getImplementation() == null) continue;
            List<? extends CharSequence> params = m.getParameterTypes();
            if ("V".equals(m.getReturnType()) && params.size() == 1 && D_INTENT.contentEquals(params.get(0))) {
                if (containsAllStrings(m, "key_replay_destination", "sendVoipAction DaemonService get content: ")) {
                    intentMethods.add(m.getName());
                    matched = true;
                }
            } else if ("V".equals(m.getReturnType()) && params.size() == 2
                    && D_SBN.contentEquals(params.get(0)) && D_STRING.contentEquals(params.get(1))) {
                if (containsAllStrings(m, "deviceVOIPNotification", "VOIP ")) {
                    sbnMethods.add(m.getName());
                    matched = true;
                }
            }
        }
        if (matched) {
            receiverClasses.add(toClassName(type.getType()));
        }
    }

    private static void inspectSharedPrefs(ClassDef type, Set<String> boolMethods, Set<String> intMethods) {
        for (Method m : type.getDirectMethods()) {
            int flags = m.getAccessFlags();
            if (!AccessFlags.PUBLIC.isSet(flags) || !AccessFlags.STATIC.isSet(flags)
                    || m.getImplementation() == null) continue;
            List<? extends CharSequence> params = m.getParameterTypes();
            if (params.size() != 3 || !D_STRING.contentEquals(params.get(0))
                    || !D_STRING.contentEquals(params.get(1))) continue;
            if ("Z".equals(m.getReturnType()) && "Z".contentEquals(params.get(2))
                    && invokes(m, D_MMKV, "decodeBool")) {
                boolMethods.add(m.getName());
            } else if ("I".equals(m.getReturnType()) && "I".contentEquals(params.get(2))
                    && invokes(m, D_MMKV, "decodeInt")) {
                intMethods.add(m.getName());
            }
        }
    }

    private static void inspectPictureHelperCandidate(ClassDef type,
                                                      Map<String, Set<String>> helperNextFiles,
                                                      Map<String, Set<String>> helperSaveBitmaps) {
        for (Method m : type.getDirectMethods()) {
            if (!AccessFlags.STATIC.isSet(m.getAccessFlags()) || m.getImplementation() == null) continue;
            List<? extends CharSequence> params = m.getParameterTypes();
            if (D_STRING.equals(m.getReturnType()) && params.isEmpty()
                    && containsAllStrings(m, "NOTIFY_PICTURE_NUMBER", "pic", ".bin")) {
                helperNextFiles.computeIfAbsent(type.getType(), k -> new HashSet<>()).add(m.getName());
            } else if ("V".equals(m.getReturnType()) && params.size() == 4
                    && D_CONTEXT.contentEquals(params.get(0))
                    && D_BITMAP.contentEquals(params.get(1))
                    && D_STRING.contentEquals(params.get(2))
                    && D_BUNDLE.contentEquals(params.get(3))
                    && containsAllStrings(m, "/7/notifyPic/", "imageName")) {
                helperSaveBitmaps.computeIfAbsent(type.getType(), k -> new HashSet<>()).add(m.getName());
            }
        }
    }

    private static boolean containsAllStrings(Method method, String... needles) {
        if (method.getImplementation() == null) return false;
        int matchedMask = 0;
        int targetMask = (1 << needles.length) - 1;
        for (Instruction insn : method.getImplementation().getInstructions()) {
            if (insn instanceof ReferenceInstruction ref && ref.getReference() instanceof StringReference str) {
                String value = str.getString();
                for (int i = 0; i < needles.length; i++) {
                    if (needles[i].equals(value)) {
                        matchedMask |= (1 << i);
                        if (matchedMask == targetMask) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean invokes(Method method, String owner, String name) {
        if (method.getImplementation() == null) return false;
        for (Instruction insn : method.getImplementation().getInstructions()) {
            if (insn instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference mr) {
                if (owner.equals(mr.getDefiningClass()) && name.equals(mr.getName())) return true;
            }
        }
        return false;
    }

    private static String unique(Set<String> items) {
        return items != null && items.size() == 1 ? items.iterator().next() : "";
    }

    private static String toClassName(String descriptor) {
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private HealthDaemonSymbolResolver() {}
}
