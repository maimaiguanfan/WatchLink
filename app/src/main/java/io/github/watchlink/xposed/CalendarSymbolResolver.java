package io.github.watchlink.xposed;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.ArrayList;
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
 * Resolves obfuscated ColorOS Calendar strong-reminder (ForceAlert) symbols structurally from
 * unobfuscated anchor classes (OPlusCalendarApplication, ForceAlertService, ForceAlertLockScreen)
 * while verifying lockscreen, floating top-window, and listener dismiss/snooze action contracts.
 */
final class CalendarSymbolResolver {
    static final String SCHEMA = "calendar-v1";
    static final String APPLICATION = "com.coloros.calendar.app.app.OPlusCalendarApplication";
    static final String FORCE_ALERT_SERVICE = "com.android.calendar.alerts.ForceAlertService";
    static final String LOCK_SCREEN = "com.coloros.calendar.business.forcealertbiz.component.ForceAlertLockScreen";
    static final String FLUID_MANAGER = "com.android.calendar.seedling.notificationfluidcard.FluidSeedingManager";
    static final String FLUID_RINGTONE = "com.android.calendar.seedling.notificationfluidcard.RingtoneAndVibrateManager";

    private static final String D_APPLICATION = desc(APPLICATION);
    private static final String D_FORCE_ALERT_SERVICE = desc(FORCE_ALERT_SERVICE);
    private static final String D_LOCK_SCREEN = desc(LOCK_SCREEN);
    private static final String D_NOTIFICATION_INFO = "Lcom/android/calendar/alerts/INotificationInfo;";
    private static final String D_CONTEXT = "Landroid/content/Context;";
    private static final String D_APP = "Landroid/app/Application;";
    private static final String D_STRING = "Ljava/lang/String;";
    private static final String D_URI = "Landroid/net/Uri;";
    private static final Pattern DEX = Pattern.compile("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");

    record Symbols(
            String applicationGetter,
            String controllerClass,
            String showUiMethod,
            String stopRingMethod,
            String stopRingNoArgMethod,
            String dataBeanClass,
            String titleGetter,
            String targetIdGetter,
            String fluidCheckInterface,
            String listenerInterface,
            String listenerDismissMethod,
            String listenerSnoozeMethod,
            String lockScreenDismissMethod,
            String lockScreenSnoozeMethod,
            String topWindowClass,
            String topWindowInstanceField,
            String topWindowShowingField,
            String topWindowDismissMethod,
            String topWindowSnoozeMethod,
            boolean actionContractVerified,
            String source) {

        Map<String, String> toMap() {
            Map<String, String> map = new HashMap<>();
            map.put("applicationGetter", applicationGetter);
            map.put("controllerClass", controllerClass);
            map.put("showUiMethod", showUiMethod);
            map.put("stopRingMethod", stopRingMethod);
            map.put("stopRingNoArgMethod", stopRingNoArgMethod == null ? "" : stopRingNoArgMethod);
            map.put("dataBeanClass", dataBeanClass);
            map.put("titleGetter", titleGetter);
            map.put("targetIdGetter", targetIdGetter);
            map.put("fluidCheckInterface", fluidCheckInterface);
            map.put("listenerInterface", listenerInterface);
            map.put("listenerDismissMethod", listenerDismissMethod == null ? "" : listenerDismissMethod);
            map.put("listenerSnoozeMethod", listenerSnoozeMethod == null ? "" : listenerSnoozeMethod);
            map.put("lockScreenDismissMethod", lockScreenDismissMethod == null ? "" : lockScreenDismissMethod);
            map.put("lockScreenSnoozeMethod", lockScreenSnoozeMethod == null ? "" : lockScreenSnoozeMethod);
            map.put("topWindowClass", topWindowClass == null ? "" : topWindowClass);
            map.put("topWindowInstanceField", topWindowInstanceField == null ? "" : topWindowInstanceField);
            map.put("topWindowShowingField", topWindowShowingField == null ? "" : topWindowShowingField);
            map.put("topWindowDismissMethod", topWindowDismissMethod == null ? "" : topWindowDismissMethod);
            map.put("topWindowSnoozeMethod", topWindowSnoozeMethod == null ? "" : topWindowSnoozeMethod);
            map.put("actionContractVerified", Boolean.toString(actionContractVerified));
            return map;
        }

        static Symbols fromCache(Map<String, String> map) {
            if (map == null || map.isEmpty()) return null;
            String appGet = req(map, "applicationGetter");
            String controller = req(map, "controllerClass");
            String showUi = req(map, "showUiMethod");
            String stopRing = req(map, "stopRingMethod");
            String dataBean = req(map, "dataBeanClass");
            String titleGet = req(map, "titleGetter");
            String targetIdGet = req(map, "targetIdGetter");
            String fluidCheck = req(map, "fluidCheckInterface");
            String listener = req(map, "listenerInterface");
            if (appGet == null || controller == null || showUi == null || stopRing == null
                    || dataBean == null || titleGet == null || targetIdGet == null
                    || fluidCheck == null || listener == null) {
                return null;
            }
            String stopNoArg = map.getOrDefault("stopRingNoArgMethod", "");
            String lDismiss = map.getOrDefault("listenerDismissMethod", "");
            String lSnooze = map.getOrDefault("listenerSnoozeMethod", "");
            String lockDismiss = map.getOrDefault("lockScreenDismissMethod", "");
            String lockSnooze = map.getOrDefault("lockScreenSnoozeMethod", "");
            String topClass = map.getOrDefault("topWindowClass", "");
            String topInst = map.getOrDefault("topWindowInstanceField", "");
            String topShow = map.getOrDefault("topWindowShowingField", "");
            String topDismiss = map.getOrDefault("topWindowDismissMethod", "");
            String topSnooze = map.getOrDefault("topWindowSnoozeMethod", "");
            boolean verified = "true".equals(map.get("actionContractVerified"))
                    && !stopNoArg.isEmpty() && !lDismiss.isEmpty() && !lSnooze.isEmpty()
                    && !lockDismiss.isEmpty() && !lockSnooze.isEmpty()
                    && !topClass.isEmpty() && !topInst.isEmpty() && !topShow.isEmpty()
                    && !topDismiss.isEmpty() && !topSnooze.isEmpty();
            return new Symbols(appGet, controller, showUi, stopRing,
                    verified ? stopNoArg : "",
                    dataBean, titleGet, targetIdGet, fluidCheck, listener,
                    verified ? lDismiss : "", verified ? lSnooze : "",
                    verified ? lockDismiss : "", verified ? lockSnooze : "",
                    verified ? topClass : "", verified ? topInst : "", verified ? topShow : "",
                    verified ? topDismiss : "", verified ? topSnooze : "",
                    verified, "CACHE");
        }

        private static String req(Map<String, String> map, String key) {
            String value = map.get(key);
            return value == null || value.isEmpty() ? null : value;
        }
    }

    static Symbols resolve(ApkSymbolCache cache, String... sources) {
        String fp = cache == null ? null : ApkSymbolCache.fingerprint(SCHEMA, sources);
        if (cache != null) {
            Symbols cached = Symbols.fromCache(cache.read(fp));
            if (cached != null) return cached;
        }
        Symbols scanned = scanApk(sources);
        if (scanned != null && cache != null) {
            cache.write(fp, scanned.toMap());
        }
        return scanned;
    }

    static Symbols scanApk(String... sources) {
        if (sources == null || sources.length == 0) return null;
        try {
            ClassDef application = null, service = null, lockScreen = null;
            List<DexBackedDexFile> dexes = new ArrayList<>();
            for (String source : sources) {
                if (source == null) continue;
                try (ZipFile archive = new ZipFile(source)) {
                    Enumeration<? extends ZipEntry> entries = archive.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (!DEX.matcher(entry.getName()).matches()) continue;
                        try (BufferedInputStream in = new BufferedInputStream(archive.getInputStream(entry))) {
                            DexBackedDexFile dex = DexBackedDexFile.fromInputStream(null, in);
                            boolean keep = false;
                            for (ClassDef type : dex.getClasses()) {
                                String d = type.getType();
                                if (D_APPLICATION.equals(d)) { application = type; keep = true; }
                                else if (D_FORCE_ALERT_SERVICE.equals(d)) { service = type; keep = true; }
                                else if (D_LOCK_SCREEN.equals(d)) { lockScreen = type; keep = true; }
                            }
                            if (keep) dexes.add(dex);
                        }
                    }
                }
            }
            if (application == null || service == null || lockScreen == null) return null;

            String appGetter = findApplicationGetter(application);
            if (appGetter == null) return null;

            // 1. ForceAlertService -> controllerClass.showUiMethod(Context, dataBean, fluidCheck, listener)Z
            MethodReference showCall = findShowForceAlertCall(service);
            if (showCall == null) return null;
            String controllerDesc = showCall.getDefiningClass();
            String showUiMethod = showCall.getName();
            String dataBeanDesc = showCall.getParameterTypes().get(1).toString();
            String fluidCheckDesc = showCall.getParameterTypes().get(2).toString();
            String listenerDesc = showCall.getParameterTypes().get(3).toString();

            ClassDef controllerDef = findClass(dexes, sources, controllerDesc);
            ClassDef dataBeanDef = findClass(dexes, sources, dataBeanDesc);
            if (controllerDef == null || dataBeanDef == null) return null;

            // 2. Verify showUiMethod body on controller & find stopRingMethod(Z)V, stopRingNoArgMethod()V, topWindowClass
            Method showMethodDef = findDirectMethod(controllerDef, showUiMethod,
                    List.of(D_CONTEXT, dataBeanDesc, fluidCheckDesc, listenerDesc), "Z");
            if (showMethodDef == null || !verifyShowUiMethod(showMethodDef)) return null;

            String stopRingMethod = findStopRingMethod(controllerDef);
            if (stopRingMethod == null) return null;
            String stopRingNoArgMethod = findStopRingNoArgMethod(controllerDef, stopRingMethod);

            // 3. DataBean titleGetter()String and targetIdGetter()J from 4-arg constructor (String, String, Uri, long)V
            DataBeanGetters beanGetters = findDataBeanGetters(dataBeanDef);
            if (beanGetters == null) return null;

            // 4. Verify LockScreen stop/snooze + listener dismiss/snooze + TopWindow dismiss/snooze contracts
            String topWindowDesc = findTopWindowClass(showMethodDef, dataBeanDesc, listenerDesc);
            LockActions lockActions = stopRingNoArgMethod == null ? null
                    : findLockScreenActions(lockScreen, controllerDesc, stopRingNoArgMethod, listenerDesc);

            String listenerDismiss = "", listenerSnooze = "";
            String lockDismiss = "", lockSnooze = "";
            String topClass = "", topInst = "", topShow = "", topDismiss = "", topSnooze = "";
            boolean contractVerified = false;

            if (lockActions != null && topWindowDesc != null) {
                ClassDef topWindowDef = findClass(dexes, sources, topWindowDesc);
                if (topWindowDef != null) {
                    TopWindowContract top = findTopWindowContract(
                            topWindowDef, listenerDesc, lockActions.listenerDismiss, lockActions.listenerSnooze);
                    if (top != null) {
                        listenerDismiss = lockActions.listenerDismiss;
                        listenerSnooze = lockActions.listenerSnooze;
                        lockDismiss = lockActions.lockDismiss;
                        lockSnooze = lockActions.lockSnooze;
                        topClass = toClassName(topWindowDesc);
                        topInst = top.instanceField;
                        topShow = top.showingField;
                        topDismiss = top.dismissMethod;
                        topSnooze = top.snoozeMethod;
                        contractVerified = true;
                    }
                }
            }

            return new Symbols(
                    appGetter,
                    toClassName(controllerDesc),
                    showUiMethod,
                    stopRingMethod,
                    contractVerified ? stopRingNoArgMethod : "",
                    toClassName(dataBeanDesc),
                    beanGetters.titleGetter,
                    beanGetters.targetIdGetter,
                    toClassName(fluidCheckDesc),
                    toClassName(listenerDesc),
                    listenerDismiss,
                    listenerSnooze,
                    lockDismiss,
                    lockSnooze,
                    topClass,
                    topInst,
                    topShow,
                    topDismiss,
                    topSnooze,
                    contractVerified,
                    "DEX_SCAN");
        } catch (IOException | RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    private record DataBeanGetters(String titleGetter, String targetIdGetter) {}
    private record LockActions(String lockDismiss, String lockSnooze, String listenerDismiss, String listenerSnooze) {}
    private record TopWindowContract(String instanceField, String showingField, String dismissMethod, String snoozeMethod) {}

    private static String findApplicationGetter(ClassDef application) {
        String match = null;
        for (Method m : application.getDirectMethods()) {
            int flags = m.getAccessFlags();
            if (!AccessFlags.PUBLIC.isSet(flags) || !AccessFlags.STATIC.isSet(flags)) continue;
            if (!m.getParameterTypes().isEmpty() || !D_APP.equals(m.getReturnType())) continue;
            if (match != null) return null;
            match = m.getName();
        }
        return match;
    }

    private static MethodReference findShowForceAlertCall(ClassDef service) {
        MethodReference found = null;
        for (Method m : service.getVirtualMethods()) {
            List<? extends CharSequence> params = m.getParameterTypes();
            if (params.size() != 1 || !D_NOTIFICATION_INFO.contentEquals(params.get(0))
                    || !"V".equals(m.getReturnType()) || m.getImplementation() == null) continue;
            for (Instruction insn : m.getImplementation().getInstructions()) {
                if (insn.getOpcode() != Opcode.INVOKE_STATIC && insn.getOpcode() != Opcode.INVOKE_STATIC_RANGE) continue;
                if (!(insn instanceof ReferenceInstruction ref)
                        || !(ref.getReference() instanceof MethodReference call)) continue;
                List<? extends CharSequence> p = call.getParameterTypes();
                if (p.size() == 4 && "Z".equals(call.getReturnType())
                        && D_CONTEXT.contentEquals(p.get(0))
                        && isObjectDesc(p.get(1)) && isObjectDesc(p.get(2)) && isObjectDesc(p.get(3))) {
                    if (found != null && !sameMethod(found, call)) return null;
                    found = call;
                }
            }
        }
        return found;
    }

    private static boolean verifyShowUiMethod(Method showMethod) {
        MethodImplementation impl = showMethod.getImplementation();
        if (impl == null) return false;
        boolean hasTag = false, startsLock = false;
        for (Instruction insn : impl.getInstructions()) {
            if (insn instanceof ReferenceInstruction ref) {
                if (ref.getReference() instanceof StringReference str && "showForceAlertUI".equals(str.getString())) {
                    hasTag = true;
                } else if (ref.getReference() instanceof MethodReference call
                        && D_LOCK_SCREEN.equals(call.getDefiningClass())
                        && call.getParameterTypes().size() == 4) {
                    startsLock = true;
                }
            }
        }
        return hasTag && startsLock;
    }

    private static String findStopRingMethod(ClassDef controller) {
        String match = null;
        for (Method m : controller.getDirectMethods()) {
            int flags = m.getAccessFlags();
            if (!AccessFlags.PUBLIC.isSet(flags) || !AccessFlags.STATIC.isSet(flags)) continue;
            List<? extends CharSequence> params = m.getParameterTypes();
            if (params.size() != 1 || !"Z".contentEquals(params.get(0)) || !"V".equals(m.getReturnType())
                    || m.getImplementation() == null) continue;
            boolean hasLog = false, stopsPlayer = false, cancelsVibrator = false;
            for (Instruction insn : m.getImplementation().getInstructions()) {
                if (insn instanceof ReferenceInstruction ref) {
                    if (ref.getReference() instanceof StringReference str
                            && str.getString().contains("stopRingtoneAndVibrate")) {
                        hasLog = true;
                    } else if (ref.getReference() instanceof MethodReference call) {
                        if ("Landroid/media/MediaPlayer;".equals(call.getDefiningClass()) && "stop".equals(call.getName())) {
                            stopsPlayer = true;
                        } else if ("Landroid/os/Vibrator;".equals(call.getDefiningClass()) && "cancel".equals(call.getName())) {
                            cancelsVibrator = true;
                        }
                    }
                }
            }
            if (hasLog && stopsPlayer && cancelsVibrator) {
                if (match != null) return null;
                match = m.getName();
            }
        }
        return match;
    }

    private static String findStopRingNoArgMethod(ClassDef controller, String stopWithBool) {
        String match = null;
        for (Method m : controller.getDirectMethods()) {
            int flags = m.getAccessFlags();
            if (!AccessFlags.PUBLIC.isSet(flags) || !AccessFlags.STATIC.isSet(flags)) continue;
            if (!m.getParameterTypes().isEmpty() || !"V".equals(m.getReturnType())
                    || m.getImplementation() == null) continue;
            List<Instruction> insns = new ArrayList<>();
            for (Instruction i : m.getImplementation().getInstructions()) insns.add(i);
            if (insns.size() != 3) continue;
            if (insns.get(0) instanceof NarrowLiteralInstruction lit && lit.getNarrowLiteral() == 0
                    && insns.get(1) instanceof ReferenceInstruction ref
                    && ref.getReference() instanceof MethodReference call
                    && controller.getType().equals(call.getDefiningClass())
                    && stopWithBool.equals(call.getName())
                    && call.getParameterTypes().size() == 1 && "Z".contentEquals(call.getParameterTypes().get(0))
                    && insns.get(2).getOpcode() == Opcode.RETURN_VOID) {
                if (match != null) return null;
                match = m.getName();
            }
        }
        return match;
    }

    private static DataBeanGetters findDataBeanGetters(ClassDef dataBean) {
        Method ctor = findDirectMethod(dataBean, "<init>", List.of(D_STRING, D_STRING, D_URI, "J"), "V");
        if (ctor == null || ctor.getImplementation() == null) return null;
        MethodImplementation impl = ctor.getImplementation();
        int regCount = impl.getRegisterCount();
        // Parameter registers for instance method (this, String title, String time, Uri ringtone, long targetId):
        // p0 = regCount - 6 (this), p1 = regCount - 5 (title), p2 = regCount - 4, p3 = regCount - 3, p4/p5 = regCount - 2 (targetId)
        int titleReg = regCount - 5;
        int targetIdReg = regCount - 2;
        FieldReference titleField = null;
        FieldReference targetIdField = null;
        for (Instruction insn : impl.getInstructions()) {
            if (insn instanceof ReferenceInstruction ref && insn instanceof TwoRegisterInstruction two
                    && ref.getReference() instanceof FieldReference field
                    && dataBean.getType().equals(field.getDefiningClass())) {
                if (insn.getOpcode() == Opcode.IPUT_OBJECT && two.getRegisterA() == titleReg
                        && D_STRING.equals(field.getType())) {
                    titleField = field;
                } else if (insn.getOpcode() == Opcode.IPUT_WIDE && two.getRegisterA() == targetIdReg
                        && "J".equals(field.getType())) {
                    targetIdField = field;
                }
            }
        }
        if (titleField == null || targetIdField == null) return null;
        String titleGetter = findGetter(dataBean, titleField, D_STRING);
        String targetIdGetter = findGetter(dataBean, targetIdField, "J");
        if (titleGetter == null || targetIdGetter == null) return null;
        return new DataBeanGetters(titleGetter, targetIdGetter);
    }

    private static String findTopWindowClass(Method showMethod, String dataBeanDesc, String listenerDesc) {
        MethodImplementation impl = showMethod.getImplementation();
        if (impl == null) return null;
        String found = null;
        for (Instruction insn : impl.getInstructions()) {
            if (insn.getOpcode() != Opcode.INVOKE_STATIC && insn.getOpcode() != Opcode.INVOKE_STATIC_RANGE) continue;
            if (!(insn instanceof ReferenceInstruction ref)
                    || !(ref.getReference() instanceof MethodReference call)) continue;
            List<? extends CharSequence> p = call.getParameterTypes();
            if (p.size() == 3 && "V".equals(call.getReturnType())
                    && D_CONTEXT.contentEquals(p.get(0))
                    && dataBeanDesc.contentEquals(p.get(1))
                    && listenerDesc.contentEquals(p.get(2))) {
                if (found != null && !found.equals(call.getDefiningClass())) return null;
                found = call.getDefiningClass();
            }
        }
        return found;
    }

    private static LockActions findLockScreenActions(ClassDef lockScreen, String controllerDesc,
                                                     String stopNoArgMethod, String listenerDesc) {
        String lockDismiss = null, lockSnooze = null;
        String listenerDismiss = null, listenerSnooze = null;
        List<Method> all = new ArrayList<>();
        for (Method m : lockScreen.getDirectMethods()) all.add(m);
        for (Method m : lockScreen.getVirtualMethods()) all.add(m);
        for (Method m : all) {
            int flags = m.getAccessFlags();
            if (AccessFlags.STATIC.isSet(flags) || !m.getParameterTypes().isEmpty()
                    || !"V".equals(m.getReturnType()) || m.getImplementation() == null) continue;
            boolean hasStopLog = false, hasLaterLog = false, callsStopRing = false, callsFinish = false;
            Set<String> listenerCalls = new HashSet<>();
            for (Instruction insn : m.getImplementation().getInstructions()) {
                if (insn instanceof ReferenceInstruction ref) {
                    if (ref.getReference() instanceof StringReference str) {
                        String text = str.getString();
                        if ("execute stopAlert".equals(text)) hasStopLog = true;
                        else if ("execute alertLater".equals(text)) hasLaterLog = true;
                    } else if (ref.getReference() instanceof MethodReference call) {
                        if (listenerDesc.equals(call.getDefiningClass())
                                && call.getParameterTypes().isEmpty() && "V".equals(call.getReturnType())) {
                            listenerCalls.add(call.getName());
                        } else if (controllerDesc.equals(call.getDefiningClass())
                                && stopNoArgMethod.equals(call.getName()) && call.getParameterTypes().isEmpty()) {
                            callsStopRing = true;
                        } else if ("Landroid/app/Activity;".equals(call.getDefiningClass())
                                && "finish".equals(call.getName()) && call.getParameterTypes().isEmpty()) {
                            callsFinish = true;
                        }
                    }
                }
            }
            if (!callsStopRing || !callsFinish || listenerCalls.size() != 1) continue;
            String listenerMethod = listenerCalls.iterator().next();
            if (hasStopLog && !hasLaterLog) {
                if (lockDismiss != null) return null;
                lockDismiss = m.getName();
                listenerDismiss = listenerMethod;
            } else if (hasLaterLog && !hasStopLog) {
                if (lockSnooze != null) return null;
                lockSnooze = m.getName();
                listenerSnooze = listenerMethod;
            }
        }
        if (lockDismiss == null || lockSnooze == null || listenerDismiss == null || listenerSnooze == null
                || listenerDismiss.equals(listenerSnooze)) {
            return null;
        }
        return new LockActions(lockDismiss, lockSnooze, listenerDismiss, listenerSnooze);
    }

    private static TopWindowContract findTopWindowContract(ClassDef topWindow, String listenerDesc,
                                                           String listenerDismiss, String listenerSnooze) {
        String instanceField = null, showingField = null;
        for (Field f : topWindow.getStaticFields()) {
            if (topWindow.getType().equals(f.getType())) {
                if (instanceField != null) return null;
                instanceField = f.getName();
            } else if ("Z".equals(f.getType())) {
                if (showingField != null) return null;
                showingField = f.getName();
            }
        }
        if (instanceField == null || showingField == null) return null;

        String dismissMethod = null, snoozeMethod = null;
        for (Method m : topWindow.getVirtualMethods()) {
            int flags = m.getAccessFlags();
            if (AccessFlags.STATIC.isSet(flags) || !m.getParameterTypes().isEmpty()
                    || !"V".equals(m.getReturnType()) || m.getImplementation() == null) continue;
            Set<String> listenerCalls = new HashSet<>();
            for (Instruction insn : m.getImplementation().getInstructions()) {
                if (insn instanceof ReferenceInstruction ref
                        && ref.getReference() instanceof MethodReference call
                        && listenerDesc.equals(call.getDefiningClass())
                        && call.getParameterTypes().isEmpty() && "V".equals(call.getReturnType())) {
                    listenerCalls.add(call.getName());
                }
            }
            if (listenerCalls.size() != 1) continue;
            String target = listenerCalls.iterator().next();
            if (listenerDismiss.equals(target)) {
                if (dismissMethod != null) return null;
                dismissMethod = m.getName();
            } else if (listenerSnooze.equals(target)) {
                if (snoozeMethod != null) return null;
                snoozeMethod = m.getName();
            }
        }
        if (dismissMethod == null || snoozeMethod == null || dismissMethod.equals(snoozeMethod)) return null;
        return new TopWindowContract(instanceField, showingField, dismissMethod, snoozeMethod);
    }

    private static String findGetter(ClassDef type, FieldReference targetField, String returnType) {
        String match = null;
        for (Method m : type.getVirtualMethods()) {
            int flags = m.getAccessFlags();
            if (!AccessFlags.PUBLIC.isSet(flags) || AccessFlags.STATIC.isSet(flags)
                    || AccessFlags.SYNTHETIC.isSet(flags) || AccessFlags.BRIDGE.isSet(flags)) continue;
            if (!m.getParameterTypes().isEmpty() || !returnType.equals(m.getReturnType())) continue;
            MethodImplementation impl = m.getImplementation();
            if (impl == null) continue;
            List<Instruction> insns = new ArrayList<>();
            for (Instruction i : impl.getInstructions()) insns.add(i);
            if (insns.size() != 2) continue;
            if (insns.get(0) instanceof ReferenceInstruction ref
                    && isInstanceGet(insns.get(0).getOpcode())
                    && ref.getReference() instanceof FieldReference field
                    && sameField(field, targetField)
                    && isReturnValue(insns.get(1).getOpcode())) {
                if (match != null) return null;
                match = m.getName();
            }
        }
        return match;
    }

    private static Method findDirectMethod(ClassDef owner, String name, List<String> params, String returnType) {
        for (Method m : owner.getDirectMethods()) {
            if (!name.equals(m.getName()) || !returnType.equals(m.getReturnType())) continue;
            List<? extends CharSequence> actual = m.getParameterTypes();
            if (actual.size() != params.size()) continue;
            boolean ok = true;
            for (int i = 0; i < params.size(); i++) {
                if (!params.get(i).contentEquals(actual.get(i))) { ok = false; break; }
            }
            if (ok) return m;
        }
        return null;
    }

    private static ClassDef findClass(List<DexBackedDexFile> cachedDexes, String[] sources, String descriptor)
            throws IOException {
        for (DexBackedDexFile dex : cachedDexes) {
            for (ClassDef type : dex.getClasses()) {
                if (descriptor.equals(type.getType())) return type;
            }
        }
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
                            if (descriptor.equals(type.getType())) {
                                cachedDexes.add(dex);
                                return type;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean isObjectDesc(CharSequence desc) {
        return desc.length() >= 3 && desc.charAt(0) == 'L' && desc.charAt(desc.length() - 1) == ';';
    }

    private static boolean sameMethod(MethodReference a, MethodReference b) {
        return a.getDefiningClass().equals(b.getDefiningClass())
                && a.getName().equals(b.getName())
                && a.getReturnType().equals(b.getReturnType())
                && a.getParameterTypes().equals(b.getParameterTypes());
    }

    private static boolean sameField(FieldReference a, FieldReference b) {
        return a.getDefiningClass().equals(b.getDefiningClass())
                && a.getName().equals(b.getName())
                && a.getType().equals(b.getType());
    }

    private static boolean isInstanceGet(Opcode op) {
        return switch (op) {
            case IGET, IGET_WIDE, IGET_OBJECT, IGET_BOOLEAN, IGET_BYTE, IGET_CHAR, IGET_SHORT -> true;
            default -> false;
        };
    }

    private static boolean isReturnValue(Opcode op) {
        return op == Opcode.RETURN || op == Opcode.RETURN_WIDE || op == Opcode.RETURN_OBJECT;
    }

    private static String desc(String className) {
        return "L" + className.replace('.', '/') + ";";
    }

    private static String toClassName(String descriptor) {
        if (descriptor == null || descriptor.length() < 3 || !descriptor.startsWith("L") || !descriptor.endsWith(";")) {
            throw new IllegalArgumentException("Invalid class descriptor: " + descriptor);
        }
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private CalendarSymbolResolver() {}
}
