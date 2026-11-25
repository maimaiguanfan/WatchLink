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
import io.github.watchlink.ui.WifiBulkItem
import io.github.watchlink.ui.WifiBulkUiState
import io.github.watchlink.validation.CalendarHostControl
import io.github.watchlink.validation.ClockHostControl
import io.github.watchlink.validation.HealthHostControl
import io.github.watchlink.validation.HuaweiReminderService
import io.github.watchlink.wifi.RootWifiConfigReader
import io.github.watchlink.wifi.WifiNetworkRecord
import io.github.watchlink.wifi.WifiSyncWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var bulkSyncJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatchLinkApp(
                state = ui,
                onEnable = ::setEnabled,
                onCalendarEnable = ::setCalendarEnabled,
                onWifiAutoEnable = ::setWifiAutoEnabled,
                onDismissMessage = { ui = ui.copy(message = null) },
                onClock = { open(Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage(TargetProfile.CLOCK.packageName)) },
                onCalendar = { open(packageManager.getLaunchIntentForPackage(TargetProfile.CALENDAR.packageName)) },
                onHealth = { open(packageManager.getLaunchIntentForPackage(TargetProfile.HEALTH.packageName)) },
                onRequestScope = ::requestScope,
                onRequestCalendarScope = ::requestCalendarScope,
                onRequestWirelessScope = ::requestWirelessScope,
                onSyncCurrentWifi = ::syncCurrentWifi,
                onLoadSavedWifi = ::loadSavedWifiViaRoot,
                onToggleWifiSelection = ::toggleWifiSelection,
                onSelectWifiPreset = ::selectWifiPreset,
                onToggleWifiForceResend = ::toggleWifiForceResend,
                onStartBulkWifiSync = ::startBulkWifiSync,
                onCancelBulkWifiSync = ::cancelBulkWifiSync,
                onClearBulkWifiMemory = ::clearBulkWifiMemory,
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

    override fun onDestroy() {
        clearBulkWifiMemory()
        super.onDestroy()
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

    private suspend fun writeRemoteWifiAutoSyncEnabled(enabled: Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            ModuleScope.writeRemoteWifiAutoSyncEnabled(enabled) { ok ->
                if (continuation.isActive) continuation.resume(ok)
            }
        }

    private suspend fun syncOneWifi(record: WifiNetworkRecord, force: Boolean): WifiSyncWire.SyncResult =
        suspendCancellableCoroutine { continuation ->
            WifiSyncWire.sendSyncOne(applicationContext, record, force) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }

    private suspend fun hotReloadTargets(onlyStale: Boolean): String =
        suspendCancellableCoroutine { continuation ->
            ModuleScope.hotReloadTargets(applicationContext, onlyStale) { outcome ->
                if (continuation.isActive) continuation.resume(outcome)
            }
        }

    private suspend fun queryHosts() {
        if (ui.saving || ui.calendarSaving || ui.wifiAutoSaving) return
        val revision = ++switchRevision
        val (clockReply, calendarReply, healthReply) = coroutineScope {
            val clockJob = async { clockCommand(ClockHostControl.QUERY) }
            val calendarJob = async { calendarCommand(CalendarHostControl.QUERY) }
            val healthJob = async { healthCommand(HealthHostControl.QUERY) }
            Triple(clockJob.await(), calendarJob.await(), healthJob.await())
        }
        if (revision == switchRevision) {
            ui = ui.copy(clockHost = clockReply, calendarHost = calendarReply, healthHost = healthReply)
        }
    }

    private suspend fun readChecks() {
        val currentClock = ui.clockHost
        val currentCalendar = ui.calendarHost
        val currentHealth = ui.healthHost
        val checks = withContext(Dispatchers.IO) {
            runCatching {
                val clockVersion = readVersion(TargetProfile.CLOCK.packageName)
                val calendarVersion = readVersion(TargetProfile.CALENDAR.packageName)
                val healthVersion = readVersion(TargetProfile.HEALTH.packageName)
                val wirelessVersion = readVersion(TargetProfile.WIRELESS_SETTINGS.packageName)
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
                    wirelessVersion = wirelessVersion,
                    wirelessTrusted = SenderVerifier.trustedReminderTarget(applicationContext, TargetProfile.WIRELESS_SETTINGS),
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

    private fun requestWirelessScope() {
        lifecycleScope.launch {
            val scope = withContext(Dispatchers.IO) {
                ModuleScope.requestWirelessSettings(applicationContext)
                ModuleScope.inspect()
            }
            ui = ui.copy(scope = scope)
        }
    }

    private fun setWifiAutoEnabled(enable: Boolean) {
        if (ui.wifiAutoSaving) return
        ++switchRevision
        ui = ui.copy(wifiAutoSaving = true, message = null)
        lifecycleScope.launch {
            val remoteOk = writeRemoteWifiAutoSyncEnabled(enable)
            val healthAction = if (enable) HealthHostControl.ENABLE_WIFI_AUTO else HealthHostControl.DISABLE_WIFI_AUTO
            val healthReply = healthCommand(healthAction)
            val scopeNow = withContext(Dispatchers.IO) { ModuleScope.inspect() }
            val saved = remoteOk || (healthReply.reachable() && healthReply.wifiAutoSyncEnabled() == enable)
            ui = ui.copy(
                scope = scopeNow,
                healthHost = healthReply,
                wifiAutoSaving = false,
                message = when {
                    !saved -> "未能保存 Wi-Fi 自动同步开关状态。请确认 LSPosed 已启用或运动健康在线后再试。"
                    enable && scopeNow.frameworkConnected() && !scopeNow.wirelessGranted() ->
                        "已开启新 Wi-Fi 自动同步，请点击下方「申请无线设置作用域」以实现免 Root、免定位自动同步。"
                    else -> null
                },
            )
            readChecks()
        }
    }

    private fun syncCurrentWifi() {
        if (ui.wifiSyncingCurrent) return
        ui = ui.copy(wifiSyncingCurrent = true, message = null)
        lifecycleScope.launch {
            val initial = healthCommand(HealthHostControl.SYNC_CURRENT_WIFI)
            if (!initial.reachable()) {
                ui = ui.copy(
                    healthHost = initial,
                    wifiSyncingCurrent = false,
                    message = "运动健康 :PhoneService 未应答，请先打开华为运动健康并确认手表已连接。",
                )
                return@launch
            }
            ui = ui.copy(healthHost = initial)
            // Poll briefly while WirelessSettings answers and watch returns 1/47 ACK.
            var latest = initial
            repeat(4) {
                delay(1200L)
                latest = healthCommand(HealthHostControl.QUERY)
                ui = ui.copy(healthHost = latest)
                if (latest.wifiLastResult() != "SENDING") return@repeat
            }
            ui = ui.copy(wifiSyncingCurrent = false)
            refreshScope(force = true)
        }
    }

    private fun loadSavedWifiViaRoot() {
        if (ui.wifiBulk.loading || ui.wifiBulk.syncing) return
        ui = ui.copy(wifiBulk = ui.wifiBulk.copy(loading = true, loadError = null, summaryMessage = null))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { RootWifiConfigReader.readSavedNetworks() }
            val items = result.networks().map { record ->
                WifiBulkItem(key = record.selectionKey(), record = record, selected = false)
            }
            val error = when (result.status()) {
                RootWifiConfigReader.Status.OK -> null
                RootWifiConfigReader.Status.EMPTY -> "未在系统配置中找到已保存的 Wi-Fi 网络。"
                RootWifiConfigReader.Status.ROOT_DENIED -> "Root 授权被拒绝或不可用。请临时授予 WatchLink Root 权限后再试（同步完成后可随时收回）。"
                RootWifiConfigReader.Status.FILE_UNAVAILABLE -> "未读取到系统 Wi-Fi 配置文件（/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml）。"
                RootWifiConfigReader.Status.PARSE_ERROR -> "系统 Wi-Fi 配置文件解析失败。"
                RootWifiConfigReader.Status.TIMEOUT -> "读取系统 Wi-Fi 配置超时，请重试。"
            }
            ui = ui.copy(
                wifiBulk = ui.wifiBulk.copy(
                    loading = false,
                    loadError = error,
                    items = items,
                    completedCount = 0,
                    totalCount = 0,
                    successCount = 0,
                    summaryMessage = null,
                )
            )
        }
    }

    private fun toggleWifiSelection(key: String) {
        if (ui.wifiBulk.syncing) return
        val updated = ui.wifiBulk.items.map { item ->
            if (item.key == key && item.record.isSupportedByWatch()) item.copy(selected = !item.selected)
            else item
        }
        ui = ui.copy(wifiBulk = ui.wifiBulk.copy(items = updated, summaryMessage = null))
    }

    private fun selectWifiPreset(mode: String) {
        if (ui.wifiBulk.syncing) return
        val updated = ui.wifiBulk.items.map { item ->
            val supported = item.record.isSupportedByWatch()
            val selected = when (mode) {
                "ALL" -> supported
                "ENCRYPTED" -> supported && !item.record.isOpen()
                else -> false
            }
            item.copy(selected = selected)
        }
        ui = ui.copy(wifiBulk = ui.wifiBulk.copy(items = updated, summaryMessage = null))
    }

    private fun toggleWifiForceResend(force: Boolean) {
        if (ui.wifiBulk.syncing) return
        ui = ui.copy(wifiBulk = ui.wifiBulk.copy(forceResend = force))
    }

    private fun startBulkWifiSync() {
        val current = ui.wifiBulk
        if (current.syncing) return
        val targets = current.items.filter { it.selected && it.record.isSupportedByWatch() }
        if (targets.isEmpty()) return
        val force = current.forceResend
        val resetItems = current.items.map { item ->
            if (item.selected && item.record.isSupportedByWatch()) item.copy(status = "PENDING")
            else item
        }
        ui = ui.copy(
            wifiBulk = current.copy(
                items = resetItems,
                syncing = true,
                completedCount = 0,
                totalCount = targets.size,
                successCount = 0,
                summaryMessage = null,
            )
        )
        bulkSyncJob = lifecycleScope.launch {
            var completed = 0
            var succeeded = 0
            var abortedReason: String? = null
            try {
                for ((index, target) in targets.withIndex()) {
                    updateBulkItemStatus(target.key, "SENDING")
                    val res = syncOneWifi(target.record, force)
                    updateBulkItemStatus(target.key, res.status())
                    completed++
                    if (res.succeeded()) succeeded++
                    ui = ui.copy(
                        wifiBulk = ui.wifiBulk.copy(
                            completedCount = completed,
                            successCount = succeeded,
                        )
                    )
                    if (!res.reachable() || res.status() in setOf("WATCH_DISCONNECTED", "UNSUPPORTED_WATCH", "DIRECT_UNAVAILABLE")) {
                        abortedReason = when (res.status()) {
                            "WATCH_DISCONNECTED" -> "手表已断开连接，批量同步已暂停。"
                            "UNSUPPORTED_WATCH" -> "当前手表未声明 Wi-Fi 同步能力，已停止。"
                            "DIRECT_UNAVAILABLE" -> "运动健康直发通道未就绪，请更新或重启运动健康。"
                            else -> "运动健康 :PhoneService 未应答，批量同步已暂停。"
                        }
                        break
                    }
                    if (index + 1 < targets.size) {
                        delay(250L)
                    }
                }
            } finally {
                val summary = abortedReason ?: "已完成 $completed / ${targets.size}（成功或已同步 $succeeded 个）。"
                ui = ui.copy(wifiBulk = ui.wifiBulk.copy(syncing = false, summaryMessage = summary))
                bulkSyncJob = null
                queryHosts()
            }
        }
    }

    private fun updateBulkItemStatus(key: String, status: String) {
        val updated = ui.wifiBulk.items.map { item ->
            if (item.key == key) item.copy(status = status) else item
        }
        ui = ui.copy(wifiBulk = ui.wifiBulk.copy(items = updated))
    }

    private fun cancelBulkWifiSync() {
        bulkSyncJob?.cancel()
        bulkSyncJob = null
        ui = ui.copy(wifiBulk = ui.wifiBulk.copy(syncing = false, summaryMessage = "已手动停止批量同步。"))
    }

    private fun clearBulkWifiMemory() {
        bulkSyncJob?.cancel()
        bulkSyncJob = null
        if (ui.wifiBulk.items.isNotEmpty() || ui.wifiBulk.loadError != null || ui.wifiBulk.summaryMessage != null) {
            ui = ui.copy(wifiBulk = WifiBulkUiState())
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
