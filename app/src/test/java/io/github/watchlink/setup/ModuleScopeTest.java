package io.github.watchlink.setup;

import io.github.watchlink.alarm.probe.TargetProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModuleScopeTest {
    private static ModuleScope.View state(boolean framework, boolean clockGrant, boolean healthGrant,
                                          boolean clockHook, boolean healthHook) {
        return state(framework, 102, clockGrant, healthGrant, false, clockHook, healthHook, false,
                new ModuleScope.TargetRuntime("UP_TO_DATE", 92),
                new ModuleScope.TargetRuntime("UP_TO_DATE", 92),
                ModuleScope.TargetRuntime.ABSENT);
    }

    private static ModuleScope.View state(boolean framework, int api, boolean clockGrant, boolean healthGrant,
                                          boolean calendarGrant, boolean clockHook, boolean healthHook,
                                          boolean calendarHook,
                                          ModuleScope.TargetRuntime clockRt, ModuleScope.TargetRuntime healthRt,
                                          ModuleScope.TargetRuntime calendarRt) {
        return new ModuleScope.View(framework, "LSPosed", "2.2.1", api,
                clockGrant, healthGrant, calendarGrant,
                clockHook, healthHook, calendarHook,
                clockRt, healthRt, calendarRt,
                false, false, "IDLE", true, true, false);
    }

    @Test public void grantedScopesAloneDoNotConfirmThatReminderCodeIsRunning() {
        assertTrue(state(true, true, true, false, false).configured());
        assertFalse(state(true, true, true, true, false).active());
        assertFalse(state(true, true, true, false, true).active());
        assertTrue(state(true, true, true, true, true).active());
    }

    @Test public void aRevokedScopeTakesPrecedenceOverAnAlreadyLoadedHost() {
        assertFalse(state(true, true, false, true, true).active());
        assertFalse(state(true, false, true, true, true).active());
    }

    @Test public void twoHostRepliesCanConfirmActivityWhenFrameworkServiceIsUnavailable() {
        assertFalse(state(false, false, false, true, true).configured());
        assertTrue(state(false, false, false, true, true).active());
        assertFalse(state(false, false, false, false, true).active());
    }

    @Test public void optionalCalendarScopeDoesNotBlockCoreActivityAndTracksItsOwnReadiness() {
        var coreOnly = state(true, 102, true, true, false, true, true, false,
                new ModuleScope.TargetRuntime("UP_TO_DATE", 91),
                new ModuleScope.TargetRuntime("UP_TO_DATE", 91),
                ModuleScope.TargetRuntime.ABSENT);
        assertTrue(coreOnly.configured());
        assertTrue(coreOnly.active());
        assertFalse(coreOnly.calendarReady());
        assertFalse(coreOnly.hasStaleTarget());

        var staleCal = state(true, 102, true, true, true, true, true, false,
                new ModuleScope.TargetRuntime("UP_TO_DATE", 91),
                new ModuleScope.TargetRuntime("UP_TO_DATE", 91),
                new ModuleScope.TargetRuntime("STALE", 90));
        assertTrue(staleCal.hasStaleTarget());
        assertFalse(staleCal.calendarReady());

        var readyCal = state(true, 102, true, true, true, true, true, true,
                new ModuleScope.TargetRuntime("UP_TO_DATE", 91),
                new ModuleScope.TargetRuntime("UP_TO_DATE", 91),
                new ModuleScope.TargetRuntime("UP_TO_DATE", 91));
        assertTrue(readyCal.calendarReady());
        assertFalse(readyCal.hasStaleTarget());
    }

    @Test public void api102TargetRuntimeDetectsStaleTargetsAndSummarizesHotReloadOutcomes() {
        var stale = state(true, 102, true, true, false, false, true, false,
                new ModuleScope.TargetRuntime("STALE", 90),
                new ModuleScope.TargetRuntime("UP_TO_DATE", 91),
                ModuleScope.TargetRuntime.ABSENT);
        assertTrue(stale.supportsApi102());
        assertTrue(stale.hasStaleTarget());
        assertTrue(stale.canHotReloadAny());

        var api101 = state(true, 101, true, true, false, true, true, false,
                ModuleScope.TargetRuntime.UNSUPPORTED, ModuleScope.TargetRuntime.UNSUPPORTED,
                ModuleScope.TargetRuntime.UNSUPPORTED);
        assertFalse(api101.supportsApi102());
        assertFalse(api101.hasStaleTarget());
        assertFalse(api101.canHotReloadAny());

        assertEquals(new ModuleScope.TargetRuntime("STALE", 90), ModuleScope.classifyState("STALE", 90));
        assertEquals(ModuleScope.TargetRuntime.ABSENT, ModuleScope.classifyState("UNKNOWN", 10));
        assertEquals("SUCCEEDED", ModuleScope.summarizeHotReload(Set.of("SUCCEEDED")));
        assertEquals("FAILED", ModuleScope.summarizeHotReload(Set.of("SUCCEEDED", "FAILED")));
        assertEquals("UNSUPPORTED", ModuleScope.summarizeHotReload(Set.of("UNSUPPORTED", "SUCCEEDED")));
    }

    @Test public void scopeListContainsClockCalendarAndHealthWithoutSystemUi() throws Exception {
        List<String> declared = Files.readAllLines(Path.of("src/main/resources/META-INF/xposed/scope.list"));
        String clock = TargetProfile.CLOCK.packageName;
        String calendar = TargetProfile.CALENDAR.packageName;
        String health = TargetProfile.HEALTH.packageName;
        assertEquals(Set.of(clock, calendar, health), Set.copyOf(declared));
        assertEquals(List.of(clock, health), ModuleScope.missingFrom(List.of()));
        assertEquals(List.of(clock), ModuleScope.missingFrom(List.of(health)));
        assertEquals(List.of(health), ModuleScope.missingFrom(List.of(clock)));
        assertTrue(ModuleScope.missingFrom(List.of(clock, health)).isEmpty());
        assertEquals(List.of(calendar), ModuleScope.missingPackages(ModuleScope.OPTIONAL_PACKAGES, List.of(clock, health)));
        assertTrue(ModuleScope.missingPackages(ModuleScope.OPTIONAL_PACKAGES, List.of(clock, health, calendar)).isEmpty());
    }
}
