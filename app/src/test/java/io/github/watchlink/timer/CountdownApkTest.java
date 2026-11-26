package io.github.watchlink.timer;

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class CountdownApkTest {
    private static ZipFile sample(String name) throws Exception {
        Path path = Path.of("/tmp/watchlink-apks", name + ".apk");
        assumeTrue("Local OEM APK required", Files.isRegularFile(path));
        return new ZipFile(path.toFile());
    }

    private static List<String> refs(ZipFile apk, String dex, String owner, String method) throws Exception {
        var bytes = apk.getInputStream(apk.getEntry(dex)).readAllBytes();
        var file = new DexBackedDexFile(null, bytes);
        for (var type : file.getClasses()) {
            if (!type.getType().equals(owner)) continue;
            for (var candidate : type.getMethods()) {
                String signature = candidate.toString();
                boolean matches = method.contains("(") ? signature.equals(owner + "->" + method) : candidate.getName().equals(method);
                if (!matches || candidate.getImplementation() == null) continue;
                var values = new ArrayList<String>();
                for (var instruction : candidate.getImplementation().getInstructions()) {
                    if (instruction instanceof ReferenceInstruction r) {
                        var ref = r.getReference();
                        values.add(ref instanceof StringReference s ? s.getString() : ref.toString());
                    }
                }
                return values;
            }
        }
        throw new AssertionError(owner + "->" + method);
    }

    @Test public void clockNotifiesAfterTimerWritesAndValidatesAlarmBeforeDisplay() throws Exception {
        try (var apk = sample("clock")) {
            assertTrue(refs(apk, "classes2.dex", "La7/c;", "<clinit>").contains(
                    "content://com.coloros.alarmclock.alarmclock/timer_schedule"));
            String provider = "Lcom/oplus/alarmclock/provider/ClockProvider;";
            for (String method : List.of("update", "insert", "delete")) {
                var calls = refs(apk, "classes2.dex", provider, method);
                assertTrue(calls.contains("timer_schedule"));
                assertTrue(calls.contains(provider + "->notifyBothProvider(Landroid/net/Uri;)V"));
            }
            assertTrue(refs(apk, "classes3.dex", "Lf7/i;", "b").containsAll(
                    List.of("duration", "first_start_time", "state", "alert_time", "pause_remain_time")));
            var states = refs(apk, "classes3.dex",
                    "Lcom/oplus/alarmclock/timer/data/TimerState$TimerScheduleState;", "<clinit>");
            assertTrue(states.indexOf("STARTED") > states.indexOf("UNKNOWN"));
            assertTrue(states.indexOf("PAUSED") > states.indexOf("STARTED"));
            String service = "Lcom/oplus/alarmclock/timer/TimerFloatingViewService;";
            var display = refs(apk, "classes3.dex", service, "onStartCommand");
            int validate = display.indexOf(service + "->n(Landroid/content/Intent;)Z");
            assertTrue(validate >= 0);
            assertTrue(display.indexOf(service + "->v(Landroid/content/Context;Landroid/content/Intent;)V") > validate);
            assertTrue(display.contains("stop_timer_action"));
        }
    }

    @Test public void clockDismissStopsKlaxonBeforeClosingAlertAndDestroyReleasesPlayback() throws Exception {
        try (var clock = sample("clock")) {
            String dex = "classes3.dex";
            String view = "Lcom/oplus/alarmclock/timer/TimerFloatingViewService;";
            String klaxon = "Lcom/oplus/alarmclock/timer/TimerKlaxon;";
            String type = "(Landroid/content/Context;Z)V";
            String stopService = "Landroid/content/Context;->stopService(Landroid/content/Intent;)Z";
            var dismiss = refs(clock, dex, view, "z" + type);
            int target = dismiss.indexOf(klaxon), stop = dismiss.indexOf(stopService);
            assertTrue(target >= 0 && stop > target && dismiss.indexOf(view + "->B" + type) > stop);
            assertFalse(refs(clock, dex, view, "B" + type).contains(stopService));
            assertTrue(refs(clock, dex, "Lcom/oplus/alarmclock/timer/TimerNotificationReceiver;", "f()V")
                    .contains(view + "->z" + type));
            assertTrue(refs(clock, dex, klaxon, "onDestroy()V").contains(klaxon + "->g()V"));
            assertTrue(refs(clock, dex, klaxon, "g()V").containsAll(List.of(
                    "Lcom/oplus/alarmclock/alert/player/RingtonePlayerApi;->q(Z)V",
                    "Lcom/oplus/alarmclock/alert/player/RingtonePlayerApi;->r()V")));
        }
    }

    @Test public void healthSupportsProgressEventAndCorrelatedAlarmButtons() throws Exception {
        try (var health = sample("health")) {
            assertTrue(refs(health, "classes4.dex", "Lctp;", "<clinit>").containsAll(
                    List.of("DELIVERY", "QUEUE", "PICK_UP", "TAXI", "Lctp;->b:Ljava/util/List;")));
            assertTrue(refs(health, "classes2.dex", "Locp;", "f(ILandroid/os/Bundle;)Ljava/lang/String;").contains("Lctp;->b:Ljava/util/List;"));
            String service = "Lcom/huawei/hwservicesmgr/ExternalPhoneService;";
            assertTrue(refs(health, "classes11.dex", service, "onStartCommand").contains("alarm_clock_msg_from_device"));
            assertTrue(refs(health, "classes11.dex", service, "e(Landroid/os/Bundle;Ljava/lang/StringBuffer;Lcom/huawei/datatype/DeviceCommand;)V").contains("formatCommandByOpen 5.8.9 start"));
        }
    }
}
