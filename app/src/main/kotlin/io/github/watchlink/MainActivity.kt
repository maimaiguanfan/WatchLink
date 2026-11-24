package io.github.watchlink

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.AlarmClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.watchlink.alarm.probe.SenderVerifier
import io.github.watchlink.alarm.probe.TargetProfile
import io.github.watchlink.diagnostics.DiagnosticShare
import io.github.watchlink.setup.ModuleScope
import io.github.watchlink.ui.HomeChecks
import io.github.watchlink.ui.WatchLinkApp
import io.github.watchlink.ui.WatchLinkUiState
import io.github.watchlink.validation.CalendarHostControl
import io.github.watchlink.validation.ClockHostControl
import io.github.watchlink.validation.HealthDaemonControl
import io.github.watchlink.validation.HealthHostControl
import io.github.watchlink.validation.HuaweiReminderService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private var ui by mutableStateOf(WatchLinkUiState())
    private var switchRevision = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatchLinkApp(
                state = ui,
                onEnable = ::setEnabled,
                onCalendarEnable = ::setCalendarEnabled,
                onPictureEnable = ::setPictureEnabled,
                onVoipEnable = ::setVoipEnabled,
                onDismissMessage = { ui = ui.copy(message = null) },
                onClock = { open(Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage(TargetProfile.CLOCK.packageName)) },
                onCalendar = { open(packageManager.getLaunchIntentForPackage(TargetProfile.CALENDAR.packageName)) },
                onHealth = { open(packageManager.getLaunchIntentForPackage(TargetProfile.HEALTH.packageName)) },
                onRequestScope = ::requestScope,
                onRequestCalendarScope = ::requestCalendarScope,
                onHotReload = ::triggerHotReload,
                onRefresh = { lifecycleScope.launch { refreshAll(forceScope = true) } },
                onHealthDownload = { open(Intent(Intent.ACTION_VIEW, "https://consumer.huawei.com/cn/support/content/zh-cn15981770/".toUri())) },
                onCoolapk = ::openCoolapk,
                onStartTest = { runHealthTest(HealthHostControl.TEST) },
                onCancelTest = { runHealthTest(HealthHostControl.CANCEL_TEST) },
                onShare = ::shareDiagnostics,
            )
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                refreshAll(forceScope = true)
                while (true) {
                    val activeTest = ui.healthHost?.testState() in setOf("SENDING", "SENT", "SENT_DIRECT", "SERVICE_RECEIVED", "OUTBOUND")
                    delay(if (activeTest) 1500L else 5000L)
                    refreshAll(forceScope = false)
                }
            }
        }
    }

    private suspend fun refreshAll(forceScope: Boolean) {
        refreshScope(forceScope)
        queryHosts()
        readChecks()
    }

    private suspend fun clockCommand(action: String): ClockHostControl.Reply =
        suspendCancellableCoroutine { continuation ->
            ClockHostControl.send(applicationContext, action) { reply ->
                if (continuation.isActive) continuation.resume(reply)
            }
        }

    private suspend fun calendarCommand(action: String): CalendarHostControl.Reply =
        suspendCancellableCoroutine { continuation ->
            CalendarHostControl.send(applicationContext, action) { reply ->
                if (continuation.isActive) continuation.resume(reply)
            }
        }

    private suspend fun healthCommand(action: String): HealthHostControl.Reply =
        suspendCancellableCoroutine { continuation ->
            HealthHostControl.send(applicationContext, action) { reply ->
                if (continuation.isActive) continuation.resume(reply)
            }
        }

    private suspend fun healthDaemonCommand(action: String): HealthDaemonControl.Reply =
        suspendCancellableCoroutine { continuation ->
            HealthDaemonControl.send(applicationContext, action) { reply ->
                if (continuation.isActive) continuation.resume(reply)
            }
        }

    private suspend fun writeRemoteEnabled(enabled: Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            ModuleScope.writeRemoteEnabled(enabled) { ok ->
                if (continuation.isActive) continuation.resume(ok)
            }
        }

    private suspend fun writeRemoteCalendarEnabled(enabled: Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            ModuleScope.writeRemoteCalendarEnabled(enabled) { ok ->
                if (continuation.isActive) continuation.resume(ok)
            }
        }

    private suspend fun writeRemotePictureEnabled(enabled: Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            ModuleScope.writeRemoteNotifPictureEnabled(enabled) { ok ->
                if (continuation.isActive) continuation.resume(ok)
            }
        }

    private suspend fun writeRemoteVoipEnabled(enabled: Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            ModuleScope.writeRemoteVoipCallEnabled(enabled) { ok ->
                if (continuation.isActive) continuation.resume(ok)
            }
        }

    private suspend fun hotReloadTargets(onlyStale: Boolean): String =
        suspendCancellableCoroutine { continuation ->
            ModuleScope.hotReloadTargets(applicationContext, onlyStale) { outcome ->
                if (continuation.isActive) continuation.resume(outcome)
            }
        }

    private suspend fun queryHosts() {
        if (ui.saving || ui.calendarSaving || ui.pictureSaving || ui.voipSaving) return
        val revision = ++switchRevision
        val (clockReply, calendarReply, healthReply, daemonReply) = coroutineScope {
            val clockJob = async { clockCommand(ClockHostControl.QUERY) }
            val calendarJob = async { calendarCommand(CalendarHostControl.QUERY) }
            val healthJob = async { healthCommand(HealthHostControl.QUERY) }
            val daemonJob = async { healthDaemonCommand(HealthDaemonControl.QUERY) }
            HostReplies(clockJob.await(), calendarJob.await(), healthJob.await(), daemonJob.await())
        }
        if (revision == switchRevision) {
            ui = ui.copy(
                clockHost = clockReply,
                calendarHost = calendarReply,
                healthHost = healthReply,
                healthDaemon = daemonReply,
            )
        }
    }

    private data class HostReplies(
        val clock: ClockHostControl.Reply,
        val calendar: CalendarHostControl.Reply,
        val health: HealthHostControl.Reply,
        val daemon: HealthDaemonControl.Reply,
    )

    private suspend fun readChecks() {
        val currentClock = ui.clockHost
        val currentCalendar = ui.calendarHost
        val currentHealth = ui.healthHost
        val checks = withContext(Dispatchers.IO) {
            runCatching {
                val clockVersion = readVersion(TargetProfile.CLOCK.packageName)
                val calendarVersion = readVersion(TargetProfile.CALENDAR.packageName)
                val healthVersion = readVersion(TargetProfile.HEALTH.packageName)
                HomeChecks(
                    clockVersion = clockVersion,
                    clockTrusted = SenderVerifier.trustedReminderTarget(applicationContext, TargetProfile.CLOCK),
                    healthVersion = healthVersion,
                    health = HuaweiReminderService.inspectHealthEndpoint(applicationContext),
                    clockHost = currentClock,
                    healthHost = currentHealth,
                    calendarVersion = calendarVersion,
                    calendarTrusted = SenderVerifier.trustedReminderTarget(applicationContext, TargetProfile.CALENDAR),
                    calendarHost = currentCalendar,
                )
            }.getOrNull()
        }
        ui = ui.copy(checks = checks, readError = checks == null)
    }

    private fun readVersion(pkg: String): String? = try {
        packageManager.getPackageInfo(pkg, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private suspend fun refreshScope(force: Boolean) {
        val scope = withContext(Dispatchers.IO) {
            ModuleScope.refresh(applicationContext, force)
            ModuleScope.inspect()
        }
        ui = ui.copy(scope = scope)
    }

    private fun requestScope() {
        lifecycleScope.launch {
            val scope = withContext(Dispatchers.IO) {
                ModuleScope.request(applicationContext)
                ModuleScope.inspect()
            }
            ui = ui.copy(scope = scope)
        }
    }

    private fun requestCalendarScope() {
        lifecycleScope.launch {
            val scope = withContext(Dispatchers.IO) {
                ModuleScope.requestCalendar(applicationContext)
                ModuleScope.inspect()
            }
            ui = ui.copy(scope = scope)
        }
    }

    private fun triggerHotReload(onlyStale: Boolean) {
        if (ui.reloading) return
        ui = ui.copy(reloading = true, message = null)
        lifecycleScope.launch {
            val outcome = hotReloadTargets(onlyStale)
            delay(500L)
            refreshAll(forceScope = true)
            ui = ui.copy(
                reloading = false,
                message = when (outcome) {
                    "SUCCEEDED" -> "宿主已完成热重载，新版本模块已生效。"
                    "IN_PROGRESS" -> "宿主正在热重载，请稍后刷新查看结果。"
                    "UNSUPPORTED" -> "当前框架不支持热重载，请手动重启目标宿主应用。"
                    "NO_TARGETS" -> "当前没有需要热重载的运行中宿主。"
                    "PROCESS_DIED" -> "宿主进程已重启，请稍后刷新确认生效状态。"
                    else -> "热重载未完成（可能正在响铃保护或被框架拒绝），可稍后重试或手动重启宿主。"
                }
            )
        }
    }

    private fun setEnabled(enable: Boolean) {
        if (ui.saving) return
        ++switchRevision
        ui = ui.copy(saving = true, message = null)
        lifecycleScope.launch {
            val remoteOk = writeRemoteEnabled(enable)
            val action = if (enable) ClockHostControl.ENABLE else ClockHostControl.DISABLE
            val healthAction = if (enable) HealthHostControl.ENABLE else HealthHostControl.DISABLE
            val (clockReply, healthReply) = coroutineScope {
                val clockJob = async { clockCommand(action) }
                val healthJob = async { healthCommand(healthAction) }
                clockJob.await() to healthJob.await()
            }
            val scopeNow = withContext(Dispatchers.IO) { ModuleScope.inspect() }
            val anyHostSaved = (clockReply.reachable() && clockReply.enabled() == enable)
                    || (healthReply.reachable() && healthReply.enabled() == enable)
            val saved = remoteOk || anyHostSaved
            ui = ui.copy(
                scope = scopeNow,
                clockHost = clockReply,
                healthHost = healthReply,
                saving = false,
                message = when {
                    !saved -> "未能保存开关状态。请确认 LSPosed 已启用，或先打开系统时钟和运动健康后再试。"
                    !remoteOk && (!clockReply.reachable() || !healthReply.reachable()) ->
                        "已保存到当前在线宿主；离线宿主打开后请重新核对。"
                    else -> null
                },
            )
            readChecks()
        }
    }

    private fun setCalendarEnabled(enable: Boolean) {
        if (ui.calendarSaving) return
        ++switchRevision
        ui = ui.copy(calendarSaving = true, message = null)
        lifecycleScope.launch {
            val remoteOk = writeRemoteCalendarEnabled(enable)
            val calAction = if (enable) CalendarHostControl.ENABLE else CalendarHostControl.DISABLE
            val healthAction = if (enable) HealthHostControl.ENABLE_CALENDAR else HealthHostControl.DISABLE_CALENDAR
            val (calendarReply, healthReply) = coroutineScope {
                val calJob = async { calendarCommand(calAction) }
                val healthJob = async { healthCommand(healthAction) }
                calJob.await() to healthJob.await()
            }
            val scopeNow = withContext(Dispatchers.IO) { ModuleScope.inspect() }
            val anyHostSaved = (calendarReply.reachable() && calendarReply.enabled() == enable)
                    || (healthReply.reachable() && healthReply.calendarEnabled() == enable)
            val saved = remoteOk || anyHostSaved
            ui = ui.copy(
                scope = scopeNow,
                calendarHost = calendarReply,
                healthHost = healthReply,
                calendarSaving = false,
                message = when {
                    !saved -> "未能保存日历强提醒开关状态。请确认 LSPosed 已启用，或先打开系统日历与运动健康后再试。"
                    enable && scopeNow.frameworkConnected() && !scopeNow.calendarGranted() ->
                        "已开启日历强提醒开关，请点击下方「申请系统日历作用域」完成授权。"
                    !remoteOk && (!calendarReply.reachable() || !healthReply.reachable()) ->
                        "已保存到当前在线宿主；离线宿主打开后请重新核对。"
                    else -> null
                },
            )
            readChecks()
        }
    }

    private fun setPictureEnabled(enable: Boolean) {
        if (ui.pictureSaving) return
        ++switchRevision
        ui = ui.copy(pictureSaving = true, message = null)
        lifecycleScope.launch {
            val remoteOk = writeRemotePictureEnabled(enable)
            val daemonAction = if (enable) HealthDaemonControl.ENABLE_PICTURE else HealthDaemonControl.DISABLE_PICTURE
            val daemonReply = healthDaemonCommand(daemonAction)
            val scopeNow = withContext(Dispatchers.IO) { ModuleScope.inspect() }
            val saved = remoteOk || (daemonReply.reachable() && daemonReply.pictureEnabled() == enable)
            ui = ui.copy(
                scope = scopeNow,
                healthDaemon = daemonReply,
                pictureSaving = false,
                message = when {
                    !saved -> "未能保存通知图片推送开关。请确认 LSPosed 已启用，或先打开华为运动健康后再试。"
                    enable && !daemonReply.reachable() ->
                        "已开启通知图片推送，请确保运动健康通知访问已授权并重启/热重载运动健康。"
                    else -> null
                },
            )
            readChecks()
        }
    }

    private fun setVoipEnabled(enable: Boolean) {
        if (ui.voipSaving) return
        ++switchRevision
        ui = ui.copy(voipSaving = true, message = null)
        lifecycleScope.launch {
            val remoteOk = writeRemoteVoipEnabled(enable)
            val daemonAction = if (enable) HealthDaemonControl.ENABLE_VOIP else HealthDaemonControl.DISABLE_VOIP
            val healthAction = if (enable) HealthHostControl.ENABLE_VOIP else HealthHostControl.DISABLE_VOIP
            val (daemonReply, healthReply) = coroutineScope {
                val daemonJob = async { healthDaemonCommand(daemonAction) }
                val healthJob = async { healthCommand(healthAction) }
                daemonJob.await() to healthJob.await()
            }
            val scopeNow = withContext(Dispatchers.IO) { ModuleScope.inspect() }
            val anyHostSaved = (daemonReply.reachable() && daemonReply.voipEnabled() == enable)
                    || (healthReply.reachable() && healthReply.voipEnabled() == enable)
            val saved = remoteOk || anyHostSaved
            ui = ui.copy(
                scope = scopeNow,
                healthDaemon = daemonReply,
                healthHost = healthReply,
                voipSaving = false,
                message = when {
                    !saved -> "未能保存 VoIP 通话开关。请确认 LSPosed 已启用，或先打开华为运动健康后再试。"
                    enable && !daemonReply.reachable() ->
                        "已开启 VoIP 来电振铃与拒接，请确保运动健康通知访问已授权并重启/热重载运动健康。"
                    else -> null
                },
            )
            readChecks()
        }
    }

    private fun runHealthTest(command: String) {
        if (ui.testing) return
        ui = ui.copy(testing = true, message = null)
        lifecycleScope.launch {
            val reply = healthCommand(command)
            ui = ui.copy(
                healthHost = reply,
                testing = false,
                message = if (!reply.reachable()) "运动健康 :PhoneService 未应答。请先打开华为运动健康并确认手表已连接。" else null,
            )
            readChecks()
        }
    }

    private fun openCoolapk() {
        val profile = "https://www.coolapk.com/u/1224931".toUri()
        if (!launchIfAvailable(Intent(Intent.ACTION_VIEW, profile).setPackage("com.coolapk.market"))) {
            open(Intent(Intent.ACTION_VIEW, profile))
        }
    }

    private fun shareDiagnostics() {
        if (ui.sharing) return
        ui = ui.copy(sharing = true)
        lifecycleScope.launch {
            try {
                val chooser = withContext(Dispatchers.IO) { DiagnosticShare.prepare(applicationContext) }
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) open(chooser)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ui = ui.copy(message = "未能准备诊断日志，请重试。")
            } finally {
                ui = ui.copy(sharing = false)
            }
        }
    }

    private fun launchIfAvailable(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun open(intent: Intent?) {
        if (intent == null || !launchIfAvailable(intent)) ui = ui.copy(message = "未找到可打开此页面的应用。")
    }
}
