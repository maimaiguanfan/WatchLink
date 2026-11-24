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
        return state(framework, 102, clockGrant, healthGrant, clockHook, healthHook,
                new ModuleScope.TargetRuntime("UP_TO_DATE", 91),
                new ModuleScope.TargetRuntime("UP_TO_DATE", 91));
    }

    private static ModuleScope.View state(boolean framework, int api, boolean clockGrant, boolean healthGrant,
                                          boolean clockHook, boolean healthHook,
                                          ModuleScope.TargetRuntime clockRt, ModuleScope.TargetRuntime healthRt) {
        return new ModuleScope.View(framework, "LSPosed", "2.2.1", api,
                clockGrant, healthGrant, clockHook, healthHook,
                clockRt, healthRt,
                false, false, "IDLE", true, true);
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

    @Test public void api102TargetRuntimeDetectsStaleTargetsAndSummarizesHotReloadOutcomes() {
        var stale = state(true, 102, true, true, false, true,
                new ModuleScope.TargetRuntime("STALE", 90),
                new ModuleScope.TargetRuntime("UP_TO_DATE", 91));
        assertTrue(stale.supportsApi102());
        assertTrue(stale.hasStaleTarget());
        assertTrue(stale.canHotReloadAny());

        var api101 = state(true, 101, true, true, true, true,
                ModuleScope.TargetRuntime.UNSUPPORTED, ModuleScope.TargetRuntime.UNSUPPORTED);
        assertFalse(api101.supportsApi102());
        assertFalse(api101.hasStaleTarget());
        assertFalse(api101.canHotReloadAny());

        assertEquals(new ModuleScope.TargetRuntime("STALE", 90), ModuleScope.classifyState("STALE", 90));
        assertEquals(ModuleScope.TargetRuntime.ABSENT, ModuleScope.classifyState("UNKNOWN", 10));
        assertEquals("SUCCEEDED", ModuleScope.summarizeHotReload(Set.of("SUCCEEDED")));
        assertEquals("FAILED", ModuleScope.summarizeHotReload(Set.of("SUCCEEDED", "FAILED")));
        assertEquals("UNSUPPORTED", ModuleScope.summarizeHotReload(Set.of("UNSUPPORTED", "SUCCEEDED")));
    }

    @Test public void scopeListContainsOnlyClockAndHealthWithoutSystemUi() throws Exception {
        List<String> declared = Files.readAllLines(Path.of("src/main/resources/META-INF/xposed/scope.list"));
        String clock = TargetProfile.CLOCK.packageName, health = TargetProfile.HEALTH.packageName;
        assertEquals(Set.of(clock, health), Set.copyOf(declared));
        assertEquals(List.of(clock, health), ModuleScope.missingFrom(List.of()));
        assertEquals(List.of(clock), ModuleScope.missingFrom(List.of(health)));
        assertEquals(List.of(health), ModuleScope.missingFrom(List.of(clock)));
        assertTrue(ModuleScope.missingFrom(List.of(clock, health)).isEmpty());
    }
}
