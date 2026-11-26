package io.github.watchlink.xposed;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class ClockTimerSymbolResolverTest {
    @Test public void resolvesGuardedDisplayStopAndRestartFromClockApk() {
        String apk = "/tmp/watchlink-apks/clock.apk";
        assumeTrue(Files.isRegularFile(Path.of(apk)));
        var cache = new ApkSymbolCache(new ClockSymbolResolverTest.MemoryPrefs());
        String[] sources = {apk};
        cache.write(ApkSymbolCache.fingerprint("timer-v1", sources), Map.of("show", "v", "stop", "B", "restart", "true"));
        var symbols = ClockTimerSymbolResolver.resolve(cache, sources);
        assertNotNull(symbols);
        assertEquals("v", symbols.show());
        assertEquals("z", symbols.stop());
        assertTrue(symbols.restart());
        assertEquals(symbols, ClockTimerSymbolResolver.resolve(cache, sources));
    }

    @Test public void requiresStoppingTheExactSoundServiceAndPreservingRepeat() {
        String ctx = "Landroid/content/Context;", intent = "Landroid/content/Intent;";
        var klaxon = new ImmutableTypeReference("Lcom/oplus/alarmclock/timer/TimerKlaxon;");
        var ctor = new ImmutableMethodReference(intent, "<init>", List.of(ctx, "Ljava/lang/Class;"), "V");
        var stop = new ImmutableMethodReference(ctx, "stopService", List.of(intent), "Z");
        var close = new ImmutableMethodReference("LView;", "renamedClose", List.of(ctx, "Z"), "V");
        var code = new ArrayList<Instruction>(List.of(
                new ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, new ImmutableTypeReference(intent)),
                new ImmutableInstruction21c(Opcode.CONST_CLASS, 1, klaxon),
                new ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 3, 0, 2, 1, 0, 0, ctor),
                new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 2, 0, 0, 0, 0, stop),
                new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 2, 2, close),
                new ImmutableInstruction10x(Opcode.RETURN_VOID)));
        assertTrue(ClockTimerSymbolResolver.stopContract(code, 2, close.toString()));
        var wrongTarget = new ArrayList<>(code);
        wrongTarget.set(1, new ImmutableInstruction21c(Opcode.CONST_CLASS, 1, new ImmutableTypeReference("LView;")));
        assertFalse(ClockTimerSymbolResolver.stopContract(wrongTarget, 2, close.toString()));
        var wrongIntent = new ArrayList<>(code);
        wrongIntent.set(3, new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 2, 1, 0, 0, 0, stop));
        assertFalse(ClockTimerSymbolResolver.stopContract(wrongIntent, 2, close.toString()));
        var wrongFlag = new ArrayList<>(code);
        wrongFlag.set(4, new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 2, 0, 0, 0, 0, close));
        assertFalse(ClockTimerSymbolResolver.stopContract(wrongFlag, 2, close.toString()));
        code.remove(3);
        assertFalse(ClockTimerSymbolResolver.stopContract(code, 2, close.toString()));
    }

    @Test public void rejectsPartialAlertCloseContractsIncludingSameNamedCallsOnOtherClasses() {
        var refs = Set.of("timer_repeat", "stop_timer_action", "TIMER_ALERT_STOP_TIMER",
                "Landroid/content/Context;->startForegroundService(Landroid/content/Intent;)Landroid/content/ComponentName;",
                "Landroid/content/Context;->sendBroadcast(Landroid/content/Intent;)V");
        assertTrue(ClockTimerSymbolResolver.alertCloseContract(refs));
        var mutating = new HashSet<>(refs);
        mutating.add("Landroid/content/ContentResolver;->delete(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I");
        assertFalse(ClockTimerSymbolResolver.alertCloseContract(mutating));
        for (String removed : refs) {
            var damaged = new HashSet<>(refs);
            damaged.remove(removed);
            damaged.add("Lexample/Other;->sendBroadcast(Landroid/content/Intent;)V");
            assertFalse(ClockTimerSymbolResolver.alertCloseContract(damaged));
        }
    }
}
