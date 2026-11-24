package io.github.watchlink.xposed;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload;
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
 * Resolves obfuscated ColorOS Clock symbols structurally from unobfuscated anchor classes
 * (AlarmKlaxon, AlarmService, AlarmStateManager, ClockProvider, AlarmClockApplication)
 * while simultaneously verifying the native action bytecode contract.
 */
final class ClockSymbolResolver {
    static final String SCHEMA = "clock-v2";
    static final String KLAXON = "com.oplus.alarmclock.alert.AlarmKlaxon";
    static final String SERVICE = "com.oplus.alarmclock.alert.AlarmService";
    static final String STATE_MANAGER = "com.oplus.alarmclock.alarmclock.AlarmStateManager";
    static final String PROVIDER = "com.oplus.alarmclock.provider.ClockProvider";
    static final String APPLICATION = "com.oplus.alarmclock.AlarmClockApplication";

    private static final String D_KLAXON = desc(KLAXON);
    private static final String D_SERVICE = desc(SERVICE);
    private static final String D_STATE_MANAGER = desc(STATE_MANAGER);
    private static final String D_PROVIDER = desc(PROVIDER);
    private static final String D_APPLICATION = desc(APPLICATION);
    private static final String D_CONTEXT = "Landroid/content/Context;";
    private static final String D_STRING = "Ljava/lang/String;";
    private static final String D_BOOLEAN_OBJ = "Ljava/lang/Boolean;";
    private static final String D_URI = "Landroid/net/Uri;";
    private static final Pattern DEX = Pattern.compile("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");

    record Symbols(
            String occurrenceClass,
            String alarmModelClass,
            String scheduleGetter,
            String parentGetter,
            String timeGetter,
            String snoozesGetter,
            String stateGetter,
            String modelGetter,
            String canSnoozeMethod,
            String uuidGetter,
            String serviceInstanceField,
            String serviceAcceptMethod,
            String providerNotifyMethod,
            String applicationGetter,
            String dismissMethod,
            String snoozeMethod,
            boolean actionContractVerified,
            String source) {

        Map<String, String> toMap() {
            Map<String, String> map = new HashMap<>();
            map.put("occurrenceClass", occurrenceClass);
            map.put("alarmModelClass", alarmModelClass);
            map.put("scheduleGetter", scheduleGetter);
            map.put("parentGetter", parentGetter);
            map.put("timeGetter", timeGetter);
            map.put("snoozesGetter", snoozesGetter);
            map.put("stateGetter", stateGetter);
            map.put("modelGetter", modelGetter);
            map.put("canSnoozeMethod", canSnoozeMethod);
            map.put("uuidGetter", uuidGetter);
            map.put("serviceInstanceField", serviceInstanceField);
            map.put("serviceAcceptMethod", serviceAcceptMethod);
            map.put("providerNotifyMethod", providerNotifyMethod);
            map.put("applicationGetter", applicationGetter);
            map.put("dismissMethod", dismissMethod == null ? "" : dismissMethod);
            map.put("snoozeMethod", snoozeMethod == null ? "" : snoozeMethod);
            map.put("actionContractVerified", Boolean.toString(actionContractVerified));
            return map;
        }

        static Symbols fromCache(Map<String, String> map) {
            if (map == null || map.isEmpty()) return null;
            String occ = req(map, "occurrenceClass");
            String model = req(map, "alarmModelClass");
            String schedule = req(map, "scheduleGetter");
            String parent = req(map, "parentGetter");
            String time = req(map, "timeGetter");
            String snoozes = req(map, "snoozesGetter");
            String state = req(map, "stateGetter");
            String modelGet = req(map, "modelGetter");
            String canSnooze = req(map, "canSnoozeMethod");
            String uuid = req(map, "uuidGetter");
            String svcField = req(map, "serviceInstanceField");
            String svcAccept = req(map, "serviceAcceptMethod");
            String notify = req(map, "providerNotifyMethod");
            String appGet = req(map, "applicationGetter");
            if (occ == null || model == null || schedule == null || parent == null || time == null
                    || snoozes == null || state == null || modelGet == null || canSnooze == null
                    || uuid == null || svcField == null || svcAccept == null || notify == null || appGet == null) {
                return null;
            }
            String dismiss = map.getOrDefault("dismissMethod", "");
            String snooze = map.getOrDefault("snoozeMethod", "");
            boolean verified = "true".equals(map.get("actionContractVerified"))
                    && !dismiss.isEmpty() && !snooze.isEmpty();
            return new Symbols(occ, model, schedule, parent, time, snoozes, state, modelGet, canSnooze, uuid,
                    svcField, svcAccept, notify, appGet,
                    verified ? dismiss : "", verified ? snooze : "", verified, "CACHE");
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
            ClassDef klaxon = null, service = null, manager = null, provider = null, application = null;
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
                            boolean keepDex = false;
                            for (ClassDef type : dex.getClasses()) {
                                String d = type.getType();
                                if (D_KLAXON.equals(d)) { klaxon = type; keepDex = true; }
                                else if (D_SERVICE.equals(d)) { service = type; keepDex = true; }
                                else if (D_STATE_MANAGER.equals(d)) { manager = type; keepDex = true; }
                                else if (D_PROVIDER.equals(d)) { provider = type; keepDex = true; }
                                else if (D_APPLICATION.equals(d)) { application = type; keepDex = true; }
                            }
                            if (keepDex) dexes.add(dex);
                        }
                    }
                }
            }
            if (klaxon == null || service == null || manager == null || provider == null || application == null) {
                return null;
            }

            // 1. AlarmKlaxon.start(Context, <Occurrence>, Boolean) -> Occurrence descriptor
            String occurrenceDesc = findOccurrenceFromKlaxon(klaxon);
            if (occurrenceDesc == null) return null;

            ClassDef occurrenceDef = findClass(dexes, occurrenceDesc);
            if (occurrenceDef == null) occurrenceDef = findClassAcrossSources(sources, occurrenceDesc);
            if (occurrenceDef == null) return null;

            // 2. Occurrence fields from toString() literal-to-iget mapping -> AlarmModel descriptor & getters/setters
            Map<String, FieldReference> occFields = mapToStringFields(occurrenceDef);
            FieldReference scheduleField = occFields.get("AlarmSchedule{mId=");
            FieldReference timeField = occFields.get(", mTime=");
            FieldReference snoozeCountField = occFields.containsKey(", mSnoonzeTime=")
                    ? occFields.get(", mSnoonzeTime=") : occFields.get(", mSnoozeTime=");
            FieldReference stateField = occFields.get(", mAlarmState=");
            FieldReference parentField = occFields.get(", mAlarmId=");
            FieldReference alarmField = occFields.get(", mAlarm=");
            if (scheduleField == null || timeField == null || snoozeCountField == null
                    || stateField == null || parentField == null || alarmField == null) {
                return null;
            }
            String alarmModelDesc = alarmField.getType();
            ClassDef alarmModelDef = findClass(dexes, alarmModelDesc);
            if (alarmModelDef == null) alarmModelDef = findClassAcrossSources(sources, alarmModelDesc);
            if (alarmModelDef == null) return null;

            String scheduleGetter = findGetter(occurrenceDef, scheduleField, "J");
            String parentGetter = findGetter(occurrenceDef, parentField, "J");
            String timeGetter = findGetter(occurrenceDef, timeField, "J");
            String snoozesGetter = findGetter(occurrenceDef, snoozeCountField, "I");
            String stateGetter = findGetter(occurrenceDef, stateField, "I");
            String modelGetter = findGetter(occurrenceDef, alarmField, alarmModelDesc);
            String canSnoozeMethod = findCanSnooze(occurrenceDef, alarmModelDesc);
            String setSnoozeMethod = findSetter(occurrenceDef, snoozeCountField, "I");
            String setTimeMethod = findSetter(occurrenceDef, timeField, "J");
            if (scheduleGetter == null || parentGetter == null || timeGetter == null || snoozesGetter == null
                    || stateGetter == null || modelGetter == null || canSnoozeMethod == null) {
                return null;
            }

            // 3. AlarmModel UUID field & getter
            FieldReference uuidField = findUuidField(alarmModelDef);
            String uuidGetter = uuidField == null ? null : findGetter(alarmModelDef, uuidField, D_STRING);
            if (uuidGetter == null) return null;

            // 4. AlarmService instance field, accept(String)V, and stopService(Context, long, boolean)V
            String serviceInstanceField = findUniquePublicInstanceField(service, occurrenceDesc);
            String serviceAcceptMethod = findServiceAcceptMethod(service);
            String providerNotifyMethod = findProviderNotifyMethod(provider);
            String applicationGetter = findApplicationGetter(application);
            if (serviceInstanceField == null || serviceAcceptMethod == null
                    || providerNotifyMethod == null || applicationGetter == null) {
                return null;
            }

            // 5. Verify native action contract (AlarmService.stop + AlarmStateManager finish/snooze/dismiss)
            String stopMethod = findStopServiceMethod(service);
            String dismissMethod = "";
            String snoozeMethod = "";
            boolean contractVerified = false;
            if (stopMethod != null && setSnoozeMethod != null && setTimeMethod != null) {
                ActionMethods actions = findVerifiedActions(
                        manager, occurrenceDesc, stopMethod, setSnoozeMethod, setTimeMethod);
                if (actions != null) {
                    dismissMethod = actions.dismiss;
                    snoozeMethod = actions.snooze;
                    contractVerified = true;
                }
            }

            return new Symbols(
                    toClassName(occurrenceDesc),
                    toClassName(alarmModelDesc),
                    scheduleGetter, parentGetter, timeGetter, snoozesGetter, stateGetter,
                    modelGetter, canSnoozeMethod, uuidGetter,
                    serviceInstanceField, serviceAcceptMethod, providerNotifyMethod, applicationGetter,
                    dismissMethod, snoozeMethod, contractVerified, "DEX_SCAN");
        } catch (IOException | RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    private record ActionMethods(String dismiss, String snooze) {}

    private static String findOccurrenceFromKlaxon(ClassDef klaxon) {
        String found = null;
        for (Method m : klaxon.getDirectMethods()) {
            if (!AccessFlags.PUBLIC.isSet(m.getAccessFlags()) || !AccessFlags.STATIC.isSet(m.getAccessFlags())) continue;
            if (!"start".equals(m.getName()) || !"V".equals(m.getReturnType())) continue;
            List<? extends CharSequence> params = m.getParameterTypes();
            if (params.size() != 3) continue;
            if (D_CONTEXT.contentEquals(params.get(0)) && D_BOOLEAN_OBJ.contentEquals(params.get(2))) {
                String candidate = params.get(1).toString();
                if (found != null && !found.equals(candidate)) return null;
                found = candidate;
            }
        }
        return found;
    }

    private static Map<String, FieldReference> mapToStringFields(ClassDef type) {
        Map<String, FieldReference> result = new HashMap<>();
        Method toString = null;
        for (Method m : type.getVirtualMethods()) {
            if ("toString".equals(m.getName()) && m.getParameterTypes().isEmpty() && D_STRING.equals(m.getReturnType())) {
                toString = m;
                break;
            }
        }
        if (toString == null || toString.getImplementation() == null) return result;
        String lastLabel = null;
        for (Instruction insn : toString.getImplementation().getInstructions()) {
            if (insn instanceof ReferenceInstruction ref) {
                if (ref.getReference() instanceof StringReference str) {
                    String text = str.getString();
                    if (text.contains("=")) lastLabel = text;
                } else if (ref.getReference() instanceof FieldReference field
                        && isInstanceGet(insn.getOpcode())
                        && type.getType().equals(field.getDefiningClass())
                        && lastLabel != null) {
                    result.putIfAbsent(lastLabel, field);
                    lastLabel = null;
                }
            }
        }
        return result;
    }

    private static FieldReference findUuidField(ClassDef model) {
        Map<String, FieldReference> byLabel = mapToStringFields(model);
        FieldReference fromToString = byLabel.get(", mUUID=");
        if (fromToString != null && D_STRING.equals(fromToString.getType())) return fromToString;
        // Fallback: look for UUID.randomUUID() -> ... -> iput-object in <init>()V
        for (Method m : model.getDirectMethods()) {
            if (!"<init>".equals(m.getName()) || !m.getParameterTypes().isEmpty() || m.getImplementation() == null) continue;
            boolean sawUuid = false;
            for (Instruction insn : m.getImplementation().getInstructions()) {
                if (insn instanceof ReferenceInstruction ref) {
                    if (ref.getReference() instanceof MethodReference call
                            && "Ljava/util/UUID;".equals(call.getDefiningClass())
                            && "randomUUID".equals(call.getName())) {
                        sawUuid = true;
                    } else if (sawUuid && insn.getOpcode() == Opcode.IPUT_OBJECT
                            && ref.getReference() instanceof FieldReference field
                            && model.getType().equals(field.getDefiningClass())
                            && D_STRING.equals(field.getType())) {
                        return field;
                    }
                }
            }
        }
        return null;
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

    private static String findSetter(ClassDef type, FieldReference targetField, String paramType) {
        String match = null;
        for (Method m : type.getVirtualMethods()) {
            int flags = m.getAccessFlags();
            if (!AccessFlags.PUBLIC.isSet(flags) || AccessFlags.STATIC.isSet(flags)
                    || AccessFlags.SYNTHETIC.isSet(flags) || AccessFlags.BRIDGE.isSet(flags)) continue;
            if (m.getParameterTypes().size() != 1 || !paramType.contentEquals(m.getParameterTypes().get(0))
                    || !"V".equals(m.getReturnType())) continue;
            MethodImplementation impl = m.getImplementation();
            if (impl == null) continue;
            List<Instruction> insns = new ArrayList<>();
            for (Instruction i : impl.getInstructions()) insns.add(i);
            if (insns.size() != 2) continue;
            if (insns.get(0) instanceof ReferenceInstruction ref
                    && isInstancePut(insns.get(0).getOpcode())
                    && ref.getReference() instanceof FieldReference field
                    && sameField(field, targetField)
                    && insns.get(1).getOpcode() == Opcode.RETURN_VOID) {
                if (match != null) return null;
                match = m.getName();
            }
        }
        return match;
    }

    private static String findCanSnooze(ClassDef occurrence, String alarmModelDesc) {
        String match = null;
        for (Method m : occurrence.getVirtualMethods()) {
            int flags = m.getAccessFlags();
            if (!AccessFlags.PUBLIC.isSet(flags) || AccessFlags.STATIC.isSet(flags)) continue;
            if (m.getParameterTypes().size() != 1 || !alarmModelDesc.contentEquals(m.getParameterTypes().get(0))
                    || !"Z".equals(m.getReturnType())) continue;
            if (match != null) return null;
            match = m.getName();
        }
        return match;
    }

    private static String findUniquePublicInstanceField(ClassDef owner, String fieldTypeDesc) {
        String match = null;
        for (Field f : owner.getInstanceFields()) {
            if (AccessFlags.PUBLIC.isSet(f.getAccessFlags()) && fieldTypeDesc.equals(f.getType())) {
                if (match != null) return null;
                match = f.getName();
            }
        }
        return match;
    }

    private static String findServiceAcceptMethod(ClassDef service) {
        String match = null;
        for (Method m : service.getVirtualMethods()) {
            int flags = m.getAccessFlags();
            if (!AccessFlags.PUBLIC.isSet(flags) || AccessFlags.STATIC.isSet(flags)) continue;
            if (m.getParameterTypes().size() != 1 || !D_STRING.contentEquals(m.getParameterTypes().get(0))
                    || !"V".equals(m.getReturnType()) || m.getImplementation() == null) continue;
            boolean callsStop = false;
            boolean hasStopLog = false;
            for (Instruction insn : m.getImplementation().getInstructions()) {
                if (insn instanceof ReferenceInstruction ref) {
                    if (ref.getReference() instanceof MethodReference call
                            && D_KLAXON.equals(call.getDefiningClass()) && "stop".equals(call.getName())) {
                        callsStop = true;
                    } else if (ref.getReference() instanceof StringReference str
                            && str.getString().contains("stopCurrentAlarm")) {
                        hasStopLog = true;
                    }
                }
            }
            if (callsStop && hasStopLog) {
                if (match != null) return null;
                match = m.getName();
            }
        }
        return match;
    }

    private static String findProviderNotifyMethod(ClassDef provider) {
        String match = null;
        for (Method m : provider.getDirectMethods()) {
            int flags = m.getAccessFlags();
            if (!AccessFlags.PRIVATE.isSet(flags) || AccessFlags.STATIC.isSet(flags)) continue;
            if (m.getParameterTypes().size() != 1 || !D_URI.contentEquals(m.getParameterTypes().get(0))
                    || !"V".equals(m.getReturnType()) || m.getImplementation() == null) continue;
            for (Instruction insn : m.getImplementation().getInstructions()) {
                if (insn instanceof ReferenceInstruction ref
                        && ref.getReference() instanceof MethodReference call
                        && "Landroid/content/ContentResolver;".equals(call.getDefiningClass())
                        && "notifyChange".equals(call.getName())) {
                    if (match != null) return null;
                    match = m.getName();
                    break;
                }
            }
        }
        return match;
    }

    private static String findApplicationGetter(ClassDef application) {
        String match = null;
        for (Method m : application.getDirectMethods()) {
            int flags = m.getAccessFlags();
            if (!AccessFlags.PUBLIC.isSet(flags) || !AccessFlags.STATIC.isSet(flags)) continue;
            if (!m.getParameterTypes().isEmpty() || !D_APPLICATION.equals(m.getReturnType())) continue;
            if (match != null) return null;
            match = m.getName();
        }
        return match;
    }

    private static String findStopServiceMethod(ClassDef service) {
        String match = null;
        for (Method m : service.getDirectMethods()) {
            int flags = m.getAccessFlags();
            if (!AccessFlags.PUBLIC.isSet(flags) || !AccessFlags.STATIC.isSet(flags)) continue;
            List<? extends CharSequence> params = m.getParameterTypes();
            if (params.size() != 3 || !D_CONTEXT.contentEquals(params.get(0))
                    || !"J".contentEquals(params.get(1)) || !"Z".contentEquals(params.get(2))
                    || !"V".equals(m.getReturnType())) continue;
            if (verifyStopServiceBody(m.getImplementation())) {
                if (match != null) return null;
                match = m.getName();
            }
        }
        return match;
    }

    private static boolean verifyStopServiceBody(MethodImplementation body) {
        if (body == null) return false;
        Set<Integer> branchTargets = branchTargets(body);
        int address = 0;
        Instruction previous = null;
        int flagWrites = 0;
        boolean hasStopAction = false, hasSetAction = false, hasStartFgs = false;
        for (Instruction insn : body.getInstructions()) {
            if (insn instanceof ReferenceInstruction ref) {
                if (ref.getReference() instanceof StringReference str && "STOP_ALARM".equals(str.getString())) {
                    hasStopAction = true;
                } else if (ref.getReference() instanceof MethodReference call) {
                    if ("Landroid/content/Intent;".equals(call.getDefiningClass())) {
                        if ("setAction".equals(call.getName())) hasSetAction = true;
                        else if ("putExtra".equals(call.getName()) && call.getParameterTypes().size() == 2
                                && D_STRING.contentEquals(call.getParameterTypes().get(0))
                                && "Z".contentEquals(call.getParameterTypes().get(1))) {
                            flagWrites++;
                            if (branchTargets.contains(address)
                                    || !(previous instanceof ReferenceInstruction key)
                                    || !(key.getReference() instanceof StringReference keyStr)
                                    || !"IS_ALARM_DISMISSED".equals(keyStr.getString())
                                    || !(previous instanceof OneRegisterInstruction loadedKey)
                                    || loadedKey.getRegisterA() != argument(insn, 1)
                                    || argument(insn, 2) != body.getRegisterCount() - 1) {
                                return false;
                            }
                        }
                    } else if (D_CONTEXT.equals(call.getDefiningClass())
                            && "startForegroundService".equals(call.getName())) {
                        hasStartFgs = true;
                    }
                }
            }
            previous = insn;
            address += insn.getCodeUnits();
        }
        return flagWrites == 1 && hasStopAction && hasSetAction && hasStartFgs;
    }

    private static ActionMethods findVerifiedActions(
            ClassDef manager, String occDesc, String stopMethodName,
            String setSnoozeMethod, String setTimeMethod) {
        List<Method> transitions = new ArrayList<>();
        for (Method m : manager.getDirectMethods()) {
            int flags = m.getAccessFlags();
            if (!AccessFlags.PUBLIC.isSet(flags) || !AccessFlags.STATIC.isSet(flags)) continue;
            List<? extends CharSequence> params = m.getParameterTypes();
            if (params.size() == 2 && D_CONTEXT.contentEquals(params.get(0))
                    && occDesc.contentEquals(params.get(1)) && "V".equals(m.getReturnType())) {
                transitions.add(m);
            }
        }
        String finish = null, snooze = null, dismiss = null;
        for (Method m : transitions) {
            TransitionScan scan = scanTransition(m.getImplementation(), occDesc, stopMethodName, setSnoozeMethod, setTimeMethod);
            if (scan == null) continue;
            if (scan.stopCalls == 1 && scan.stopWithTrue && !scan.stopWithFalse
                    && !scan.callsSetSnooze && !scan.callsSetTime) {
                if (finish != null) return null;
                finish = m.getName();
            } else if (scan.stopCalls == 1 && scan.stopWithFalse && !scan.stopWithTrue
                    && scan.callsSetSnooze && scan.callsSetTime) {
                if (snooze != null) return null;
                snooze = m.getName();
            }
        }
        if (finish == null || snooze == null) return null;
        for (Method m : transitions) {
            if (m.getName().equals(finish) || m.getName().equals(snooze)) continue;
            if (verifyDismissBody(m.getImplementation(), occDesc, finish, snooze)) {
                if (dismiss != null) return null;
                dismiss = m.getName();
            }
        }
        return dismiss == null ? null : new ActionMethods(dismiss, snooze);
    }

    private record TransitionScan(int stopCalls, boolean stopWithTrue, boolean stopWithFalse,
                                  boolean callsSetSnooze, boolean callsSetTime) {}

    private static TransitionScan scanTransition(
            MethodImplementation body, String occDesc, String stopMethodName,
            String setSnoozeMethod, String setTimeMethod) {
        if (body == null) return null;
        Set<Integer> branchTargets = branchTargets(body);
        int address = 0;
        Instruction previous = null;
        int stopCalls = 0;
        boolean withTrue = false, withFalse = false, setSnooze = false, setTime = false;
        for (Instruction insn : body.getInstructions()) {
            if (insn instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference call) {
                if (D_SERVICE.equals(call.getDefiningClass()) && stopMethodName.equals(call.getName())
                        && call.getParameterTypes().size() == 3) {
                    stopCalls++;
                    if (branchTargets.contains(address)) return null;
                    if (passesStopFlag(previous, insn, 1)) withTrue = true;
                    else if (passesStopFlag(previous, insn, 0)) withFalse = true;
                    else return null;
                } else if (occDesc.equals(call.getDefiningClass())) {
                    if (setSnoozeMethod.equals(call.getName()) && call.getParameterTypes().size() == 1
                            && "I".contentEquals(call.getParameterTypes().get(0))) setSnooze = true;
                    else if (setTimeMethod.equals(call.getName()) && call.getParameterTypes().size() == 1
                            && "J".contentEquals(call.getParameterTypes().get(0))) setTime = true;
                }
            }
            previous = insn;
            address += insn.getCodeUnits();
        }
        return new TransitionScan(stopCalls, withTrue, withFalse, setSnooze, setTime);
    }

    private static boolean verifyDismissBody(MethodImplementation body, String occDesc,
                                             String finishMethod, String snoozeMethod) {
        if (body == null) return false;
        boolean hasDismissAction = false, callsFinish = false, callsSnooze = false;
        for (Instruction insn : body.getInstructions()) {
            if (insn instanceof ReferenceInstruction ref) {
                if (ref.getReference() instanceof StringReference str
                        && "com.oplus.alarmclock.action.repeat_alarm_dismiss".equals(str.getString())) {
                    hasDismissAction = true;
                } else if (ref.getReference() instanceof MethodReference call
                        && D_STATE_MANAGER.equals(call.getDefiningClass())
                        && call.getParameterTypes().size() == 2
                        && D_CONTEXT.contentEquals(call.getParameterTypes().get(0))
                        && occDesc.contentEquals(call.getParameterTypes().get(1))) {
                    if (finishMethod.equals(call.getName())) callsFinish = true;
                    if (snoozeMethod.equals(call.getName())) callsSnooze = true;
                }
            }
        }
        return hasDismissAction && callsFinish && !callsSnooze;
    }

    static boolean passesStopFlag(Instruction previous, Instruction call, int value) {
        return (call.getOpcode() == Opcode.INVOKE_STATIC || call.getOpcode() == Opcode.INVOKE_STATIC_RANGE)
                && previous instanceof NarrowLiteralInstruction literal
                && previous instanceof OneRegisterInstruction destination
                && (previous.getOpcode() == Opcode.CONST_4 || previous.getOpcode() == Opcode.CONST_16
                    || previous.getOpcode() == Opcode.CONST)
                && literal.getNarrowLiteral() == value && destination.getRegisterA() == argument(call, 3);
    }

    private static Set<Integer> branchTargets(MethodImplementation body) {
        Set<Integer> targets = new HashSet<>();
        Map<Integer, SwitchPayload> payloads = new HashMap<>();
        Map<Integer, Integer> switches = new HashMap<>();
        int address = 0;
        for (Instruction insn : body.getInstructions()) {
            if (insn instanceof OffsetInstruction jump) {
                int target = address + jump.getCodeOffset();
                if (insn.getOpcode() == Opcode.PACKED_SWITCH || insn.getOpcode() == Opcode.SPARSE_SWITCH) {
                    switches.put(address, target);
                } else targets.add(target);
            } else if (insn instanceof SwitchPayload payload) {
                payloads.put(address, payload);
            }
            address += insn.getCodeUnits();
        }
        for (var entry : switches.entrySet()) {
            SwitchPayload payload = payloads.get(entry.getValue());
            if (payload == null) throw new IllegalArgumentException("Invalid switch target");
            for (var element : payload.getSwitchElements()) targets.add(entry.getKey() + element.getOffset());
        }
        for (var block : body.getTryBlocks()) {
            for (var handler : block.getExceptionHandlers()) targets.add(handler.getHandlerCodeAddress());
        }
        return targets;
    }

    private static int argument(Instruction instruction, int index) {
        if (instruction instanceof RegisterRangeInstruction range && index < range.getRegisterCount())
            return range.getStartRegister() + index;
        if (!(instruction instanceof FiveRegisterInstruction invoke) || index >= invoke.getRegisterCount()) return -1;
        return switch (index) {
            case 1 -> invoke.getRegisterD();
            case 2 -> invoke.getRegisterE();
            case 3 -> invoke.getRegisterF();
            default -> -1;
        };
    }

    private static ClassDef findClass(List<DexBackedDexFile> dexes, String descriptor) {
        for (DexBackedDexFile dex : dexes) {
            for (ClassDef type : dex.getClasses()) {
                if (descriptor.equals(type.getType())) return type;
            }
        }
        return null;
    }

    private static ClassDef findClassAcrossSources(String[] sources, String descriptor) throws IOException {
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
                            if (descriptor.equals(type.getType())) return type;
                        }
                    }
                }
            }
        }
        return null;
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

    private static boolean isInstancePut(Opcode op) {
        return switch (op) {
            case IPUT, IPUT_WIDE, IPUT_OBJECT, IPUT_BOOLEAN, IPUT_BYTE, IPUT_CHAR, IPUT_SHORT -> true;
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

    private ClockSymbolResolver() {}
}
