package io.github.watchlink.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.watchlink.BuildConfig
import io.github.watchlink.alarm.probe.TargetProfile
import io.github.watchlink.notify.SleepVideoStopPolicy
import io.github.watchlink.setup.ModuleScope
import io.github.watchlink.setup.WatchLinkPrefs
import io.github.watchlink.validation.CalendarHostControl
import io.github.watchlink.validation.ClockHostControl
import io.github.watchlink.validation.HealthHostControl
import io.github.watchlink.wifi.WifiNetworkRecord
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.window.WindowListPopup
import java.text.DateFormat
import java.util.Date

internal data class WifiBulkItem(
    val key: String,
    val record: WifiNetworkRecord,
    val selected: Boolean,
    val status: String = "IDLE",
)

internal data class WifiBulkUiState(
    val loading: Boolean = false,
    val loadError: String? = null,
    val items: List<WifiBulkItem> = emptyList(),
    val forceResend: Boolean = false,
    val syncing: Boolean = false,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val successCount: Int = 0,
    val summaryMessage: String? = null,
)

internal data class WatchLinkUiState(
    val clockHost: ClockHostControl.Reply? = null,
    val calendarHost: CalendarHostControl.Reply? = null,
    val healthHost: HealthHostControl.Reply? = null,
    val checks: HomeChecks? = null,
    val scope: ModuleScope.View? = null,
    val saving: Boolean = false,
    val timerSaving: Boolean = false,
    val calendarSaving: Boolean = false,
    val liveWindowSaving: Boolean = false,
    val gameFlashbackSaving: Boolean = false,
    val notifyMuteDndSaving: Boolean = false,
    val notifySilentPushSaving: Boolean = false,
    val notifySilentScreenOnSaving: Boolean = false,
    val notifySilentFocusSaving: Boolean = false,
    val notifySilentGameSaving: Boolean = false,
    val notifyDisconnectStageSaving: Boolean = false,
    val notifyBurstThrottleSaving: Boolean = false,
    val batteryPhoneFullWatchSaving: Boolean = false,
    val batteryPhoneLowWatchSaving: Boolean = false,
    val batteryWatchFullPhoneSaving: Boolean = false,
    val batteryWatchLowPhoneSaving: Boolean = false,
    val sleepAutoDndSaving: Boolean = false,
    val wakeExitDndSaving: Boolean = false,
    val sleepPauseMediaSaving: Boolean = false,
    val sleepStopVideoSaving: Boolean = false,
    val myDevicesCardSaving: Boolean = false,
    val wifiAutoSaving: Boolean = false,
    val rawCommandDebugSaving: Boolean = false,
    val wifiSyncingCurrent: Boolean = false,
    val wifiBulk: WifiBulkUiState = WifiBulkUiState(),
    val reloading: Boolean = false,
    val testing: Boolean = false,
    val liveTesting: Boolean = false,
    val sharing: Boolean = false,
    val refreshing: Boolean = false,
    val readError: Boolean = false,
    val message: String? = null,
) {
    val effectiveEnabled: Boolean
        get() = scope?.remoteEnabled()
            ?: clockHost?.takeIf { it.reachable() }?.enabled()
            ?: healthHost?.takeIf { it.reachable() }?.enabled()
            ?: false

    val canToggle: Boolean
        get() = !saving && (scope?.remotePrefsSupported() == true
                || clockHost?.reachable() == true
                || healthHost?.reachable() == true)

    val effectiveTimerEnabled: Boolean
        get() = scope?.timerOptions()?.enabled() == true

    val effectiveCalendarEnabled: Boolean
        get() = scope?.remoteCalendarEnabled()
            ?: calendarHost?.takeIf { it.reachable() }?.enabled()
            ?: healthHost?.takeIf { it.reachable() }?.calendarEnabled()
            ?: false

    val canToggleCalendar: Boolean
        get() = !calendarSaving && (scope?.remotePrefsSupported() == true
                || calendarHost?.reachable() == true
                || healthHost?.reachable() == true)

    val effectiveLiveWindowEnabled: Boolean
        get() = scope?.remoteLiveWindowEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.liveWindowEnabled()
            ?: false

    val canToggleLiveWindow: Boolean
        get() = !liveWindowSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveGameFlashbackEnabled: Boolean
        get() = scope?.remoteGameFlashbackEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.gameFlashbackEnabled()
            ?: false

    val effectiveGameFlashbackLeadSec: Int
        get() = scope?.remoteGameFlashbackLeadSec()
            ?: healthHost?.takeIf { it.reachable() }?.gameFlashbackLeadSec()
            ?: WatchLinkPrefs.DEFAULT_GAME_FLASHBACK_LEAD_SEC

    val canToggleGameFlashback: Boolean
        get() = !gameFlashbackSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveNotifyMuteDndEnabled: Boolean
        get() = scope?.remoteNotifyMuteDndEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.notifyMuteDndEnabled()
            ?: false

    val canToggleNotifyMuteDnd: Boolean
        get() = !notifyMuteDndSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveNotifySilentPushEnabled: Boolean
        get() = scope?.remoteNotifySilentPushEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.notifySilentPushEnabled()
            ?: false

    val canToggleNotifySilentPush: Boolean
        get() = !notifySilentPushSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveNotifySilentScreenOnEnabled: Boolean
        get() = scope?.remoteNotifySilentScreenOnEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.notifySilentScreenOnEnabled()
            ?: false

    val canToggleNotifySilentScreenOn: Boolean
        get() = !notifySilentScreenOnSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveNotifySilentFocusEnabled: Boolean
        get() = scope?.remoteNotifySilentFocusEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.notifySilentFocusEnabled()
            ?: false

    val canToggleNotifySilentFocus: Boolean
        get() = !notifySilentFocusSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveNotifySilentGameEnabled: Boolean
        get() = scope?.remoteNotifySilentGameEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.notifySilentGameEnabled()
            ?: false

    val canToggleNotifySilentGame: Boolean
        get() = !notifySilentGameSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveNotifyDisconnectStageEnabled: Boolean
        get() = scope?.remoteNotifyDisconnectStageEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.notifyDisconnectStageEnabled()
            ?: false

    val canToggleNotifyDisconnectStage: Boolean
        get() = !notifyDisconnectStageSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveNotifyBurstThrottleEnabled: Boolean
        get() = scope?.remoteNotifyBurstThrottleEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.notifyBurstThrottleEnabled()
            ?: false

    val effectiveNotifyBurstThrottleSec: Int
        get() = scope?.remoteNotifyBurstThrottleSec()
            ?: healthHost?.takeIf { it.reachable() }?.notifyBurstThrottleSec()
            ?: WatchLinkPrefs.DEFAULT_NOTIFY_BURST_THROTTLE_SEC

    val canToggleNotifyBurstThrottle: Boolean
        get() = !notifyBurstThrottleSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveBatteryPhoneFullWatchEnabled: Boolean
        get() = scope?.remoteBatteryPhoneFullWatchEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.batteryPhoneFullWatchEnabled()
            ?: false

    val canToggleBatteryPhoneFullWatch: Boolean
        get() = !batteryPhoneFullWatchSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveBatteryPhoneLowWatchEnabled: Boolean
        get() = scope?.remoteBatteryPhoneLowWatchEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.batteryPhoneLowWatchEnabled()
            ?: false

    val effectiveBatteryPhoneLowThreshold: Int
        get() = scope?.remoteBatteryPhoneLowThreshold()
            ?: healthHost?.takeIf { it.reachable() }?.batteryPhoneLowThreshold()
            ?: WatchLinkPrefs.DEFAULT_BATTERY_LOW_THRESHOLD

    val canToggleBatteryPhoneLowWatch: Boolean
        get() = !batteryPhoneLowWatchSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveBatteryWatchFullPhoneEnabled: Boolean
        get() = scope?.remoteBatteryWatchFullPhoneEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.batteryWatchFullPhoneEnabled()
            ?: false

    val canToggleBatteryWatchFullPhone: Boolean
        get() = !batteryWatchFullPhoneSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveBatteryWatchLowPhoneEnabled: Boolean
        get() = scope?.remoteBatteryWatchLowPhoneEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.batteryWatchLowPhoneEnabled()
            ?: false

    val effectiveBatteryWatchLowThreshold: Int
        get() = scope?.remoteBatteryWatchLowThreshold()
            ?: healthHost?.takeIf { it.reachable() }?.batteryWatchLowThreshold()
            ?: WatchLinkPrefs.DEFAULT_BATTERY_LOW_THRESHOLD

    val canToggleBatteryWatchLowPhone: Boolean
        get() = !batteryWatchLowPhoneSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveSleepAutoDndEnabled: Boolean
        get() = scope?.remoteSleepAutoDndEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.sleepAutoDndEnabled()
            ?: false

    val canToggleSleepAutoDnd: Boolean
        get() = !sleepAutoDndSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveWakeExitDndEnabled: Boolean
        get() = scope?.remoteWakeExitDndEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.wakeExitDndEnabled()
            ?: false

    val canToggleWakeExitDnd: Boolean
        get() = !wakeExitDndSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveSleepPauseMediaEnabled: Boolean
        get() = scope?.remoteSleepPauseMediaEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.sleepPauseMediaEnabled()
            ?: false

    val canToggleSleepPauseMedia: Boolean
        get() = !sleepPauseMediaSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveSleepStopVideoEnabled: Boolean
        get() = scope?.remoteSleepStopVideoEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.sleepStopVideoEnabled()
            ?: false

    val effectiveSleepStopVideoCustomPkgs: String
        get() = scope?.remoteSleepStopVideoCustomPkgs()
            ?: healthHost?.takeIf { it.reachable() }?.sleepStopVideoCustomPkgs()
            ?: ""

    val canToggleSleepStopVideo: Boolean
        get() = !sleepStopVideoSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveMyDevicesCardEnabled: Boolean
        get() = scope?.remoteMyDevicesCardEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.myDevicesCardEnabled()
            ?: false

    val effectiveMyDevicesTapTarget: String
        get() = scope?.remoteMyDevicesTapTarget()
            ?: healthHost?.takeIf { it.reachable() }?.myDevicesTapTarget()
            ?: WatchLinkPrefs.TAP_TARGET_HEALTH

    val canToggleMyDevicesCard: Boolean
        get() = !myDevicesCardSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveWifiAutoEnabled: Boolean
        get() = scope?.remoteWifiAutoSyncEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.wifiAutoSyncEnabled()
            ?: false

    val canToggleWifiAuto: Boolean
        get() = !wifiAutoSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val effectiveRawCommandDebugEnabled: Boolean
        get() = scope?.remoteRawCommandDebugEnabled()
            ?: healthHost?.takeIf { it.reachable() }?.rawCommandDebugEnabled()
            ?: false

    val canToggleRawCommandDebug: Boolean
        get() = !rawCommandDebugSaving && (scope?.remotePrefsSupported() == true
                || healthHost?.reachable() == true)

    val enabledFeatureCount: Int
        get() = listOf(
            effectiveEnabled,
            effectiveTimerEnabled,
            effectiveCalendarEnabled,
            effectiveLiveWindowEnabled,
            effectiveGameFlashbackEnabled,
            effectiveNotifyMuteDndEnabled,
            effectiveNotifySilentPushEnabled,
            effectiveNotifySilentScreenOnEnabled,
            effectiveNotifySilentFocusEnabled,
            effectiveNotifySilentGameEnabled,
            effectiveNotifyDisconnectStageEnabled,
            effectiveNotifyBurstThrottleEnabled,
            effectiveBatteryPhoneFullWatchEnabled,
            effectiveBatteryPhoneLowWatchEnabled,
            effectiveBatteryWatchFullPhoneEnabled,
            effectiveBatteryWatchLowPhoneEnabled,
            effectiveSleepAutoDndEnabled,
            effectiveWakeExitDndEnabled,
            effectiveSleepPauseMediaEnabled,
            effectiveSleepStopVideoEnabled,
            effectiveMyDevicesCardEnabled,
            effectiveWifiAutoEnabled,
        ).count { it }
}

private enum class Page(val title: String) {
    HOME("WatchLink"),
    REMINDERS("提醒同步"),
    LIVE("实况窗同步"),
    NOTIFICATIONS("通知与入睡"),
    BATTERY("充电与电量提醒"),
    MY_DEVICES("设备空间卡片"),
    WIFI("Wi-Fi 同步"),
    WIFI_SYNC("批量同步 Wi-Fi"),
    DIAGNOSTICS("测试与诊断"),
    GUIDE("使用说明"),
}

@Composable
internal fun WatchLinkApp(
    state: WatchLinkUiState,
    onEnable: (Boolean) -> Unit,
    onCalendarEnable: (Boolean) -> Unit,
    onTimerOption: (String, Boolean) -> Unit,
    onLiveWindowEnable: (Boolean) -> Unit,
    onGameFlashbackEnable: (Boolean) -> Unit,
    onGameFlashbackLeadSec: (Int) -> Unit,
    onNotifyMuteDndEnable: (Boolean) -> Unit,
    onNotifySilentPushEnable: (Boolean) -> Unit,
    onNotifySilentScreenOnEnable: (Boolean) -> Unit,
    onNotifySilentFocusEnable: (Boolean) -> Unit,
    onNotifySilentGameEnable: (Boolean) -> Unit,
    onNotifyDisconnectStageEnable: (Boolean) -> Unit,
    onNotifyBurstThrottleEnable: (Boolean) -> Unit,
    onNotifyBurstThrottleSec: (Int) -> Unit,
    onBatteryPhoneFullWatchEnable: (Boolean) -> Unit,
    onBatteryPhoneLowWatchEnable: (Boolean) -> Unit,
    onBatteryPhoneLowThreshold: (Int) -> Unit,
    onBatteryWatchFullPhoneEnable: (Boolean) -> Unit,
    onBatteryWatchLowPhoneEnable: (Boolean) -> Unit,
    onBatteryWatchLowThreshold: (Int) -> Unit,
    onSleepAutoDndEnable: (Boolean) -> Unit,
    onWakeExitDndEnable: (Boolean) -> Unit,
    onSleepPauseMediaEnable: (Boolean) -> Unit,
    onSleepStopVideoEnable: (Boolean) -> Unit,
    onSleepStopVideoCustomPkgs: (String) -> Unit,
    onMyDevicesCardEnable: (Boolean) -> Unit,
    onMyDevicesTapTarget: (String) -> Unit,
    onOpenDndAccessSettings: () -> Unit,
    onOpenWriteSettingsAccess: () -> Unit,
    onWifiAutoEnable: (Boolean) -> Unit,
    onRawCommandDebugEnable: (Boolean) -> Unit,
    onDismissMessage: () -> Unit,
    onClock: () -> Unit,
    onCalendar: () -> Unit,
    onHealth: () -> Unit,
    onRequestScope: () -> Unit,
    onRequestCalendarScope: () -> Unit,
    onRequestUmsScope: () -> Unit,
    onRequestWirelessScope: () -> Unit,
    onRequestMyDevicesScope: () -> Unit,
    onSyncCurrentWifi: () -> Unit,
    onLoadSavedWifi: () -> Unit,
    onToggleWifiSelection: (String) -> Unit,
    onSelectWifiPreset: (String) -> Unit,
    onToggleWifiForceResend: (Boolean) -> Unit,
    onStartBulkWifiSync: () -> Unit,
    onCancelBulkWifiSync: () -> Unit,
    onClearBulkWifiMemory: () -> Unit,
    onHotReload: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onHealthDownload: () -> Unit,
    onCoolapk: () -> Unit,
    onStartTest: () -> Unit,
    onCancelTest: () -> Unit,
    onStartLiveTest: () -> Unit,
    onCancelLiveTest: () -> Unit,
    onShare: () -> Unit,
) {
    MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        var history by rememberSaveable { mutableStateOf(arrayListOf<Page>()) }
        val page = history.lastOrNull() ?: Page.HOME
        val openPage: (Page) -> Unit = { next -> history = ArrayList(history + next) }
        val homeScroll = rememberLazyListState()
        val scroll = if (page == Page.HOME) homeScroll else key(page) { rememberLazyListState() }
        val behavior = MiuixScrollBehavior()
        val back = {
            if (page == Page.WIFI_SYNC) onClearBulkWifiMemory()
            history = ArrayList(history.dropLast(1))
        }
        BackHandler(enabled = page != Page.HOME, onBack = back)
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
            if (page == Page.HOME) TopAppBar(title = page.title, scrollBehavior = behavior)
            else SmallTopAppBar(title = page.title, navigationIcon = {
                IconButton(onClick = back) { Icon(Back, "返回") }
            })
        }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                LazyColumn(
                    modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize()
                        .then(if (page == Page.HOME) Modifier.nestedScroll(behavior.nestedScrollConnection) else Modifier),
                    state = scroll,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.message?.let { message ->
                        item("banner") {
                            Card(Modifier.fillMaxWidth()) {
                                BasicComponent(title = message, endActions = { TextButton("知道了", onDismissMessage) })
                            }
                        }
                    }
                    when (page) {
                        Page.HOME -> homePage(state, onRefresh, openPage)
                        Page.REMINDERS -> remindersPage(
                            state = state,
                            onEnable = onEnable,
                            onTimerOption = onTimerOption,
                            onCalendarEnable = onCalendarEnable,
                            onRequestCalendarScope = onRequestCalendarScope,
                            onOpenCalendar = onCalendar,
                            onRefresh = onRefresh,
                        )
                        Page.LIVE -> livePage(
                            state = state,
                            onLiveWindowEnable = onLiveWindowEnable,
                            onGameFlashbackEnable = onGameFlashbackEnable,
                            onGameFlashbackLeadSec = onGameFlashbackLeadSec,
                            onRequestUmsScope = onRequestUmsScope,
                        )
                        Page.NOTIFICATIONS -> notificationsPage(
                            state = state,
                            onNotifyMuteDndEnable = onNotifyMuteDndEnable,
                            onNotifySilentPushEnable = onNotifySilentPushEnable,
                            onNotifySilentScreenOnEnable = onNotifySilentScreenOnEnable,
                            onNotifySilentFocusEnable = onNotifySilentFocusEnable,
                            onNotifySilentGameEnable = onNotifySilentGameEnable,
                            onNotifyDisconnectStageEnable = onNotifyDisconnectStageEnable,
                            onNotifyBurstThrottleEnable = onNotifyBurstThrottleEnable,
                            onNotifyBurstThrottleSec = onNotifyBurstThrottleSec,
                            onSleepAutoDndEnable = onSleepAutoDndEnable,
                            onWakeExitDndEnable = onWakeExitDndEnable,
                            onSleepPauseMediaEnable = onSleepPauseMediaEnable,
                            onSleepStopVideoEnable = onSleepStopVideoEnable,
                            onSleepStopVideoCustomPkgs = onSleepStopVideoCustomPkgs,
                            onOpenDndAccessSettings = onOpenDndAccessSettings,
                            onOpenWriteSettingsAccess = onOpenWriteSettingsAccess,
                        )
                        Page.BATTERY -> batteryPage(
                            state = state,
                            onBatteryPhoneFullWatchEnable = onBatteryPhoneFullWatchEnable,
                            onBatteryPhoneLowWatchEnable = onBatteryPhoneLowWatchEnable,
                            onBatteryPhoneLowThreshold = onBatteryPhoneLowThreshold,
                            onBatteryWatchFullPhoneEnable = onBatteryWatchFullPhoneEnable,
                            onBatteryWatchLowPhoneEnable = onBatteryWatchLowPhoneEnable,
                            onBatteryWatchLowThreshold = onBatteryWatchLowThreshold,
                        )
                        Page.MY_DEVICES -> myDevicesPage(
                            state = state,
                            onMyDevicesCardEnable = onMyDevicesCardEnable,
                            onMyDevicesTapTarget = onMyDevicesTapTarget,
                            onRequestMyDevicesScope = onRequestMyDevicesScope,
                        )
                        Page.WIFI -> wifiPage(
                            state = state,
                            onWifiAutoEnable = onWifiAutoEnable,
                            onRequestWirelessScope = onRequestWirelessScope,
                            onSyncCurrentWifi = onSyncCurrentWifi,
                            onOpenWifiBulk = { openPage(Page.WIFI_SYNC) },
                        )
                        Page.WIFI_SYNC -> wifiBulkSyncPage(
                            state = state,
                            onLoadSavedWifi = onLoadSavedWifi,
                            onToggleWifiSelection = onToggleWifiSelection,
                            onSelectWifiPreset = onSelectWifiPreset,
                            onToggleWifiForceResend = onToggleWifiForceResend,
                            onStartBulkWifiSync = onStartBulkWifiSync,
                            onCancelBulkWifiSync = onCancelBulkWifiSync,
                        )
                        Page.DIAGNOSTICS -> {
                            setupActions(state, onHotReload, onRequestScope, onClock, onHealth, onRefresh, onHealthDownload)
                            diagnosticsPage(
                                state, onStartTest, onCancelTest, onStartLiveTest, onCancelLiveTest,
                                onRawCommandDebugEnable, onHotReload, onRefresh, onShare,
                            )
                        }
                        Page.GUIDE -> guidePage(onCoolapk)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.homePage(state: WatchLinkUiState, onRefresh: () -> Unit, openPage: (Page) -> Unit) {
    val issues = state.checks?.issues(state.scope).orEmpty()
    item("status") { StatusCard(state, issues, onRefresh) { openPage(Page.DIAGNOSTICS) } }

    item("features-title") { SectionTitle("功能设置") }
    item("features") {
        Card(Modifier.fillMaxWidth()) {
            Menu(Page.REMINDERS.title, "手机闹钟、倒计时与日历强提醒") { openPage(Page.REMINDERS) }
            HorizontalDivider()
            Menu(Page.LIVE.title, "流体云、实况通知与游戏闪回") { openPage(Page.LIVE) }
            HorizontalDivider()
            Menu(Page.NOTIFICATIONS.title, "静默推送、防轰炸、断连暂存与入睡联动") { openPage(Page.NOTIFICATIONS) }
            HorizontalDivider()
            Menu(Page.BATTERY.title, "手机与手表充满及低电量双向提醒") { openPage(Page.BATTERY) }
            HorizontalDivider()
            Menu(Page.MY_DEVICES.title, "在系统设备空间显示手表状态与电量") { openPage(Page.MY_DEVICES) }
            HorizontalDivider()
            Menu(Page.WIFI.title, "自动或批量同步手机 Wi-Fi 到手表") { openPage(Page.WIFI) }
        }
    }

    item("more-title") { SectionTitle("更多") }
    item("more") {
        Card(Modifier.fillMaxWidth()) {
            Menu(Page.DIAGNOSTICS.title, "提醒测试、宿主状态与日志导出") { openPage(Page.DIAGNOSTICS) }
            HorizontalDivider()
            Menu(Page.GUIDE.title, "配置步骤、作用域说明与版本信息") { openPage(Page.GUIDE) }
        }
    }
}

@Composable
private fun StatusCard(
    state: WatchLinkUiState,
    issues: List<SetupIssue>,
    onRefresh: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val level = setupLevel(state.checks, issues, state.scope)
    val enabledCount = state.enabledFeatureCount

    val (background, ink) = when (level) {
        SetupLevel.READY -> (if (enabledCount > 0) Color(0xFF15694E) else Color(0xFF2356B8)) to Color.White
        SetupLevel.ATTENTION -> Color(0xFFFFDF8E) to Color(0xFF473108)
        SetupLevel.MISSING_SCOPE -> Color(0xFFB0323F) to Color.White
        SetupLevel.PENDING -> if (isSystemInDarkTheme()) Color(0xFF373A3E) to Color(0xFFE4E6EA)
        else Color(0xFFE9EBEE) to Color(0xFF393C41)
    }
    val title = when (level) {
        SetupLevel.READY -> if (enabledCount > 0) "运行中" else "已就绪"
        SetupLevel.ATTENTION -> "有 ${issues.size} 项待处理"
        SetupLevel.MISSING_SCOPE -> "模块未启用"
        SetupLevel.PENDING -> when {
            state.refreshing || state.checks == null -> "检查中…"
            state.readError -> "状态读取失败"
            else -> "等待宿主响应"
        }
    }
    val watchBatterySuffix = state.healthHost?.takeIf { it.reachable() && it.watchConnected() && it.watchBattery() >= 0 }
        ?.let { h -> if (h.watchCharging()) " · 充电中 ${h.watchBattery()}%" else " · 电量 ${h.watchBattery()}%" }
        ?: ""
    val summary = when (level) {
        SetupLevel.READY -> if (enabledCount > 0) "已开启 $enabledCount 项功能 · 手表已连接$watchBatterySuffix"
        else "手表已连接$watchBatterySuffix，可按需开启功能"
        SetupLevel.ATTENTION -> "轻触查看详情与处理建议"
        SetupLevel.MISSING_SCOPE -> "轻触前往配置作用域"
        SetupLevel.PENDING -> when {
            state.refreshing || state.checks == null -> "正在获取宿主与手表状态"
            state.readError -> "轻触重新刷新"
            else -> "轻触查看宿主与连接状态"
        }
    }
    val shouldRefreshOnClick = level == SetupLevel.PENDING && (state.checks == null || state.readError)

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(background, ink)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(
                    enabled = !state.refreshing || !shouldRefreshOnClick,
                    role = Role.Button,
                    onClickLabel = if (shouldRefreshOnClick) "刷新状态" else "查看测试与诊断",
                ) {
                    if (shouldRefreshOnClick) onRefresh() else onOpenDiagnostics()
                }
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(44.dp).background(ink.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (level == SetupLevel.READY) ShieldReady else ShieldStatus, null, tint = ink, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    color = ink,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() }
                )
                Text(summary, color = ink.copy(alpha = .9f), fontSize = 13.5.sp)
            }
            if (!shouldRefreshOnClick) {
                Icon(Next, null, tint = ink.copy(alpha = .85f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun LazyListScope.remindersPage(
    state: WatchLinkUiState,
    onEnable: (Boolean) -> Unit,
    onTimerOption: (String, Boolean) -> Unit,
    onCalendarEnable: (Boolean) -> Unit,
    onRequestCalendarScope: () -> Unit,
    onOpenCalendar: () -> Unit,
    onRefresh: () -> Unit,
) {
    item("clock-title") { SectionTitle("系统时钟") }
    item("clock-card") { ClockRemindersCard(state, onEnable, onTimerOption) }
    item("calendar-title") { SectionTitle("系统日历") }
    item("calendar-card") {
        CalendarReminderCard(state, onCalendarEnable, onRequestCalendarScope, onOpenCalendar, onRefresh)
    }
}

@Composable
private fun ClockRemindersCard(
    state: WatchLinkUiState,
    onEnable: (Boolean) -> Unit,
    onTimerOption: (String, Boolean) -> Unit,
) {
    val alarmEnabled = state.effectiveEnabled
    val alarmSummary = when {
        state.saving -> "正在保存…"
        !state.canToggle -> "请先启用模块或打开系统时钟"
        else -> "手机响铃时同步手表，支持表端停止或延后"
    }

    val options = state.scope?.timerOptions()
    val timerEnabled = options?.enabled() == true
    val canSaveTimer = state.scope?.remotePrefsSupported() == true && !state.timerSaving
    val timerSummary = when {
        state.timerSaving -> "正在保存…"
        state.scope?.remotePrefsSupported() != true -> "需要 LSPosed RemotePreferences 支持"
        else -> "倒计时结束时提醒手表，支持表端停止"
    }
    val liveSupport = state.healthHost?.takeIf { it.reachable() }?.liveWatchSupport()
    val timerLiveSummary = if (liveSupport == "UNSUPPORTED") "当前手表不支持实况窗" else "在手表实时显示进度与预计结束时间"

    Card(Modifier.fillMaxWidth()) {
        SwitchRow("同步手机闹钟", alarmSummary, alarmEnabled, state.canToggle, onEnable)
        HorizontalDivider()
        SwitchRow("同步倒计时", timerSummary, timerEnabled, canSaveTimer) {
            onTimerOption(WatchLinkPrefs.KEY_TIMER_ENABLED, it)
        }
        if (timerEnabled) {
            HorizontalDivider()
            SwitchRow("运行中显示实况窗", timerLiveSummary, options.live(), canSaveTimer) {
                onTimerOption(WatchLinkPrefs.KEY_TIMER_LIVE, it)
            }
            HorizontalDivider()
            SwitchRow("延后键改为再次计时", "表端点击延后时按原时长重新计时", options.restart(), canSaveTimer) {
                onTimerOption(WatchLinkPrefs.KEY_TIMER_RESTART, it)
            }
        }
    }
}

@Composable
private fun CalendarReminderCard(
    state: WatchLinkUiState,
    onEnable: (Boolean) -> Unit,
    onRequestCalendarScope: () -> Unit,
    onOpenCalendar: () -> Unit,
    onRefresh: () -> Unit,
) {
    val enabled = state.effectiveCalendarEnabled
    val scope = state.scope
    val checks = state.checks
    val calendarHost = state.calendarHost
    val trusted = checks == null || checks.calendarTrusted
    val needsScope = enabled && trusted && scope?.frameworkConnected() == true && !scope.calendarGranted()
    val needsLaunch = enabled && trusted && !needsScope && scope?.calendarReady() != true && calendarHost?.reachable() != true

    val summary = when {
        !trusted -> "未检测到官方系统日历"
        state.calendarSaving -> "正在保存…"
        !state.canToggleCalendar -> "请先启用模块或打开系统日历"
        needsScope -> "需授权「系统日历」作用域"
        needsLaunch -> "请打开或重启系统日历以生效"
        else -> "同步日程与待办强提醒，支持表端停止或延后"
    }

    Card(Modifier.fillMaxWidth()) {
        SwitchRow("同步日历强提醒", summary, enabled, trusted && state.canToggleCalendar, onEnable)
        if (needsScope) {
            HorizontalDivider()
            if (scope.requestPending()) {
                BasicComponent(title = "等待 LSPosed 授权", summary = "请在弹窗中同意「系统日历」")
            } else {
                Menu("申请系统日历作用域", "授权后即可同步日历强提醒", onRequestCalendarScope)
            }
        } else if (needsLaunch) {
            HorizontalDivider()
            Menu("打开系统日历", "启动日历以加载模块", onOpenCalendar)
            HorizontalDivider()
            Menu("刷新状态", "重启日历后点此确认", onRefresh)
        }
    }
}

private fun LazyListScope.livePage(
    state: WatchLinkUiState,
    onLiveWindowEnable: (Boolean) -> Unit,
    onGameFlashbackEnable: (Boolean) -> Unit,
    onGameFlashbackLeadSec: (Int) -> Unit,
    onRequestUmsScope: () -> Unit,
) {
    val liveEnabled = state.effectiveLiveWindowEnabled
    val gameEnabled = state.effectiveGameFlashbackEnabled
    val leadSec = state.effectiveGameFlashbackLeadSec
    val scope = state.scope
    val checks = state.checks
    val umsTrusted = checks == null || checks.umsTrusted
    val needsUmsScope = (liveEnabled || gameEnabled) && umsTrusted && scope?.frameworkConnected() == true && !scope.umsGranted()

    val liveSummary = when {
        state.liveWindowSaving -> "正在保存…"
        !state.canToggleLiveWindow -> "请先启用模块或打开运动健康"
        needsUmsScope -> "需授权「智慧服务」以读取流体云"
        else -> "同步取餐、外卖、打车、出行、快递与排号进度"
    }
    val gameSummary = when {
        state.gameFlashbackSaving -> "正在保存…"
        !state.canToggleGameFlashback -> "请先启用模块或打开运动健康"
        else -> "同步游戏复活与起飞倒计时"
    }

    item("live-regular-title") { SectionTitle("流体云与实况通知") }
    item("live-regular-card") {
        Card(Modifier.fillMaxWidth()) {
            SwitchRow("同步流体云与实况通知", liveSummary, liveEnabled, state.canToggleLiveWindow, onLiveWindowEnable)
            if (needsUmsScope) {
                HorizontalDivider()
                if (scope.requestPending()) {
                    BasicComponent(title = "等待 LSPosed 授权", summary = "请在弹窗中同意「智慧服务」")
                } else {
                    Menu("申请智慧服务作用域", "用于读取 ColorOS 流体云进度", onRequestUmsScope)
                }
            }
        }
    }

    item("live-game-title") { SectionTitle("游戏闪回") }
    item("live-game-card") {
        Card(Modifier.fillMaxWidth()) {
            SwitchRow("同步游戏闪回", gameSummary, gameEnabled, state.canToggleGameFlashback, onGameFlashbackEnable)
            if (gameEnabled) {
                HorizontalDivider()
                var leadInput by rememberSaveable(leadSec) { mutableStateOf(leadSec.toString()) }
                var leadError by remember { mutableStateOf<String?>(null) }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "复活提前震动（秒）",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "当前：${if (leadSec == 0) "复活瞬间（0 秒）" else "提前 $leadSec 秒"}；若大于剩余复活时间则立即提醒一次",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = leadInput,
                            onValueChange = { raw ->
                                leadInput = raw.filter { it.isDigit() }.take(3)
                                leadError = null
                            },
                            label = "0 ~ ${WatchLinkPrefs.MAX_GAME_FLASHBACK_LEAD_SEC} 秒",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "保存",
                            enabled = state.canToggleGameFlashback && leadInput.isNotBlank(),
                            onClick = {
                                val parsed = leadInput.toIntOrNull()
                                if (parsed == null || parsed < WatchLinkPrefs.MIN_GAME_FLASHBACK_LEAD_SEC
                                    || parsed > WatchLinkPrefs.MAX_GAME_FLASHBACK_LEAD_SEC) {
                                    leadError = "请输入 ${WatchLinkPrefs.MIN_GAME_FLASHBACK_LEAD_SEC} ~ ${WatchLinkPrefs.MAX_GAME_FLASHBACK_LEAD_SEC} 之间的整数"
                                } else {
                                    leadError = null
                                    onGameFlashbackLeadSec(parsed)
                                }
                            },
                        )
                    }
                    leadError?.let { err ->
                        Text(
                            text = err,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.notificationsPage(
    state: WatchLinkUiState,
    onNotifyMuteDndEnable: (Boolean) -> Unit,
    onNotifySilentPushEnable: (Boolean) -> Unit,
    onNotifySilentScreenOnEnable: (Boolean) -> Unit,
    onNotifySilentFocusEnable: (Boolean) -> Unit,
    onNotifySilentGameEnable: (Boolean) -> Unit,
    onNotifyDisconnectStageEnable: (Boolean) -> Unit,
    onNotifyBurstThrottleEnable: (Boolean) -> Unit,
    onNotifyBurstThrottleSec: (Int) -> Unit,
    onSleepAutoDndEnable: (Boolean) -> Unit,
    onWakeExitDndEnable: (Boolean) -> Unit,
    onSleepPauseMediaEnable: (Boolean) -> Unit,
    onSleepStopVideoEnable: (Boolean) -> Unit,
    onSleepStopVideoCustomPkgs: (String) -> Unit,
    onOpenDndAccessSettings: () -> Unit,
    onOpenWriteSettingsAccess: () -> Unit,
) {
    val muteDndEnabled = state.effectiveNotifyMuteDndEnabled
    val silentPushEnabled = state.effectiveNotifySilentPushEnabled
    val silentScreenOnEnabled = state.effectiveNotifySilentScreenOnEnabled
    val silentFocusEnabled = state.effectiveNotifySilentFocusEnabled
    val silentGameEnabled = state.effectiveNotifySilentGameEnabled
    val disconnectStageEnabled = state.effectiveNotifyDisconnectStageEnabled
    val burstThrottleEnabled = state.effectiveNotifyBurstThrottleEnabled
    val burstThrottleSec = state.effectiveNotifyBurstThrottleSec
    val sleepDndEnabled = state.effectiveSleepAutoDndEnabled
    val wakeDndEnabled = state.effectiveWakeExitDndEnabled
    val sleepPauseMediaEnabled = state.effectiveSleepPauseMediaEnabled
    val sleepStopVideoEnabled = state.effectiveSleepStopVideoEnabled
    val customVideoPkgsRaw = state.effectiveSleepStopVideoCustomPkgs
    val customVideoPkgs = SleepVideoStopPolicy.parseCustomPackages(customVideoPkgsRaw)
    val health = state.healthHost
    val missingDndPolicy = (sleepDndEnabled || wakeDndEnabled)
            && health?.reachable() == true && !health.dndPolicyAccess()
    val missingWriteSettings = sleepStopVideoEnabled
            && health?.reachable() == true && !health.writeSettingsAccess()

    val muteDndSummary = when {
        state.notifyMuteDndSaving -> "正在保存…"
        !state.canToggleNotifyMuteDnd -> "请先启用模块或打开运动健康"
        else -> "手机开启免打扰时，手表静默接收不震动"
    }
    val silentPushSummary = when {
        state.notifySilentPushSaving -> "正在保存…"
        !state.canToggleNotifySilentPush -> "请先启用模块或打开运动健康"
        else -> "手机无声无震动的通知，推送至手表时保持静默"
    }
    val silentScreenOnSummary = when {
        state.notifySilentScreenOnSaving -> "正在保存…"
        !state.canToggleNotifySilentScreenOn -> "请先启用模块或打开运动健康"
        else -> "手机亮屏且已解锁使用时，手表仅接收不震动"
    }
    val silentFocusSummary = when {
        state.notifySilentFocusSaving -> "正在保存…"
        !state.canToggleNotifySilentFocus -> "请先启用模块或打开运动健康"
        else -> "开启禅定空间（含轻禅定）或专注模式时静默接收"
    }
    val silentGameSummary = when {
        state.notifySilentGameSaving -> "正在保存…"
        !state.canToggleNotifySilentGame -> "请先启用模块或打开运动健康"
        else -> "游戏在前台运行时普通通知静默，游戏闪回不受影响"
    }
    val disconnectStageSummary = when {
        state.notifyDisconnectStageSaving -> "正在保存…"
        !state.canToggleNotifyDisconnectStage -> "请先启用模块或打开运动健康"
        else -> "断连 1 分钟内最多暂存 5 条通知，重连后仅 1 条震动提醒，其余静默补发"
    }
    val burstThrottleSummary = when {
        state.notifyBurstThrottleSaving -> "正在保存…"
        !state.canToggleNotifyBurstThrottle -> "请先启用模块或打开运动健康"
        else -> "设定时间窗口内，同一应用仅首条震动，后续通知改为静默推送"
    }
    val sleepDndSummary = when {
        state.sleepAutoDndSaving -> "正在保存…"
        !state.canToggleSleepAutoDnd -> "请先启用模块或打开运动健康"
        sleepDndEnabled && missingDndPolicy -> "需在系统「勿扰权限」允许华为运动健康"
        else -> "检测到入睡时自动开启手机免打扰"
    }
    val wakeDndSummary = when {
        state.wakeExitDndSaving -> "正在保存…"
        !state.canToggleWakeExitDnd -> "请先启用模块或打开运动健康"
        wakeDndEnabled && missingDndPolicy -> "需在系统「勿扰权限」允许华为运动健康"
        else -> "检测到醒来时自动关闭手机免打扰"
    }
    val sleepPauseMediaSummary = when {
        state.sleepPauseMediaSaving -> "正在保存…"
        !state.canToggleSleepPauseMedia -> "请先启用模块或打开运动健康"
        else -> "检测到入睡时暂停手机媒体播放，醒来不自动恢复"
    }
    val sleepStopVideoSummary = when {
        state.sleepStopVideoSaving -> "正在保存…"
        !state.canToggleSleepStopVideo -> "请先启用模块或打开运动健康"
        else -> "检测到入睡时停止短视频与直播、返回桌面并自动息屏"
    }

    item("notify-dnd-title") { SectionTitle("手表静默推送") }
    item("notify-dnd-card") {
        Card(Modifier.fillMaxWidth()) {
            SwitchRow(
                "免打扰时静默推送",
                muteDndSummary,
                muteDndEnabled,
                state.canToggleNotifyMuteDnd,
                onNotifyMuteDndEnable,
            )
            HorizontalDivider()
            SwitchRow(
                "静默通知同步静默",
                silentPushSummary,
                silentPushEnabled,
                state.canToggleNotifySilentPush,
                onNotifySilentPushEnable,
            )
            HorizontalDivider()
            SwitchRow(
                "亮屏解锁时静默推送",
                silentScreenOnSummary,
                silentScreenOnEnabled,
                state.canToggleNotifySilentScreenOn,
                onNotifySilentScreenOnEnable,
            )
            HorizontalDivider()
            SwitchRow(
                "禅定与专注时静默推送",
                silentFocusSummary,
                silentFocusEnabled,
                state.canToggleNotifySilentFocus,
                onNotifySilentFocusEnable,
            )
            HorizontalDivider()
            SwitchRow(
                "游戏前台时静默推送",
                silentGameSummary,
                silentGameEnabled,
                state.canToggleNotifySilentGame,
                onNotifySilentGameEnable,
            )
        }
    }

    item("notify-burst-title") { SectionTitle("连发防轰炸") }
    item("notify-burst-card") {
        Card(Modifier.fillMaxWidth()) {
            SwitchRow(
                "通知连发防轰炸",
                burstThrottleSummary,
                burstThrottleEnabled,
                state.canToggleNotifyBurstThrottle,
                onNotifyBurstThrottleEnable,
            )
            if (burstThrottleEnabled) {
                HorizontalDivider()
                var secInput by rememberSaveable(burstThrottleSec) { mutableStateOf(burstThrottleSec.toString()) }
                var secError by remember { mutableStateOf<String?>(null) }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "同应用防轰炸间隔（秒）",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "当前：$burstThrottleSec 秒；固定窗口内同一应用后续普通通知自动转为静默，不影响来电与强提醒",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = secInput,
                            onValueChange = { raw ->
                                secInput = raw.filter { it.isDigit() }.take(3)
                                secError = null
                            },
                            label = "${WatchLinkPrefs.MIN_NOTIFY_BURST_THROTTLE_SEC} ~ ${WatchLinkPrefs.MAX_NOTIFY_BURST_THROTTLE_SEC} 秒",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "保存",
                            enabled = state.canToggleNotifyBurstThrottle && secInput.isNotBlank(),
                            onClick = {
                                val parsed = secInput.toIntOrNull()
                                if (parsed == null || parsed < WatchLinkPrefs.MIN_NOTIFY_BURST_THROTTLE_SEC
                                    || parsed > WatchLinkPrefs.MAX_NOTIFY_BURST_THROTTLE_SEC) {
                                    secError = "请输入 ${WatchLinkPrefs.MIN_NOTIFY_BURST_THROTTLE_SEC} ~ ${WatchLinkPrefs.MAX_NOTIFY_BURST_THROTTLE_SEC} 之间的整数"
                                } else {
                                    secError = null
                                    onNotifyBurstThrottleSec(parsed)
                                }
                            },
                        )
                    }
                    secError?.let { err ->
                        Text(
                            text = err,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    item("notify-disconnect-title") { SectionTitle("断连补发") }
    item("notify-disconnect-card") {
        Card(Modifier.fillMaxWidth()) {
            SwitchRow(
                "短时断连通知暂存",
                disconnectStageSummary,
                disconnectStageEnabled,
                state.canToggleNotifyDisconnectStage,
                onNotifyDisconnectStageEnable,
            )
        }
    }

    item("sleep-dnd-title") { SectionTitle("入睡联动") }
    item("sleep-dnd-card") {
        Card(Modifier.fillMaxWidth()) {
            SwitchRow(
                "入睡开启免打扰",
                sleepDndSummary,
                sleepDndEnabled,
                state.canToggleSleepAutoDnd,
                onSleepAutoDndEnable,
            )
            HorizontalDivider()
            SwitchRow(
                "醒来退出免打扰",
                wakeDndSummary,
                wakeDndEnabled,
                state.canToggleWakeExitDnd,
                onWakeExitDndEnable,
            )
            HorizontalDivider()
            SwitchRow(
                "入睡暂停媒体播放",
                sleepPauseMediaSummary,
                sleepPauseMediaEnabled,
                state.canToggleSleepPauseMedia,
                onSleepPauseMediaEnable,
            )
            HorizontalDivider()
            SwitchRow(
                "入睡停止短视频与直播",
                sleepStopVideoSummary,
                sleepStopVideoEnabled,
                state.canToggleSleepStopVideo,
                onSleepStopVideoEnable,
            )
            if (missingDndPolicy) {
                HorizontalDivider()
                Menu("授予免打扰控制权限", "前往系统「勿扰权限」允许华为运动健康", onOpenDndAccessSettings)
            }
            if (missingWriteSettings) {
                HorizontalDivider()
                Menu("授予修改系统设置权限", "允许华为运动健康在停止视频后自动息屏", onOpenWriteSettingsAccess)
            }
        }
    }

    if (sleepStopVideoEnabled) {
        item("sleep-video-pkgs-title") { SectionTitle("短视频与直播应用列表") }
        item("sleep-video-pkgs-card") {
            var inputPkg by rememberSaveable { mutableStateOf("") }
            var inputError by remember { mutableStateOf<String?>(null) }
            val presetLabels = remember {
                SleepVideoStopPolicy.DEFAULT_PRESETS.joinToString("、") { it.label() }
            }
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = "内置支持平台（${SleepVideoStopPolicy.DEFAULT_PRESETS.size} 个）",
                    summary = presetLabels,
                )
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "添加自定义包名",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = inputPkg,
                            onValueChange = {
                                inputPkg = it
                                inputError = null
                            },
                            label = "例如 com.example.video",
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "添加",
                            enabled = state.canToggleSleepStopVideo && inputPkg.isNotBlank(),
                            onClick = {
                                val candidate = inputPkg.trim()
                                if (!SleepVideoStopPolicy.isValidPackageName(candidate)) {
                                    inputError = "包名格式不正确，需包含点号（如 com.example.app）"
                                } else if (customVideoPkgs.contains(candidate)) {
                                    inputError = "该包名已在自定义列表中"
                                } else {
                                    val updated = SleepVideoStopPolicy.addCustomPackage(customVideoPkgsRaw, candidate)
                                    inputPkg = ""
                                    inputError = null
                                    onSleepStopVideoCustomPkgs(updated)
                                }
                            },
                        )
                    }
                    inputError?.let { err ->
                        Text(
                            text = err,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
                if (customVideoPkgs.isEmpty()) {
                    HorizontalDivider()
                    BasicComponent(
                        title = "已添加自定义包名",
                        summary = "暂未添加，可按需补充其他短视频、短剧或直播 APP 包名",
                    )
                } else {
                    customVideoPkgs.forEach { pkg ->
                        HorizontalDivider()
                        BasicComponent(
                            title = pkg,
                            summary = "自定义短视频 / 直播应用",
                            endActions = {
                                TextButton(
                                    text = "移除",
                                    enabled = state.canToggleSleepStopVideo,
                                    onClick = {
                                        val updated = SleepVideoStopPolicy.removeCustomPackage(customVideoPkgsRaw, pkg)
                                        onSleepStopVideoCustomPkgs(updated)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.batteryPage(
    state: WatchLinkUiState,
    onBatteryPhoneFullWatchEnable: (Boolean) -> Unit,
    onBatteryPhoneLowWatchEnable: (Boolean) -> Unit,
    onBatteryPhoneLowThreshold: (Int) -> Unit,
    onBatteryWatchFullPhoneEnable: (Boolean) -> Unit,
    onBatteryWatchLowPhoneEnable: (Boolean) -> Unit,
    onBatteryWatchLowThreshold: (Int) -> Unit,
) {
    val phoneFullEnabled = state.effectiveBatteryPhoneFullWatchEnabled
    val phoneLowEnabled = state.effectiveBatteryPhoneLowWatchEnabled
    val phoneLowThreshold = state.effectiveBatteryPhoneLowThreshold
    val watchFullEnabled = state.effectiveBatteryWatchFullPhoneEnabled
    val watchLowEnabled = state.effectiveBatteryWatchLowPhoneEnabled
    val watchLowThreshold = state.effectiveBatteryWatchLowThreshold

    val phoneFullSummary = when {
        state.batteryPhoneFullWatchSaving -> "正在保存…"
        !state.canToggleBatteryPhoneFullWatch -> "请先启用模块或打开运动健康"
        else -> "手机充至 100% 或达到 ColorOS 充电上限时提醒手表（智能充电保护暂缓与旁路供电不触发），遵循手表静默推送规则"
    }
    val phoneLowSummary = when {
        state.batteryPhoneLowWatchSaving -> "正在保存…"
        !state.canToggleBatteryPhoneLowWatch -> "请先启用模块或打开运动健康"
        else -> "手机未插电且电量降至设定阈值时提醒手表，遵循手表静默推送规则"
    }
    val watchFullSummary = when {
        state.batteryWatchFullPhoneSaving -> "正在保存…"
        !state.canToggleBatteryWatchFullPhone -> "请先启用模块或打开运动健康"
        else -> "手表充电至 100% 时在手机弹出通知（自动过滤防回声）"
    }
    val watchLowSummary = when {
        state.batteryWatchLowPhoneSaving -> "正在保存…"
        !state.canToggleBatteryWatchLowPhone -> "请先启用模块或打开运动健康"
        else -> "手表未充电且电量降至设定阈值时在手机弹出通知"
    }

    item("battery-phone-title") { SectionTitle("手机提醒手表") }
    item("battery-phone-card") {
        Card(Modifier.fillMaxWidth()) {
            SwitchRow(
                "手机充满提醒手表",
                phoneFullSummary,
                phoneFullEnabled,
                state.canToggleBatteryPhoneFullWatch,
                onBatteryPhoneFullWatchEnable,
            )
            HorizontalDivider()
            SwitchRow(
                "手机低电量提醒手表",
                phoneLowSummary,
                phoneLowEnabled,
                state.canToggleBatteryPhoneLowWatch,
                onBatteryPhoneLowWatchEnable,
            )
            if (phoneLowEnabled) {
                HorizontalDivider()
                var phoneInput by rememberSaveable(phoneLowThreshold) { mutableStateOf(phoneLowThreshold.toString()) }
                var phoneError by remember { mutableStateOf<String?>(null) }
                val resetAt = io.github.watchlink.notify.BatteryAlertPolicy.hysteresisResetLevel(phoneLowThreshold)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "手机低电量阈值（%）",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "当前：$phoneLowThreshold%；插电或电量回升至 $resetAt% 后重置提醒状态",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = phoneInput,
                            onValueChange = { raw ->
                                phoneInput = raw.filter { it.isDigit() }.take(2)
                                phoneError = null
                            },
                            label = "${WatchLinkPrefs.MIN_BATTERY_LOW_THRESHOLD} ~ ${WatchLinkPrefs.MAX_BATTERY_LOW_THRESHOLD}%",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "保存",
                            enabled = state.canToggleBatteryPhoneLowWatch && phoneInput.isNotBlank(),
                            onClick = {
                                val parsed = phoneInput.toIntOrNull()
                                if (parsed == null || parsed < WatchLinkPrefs.MIN_BATTERY_LOW_THRESHOLD
                                    || parsed > WatchLinkPrefs.MAX_BATTERY_LOW_THRESHOLD) {
                                    phoneError = "请输入 ${WatchLinkPrefs.MIN_BATTERY_LOW_THRESHOLD} ~ ${WatchLinkPrefs.MAX_BATTERY_LOW_THRESHOLD} 之间的整数"
                                } else {
                                    phoneError = null
                                    onBatteryPhoneLowThreshold(parsed)
                                }
                            },
                        )
                    }
                    phoneError?.let { err ->
                        Text(
                            text = err,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    item("battery-watch-title") { SectionTitle("手表提醒手机") }
    item("battery-watch-card") {
        Card(Modifier.fillMaxWidth()) {
            SwitchRow(
                "手表充满提醒手机",
                watchFullSummary,
                watchFullEnabled,
                state.canToggleBatteryWatchFullPhone,
                onBatteryWatchFullPhoneEnable,
            )
            HorizontalDivider()
            SwitchRow(
                "手表低电量提醒手机",
                watchLowSummary,
                watchLowEnabled,
                state.canToggleBatteryWatchLowPhone,
                onBatteryWatchLowPhoneEnable,
            )
            if (watchLowEnabled) {
                HorizontalDivider()
                var watchInput by rememberSaveable(watchLowThreshold) { mutableStateOf(watchLowThreshold.toString()) }
                var watchError by remember { mutableStateOf<String?>(null) }
                val resetAt = io.github.watchlink.notify.BatteryAlertPolicy.hysteresisResetLevel(watchLowThreshold)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "手表低电量阈值（%）",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "当前：$watchLowThreshold%；开始充电或电量回升至 $resetAt% 后重置提醒状态",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = watchInput,
                            onValueChange = { raw ->
                                watchInput = raw.filter { it.isDigit() }.take(2)
                                watchError = null
                            },
                            label = "${WatchLinkPrefs.MIN_BATTERY_LOW_THRESHOLD} ~ ${WatchLinkPrefs.MAX_BATTERY_LOW_THRESHOLD}%",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "保存",
                            enabled = state.canToggleBatteryWatchLowPhone && watchInput.isNotBlank(),
                            onClick = {
                                val parsed = watchInput.toIntOrNull()
                                if (parsed == null || parsed < WatchLinkPrefs.MIN_BATTERY_LOW_THRESHOLD
                                    || parsed > WatchLinkPrefs.MAX_BATTERY_LOW_THRESHOLD) {
                                    watchError = "请输入 ${WatchLinkPrefs.MIN_BATTERY_LOW_THRESHOLD} ~ ${WatchLinkPrefs.MAX_BATTERY_LOW_THRESHOLD} 之间的整数"
                                } else {
                                    watchError = null
                                    onBatteryWatchLowThreshold(parsed)
                                }
                            },
                        )
                    }
                    watchError?.let { err ->
                        Text(
                            text = err,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.myDevicesPage(
    state: WatchLinkUiState,
    onMyDevicesCardEnable: (Boolean) -> Unit,
    onMyDevicesTapTarget: (String) -> Unit,
    onRequestMyDevicesScope: () -> Unit,
) {
    val enabled = state.effectiveMyDevicesCardEnabled
    val tapTarget = state.effectiveMyDevicesTapTarget
    val scope = state.scope
    val checks = state.checks
    val trusted = checks == null || checks.myDevicesTrusted
    val needsScope = enabled && trusted && scope?.frameworkConnected() == true && !scope.myDevicesGranted()

    val cardSummary = when {
        !trusted -> "未检测到官方 ColorOS 设备空间"
        state.myDevicesCardSaving -> "正在保存…"
        !state.canToggleMyDevicesCard -> "请先启用模块或打开运动健康"
        needsScope -> "需授权「设备空间」作用域"
        else -> "在系统设备空间与控制中心显示手表状态与电量"
    }

    item("mydevices-title") { SectionTitle("ColorOS 设备空间") }
    item("mydevices-card") {
        Card(Modifier.fillMaxWidth()) {
            SwitchRow(
                "显示手表设备卡片",
                cardSummary,
                enabled,
                trusted && state.canToggleMyDevicesCard,
                onMyDevicesCardEnable,
            )
            if (needsScope) {
                HorizontalDivider()
                if (scope.requestPending()) {
                    BasicComponent(title = "等待 LSPosed 授权", summary = "请在弹窗中同意「我的设备（设备空间）」")
                } else {
                    Menu("申请设备空间作用域", "向 LSPosed 申请「设备空间」作用域", onRequestMyDevicesScope)
                }
            }
            if (enabled) {
                HorizontalDivider()
                val options = listOf("华为运动健康", "WatchLink")
                val selectedIndex = if (tapTarget == WatchLinkPrefs.TAP_TARGET_WATCHLINK) 1 else 0
                var expanded by remember { mutableStateOf(false) }
                BasicComponent(
                    title = "点击卡片打开",
                    enabled = state.canToggleMyDevicesCard,
                    onClick = { if (state.canToggleMyDevicesCard) expanded = true },
                    endActions = {
                        Box(contentAlignment = Alignment.CenterEnd) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = options[selectedIndex],
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                                DropdownArrowEndAction(
                                    actionColor = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            }
                            WindowListPopup(
                                show = expanded,
                                popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
                                alignment = PopupPositionProvider.Align.End,
                                onDismissRequest = { expanded = false },
                            ) {
                                ListPopupColumn {
                                    options.forEachIndexed { idx, label ->
                                        DropdownImpl(
                                            text = label,
                                            optionSize = options.size,
                                            isSelected = idx == selectedIndex,
                                            index = idx,
                                            onSelectedIndexChange = { picked ->
                                                expanded = false
                                                val nextTarget = if (picked == 1) WatchLinkPrefs.TAP_TARGET_WATCHLINK
                                                else WatchLinkPrefs.TAP_TARGET_HEALTH
                                                if (nextTarget != tapTarget) {
                                                    onMyDevicesTapTarget(nextTarget)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun LazyListScope.wifiPage(
    state: WatchLinkUiState,
    onWifiAutoEnable: (Boolean) -> Unit,
    onRequestWirelessScope: () -> Unit,
    onSyncCurrentWifi: () -> Unit,
    onOpenWifiBulk: () -> Unit,
) {
    val enabled = state.effectiveWifiAutoEnabled
    val scope = state.scope
    val checks = state.checks
    val health = state.healthHost
    val trusted = checks == null || checks.wirelessTrusted
    val needsScope = enabled && trusted && scope?.frameworkConnected() == true && !scope.wirelessGranted()

    val autoSummary = when {
        !trusted -> "未检测到官方无线设置"
        state.wifiAutoSaving -> "正在保存…"
        !state.canToggleWifiAuto -> "请先启用模块或打开运动健康"
        needsScope -> "需授权「无线设置」作用域"
        else -> "手机连入新网络时免 Root 自动同步到手表"
    }

    item("wifi-auto-title") { SectionTitle("自动同步") }
    item("wifi-auto-card") {
        Card(Modifier.fillMaxWidth()) {
            SwitchRow("自动同步新连接的 Wi-Fi", autoSummary, enabled, trusted && state.canToggleWifiAuto, onWifiAutoEnable)
            if (needsScope) {
                HorizontalDivider()
                if (scope.requestPending()) {
                    BasicComponent(title = "等待 LSPosed 授权", summary = "请在弹窗中同意「无线设置」")
                } else {
                    Menu("申请无线设置作用域", "无需定位权限即可读取当前已连接网络", onRequestWirelessScope)
                }
            } else if (trusted && health?.reachable() == true && (scope?.wirelessGranted() == true || scope?.frameworkConnected() != true)) {
                HorizontalDivider()
                BasicComponent(
                    title = "立即同步当前 Wi-Fi",
                    summary = if (state.wifiSyncingCurrent) "正在同步到手表…" else "手动将当前已连接的 Wi-Fi 下发到手表",
                    endActions = {
                        TextButton(
                            if (state.wifiSyncingCurrent) "同步中…" else "同步",
                            onSyncCurrentWifi,
                            enabled = !state.wifiSyncingCurrent
                        )
                    }
                )
            }
        }
    }

    item("wifi-bulk-title") { SectionTitle("批量导入") }
    item("wifi-bulk-card") {
        Card(Modifier.fillMaxWidth()) {
            Menu("批量同步已保存 Wi-Fi", "临时使用 Root 读取并批量导入手机已保存网络", onOpenWifiBulk)
        }
    }
}

private fun LazyListScope.wifiBulkSyncPage(
    state: WatchLinkUiState,
    onLoadSavedWifi: () -> Unit,
    onToggleWifiSelection: (String) -> Unit,
    onSelectWifiPreset: (String) -> Unit,
    onToggleWifiForceResend: (Boolean) -> Unit,
    onStartBulkWifiSync: () -> Unit,
    onCancelBulkWifiSync: () -> Unit,
) {
    val bulk = state.wifiBulk
    val health = state.healthHost
    val supportedItems = bulk.items.filter { it.record.isSupportedByWatch() }
    val selectedCount = supportedItems.count { it.selected }

    item("wifi-bulk-read") {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = if (bulk.items.isEmpty()) "读取已保存 Wi-Fi" else "重新读取列表",
                summary = when {
                    bulk.loading -> "正在通过 Root 读取…"
                    bulk.loadError != null -> bulk.loadError
                    bulk.items.isNotEmpty() -> "共 ${bulk.items.size} 个网络，可同步 ${supportedItems.size} 个"
                    else -> "临时 Root 仅内存读取，离页清空，不展示明文密码"
                },
                endActions = {
                    TextButton(
                        if (bulk.loading) "读取中…" else "读取",
                        onLoadSavedWifi,
                        enabled = !bulk.loading && !bulk.syncing
                    )
                }
            )
        }
    }

    if (bulk.items.isNotEmpty()) {
        item("wifi-bulk-controls") {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = "已选 $selectedCount / ${supportedItems.size}",
                    summary = "支持开放与加密网络，暂不支持企业认证",
                    endActions = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton("全选", { onSelectWifiPreset("ALL") }, enabled = !bulk.syncing)
                            TextButton("仅加密", { onSelectWifiPreset("ENCRYPTED") }, enabled = !bulk.syncing)
                            TextButton("清空", { onSelectWifiPreset("NONE") }, enabled = !bulk.syncing)
                        }
                    }
                )
                HorizontalDivider()
                SwitchRow("强制重发已同步网络", "重发手表已记录的网络", bulk.forceResend, !bulk.syncing, onToggleWifiForceResend)
                HorizontalDivider()
                val progressSummary = when {
                    bulk.syncing -> "同步中 ${bulk.completedCount} / ${bulk.totalCount}（已确认 ${bulk.successCount}）"
                    bulk.summaryMessage != null -> bulk.summaryMessage
                    health?.reachable() != true -> "请先打开华为运动健康并连接手表"
                    else -> "按序下发到手表并等待回执"
                }
                BasicComponent(
                    title = if (bulk.syncing) "正在同步" else "开始同步",
                    summary = progressSummary,
                    endActions = {
                        if (bulk.syncing) {
                            TextButton("停止", onCancelBulkWifiSync)
                        } else {
                            TextButton(
                                "同步（$selectedCount）",
                                onStartBulkWifiSync,
                                enabled = selectedCount > 0 && health?.reachable() == true
                            )
                        }
                    }
                )
            }
        }

        item("wifi-bulk-list-title") { SectionTitle("网络列表") }
        item("wifi-bulk-list") {
            Card(Modifier.fillMaxWidth()) {
                bulk.items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider()
                    val supported = item.record.isSupportedByWatch()
                    val statusText = wifiBulkItemStatusText(item.status)
                    val summary = buildString {
                        append(item.record.securityLabel())
                        if (item.record.hidden()) append(" · 隐藏网络")
                        if (statusText.isNotEmpty()) append(" · ").append(statusText)
                    }
                    BasicComponent(
                        title = item.record.ssid(),
                        summary = summary,
                        onClick = if (supported && !bulk.syncing) { { onToggleWifiSelection(item.key) } } else null,
                        endActions = {
                            Checkbox(
                                state = if (item.selected) ToggleableState.On else ToggleableState.Off,
                                onClick = { onToggleWifiSelection(item.key) },
                                enabled = supported && !bulk.syncing
                            )
                        }
                    )
                }
            }
        }
    }
}

private fun LazyListScope.setupActions(
    state: WatchLinkUiState,
    onHotReload: (Boolean) -> Unit,
    onRequestScope: () -> Unit,
    onClock: () -> Unit,
    onHealth: () -> Unit,
    onRefresh: () -> Unit,
    onHealthDownload: () -> Unit,
) {
    val issues = state.checks?.issues(state.scope).orEmpty()
    if (state.scope?.active() != true && SetupIssue.SCOPE !in issues && SetupIssue.HOST_STALE !in issues) {
        item("setup-scope") {
            Card(Modifier.fillMaxWidth()) {
                ScopeActions(state.scope, onRequestScope, onClock, onHealth, onRefresh)
            }
        }
    }
    if (state.checks != null && issues.isNotEmpty()) {
        item("remedies-title") { SectionTitle("待处理事项") }
        item("remedies") {
            Card(Modifier.fillMaxWidth()) {
                issues.forEachIndexed { index, issue ->
                    if (index > 0) HorizontalDivider()
                    when (issue) {
                        SetupIssue.HOST_STALE -> BasicComponent(
                            title = "热重载宿主以应用新版本",
                            summary = "有宿主仍在运行旧版本，点击即可热重载生效，无需重启手机。",
                            endActions = {
                                TextButton(
                                    if (state.reloading) "重载中…" else "热重载",
                                    { onHotReload(true) },
                                    enabled = !state.reloading
                                )
                            }
                        )
                        SetupIssue.SCOPE -> ScopeActions(state.scope, onRequestScope, onClock, onHealth, onRefresh)
                        SetupIssue.CLOCK_APP -> Menu("检查系统时钟", "请使用官方签名的一加 / ColorOS 系统时钟。", onClock)
                        SetupIssue.CLOCK_CONTRACT -> BasicComponent(
                            title = "表端反控已保护性暂停",
                            summary = "提醒仍可同步到手表，当前时钟版本的表端停止/延后已自动禁用。"
                        )
                        SetupIssue.HEALTH_SERVICE -> Menu("检查华为运动健康", "请安装官方签名的华为运动健康。", onHealthDownload)
                        SetupIssue.HEALTH_STOPPED -> Menu("打开华为运动健康", "运动健康当前已停止，打开后确认手表已连接。", onHealth)
                        SetupIssue.WATCH_DISCONNECTED -> Menu("连接华为手表", "未检测到已连接的手表，请打开运动健康确认连接。", onHealth)
                        SetupIssue.WATCH_SWITCH_OFF -> Menu("检查手表闹钟协同", "运动健康中该手表的闹钟协同记录为关闭状态。", onHealth)
                        SetupIssue.WATCH_UNSUPPORTED -> BasicComponent(
                            title = "手表不支持原生闹钟协同",
                            summary = "已连接设备未声明华为原生协同闹钟能力。"
                        )
                        SetupIssue.WATCH_DEVICE_MISMATCH -> Menu("确认主设备", "运动健康的主设备与连接记录不一致。", onHealth)
                        SetupIssue.HEALTH_SDK_DEGRADED -> BasicComponent(
                            title = "运动健康处于兼容模式",
                            summary = "可发送测试提醒验证弹窗与表端按键回传。"
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.diagnosticsPage(
    state: WatchLinkUiState,
    onStartTest: () -> Unit,
    onCancelTest: () -> Unit,
    onStartLiveTest: () -> Unit,
    onCancelLiveTest: () -> Unit,
    onRawCommandDebugEnable: (Boolean) -> Unit,
    onHotReload: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
) {
    val health = state.healthHost
    val clock = state.clockHost
    val calendar = state.calendarHost
    val scope = state.scope
    val testState = health?.testState() ?: "IDLE"
    val testRunning = testState in setOf("SENDING", "SENT", "SENT_DIRECT", "SERVICE_RECEIVED", "OUTBOUND")
    val liveTestState = health?.liveTestState() ?: "IDLE"
    val liveTestRunning = liveTestState in setOf("OPENED", "UPDATED")

    item("test-title") { SectionTitle("手表测试") }
    item("test-card") {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "强提醒测试",
                summary = if (health?.reachable() != true) {
                    "运动健康未应答，请先打开运动健康并连接手表。"
                } else {
                    testStateSummary(testState, health.testDispatchMode(), health.testActionSource())
                },
                endActions = {
                    if (testRunning) {
                        TextButton("结束", onCancelTest, enabled = !state.testing)
                    } else {
                        TextButton("发送", onStartTest, enabled = !state.testing && health?.reachable() == true)
                    }
                }
            )
            HorizontalDivider()
            BasicComponent(
                title = "实况窗测试",
                summary = if (health?.reachable() != true) {
                    "运动健康未应答，请先打开运动健康并连接手表。"
                } else {
                    liveTestStateSummary(liveTestState, health.liveDispatchMode())
                },
                endActions = {
                    if (liveTestRunning) {
                        TextButton("结束", onCancelLiveTest, enabled = !state.liveTesting)
                    } else {
                        TextButton("发送", onStartLiveTest, enabled = !state.liveTesting && health?.reachable() == true)
                    }
                }
            )
        }
    }

    val hasClockRing = clock != null && clock.reachable() && clock.lastRingAt() > 0
    val hasCalendarRing = calendar != null && calendar.reachable() && calendar.lastRingAt() > 0
    if (hasClockRing || hasCalendarRing) {
        item("recent-title") { SectionTitle("最近同步记录") }
        item("recent-card") {
            Card(Modifier.fillMaxWidth()) {
                if (hasClockRing) {
                    RingHistoryItem(
                        at = clock.lastRingAt(),
                        delivery = clock.delivery(),
                        action = clock.action(),
                        actionResult = clock.actionResult(),
                        close = clock.close(),
                        subject = "手机闹钟",
                    )
                }
                if (hasClockRing && hasCalendarRing) HorizontalDivider()
                if (hasCalendarRing) {
                    RingHistoryItem(
                        at = calendar.lastRingAt(),
                        delivery = calendar.delivery(),
                        action = calendar.action(),
                        actionResult = calendar.actionResult(),
                        close = calendar.close(),
                        subject = "日历强提醒",
                    )
                }
            }
        }
    }

    item("hosts-title") { SectionTitle("宿主与热重载") }
    item("hosts-card") {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(title = "框架环境", summary = frameworkSummaryText(scope))
            HorizontalDivider()
            BasicComponent(title = "手表连接", summary = watchStateSimple(health))
            HorizontalDivider()
            BasicComponent(title = "系统时钟", summary = targetRuntimeText(scope, TargetProfile.CLOCK))
            HorizontalDivider()
            BasicComponent(title = "运动健康（:PhoneService）", summary = targetRuntimeText(scope, TargetProfile.HEALTH))
            HorizontalDivider()
            BasicComponent(title = "运动健康（:DaemonService）", summary = daemonRuntimeText(scope))
            HorizontalDivider()
            BasicComponent(title = "系统日历（可选）", summary = targetRuntimeText(scope, TargetProfile.CALENDAR))
            HorizontalDivider()
            BasicComponent(title = "智慧服务（可选）", summary = targetRuntimeText(scope, TargetProfile.UMS))
            HorizontalDivider()
            BasicComponent(title = "无线设置（可选）", summary = targetRuntimeText(scope, TargetProfile.WIRELESS_SETTINGS))
            HorizontalDivider()
            BasicComponent(title = "设备空间（可选）", summary = targetRuntimeText(scope, TargetProfile.MY_DEVICES))
            HorizontalDivider()
            BasicComponent(
                title = "热重载宿主",
                summary = when {
                    scope?.supportsApi102() != true -> "当前框架不支持热重载，请手动重启宿主。"
                    !scope.canHotReloadAny() -> "暂无运行中的可热重载宿主。"
                    scope.hasStaleTarget() -> "检测到旧版本宿主，点击即可热重载（响铃中自动延后）。"
                    else -> "宿主均为最新版本，可按需手动重载（响铃中自动延后）。"
                },
                endActions = {
                    TextButton(
                        if (state.reloading) "重载中…" else "热重载",
                        { onHotReload(false) },
                        enabled = !state.reloading && scope?.canHotReloadAny() == true
                    )
                }
            )
            HorizontalDivider()
            Menu("刷新全部状态", "重新查询作用域、宿主与手表状态", onRefresh)
        }
    }

    item("channels-title") { SectionTitle("通道与符号解析") }
    item("channels-card") {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(title = "时钟符号解析", summary = clockResilienceText(clock))
            HorizontalDivider()
            BasicComponent(title = "日历符号解析", summary = calendarResilienceText(calendar))
            HorizontalDivider()
            BasicComponent(title = "运动健康符号解析", summary = healthResilienceText(health))
            HorizontalDivider()
            BasicComponent(title = "配置持久化", summary = remotePrefsDetail(state))
            HorizontalDivider()
            BasicComponent(title = "提醒下发方式", summary = dispatchModeText(health?.lastDispatchMode() ?: clock?.dispatchMode()))
            HorizontalDivider()
            BasicComponent(title = "表端按键回程", summary = actionSourceText(health?.lastActionSource() ?: clock?.actionSource()))
            HorizontalDivider()
            BasicComponent(title = "实况窗通道", summary = liveDiagnosticsText(health))
            HorizontalDivider()
            BasicComponent(title = "通知与入睡联动", summary = sleepDndDiagnosticsText(health))
            HorizontalDivider()
            BasicComponent(title = "Wi-Fi 通道", summary = wifiDiagnosticsText(health))
        }
    }

    item("debug-raw-title") { SectionTitle("高级调试") }
    item("debug-raw-card") {
        Card(Modifier.fillMaxWidth()) {
            SwitchRow(
                title = "自定义指令调试",
                summary = "高风险：允许通过 ADB 广播命令直接向手表发送底层蓝牙指令（如 5.49.5、5.1.12 或 WearEngine 事件）。错误或危险指令（如 5.1.13 恢复出厂）可能导致手表异常、配置损坏或重启，仅限开发者协议调试使用。\n" +
                    "默认关闭且不监听任何调试命令；开启后仍需通过命令行发送（Action：${BuildConfig.APPLICATION_ID}.debug.SEND_RAW_COMMAND）。",
                checked = state.effectiveRawCommandDebugEnabled,
                enabled = state.canToggleRawCommandDebug,
                onCheckedChange = onRawCommandDebugEnable,
            )
        }
    }

    item("share-title") { SectionTitle("诊断日志") }
    item("share-card") {
        Card(Modifier.fillMaxWidth()) {
            if (state.sharing) {
                BasicComponent(title = "正在打包脱敏日志…", summary = "仅收集允许列表事件，不含个人内容。")
            } else {
                Menu("导出脱敏日志", "生成 ZIP 诊断包，不含日程、闹钟原文与设备号", onShare)
            }
        }
    }
}

@Composable
private fun RingHistoryItem(
    at: Long,
    delivery: String,
    action: String,
    actionResult: String,
    close: String,
    subject: String,
) {
    val stamp = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    val actionLabel = when (action) {
        "SNOOZE" -> "延后"
        "DISMISS" -> "停止"
        else -> ""
    }
    BasicComponent(
        title = "$subject · ${stamp.format(Date(at))}",
        summary = buildList {
            add(deliverySummarySimple(delivery, subject))
            if (actionLabel.isNotEmpty()) {
                add(when (actionResult) {
                    "VERIFIED" -> "表端点击「$actionLabel」，手机已同步$actionLabel。"
                    "REJECTED" -> "收到表端「$actionLabel」，手机未执行。"
                    else -> "收到表端「$actionLabel」，结果待核对。"
                })
            }
            when (close) {
                "OUTBOUND" -> add("手机响铃结束后，手表提醒已自动关闭。")
                "SENT" -> add("已通知手表关闭本次提醒。")
                "UNCERTAIN" -> add("手表提醒关闭结果待核对。")
            }
        }.joinToString("\n")
    )
}

@Composable
private fun ScopeActions(
    scope: ModuleScope.View?,
    onRequest: () -> Unit,
    onClock: () -> Unit,
    onHealth: () -> Unit,
    onRefresh: () -> Unit,
) {
    val guidance = when {
        scope?.requestPending() == true -> "请在 LSPosed 弹窗中同意「系统时钟」与「华为运动健康」。"
        scope?.requestResult() == "DENIED" -> "上次申请未通过，可重试或在 LSPosed 中手动勾选。"
        scope?.frameworkConnected() != true -> "请在 LSPosed 启用 WatchLink，并勾选「系统时钟」与「华为运动健康」。"
        scope.configured() -> "作用域已勾选，请重启未生效的宿主后返回刷新。"
        else -> "自动向 LSPosed 申请「系统时钟」与「华为运动健康」作用域。"
    }
    if (scope?.requestPending() == true) {
        BasicComponent(title = "等待 LSPosed 授权", summary = guidance)
    } else if (scope?.configured() == true) {
        Menu("刷新生效状态", guidance, onRefresh)
    } else {
        Menu("一键申请基础作用域", guidance, onRequest)
    }
    if (scope?.configured() == true && !scope.clockActive()) {
        HorizontalDivider()
        Menu("打开系统时钟", "启动系统时钟以加载模块", onClock)
    }
    if (scope?.configured() == true && !scope.healthActive()) {
        HorizontalDivider()
        Menu("打开华为运动健康", "启动运动健康并保持手表连接", onHealth)
    }
}

private fun LazyListScope.guidePage(onCoolapk: () -> Unit) {
    item("guide-start-title") { SectionTitle("快速配置") }
    item("guide-start-card") {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "1. 启用模块与基础作用域",
                summary = "在 LSPosed 中启用 WatchLink，并勾选基础宿主「系统时钟」与「华为运动健康」。其余功能按需勾选对应可选作用域即可，无需 Magisk 模块，也无需勾选 SystemUI。"
            )
            HorizontalDivider()
            BasicComponent(
                title = "2. 重载宿主并开启功能",
                summary = "首次勾选后重启已勾选宿主（或前往「测试与诊断」点击热重载）。所有功能开关默认关闭，请进入「功能设置」按需开启。"
            )
            HorizontalDivider()
            BasicComponent(
                title = "3. 零后台与跨重启生效",
                summary = "所有配置均通过 LSPosed RemotePreferences 持久化。设置完成后可直接关闭 WatchLink，手机重启后无需再打开本应用，各宿主直接协同工作。"
            )
        }
    }

    item("guide-features-title") { SectionTitle("功能与作用域说明") }
    item("guide-features-card") {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "提醒同步（基础作用域）",
                summary = "闹钟与倒计时仅需勾选「系统时钟」与「华为运动健康」，通过华为原生强提醒协议下发手表，支持表端停止或延后，手机端先停也会同步关闭手表弹窗。\n" +
                    "倒计时支持「运行中显示实况窗」（需手表支持实况窗，展示进度与预计结束时间）与「延后键改为再次计时」（按原时长重计）；出于隐私保护，模块不会读取自定义倒计时备注。\n" +
                    "日历强提醒需额外勾选「系统日历」，仅同步明确设为强提醒的日程与待办，普通通知日程仍走运动健康原有通知渠道。"
            )
            HorizontalDivider()
            BasicComponent(
                title = "实况窗与游戏闪回（建议勾选智慧服务）",
                summary = "可将取餐码、外卖配送、打车行程、航班/高铁出行、快递物流（含驿站取件码）、电影票与排号进度同步至支持实况窗的手表，自动适配 10 格弦月窗宽度，进度更新时保持静默。\n" +
                    "「同步游戏闪回」可同步游戏复活与起飞倒计时，支持自定义提前 0–300 秒震动提醒（若大于剩余复活时间则立即提醒一次），并在游戏前台静默期间自动放行。\n" +
                    "Android 实况通知依赖运动健康已有的通知监听；ColorOS 流体云需额外勾选「智慧服务（com.oplus.pantanal.ums）」。"
            )
            HorizontalDivider()
            BasicComponent(
                title = "通知与入睡（基础作用域）",
                summary = "仅需勾选「华为运动健康」，无需新增作用域。\n" +
                    "「手表静默推送」基于华为 5.2.1 静默标记，提供免打扰、手机静默通知、亮屏解锁、禅定与专注、游戏前台五项独立开关，开启后通知仅静默送达手表而不震动（不拦截来电与强提醒，游戏闪回也会穿透游戏前台静默）。\n" +
                    "「通知连发防轰炸」可在自定义秒数（1–300 秒，默认 10 秒）内让同一应用的后续普通通知自动转为静默推送，仅首条震动提醒，不遗漏通知内容也不拦截来电与强提醒。\n" +
                    "「短时断连通知暂存」在手表短暂断连时于内存中暂存 1 分钟内最多 5 条普通通知（同条更新自动去重，手机端已读清除同步移除）；重连后按时间顺序补发，其中仅最新 1 条震动提醒，其余全部以静默方式补发，避免连续震动打扰。\n" +
                    "「入睡联动」提供入睡开启免打扰、醒来退出免打扰、入睡暂停媒体播放、入睡停止短视频与直播（返回桌面并自动息屏，支持自定义应用包名）四项独立开关。"
            )
            HorizontalDivider()
            BasicComponent(
                title = "充电与电量提醒（基础作用域）",
                summary = "仅需勾选「华为运动健康」，提供手机充满提醒手表、手机低电量提醒手表、手表充满提醒手机、手表低电量提醒手机四项独立开关。\n" +
                    "手机充满支持 100% 充满以及 ColorOS 充电上限（如 80%/85%/90%/95%），自动排除夜间智能充电保护暂缓与旁路供电；手机与手表低电量均支持 1–80% 自定义阈值（默认 20%，+5% 迟滞防抖）。\n" +
                    "手机发往手表的电量提醒会遵循「手表静默推送」中已开启的静默规则。"
            )
            HorizontalDivider()
            BasicComponent(
                title = "快捷设置磁贴（基础作用域）",
                summary = "可在系统下拉控制中心添加「WatchLink 手表状态」磁贴，直接显示手表当前电量与充电状态；单击磁贴立即向手表刷新电量，长按磁贴进入 WatchLink。磁贴按需查询，不常驻后台。"
            )
            HorizontalDivider()
            BasicComponent(
                title = "设备空间卡片（需勾选设备空间）",
                summary = "需额外勾选「我的设备 / 设备空间（com.heytap.mydevices）」，不依赖 OPPO 健康，也无需 WatchLink 常驻后台。运动健康会自动同步手表图标、连接状态与电量到设备空间；可在设置页自定义点击卡片打开华为运动健康或 WatchLink。"
            )
            HorizontalDivider()
            BasicComponent(
                title = "Wi-Fi 同步（自动同步需勾选无线设置）",
                summary = "自动同步需额外勾选「无线设置（com.oplus.wirelesssettings）」，无需 Root 与定位权限，手机连入新网络时自动下发手表。\n" +
                    "「批量同步」可在首次使用时临时通过 Root 于内存中读取已保存网络，不展示也不保存明文密码，离页即清空；支持开放与 WPA2/WPA3/WEP 网络，暂不支持企业认证（EAP）。"
            )
        }
    }

    item("guide-tips-title") { SectionTitle("维护与排查") }
    item("guide-tips-card") {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "版本升级与热重载",
                summary = "模块内置 DEX 动态符号解析与缓存机制，系统时钟、日历或运动健康更新时会自动重新适配；若反控契约校验未通过，会保护性暂停表端反控而保留单向提醒。\n" +
                    "更新 WatchLink 后可在「测试与诊断」一键热重载，响铃期间会自动保护延迟，不影响当前提醒。"
            )
            HorizontalDivider()
            BasicComponent(
                title = "未能同步时的排查",
                summary = "1. 确认华为运动健康在后台正常运行且手表保持蓝牙连接；\n" +
                    "2. 前往「测试与诊断」确认对应宿主为最新状态，并发送强提醒或实况窗测试验证链路；\n" +
                    "3. 如仍异常，可导出脱敏诊断日志（不含个人日程、闹钟内容、密码与设备号）反馈排查。"
            )
        }
    }

    item("about-title") { SectionTitle("关于") }
    item("about-card") {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "版本与基线",
                summary = "WatchLink ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\n" +
                    "包名：${BuildConfig.APPLICATION_ID}\n" +
                    "框架：LSPosed API 102（兼容 API 101）\n" +
                    "基线：时钟 $VERIFIED_CLOCK · 日历 $VERIFIED_CALENDAR · 智慧服务 $VERIFIED_UMS · 设备空间 $VERIFIED_MY_DEVICES · 无线设置 $VERIFIED_WIRELESS · 运动健康 $VERIFIED_HEALTH"
            )
            HorizontalDivider()
            Menu("作者的酷安主页", "查看更新日志与问题反馈", onCoolapk)
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
        endActions = { Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange) }
    )
}

private fun liveSupportSimple(health: HealthHostControl.Reply?): String = when {
    health == null || !health.reachable() -> "待运动健康上线"
    else -> when (health.liveWatchSupport()) {
        "SUPPORTED_LIVE_LEVEL" -> "已支持原生实况窗（bit 84 + 219）"
        "SUPPORTED_BASIC" -> "已支持基础实况窗（bit 84）"
        "UNSUPPORTED" -> "手表不支持实况窗"
        "WATCH_DISCONNECTED" -> "手表未连接"
        else -> "待确认"
    }
}

private fun liveResultSimple(result: String?, source: String?): String {
    val src = when (source) {
        "UMS" -> "流体云"
        "DAEMON" -> "实况通知"
        "CLOCK_TIMER" -> "倒计时"
        "TEST" -> "实况测试"
        else -> ""
    }
    val msg = when (result) {
        "OPENED" -> "已弹出实况窗"
        "UPDATED" -> "已静默更新"
        "CLOSED" -> "已自动关闭"
        "WATCH_DISCONNECTED" -> "未连接手表"
        "UNSUPPORTED_WATCH" -> "手表不支持"
        "DISABLED" -> "开关已关闭"
        "BG_RESTRICTED" -> "后台受限且直发不可用"
        "FAILED" -> "推送失败"
        else -> ""
    }
    return if (msg.isEmpty()) "" else if (src.isEmpty()) msg else "$src · $msg"
}

private fun liveDiagnosticsText(health: HealthHostControl.Reply?): String {
    if (health == null || !health.reachable()) return "待运动健康上线"
    val cap = liveSupportSimple(health)
    val last = liveResultSimple(health.liveLastResult(), health.liveLastSource())
    val mode = dispatchModeText(health.liveDispatchMode())
    return buildString {
        append(cap).append(" · 活跃 ").append(health.liveActiveCount()).append(" 条")
        if (last.isNotEmpty()) append("\n最近：").append(last).append("（").append(mode).append("）")
    }
}

private fun liveTestStateSummary(state: String, mode: String): String = when (state) {
    "IDLE" -> "发送模拟配送实况窗（35% 弹窗 → 6 秒静默更新至 80% → 14 秒关闭）。"
    "OPENED" -> "已弹出 35% 模拟实况窗（${dispatchModeText(mode)}），6 秒后更新…"
    "UPDATED" -> "已静默更新至 80%，即将自动关闭…"
    "CLOSED" -> "实况窗测试已完成并关闭（${dispatchModeText(mode)}）。"
    "WATCH_UNAVAILABLE" -> "未检测到支持实况窗的已连接手表。"
    else -> "测试未成功，请确认手表连接或热重载后重试。"
}

private fun sleepDndDiagnosticsText(health: HealthHostControl.Reply?): String {
    if (health == null || !health.reachable()) return "待运动健康上线"
    val cap = when (health.sleepWatchSupport()) {
        "SUPPORTED_136" -> "实时睡眠（bit 136）"
        "UNSUPPORTED" -> "未声明 bit 136"
        "WATCH_DISCONNECTED" -> "手表未连接"
        else -> "睡眠能力待确认"
    }
    val silentCap = when (health.silentPushWatchSupport()) {
        "SUPPORTED_94" -> "静默推送（bit 94）"
        "UNSUPPORTED" -> "未声明 bit 94"
        "WATCH_DISCONNECTED" -> "手表未连接"
        else -> "静默能力待确认"
    }
    val hook = if (health.sleepHookReady()) "睡眠监听就绪" else "睡眠监听降级"
    val policy = if (health.dndPolicyAccess()) "免打扰权限就绪" else "缺少免打扰权限"
    val last = when (health.sleepLastEvent()) {
        "FALL_ASLEEP_DND_ON" -> "入睡已开启手机免打扰"
        "FALL_ASLEEP_SKIP_ALREADY_DND" -> "入睡时手机已免打扰（已跳过）"
        "FALL_ASLEEP_MEDIA_PAUSED" -> "入睡已暂停手机媒体播放"
        "FALL_ASLEEP_VIDEO_STOPPED" -> "入睡已停止短视频 / 直播并息屏"
        "WAKE_UP_DND_OFF" -> "醒来已退出手机免打扰"
        "WAKE_UP_SKIP_ALREADY_OFF" -> "醒来时手机未开免打扰（已跳过）"
        "WAKE_UP_SKIP_NOT_OWNED" -> "醒来已跳过"
        "NO_POLICY_ACCESS" -> "缺少免打扰权限"
        "FAILED" -> "切换免打扰失败"
        else -> ""
    }
    val stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
    val fallAsleepText = if (health.sleepLastFallAsleepAt() > 0L) {
        stamp.format(Date(health.sleepLastFallAsleepAt()))
    } else "暂无记录"
    val wakeUpText = if (health.sleepLastWakeUpAt() > 0L) {
        stamp.format(Date(health.sleepLastWakeUpAt()))
    } else "暂无记录"
    return buildString {
        append(silentCap).append(" · ").append(cap)
        append("\n").append(hook).append(" · ").append(policy)
        append("\n最近入睡：").append(fallAsleepText)
        append("\n最近醒来：").append(wakeUpText)
        if (last.isNotEmpty()) append("\n最近联动：").append(last)
    }
}

private fun wifiSupportSimple(health: HealthHostControl.Reply?): String = when {
    health == null || !health.reachable() -> "待运动健康上线"
    else -> when (health.wifiWatchSupport()) {
        "SUPPORTED_NEW" -> "已支持新版同步（bit 241）"
        "SUPPORTED_LEGACY" -> "已支持标准同步"
        "SUPPORTED_UNKNOWN_CAP" -> "已支持 Wi-Fi 同步"
        "UNSUPPORTED" -> "手表不支持 Wi-Fi 同步"
        "WATCH_DISCONNECTED" -> "手表未连接"
        else -> "待确认"
    }
}

private fun wifiResultSimple(result: String?, source: String?): String {
    val src = when (source) {
        "BULK_1A" -> "批量"
        "AUTO_1B" -> "自动"
        else -> ""
    }
    val msg = when (result) {
        "SENDING" -> "同步中…"
        "ACK_OK" -> "手表已确认（100000）"
        "SENT_NO_ACK_HOOK" -> "已下发（无回执监听）"
        "SKIPPED_DUP" -> "已同步过，已跳过"
        "ACK_FAIL" -> "手表返回失败"
        "ACK_TIMEOUT" -> "等待手表回执超时"
        "WATCH_DISCONNECTED" -> "手表未连接"
        "UNSUPPORTED_WATCH" -> "手表不支持"
        "UNSUPPORTED_NETWORK" -> "不支持的网络类型"
        "WIRELESS_UNANSWERED" -> "无线设置未应答"
        "NOT_CONNECTED" -> "当前未连接 Wi-Fi"
        "DISABLED" -> "自动同步已关闭"
        "DIRECT_UNAVAILABLE" -> "直发通道未就绪"
        "FAILED" -> "同步失败"
        else -> ""
    }
    return if (msg.isEmpty()) "" else if (src.isEmpty()) msg else "$src · $msg"
}

private fun wifiBulkItemStatusText(status: String): String = when (status) {
    "PENDING" -> "排队中"
    "SENDING" -> "同步中…"
    "ACK_OK" -> "已确认"
    "SENT_NO_ACK_HOOK" -> "已下发"
    "SKIPPED_DUP" -> "已跳过"
    "ACK_FAIL" -> "手表拒绝"
    "ACK_TIMEOUT" -> "超时"
    "WATCH_DISCONNECTED" -> "未连接"
    "UNSUPPORTED_WATCH" -> "手表不支持"
    "UNSUPPORTED_NETWORK" -> "不支持"
    "HEALTH_UNREACHABLE" -> "健康未应答"
    "FAILED", "DIRECT_UNAVAILABLE" -> "失败"
    else -> ""
}

private fun wifiDiagnosticsText(health: HealthHostControl.Reply?): String {
    if (health == null || !health.reachable()) return "待运动健康上线"
    val cap = wifiSupportSimple(health)
    val ack = if (health.wifiAckReady()) "回执就绪" else "回执降级"
    val last = wifiResultSimple(health.wifiLastResult(), health.wifiLastSource())
    return buildString {
        append(cap).append(" · ").append(ack).append(" · 已同步 ").append(health.wifiSyncedCount()).append(" 个")
        if (last.isNotEmpty()) append("\n最近：").append(last)
    }
}

private fun watchStateSimple(health: HealthHostControl.Reply?): String {
    if (health == null || !health.reachable()) return "待运动健康上线"
    val base = when (health.watchState()) {
        "READY_SUPPORTED_ON" -> "已连接，支持协同"
        "READY_SUPPORTED_UNSET" -> "已连接，首次响铃自动启用"
        "READY_SUPPORTED_OFF" -> "已连接，协同开关已关闭"
        "READY_UNSUPPORTED" -> "设备不支持原生协同"
        "READY_DEVICE_MISMATCH" -> "多设备或主设备不一致"
        "WATCH_DISCONNECTED" -> "手表未连接"
        "DEGRADED" -> "兼容模式"
        else -> "待确认"
    }
    if (!health.watchConnected()) return base
    val parts = buildList {
        if (health.watchName().isNotEmpty()) add(health.watchName())
        add(base)
        if (health.watchBattery() >= 0) {
            add(if (health.watchCharging()) "充电中 ${health.watchBattery()}%" else "电量 ${health.watchBattery()}%")
        } else if (health.watchCharging()) {
            add("充电中")
        }
    }
    return parts.joinToString(" · ")
}

private fun frameworkSummaryText(scope: ModuleScope.View?): String {
    if (scope == null || !scope.frameworkConnected()) return "未连接到 LSPosed 服务"
    val name = scope.frameworkName().ifEmpty { "LSPosed" }
    val ver = if (scope.frameworkVersion().isEmpty()) "" else " ${scope.frameworkVersion()}"
    val apiLabel = if (scope.supportsApi102()) "API ${scope.frameworkApiVersion()}（支持热重载）"
    else "API ${scope.frameworkApiVersion()}（兼容模式）"
    return "$name$ver · $apiLabel"
}

private fun targetRuntimeText(scope: ModuleScope.View?, target: TargetProfile): String {
    if (scope == null) return "检查中"
    val active = when (target) {
        TargetProfile.CLOCK -> scope.clockActive()
        TargetProfile.CALENDAR -> scope.calendarActive()
        TargetProfile.HEALTH -> scope.healthActive()
        TargetProfile.WIRELESS_SETTINGS -> scope.wirelessActive()
        TargetProfile.UMS -> scope.umsActive()
        TargetProfile.MY_DEVICES -> scope.myDevicesActive()
    }
    val runtime = when (target) {
        TargetProfile.CLOCK -> scope.clockRuntime()
        TargetProfile.CALENDAR -> scope.calendarRuntime()
        TargetProfile.HEALTH -> scope.healthRuntime()
        TargetProfile.WIRELESS_SETTINGS -> scope.wirelessRuntime()
        TargetProfile.UMS -> scope.umsRuntime()
        TargetProfile.MY_DEVICES -> scope.myDevicesRuntime()
    }
    if ((target == TargetProfile.CALENDAR && !scope.calendarGranted())
        || (target == TargetProfile.WIRELESS_SETTINGS && !scope.wirelessGranted())
        || (target == TargetProfile.UMS && !scope.umsGranted())
        || (target == TargetProfile.MY_DEVICES && !scope.myDevicesGranted())
    ) {
        if (scope.frameworkConnected()) return "未勾选（可选）"
    }
    val witness = if (active) "已应答" else "未应答"
    if (!scope.supportsApi102() || runtime.state() == "UNSUPPORTED") {
        return witness
    }
    val codeNote = if (runtime.loadedVersionCode() > 0) " · code ${runtime.loadedVersionCode()}" else ""
    val stateLabel = when (runtime.state()) {
        "UP_TO_DATE" -> "最新"
        "STALE" -> "旧版本，建议热重载"
        "RELOADING" -> "热重载中"
        "FAILED" -> "加载失败，建议重载"
        "ABSENT" -> "未运行"
        else -> runtime.state()
    }
    return "$stateLabel$codeNote · $witness"
}

private fun daemonRuntimeText(scope: ModuleScope.View?): String {
    if (scope == null) return "检查中"
    if (!scope.supportsApi102() || scope.daemonRuntime().state() == "UNSUPPORTED") {
        return "按需随通知监听加载"
    }
    val runtime = scope.daemonRuntime()
    val codeNote = if (runtime.loadedVersionCode() > 0) " · code ${runtime.loadedVersionCode()}" else ""
    val stateLabel = when (runtime.state()) {
        "UP_TO_DATE" -> "最新"
        "STALE" -> "旧版本，建议热重载"
        "RELOADING" -> "热重载中"
        "FAILED" -> "加载失败，建议重载"
        "ABSENT" -> "未运行"
        else -> runtime.state()
    }
    return "$stateLabel$codeNote"
}

private fun clockResilienceText(clock: ClockHostControl.Reply?): String {
    if (clock == null || !clock.reachable()) return "待系统时钟上线"
    val source = when (clock.symbolSource()) {
        "CACHE" -> "缓存命中"
        "DEX_SCAN" -> "DEX 自动解析"
        else -> "未知"
    }
    val contract = if (clock.localClockControl()) "反控契约已校验" else "反控已保护禁用"
    return "$source · $contract"
}

private fun calendarResilienceText(calendar: CalendarHostControl.Reply?): String {
    if (calendar == null || !calendar.reachable()) return "未启用或待日历上线"
    val source = when (calendar.symbolSource()) {
        "CACHE" -> "缓存命中"
        "DEX_SCAN" -> "DEX 自动解析"
        else -> "未知"
    }
    val contract = if (calendar.localCalendarControl()) "反控契约已校验" else "仅单向提醒"
    return "$source · $contract"
}

private fun healthResilienceText(health: HealthHostControl.Reply?): String {
    if (health == null || !health.reachable()) return "待运动健康上线"
    val source = when (health.symbolSource()) {
        "CACHE" -> "缓存命中"
        "FAST_PATH" -> "基线匹配"
        "DEX_SCAN" -> "DEX 自动解析"
        "DEGRADED" -> "零混淆兼容模式"
        else -> "未知"
    }
    val mode = when {
        health.serviceWitness() && health.sdkObserver() -> "原生服务 + SDK 观测"
        health.serviceWitness() -> "原生服务（SDK 降级）"
        else -> "未就绪"
    }
    return "$source · $mode"
}

private fun remotePrefsDetail(state: WatchLinkUiState): String {
    val uiSupport = if (state.scope?.remotePrefsSupported() == true) "支持" else "未检测到"
    val clockSupport = when {
        state.clockHost?.reachable() != true -> "离线"
        state.clockHost.remotePrefs() -> "已读取"
        else -> "本地回退"
    }
    val healthSupport = when {
        state.healthHost?.reachable() != true -> "离线"
        state.healthHost.remotePrefs() -> "已读取"
        else -> "本地回退"
    }
    val calendarPart = if (state.calendarHost?.reachable() == true) {
        " · 日历：" + if (state.calendarHost.remotePrefs()) "已读取" else "本地回退"
    } else ""
    return "设置页：$uiSupport · 时钟：$clockSupport$calendarPart · 健康：$healthSupport"
}

private fun dispatchModeText(mode: String?): String = when (mode) {
    "START_SERVICE" -> "原生服务自启"
    "DIRECT_SDK" -> "SDK 直发回退"
    "BG_RESTRICTED" -> "后台受限且直发不可用"
    "DENIED" -> "系统拒绝"
    "FAILED" -> "发送失败"
    else -> "暂无记录"
}

private fun actionSourceText(source: String?): String = when (source) {
    "SDK_AND_INTENT" -> "SDK + 原生服务双通道一致"
    "INTENT_ONLY" -> "原生服务回程"
    "SDK_ONLY" -> "SDK 报文观测"
    else -> "暂无记录"
}

private fun testStateSummary(state: String, mode: String, actionSource: String): String = when (state) {
    "IDLE" -> "发送独立「WatchLink 测试」强提醒，验证弹窗与停止/延后按键回传。"
    "SENDING" -> "正在发送测试提醒…"
    "SENT" -> "已提交至运动健康原生服务，等待手表响铃…"
    "SENT_DIRECT" -> "已通过 SDK 直发提交，等待手表响铃…"
    "SERVICE_RECEIVED" -> "运动健康已受理，等待手表显示…"
    "OUTBOUND" -> "已下发到手表，请在表端点击「停止」或「延后」。"
    "SNOOZE" -> "测试成功：收到「延后」（${actionSourceText(actionSource)} · ${dispatchModeText(mode)}）。"
    "DISMISS" -> "测试成功：收到「停止」（${actionSourceText(actionSource)} · ${dispatchModeText(mode)}）。"
    "CLOSED" -> "本轮测试已结束（${dispatchModeText(mode)}）。"
    "WATCH_UNAVAILABLE" -> "未检测到支持协同的已连接手表。"
    "BUSY" -> "当前已有进行中的提醒或测试，请稍后再试。"
    "BACKGROUND_RESTRICTED" -> "后台服务受限且直发回退不可用。"
    "DENIED" -> "发送被系统拒绝，可导出日志排查。"
    else -> "测试未完成，请确认手表连接后重试。"
}

private fun deliverySummarySimple(status: String, subject: String): String = when (status) {
    "SENDING" -> "正在推送${subject}到手表…"
    "SENT", "SENT_DIRECT", "SERVICE_RECEIVED" -> "已发往运动健康推送至手表。"
    "OUTBOUND" -> "已推送原生提醒到手表。"
    "ENDED_BEFORE_OPEN" -> "送达手表前，${subject}已结束。"
    "DISABLED" -> "当时同步开关关闭，未推送。"
    "WATCH_UNAVAILABLE" -> "响铃时手表未连接或不支持协同。"
    "HEALTH_UNANSWERED" -> "运动健康未应答，请确保其后台运行。"
    "HEALTH_STOPPED" -> "响铃时运动健康已停止。"
    "BACKGROUND_RESTRICTED" -> "运动健康后台受限，推送失败。"
    "DENIED" -> "系统拒绝本次推送。"
    "BUSY" -> "已有另一轮提醒进行中，本次已跳过。"
    "FAILED", "UNAVAILABLE" -> "推送失败，可导出日志排查。"
    else -> "已检测到${subject}响铃。"
}

@Composable
private fun Menu(title: String, summary: String? = null, action: () -> Unit) {
    BasicComponent(
        title = title,
        summary = summary,
        onClick = action,
        endActions = {
            Icon(Next, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(18.dp))
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    SmallTitle(text, Modifier.semantics { heading() }, insideMargin = PaddingValues(horizontal = 12.dp))
}

private fun arrow(name: String, left: Boolean) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    path(
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
    ) {
        val edge = if (left) 15f else 9f
        moveTo(edge, 5f)
        lineTo(24f - edge, 12f)
        lineTo(edge, 19f)
    }
}.build()

private val Back = arrow("Back", true)
private val Next = arrow("Next", false)

private fun shield(ready: Boolean) = ImageVector.Builder(if (ready) "Ready" else "Status", 24.dp, 24.dp, 24f, 24f).apply {
    path(
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
    ) {
        moveTo(12f, 2.5f); lineTo(20f, 5.5f); verticalLineTo(11.8f)
        curveTo(20f, 16f, 17f, 19.4f, 12f, 21.5f)
        curveTo(7f, 19.4f, 4f, 16f, 4f, 11.8f)
        verticalLineTo(5.5f); close()
        if (ready) {
            moveTo(8.6f, 11.9f); lineTo(11f, 14.3f); lineTo(15.6f, 9.6f)
        } else {
            moveTo(12f, 8f); verticalLineTo(12.6f); moveTo(12f, 15.7f); verticalLineTo(15.8f)
        }
    }
}.build()

private val ShieldReady = shield(true)
private val ShieldStatus = shield(false)
