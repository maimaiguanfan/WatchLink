package io.github.watchlink.xposed;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Resolves only timer actions. A missing or ambiguous contract disables that action. */
final class ClockTimerSymbolResolver {
    static final String VIEW = "com.oplus.alarmclock.timer.TimerFloatingViewService";
    static final String KLAXON = "com.oplus.alarmclock.timer.TimerKlaxon";
    static final String RECEIVER = "com.oplus.alarmclock.timer.TimerNotificationReceiver";
    static final String RESTART = "com.oplus.alarmclock.Timer.RESTART_TIMER";
    private static final String FULL = "com.oplus.alarmclock.timer.TimerAlertFullScreen";
    private static final String CONTEXT = "Landroid/content/Context;", INTENT = "Landroid/content/Intent;";
    private static final List<String> SHOW_PARAMS = List.of(CONTEXT, INTENT);
    record Symbols(String show, String stop, boolean restart) {}

    static Symbols resolve(ApkSymbolCache cache, String[] sources) {
        String fingerprint = ApkSymbolCache.fingerprint("timer-v2", sources);
        Map<String, String> saved = cache.read(fingerprint);
        if (saved.containsKey("show")) return new Symbols(saved.get("show"), saved.getOrDefault("stop", ""),
                Boolean.parseBoolean(saved.get("restart")));
        Symbols result = scan(sources);
        if (result != null) cache.write(fingerprint, Map.of("show", result.show, "stop", result.stop,
                "restart", String.valueOf(result.restart)));
        return result;
    }

    static Symbols scan(String... sources) {
        Map<String, ClassDef> types = new HashMap<>();
        Set<String> wanted = Set.of(desc(VIEW), desc(RECEIVER), desc(FULL));
        for (String source : sources) {
            if (source == null) continue;
            try (ZipFile apk = new ZipFile(source)) {
                var entries = apk.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) continue;
                    try (var input = new BufferedInputStream(apk.getInputStream(entry))) {
                        for (ClassDef type : DexBackedDexFile.fromInputStream(null, input).getClasses()) {
                            if (wanted.contains(type.getType()) && types.put(type.getType(), type) != null) return null;
                        }
                    }
                }
            } catch (IOException | RuntimeException unreadable) { return null; }
        }
        ClassDef view = types.get(desc(VIEW));
        if (view == null) return null;
        Set<String> guardedCalls = guardedShows(view);
        Set<String> shows = new HashSet<>(), alertClosers = new HashSet<>();
        for (Method method : view.getMethods()) {
            Set<String> refs = references(method);
            if (method.getParameterTypes().equals(SHOW_PARAMS) && "V".equals(method.getReturnType())
                    && !AccessFlags.STATIC.isSet(method.getAccessFlags()) && guardedCalls.contains(method.getName())
                    && refs.contains("timer_index")) shows.add(method.getName());
            if (method.getParameterTypes().equals(List.of(CONTEXT, "Z")) && "V".equals(method.getReturnType())
                    && AccessFlags.STATIC.isSet(method.getAccessFlags()) && alertCloseContract(refs)) alertClosers.add(method.getName());
        }
        String alertClose = unique(alertClosers);
        Set<String> stops = new HashSet<>();
        if (!alertClose.isEmpty()) {
            for (Method method : view.getDirectMethods()) {
                if (stopsPlaybackAndAlert(method, alertClose)) stops.add(method.getName());
            }
        }
        ClassDef receiver = types.get(desc(RECEIVER)), full = types.get(desc(FULL));
        boolean restart = receiver != null && full != null && hasRestartContract(receiver, full);
        return new Symbols(unique(shows), unique(stops), restart);
    }

    // Follow the success branch of Clock's (Intent)Z validation. Stop at a control-flow boundary.
    private static Set<String> guardedShows(ClassDef view) {
        Set<String> candidates = new HashSet<>();
        for (Method start : view.getMethods()) {
            if (!"onStartCommand".equals(start.getName()) || !start.getParameterTypes().equals(List.of(INTENT, "I", "I"))
                    || !"I".equals(start.getReturnType()) || start.getImplementation() == null
                    || !references(start).contains("start_timer_action")) continue;
            TreeMap<Integer, Instruction> code = new TreeMap<>();
            int addr = 0;
            for (Instruction ins : start.getImplementation().getInstructions()) {
                code.put(addr, ins);
                addr += ins.getCodeUnits();
            }
            for (var entry : code.entrySet()) {
                if (!(reference(entry.getValue()) instanceof MethodReference check)
                        || !view.getType().equals(check.getDefiningClass()) || !"Z".equals(check.getReturnType())
                        || !check.getParameterTypes().equals(List.of(INTENT)) || !validatesRun(view, check)) continue;
                var result = code.higherEntry(entry.getKey());
                if (result == null || result.getValue().getOpcode() != Opcode.MOVE_RESULT) continue;
                var jump = code.higherEntry(result.getKey());
                if (jump == null || !(jump.getValue() instanceof OffsetInstruction branch)
                        || !(jump.getValue() instanceof OneRegisterInstruction reg)
                        || reg.getRegisterA() != ((OneRegisterInstruction) result.getValue()).getRegisterA()) continue;
                Opcode op = jump.getValue().getOpcode();
                if (op != Opcode.IF_NEZ && op != Opcode.IF_EQZ) continue;
                int success = jump.getKey() + (op == Opcode.IF_NEZ ? branch.getCodeOffset() : jump.getValue().getCodeUnits());
                for (Instruction ins : code.tailMap(success).values()) {
                    if (reference(ins) instanceof MethodReference call && view.getType().equals(call.getDefiningClass())
                            && call.getParameterTypes().equals(SHOW_PARAMS) && "V".equals(call.getReturnType()))
                        candidates.add(call.getName());
                    if (!ins.getOpcode().canContinue() || ins instanceof OffsetInstruction) break;
                }
            }
        }
        return candidates;
    }

    private static boolean validatesRun(ClassDef view, MethodReference target) {
        for (Method m : view.getMethods()) {
            if (m.equals(target)) return references(m).containsAll(Set.of("timer_index", "timer_first_start_time"));
        }
        return false;
    }

    static boolean alertCloseContract(Set<String> refs) {
        // The verified helper only constructs Intents and asks Context to deliver them.
        // Reject additions such as direct repository or provider mutations after a Clock update.
        for (String ref : refs) {
            if (ref.contains("->") && ref.contains("(") && !ref.startsWith(CONTEXT + "->")
                    && !ref.startsWith(INTENT + "->") && !ref.startsWith("Ljava/lang/Class;->")) return false;
        }
        return refs.containsAll(Set.of("stop_timer_action", "timer_repeat", "TIMER_ALERT_STOP_TIMER",
                CONTEXT + "->startForegroundService(" + INTENT + ")Landroid/content/ComponentName;",
                CONTEXT + "->sendBroadcast(" + INTENT + ")V"));
    }

    /** Clock's native dismiss stops TimerKlaxon and forwards the original repeat flag to the UI closer. */
    private static boolean stopsPlaybackAndAlert(Method method, String alertClose) {
        var impl = method.getImplementation();
        if (impl == null || !AccessFlags.PUBLIC.isSet(method.getAccessFlags()) || !AccessFlags.STATIC.isSet(method.getAccessFlags())
                || !method.getParameterTypes().equals(List.of(CONTEXT, "Z")) || !"V".equals(method.getReturnType())) return false;
        List<Instruction> code = new ArrayList<>(6);
        for (Instruction ins : impl.getInstructions()) code.add(ins);
        return stopContract(code, impl.getRegisterCount() - 2, method.getDefiningClass() + "->" + alertClose + "(" + CONTEXT + "Z)V");
    }

    static boolean stopContract(List<Instruction> code, int context, String closeMethod) {
        if (code.size() != 6 || code.get(5).getOpcode() != Opcode.RETURN_VOID) return false;
        int intent = destination(code.get(0), Opcode.NEW_INSTANCE, INTENT);
        int target = destination(code.get(1), Opcode.CONST_CLASS, desc(KLAXON));
        if (intent < 0 || target < 0 || intent == target || intent >= context || target >= context) return false;
        return calls(code.get(2), Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE,
                        INTENT + "-><init>(" + CONTEXT + "Ljava/lang/Class;)V", intent, context, target)
                && calls(code.get(3), Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE,
                        CONTEXT + "->stopService(" + INTENT + ")Z", context, intent)
                && calls(code.get(4), Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE, closeMethod, context, context + 1);
    }

    private static int destination(Instruction ins, Opcode opcode, String type) {
        return ins.getOpcode() == opcode && type.equals(String.valueOf(reference(ins)))
                && ins instanceof OneRegisterInstruction reg ? reg.getRegisterA() : -1;
    }

    private static boolean calls(Instruction ins, Opcode opcode, Opcode rangeOpcode, String method, int... expected) {
        if (!method.equals(String.valueOf(reference(ins)))) return false;
        if (ins.getOpcode() == opcode && ins instanceof FiveRegisterInstruction call) {
            int[] registers = {call.getRegisterC(), call.getRegisterD(), call.getRegisterE()};
            if (call.getRegisterCount() != expected.length) return false;
            for (int i = 0; i < expected.length; i++) if (registers[i] != expected[i]) return false;
            return true;
        }
        if (ins.getOpcode() == rangeOpcode && ins instanceof RegisterRangeInstruction call
                && call.getRegisterCount() == expected.length) {
            for (int i = 0; i < expected.length; i++) if (call.getStartRegister() + i != expected[i]) return false;
            return true;
        }
        return false;
    }

    private static boolean hasRestartContract(ClassDef receiver, ClassDef full) {
        Set<String> receiverRefs = new HashSet<>();
        for (Method m : receiver.getMethods()) receiverRefs.addAll(references(m));
        if (!receiverRefs.containsAll(Set.of(RESTART, "timerIndex"))) return false;
        for (Method m : full.getMethods()) {
            if (references(m).containsAll(Set.of(RESTART, "timerIndex", receiver.getType(),
                    CONTEXT + "->sendBroadcast(" + INTENT + ")V"))) return true;
        }
        return false;
    }

    private static Set<String> references(Method method) {
        Set<String> refs = new HashSet<>();
        if (method.getImplementation() == null) return refs;
        for (Instruction ins : method.getImplementation().getInstructions()) {
            Reference ref = reference(ins);
            if (ref != null) refs.add(ref instanceof StringReference str ? str.getString() : ref.toString());
        }
        return refs;
    }

    private static Reference reference(Instruction ins) {
        return ins instanceof ReferenceInstruction ref ? ref.getReference() : null;
    }

    private static String unique(Set<String> matches) { return matches.size() == 1 ? matches.iterator().next() : ""; }
    private static String desc(String name) { return "L" + name.replace('.', '/') + ";"; }
}
