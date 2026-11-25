package io.github.watchlink.ui

import io.github.watchlink.setup.ModuleScope
import io.github.watchlink.validation.CalendarHostControl
import io.github.watchlink.validation.ClockHostControl
import io.github.watchlink.validation.HealthHostControl
import io.github.watchlink.validation.HuaweiReminderService

internal const val VERIFIED_CLOCK = "17.6.100"
internal const val VERIFIED_CALENDAR = "17.6.30"
internal const val VERIFIED_HEALTH = "17.0.10.330"
internal const val VERIFIED_WIRELESS = "17.6.5"
internal const val VERIFIED_UMS = "17.22.8"

internal enum class SetupIssue {
    HOST_STALE,
    SCOPE,
    CLOCK_APP,
    CLOCK_CONTRACT,
    HEALTH_SERVICE,
    HEALTH_STOPPED,
    WATCH_DISCONNECTED,
    WATCH_SWITCH_OFF,
    WATCH_UNSUPPORTED,
    HEALTH_SDK_DEGRADED,
}

internal enum class SetupLevel { CHECKING, READY, ATTENTION, MISSING_SCOPE }

internal data class HomeChecks(
    val clockVersion: String?,
    val clockTrusted: Boolean,
    val healthVersion: String?,
    val health: HuaweiReminderService.HealthEndpoint,
    val clockHost: ClockHostControl.Reply? = null,
    val healthHost: HealthHostControl.Reply? = null,
    val calendarVersion: String? = null,
    val calendarTrusted: Boolean = false,
    val calendarHost: CalendarHostControl.Reply? = null,
    val wirelessVersion: String? = null,
    val wirelessTrusted: Boolean = false,
    val umsVersion: String? = null,
    val umsTrusted: Boolean = false,
) {
    fun issues(scope: ModuleScope.View?): List<SetupIssue> = buildList {
        val stale = scope?.hasStaleTarget() == true
        if (stale) add(SetupIssue.HOST_STALE)
        if (scope?.active() != true && !(stale && scope.configured())) {
            add(SetupIssue.SCOPE)
        }
        if (!clockTrusted) add(SetupIssue.CLOCK_APP)
        if (clockHost?.reachable() == true && !clockHost.localClockControl()) add(SetupIssue.CLOCK_CONTRACT)
        if (!health.trusted() || !health.service()) add(SetupIssue.HEALTH_SERVICE)
        else if (health.stopped()) add(SetupIssue.HEALTH_STOPPED)
        if (healthHost?.reachable() == true) {
            when (healthHost.watchState()) {
                "WATCH_DISCONNECTED" -> add(SetupIssue.WATCH_DISCONNECTED)
                "READY_SUPPORTED_OFF" -> add(SetupIssue.WATCH_SWITCH_OFF)
                "READY_UNSUPPORTED" -> add(SetupIssue.WATCH_UNSUPPORTED)
            }
            if (!healthHost.sdkObserver()) add(SetupIssue.HEALTH_SDK_DEGRADED)
        }
    }
}

internal fun setupLevel(checks: HomeChecks?, issues: List<SetupIssue>, scope: ModuleScope.View?): SetupLevel = when {
    checks == null -> SetupLevel.CHECKING
    SetupIssue.SCOPE in issues && scope?.configured() != true -> SetupLevel.MISSING_SCOPE
    issues.isNotEmpty() -> SetupLevel.ATTENTION
    else -> SetupLevel.READY
}
