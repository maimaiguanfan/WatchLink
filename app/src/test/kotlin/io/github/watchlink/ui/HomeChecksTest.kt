package io.github.watchlink.ui

import io.github.watchlink.setup.ModuleScope
import io.github.watchlink.validation.CalendarHostControl
import io.github.watchlink.validation.ClockHostControl
import io.github.watchlink.validation.HealthHostControl
import io.github.watchlink.validation.HuaweiReminderService
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeChecksTest {
    private fun scopeView(
        framework: Boolean = true,
        apiVersion: Int = 102,
        clockGranted: Boolean = true,
        healthGranted: Boolean = true,
        calendarGranted: Boolean = false,
        clockActive: Boolean = true,
        healthActive: Boolean = true,
        calendarActive: Boolean = false,
        clockRuntime: ModuleScope.TargetRuntime = ModuleScope.TargetRuntime("UP_TO_DATE", 92),
        healthRuntime: ModuleScope.TargetRuntime = ModuleScope.TargetRuntime("UP_TO_DATE", 92),
        calendarRuntime: ModuleScope.TargetRuntime = ModuleScope.TargetRuntime.ABSENT,
    ) = ModuleScope.View(
        framework, "LSPosed", "2.2.1", apiVersion,
        clockGranted, healthGranted, calendarGranted,
        clockActive, healthActive, calendarActive,
        clockRuntime, healthRuntime, calendarRuntime,
        false, false, "IDLE", true, true, false,
    )

    private fun clockReply(localClockControl: Boolean = true) =
        ClockHostControl.Reply(true, true, "READY", true, localClockControl, "CACHE")

    private fun healthReply(
        watchState: String = "READY_SUPPORTED_ON",
        sdkObserver: Boolean = true,
        symbolSource: String = "CACHE",
    ) = HealthHostControl.Reply(
        true, true, false, true, true, sdkObserver, symbolSource,
        watchState, "START_SERVICE", "SDK_AND_INTENT",
        "IDLE", "NONE", "NONE",
    )

    private val active = scopeView()
    private val ready = HomeChecks(
        clockVersion = VERIFIED_CLOCK,
        clockTrusted = true,
        healthVersion = VERIFIED_HEALTH,
        health = HuaweiReminderService.HealthEndpoint(true, true, false),
        clockHost = clockReply(),
        healthHost = healthReply(),
        calendarVersion = VERIFIED_CALENDAR,
        calendarTrusted = true,
        calendarHost = CalendarHostControl.unavailable(),
    )

    @Test fun healthyDualHostSetupIsReadyEvenWithoutOptionalCalendarScope() {
        assertEquals(emptyList<SetupIssue>(), ready.issues(active))
        assertEquals(SetupLevel.READY, setupLevel(ready, emptyList(), active))

        // Phase 2 dynamic DEX adaptation: newer Health version with sdkObserver=true stays READY without false alarms.
        val adaptedFutureHealth = ready.copy(
            healthVersion = "17.0.15.300",
            healthHost = healthReply(symbolSource = "DEX_SCAN", sdkObserver = true),
        )
        assertEquals(emptyList<SetupIssue>(), adaptedFutureHealth.issues(active))
        assertEquals(SetupLevel.READY, setupLevel(adaptedFutureHealth, emptyList(), active))
    }

    @Test fun staleApi102HostSurfacesHotReloadPromptInsteadOfGenericScopeWarning() {
        val staleScope = scopeView(
            clockActive = false,
            clockRuntime = ModuleScope.TargetRuntime("STALE", 90),
        )
        val issues = ready.issues(staleScope)
        assertEquals(listOf(SetupIssue.HOST_STALE), issues)
        assertEquals(SetupLevel.ATTENTION, setupLevel(ready, issues, staleScope))

        val staleCalendar = scopeView(
            calendarGranted = true,
            calendarActive = false,
            calendarRuntime = ModuleScope.TargetRuntime("STALE", 90),
        )
        assertEquals(listOf(SetupIssue.HOST_STALE), ready.issues(staleCalendar))
    }

    @Test fun missingLspScopeRaisesMissingScopeLevel() {
        val unconfigured = scopeView(clockGranted = false, clockActive = false)
        val issues = ready.issues(unconfigured)
        assertEquals(listOf(SetupIssue.SCOPE), issues)
        assertEquals(SetupLevel.MISSING_SCOPE, setupLevel(ready, issues, unconfigured))

        val pendingRestart = scopeView(healthActive = false)
        val pendingIssues = ready.issues(pendingRestart)
        assertEquals(listOf(SetupIssue.SCOPE), pendingIssues)
        assertEquals(SetupLevel.ATTENTION, setupLevel(ready, pendingIssues, pendingRestart))
    }

    @Test fun surfacesHostContractWatchStateAndDegradedAdvisories() {
        assertEquals(SetupLevel.PENDING, setupLevel(null, emptyList(), null))

        val cases = listOf(
            ready.copy(clockHost = clockReply(localClockControl = false)) to SetupIssue.CLOCK_CONTRACT,
            ready.copy(health = HuaweiReminderService.HealthEndpoint(true, true, true)) to SetupIssue.HEALTH_STOPPED,
            ready.copy(healthHost = healthReply(watchState = "WATCH_DISCONNECTED")) to SetupIssue.WATCH_DISCONNECTED,
            ready.copy(healthHost = healthReply(watchState = "READY_SUPPORTED_OFF")) to SetupIssue.WATCH_SWITCH_OFF,
            ready.copy(healthHost = healthReply(watchState = "READY_UNSUPPORTED")) to SetupIssue.WATCH_UNSUPPORTED,
            ready.copy(healthHost = healthReply(watchState = "READY_DEVICE_MISMATCH")) to SetupIssue.WATCH_DEVICE_MISMATCH,
            ready.copy(healthHost = healthReply(sdkObserver = false, symbolSource = "DEGRADED")) to SetupIssue.HEALTH_SDK_DEGRADED,
        )
        for ((checks, expected) in cases) {
            val issues = checks.issues(active)
            assertEquals(listOf(expected), issues)
            assertEquals(SetupLevel.ATTENTION, setupLevel(checks, issues, active))
        }
    }

    @Test fun incompleteEvidenceStaysPendingUntilTheHostsAndWatchAnswer() {
        val waiting = scopeView(framework = false, clockActive = false, healthActive = false)
        for ((checks, scope) in listOf(
            ready to null,
            ready to waiting,
            ready.copy(clockHost = null) to active,
            ready.copy(healthHost = HealthHostControl.unavailable()) to active,
            ready.copy(healthHost = healthReply(watchState = "UNKNOWN")) to active,
        )) {
            val issues = checks.issues(scope)
            assertEquals(emptyList<SetupIssue>(), issues)
            assertEquals(SetupLevel.PENDING, setupLevel(checks, issues, scope))
        }
        val disconnected = ready.copy(clockHost = null, healthHost = healthReply(watchState = "WATCH_DISCONNECTED"))
        assertEquals(SetupLevel.ATTENTION, setupLevel(disconnected, disconnected.issues(active), active))
    }

    @Test fun sleepingClockRemainsReadyWhenScopeRemotePrefsAndHealthWatchAreReady() {
        val sleepingClockScope = scopeView(
            clockActive = false,
            clockRuntime = ModuleScope.TargetRuntime.ABSENT,
        )
        val checksWithSleepingClock = ready.copy(
            clockHost = ClockHostControl.Reply(false, false, "UNREACHABLE", false, false, "UNKNOWN")
        )
        val issues = checksWithSleepingClock.issues(sleepingClockScope)
        assertEquals(emptyList<SetupIssue>(), issues)
        assertEquals(SetupLevel.READY, setupLevel(checksWithSleepingClock, issues, sleepingClockScope))
    }
}
