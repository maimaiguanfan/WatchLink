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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.watchlink.BuildConfig
import io.github.watchlink.alarm.probe.TargetProfile
import io.github.watchlink.setup.ModuleScope
import io.github.watchlink.validation.CalendarHostControl
import io.github.watchlink.validation.ClockHostControl
import io.github.watchlink.validation.HealthHostControl
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.text.DateFormat
import java.util.Date

internal data class WatchLinkUiState(
    val clockHost: ClockHostControl.Reply? = null,
    val calendarHost: CalendarHostControl.Reply? = null,
    val healthHost: HealthHostControl.Reply? = null,
    val checks: HomeChecks? = null,
    val scope: ModuleScope.View? = null,
    val saving: Boolean = false,
    val calendarSaving: Boolean = false,
    val reloading: Boolean = false,
    val testing: Boolean = false,
    val sharing: Boolean = false,
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

    val effectiveCalendarEnabled: Boolean
        get() = scope?.remoteCalendarEnabled()
            ?: calendarHost?.takeIf { it.reachable() }?.enabled()
            ?: healthHost?.takeIf { it.reachable() }?.calendarEnabled()
            ?: false

    val canToggleCalendar: Boolean
        get() = !calendarSaving && (scope?.remotePrefsSupported() == true
                || calendarHost?.reachable() == true
                || healthHost?.reachable() == true)
}

private enum class Page(val title: String) {
    HOME("WatchLink"),
    DIAGNOSTICS("测试与诊断"),
    GUIDE("使用说明与关于"),
}

@Composable
internal fun WatchLinkApp(
    state: WatchLinkUiState,
    onEnable: (Boolean) -> Unit,
    onCalendarEnable: (Boolean) -> Unit,
    onDismissMessage: () -> Unit,
    onClock: () -> Unit,
    onCalendar: () -> Unit,
    onHealth: () -> Unit,
    onRequestScope: () -> Unit,
    onRequestCalendarScope: () -> Unit,
    onHotReload: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onHealthDownload: () -> Unit,
    onCoolapk: () -> Unit,
    onStartTest: () -> Unit,
    onCancelTest: () -> Unit,
    onShare: () -> Unit,
) {
    MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        var page by rememberSaveable { mutableStateOf(Page.HOME) }
        val homeScroll = rememberLazyListState()
        val scroll = if (page == Page.HOME) homeScroll else key(page) { rememberLazyListState() }
        val behavior = MiuixScrollBehavior()
        val back = { page = Page.HOME }
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
                        Page.HOME -> homePage(
                            state = state,
                            onEnable = onEnable,
                            onCalendarEnable = onCalendarEnable,
                            onClock = onClock,
                            onCalendar = onCalendar,
                            onHealth = onHealth,
                            onRequestScope = onRequestScope,
                            onRequestCalendarScope = onRequestCalendarScope,
                            onHotReload = onHotReload,
                            onRefresh = onRefresh,
                            onHealthDownload = onHealthDownload,
                            onOpenDiagnostics = { page = Page.DIAGNOSTICS },
                            onOpenGuide = { page = Page.GUIDE },
                        )
                        Page.DIAGNOSTICS -> diagnosticsPage(
                            state = state,
                            onStartTest = onStartTest,
                            onCancelTest = onCancelTest,
                            onHotReload = onHotReload,
                            onRefresh = onRefresh,
                            onShare = onShare,
                        )
                        Page.GUIDE -> guidePage(onCoolapk)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.homePage(
    state: WatchLinkUiState,
    onEnable: (Boolean) -> Unit,
    onCalendarEnable: (Boolean) -> Unit,
    onClock: () -> Unit,
    onCalendar: () -> Unit,
    onHealth: () -> Unit,
    onRequestScope: () -> Unit,
    onRequestCalendarScope: () -> Unit,
    onHotReload: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onHealthDownload: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val issues = state.checks?.issues(state.scope).orEmpty()
    item("status") { StatusCard(state, issues) }

    if (state.checks == null && state.readError) {
        item("retry") {
            Card(Modifier.fillMaxWidth()) {
                Menu("重新检查", "暂时无法读取应用与模块状态，点击重试。", onRefresh)
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
                            summary = "更新模块后有宿主仍在运行旧版本，点击即可热重载生效，无需重启手机。",
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
                            title = "表端反控手机已自动暂停",
                            summary = "当前时钟版本仍可将响铃同步到手表，但手表停止/延后手机已保护性关闭，以避免误操作。"
                        )
                        SetupIssue.HEALTH_SERVICE -> Menu("检查华为运动健康", "请安装官方签名的华为运动健康应用。", onHealthDownload)
                        SetupIssue.HEALTH_STOPPED -> Menu("打开华为运动健康", "运动健康当前处于停止状态，打开后请确认手表已连接。", onHealth)
                        SetupIssue.WATCH_DISCONNECTED -> Menu("连接华为手表", "未检测到已连接的手表，请打开华为运动健康确认蓝牙连接。", onHealth)
                        SetupIssue.WATCH_SWITCH_OFF -> Menu("检查手表闹钟协同开关", "华为运动健康中该手表的闹钟协同记录当前为关闭状态。", onHealth)
                        SetupIssue.WATCH_UNSUPPORTED -> BasicComponent(
                            title = "当前手表不支持原生闹钟协同",
                            summary = "已连接设备未声明华为原生协同闹钟能力。"
                        )
                        SetupIssue.HEALTH_SDK_DEGRADED -> Menu(
                            "运动健康正以兼容模式运行",
                            "闹钟响铃推送与表端按键回传仍可正常工作；部分增强检测已降级，可在「测试与诊断」发送测试确认。",
                            onOpenDiagnostics
                        )
                    }
                }
            }
        }
    }

    item("auto") { AutoReminderCard(state, onEnable) }
    item("calendar") {
        CalendarReminderCard(
            state = state,
            onEnable = onCalendarEnable,
            onRequestCalendarScope = onRequestCalendarScope,
            onOpenCalendar = onCalendar,
            onRefresh = onRefresh,
        )
    }
    recentRing(state)
    recentCalendarRing(state)

    item("more-title") { SectionTitle("更多功能") }
    item("navigation") {
        Card(Modifier.fillMaxWidth()) {
            Menu("测试与诊断", "测试手表提醒、热重载宿主、查看适配状态与导出日志", onOpenDiagnostics)
            HorizontalDivider()
            Menu("使用说明与关于", "配置步骤、重启免启动原理、版本信息与作者主页", onOpenGuide)
        }
    }
}

@Composable
private fun StatusCard(state: WatchLinkUiState, issues: List<SetupIssue>) {
    val checks = state.checks
    val level = setupLevel(checks, issues, state.scope)
    val enabled = state.effectiveEnabled || state.effectiveCalendarEnabled
    var expanded by rememberSaveable { mutableStateOf(false) }

    val (background, ink) = when (level) {
        SetupLevel.READY -> (if (enabled) Color(0xFF15694E) else Color(0xFF2356B8)) to Color.White
        SetupLevel.ATTENTION -> Color(0xFFFFDF8E) to Color(0xFF473108)
        SetupLevel.MISSING_SCOPE -> Color(0xFFB0323F) to Color.White
        SetupLevel.CHECKING -> MiuixTheme.colorScheme.surface to MiuixTheme.colorScheme.onSurface
    }
    val title = when (level) {
        SetupLevel.READY -> if (enabled) "同步已开启" else "已准备就绪"
        SetupLevel.ATTENTION -> "有 ${issues.size} 项建议处理"
        SetupLevel.MISSING_SCOPE -> "需要启用模块"
        SetupLevel.CHECKING -> if (state.readError) "状态读取失败" else "正在检查状态"
    }
    val summary = when (level) {
        SetupLevel.READY -> if (enabled)
            "手机响铃时将自动同步到手表，重启后无需打开本应用"
        else
            "模块与手表连接正常，开启下方开关即可自动同步提醒"
        SetupLevel.ATTENTION -> "请按下方「待处理事项」完成调整，点此可展开状态概况"
        SetupLevel.MISSING_SCOPE -> "请在 LSPosed 中启用 WatchLink，并勾选系统时钟与华为运动健康"
        SetupLevel.CHECKING -> if (state.readError) "点击下方「重新检查」重试" else "正在核对模块作用域、宿主与手表状态"
    }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(background, ink)) {
        Column(
            Modifier.fillMaxWidth()
                .clickable(
                    enabled = checks != null,
                    role = Role.Button,
                    onClickLabel = if (expanded) "收起状态概况" else "展开状态概况"
                ) { expanded = !expanded }
                .padding(horizontal = 20.dp, vertical = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).background(ink.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (level == SetupLevel.READY) ShieldReady else ShieldStatus, null, tint = ink, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        title,
                        color = ink,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(summary, color = ink.copy(alpha = .92f), fontSize = 14.sp)
                }
                if (checks != null) {
                    Icon(
                        Next,
                        null,
                        tint = ink,
                        modifier = Modifier.size(18.dp).rotate(if (expanded) -90f else 90f)
                    )
                }
            }
            if (expanded && checks != null) {
                Spacer(Modifier.height(16.dp))
                CheckLine("系统时钟", hostStatusSimple(state.scope, TargetProfile.CLOCK), ink)
                CheckLine("系统日历（可选）", calendarStatusSimple(state.scope, checks), ink)
                CheckLine("华为运动健康", hostStatusSimple(state.scope, TargetProfile.HEALTH), ink)
                CheckLine("手表连接", watchStateSimple(state.healthHost), ink)
                CheckLine("重启免启动", remotePrefsSimple(state), ink)
                CheckLine("表端反控手机", when {
                    state.clockHost?.reachable() != true -> "待时钟上线"
                    state.clockHost.localClockControl() -> "已就绪"
                    else -> "暂不可用（仅单向提醒）"
                }, ink)
                val versions = buildString {
                    append("时钟 ").append(checks.clockVersion ?: "未知")
                    if (checks.calendarVersion != null) append(" · 日历 ").append(checks.calendarVersion)
                    append(" · 健康 ").append(checks.healthVersion ?: "未知")
                }
                CheckLine("应用版本", versions, ink)
            }
        }
    }
}

@Composable
private fun AutoReminderCard(state: WatchLinkUiState, onEnable: (Boolean) -> Unit) {
    val enabled = state.effectiveEnabled
    val summary = when {
        state.saving -> "正在保存开关状态…"
        !state.canToggle -> "请先完成作用域启用，或打开系统时钟 / 华为运动健康。"
        enabled -> if (state.scope?.remotePrefsSupported() == true)
            "已开启 · 重启手机后自动生效，无需后台常驻"
        else "已开启 · 已保存到当前在线宿主"
        else -> "开启后，手机闹钟响铃时自动在手表弹窗，并支持在手表上停止或延后"
    }
    Card(Modifier.fillMaxWidth()) {
        BasicComponent(
            title = "同步手机闹钟到手表",
            summary = summary,
            onClick = if (state.canToggle) { { onEnable(!enabled) } } else null,
            endActions = {
                Switch(checked = enabled, onCheckedChange = { onEnable(it) }, enabled = state.canToggle)
            }
        )
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
        !trusted -> "未检测到官方签名的 ColorOS 系统日历。"
        state.calendarSaving -> "正在保存开关状态…"
        !state.canToggleCalendar -> "请先启用模块框架，或打开系统日历 / 华为运动健康。"
        needsScope -> "开关已开启 · 请授权「系统日历」作用域后即可同步日历强提醒"
        needsLaunch -> "日历作用域已勾选 · 请打开或重启系统日历以加载模块"
        enabled && calendarHost?.reachable() == true && !calendarHost.localCalendarControl() ->
            "已开启 · 可推送强提醒到手表，但当前日历版本暂不支持表端反控"
        enabled -> "已开启 · 日历强提醒响铃时自动同步到手表，支持表端停止或延后 5 分钟"
        else -> "可选 · 同步 ColorOS 日历「强提醒」闹钟到手表，支持表端关闭与稍后提醒"
    }

    Card(Modifier.fillMaxWidth()) {
        BasicComponent(
            title = "同步日历强提醒到手表",
            summary = summary,
            onClick = if (trusted && state.canToggleCalendar) { { onEnable(!enabled) } } else null,
            endActions = {
                Switch(
                    checked = enabled,
                    onCheckedChange = { onEnable(it) },
                    enabled = trusted && state.canToggleCalendar
                )
            }
        )
        if (needsScope) {
            HorizontalDivider()
            if (scope.requestPending()) {
                BasicComponent(title = "等待 LSPosed 授权", summary = "请在 LSPosed 弹出的提示中同意「系统日历」。")
            } else {
                Menu(
                    "申请系统日历作用域",
                    "仅额外勾选「系统日历」，不影响现有闹钟同步配置。",
                    onRequestCalendarScope
                )
            }
        } else if (needsLaunch) {
            HorizontalDivider()
            Menu("打开系统日历", "启动或切回日历以加载模块，完成后可点此返回刷新。", onOpenCalendar)
            HorizontalDivider()
            Menu("刷新日历加载状态", "若已重启系统日历或完成热重载，点击确认生效。", onRefresh)
        }
    }
}

private fun LazyListScope.recentRing(state: WatchLinkUiState) {
    val host = state.clockHost
    item("last-title") { SectionTitle("最近一次闹钟同步") }
    item("last") {
        Card(Modifier.fillMaxWidth()) {
            if (host == null || !host.reachable()) {
                BasicComponent(
                    title = "暂无同步记录",
                    summary = "系统时钟尚未上线；响铃一次或打开时钟后即可查看。"
                )
            } else if (host.lastRingAt() <= 0) {
                BasicComponent(
                    title = "尚未记录手机闹钟响铃",
                    summary = "下一次手机真实响铃后，这里会显示同步与表端操作结果。"
                )
            } else {
                RingHistoryItem(
                    at = host.lastRingAt(),
                    delivery = host.delivery(),
                    action = host.action(),
                    actionResult = host.actionResult(),
                    close = host.close(),
                    subject = "手机闹钟",
                )
            }
        }
    }
}

private fun LazyListScope.recentCalendarRing(state: WatchLinkUiState) {
    val host = state.calendarHost ?: return
    if (!host.reachable() || host.lastRingAt() <= 0) return
    item("last-cal-title") { SectionTitle("最近一次日历强提醒同步") }
    item("last-cal") {
        Card(Modifier.fillMaxWidth()) {
            RingHistoryItem(
                at = host.lastRingAt(),
                delivery = host.delivery(),
                action = host.action(),
                actionResult = host.actionResult(),
                close = host.close(),
                subject = "日历强提醒",
            )
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
        title = stamp.format(Date(at)),
        summary = buildList {
            add(deliverySummarySimple(delivery, subject))
            if (actionLabel.isNotEmpty()) {
                add(when (actionResult) {
                    "VERIFIED" -> "手表点击「$actionLabel」，${subject}已确认同步$actionLabel。"
                    "REJECTED" -> "收到手表「$actionLabel」，但手机当前未执行该操作。"
                    else -> "收到手表「$actionLabel」，手机执行状态待核对。"
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

private fun LazyListScope.diagnosticsPage(
    state: WatchLinkUiState,
    onStartTest: () -> Unit,
    onCancelTest: () -> Unit,
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

    item("test-title") { SectionTitle("手表提醒测试（不影响真实闹钟）") }
    item("test-card") {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "立即发送测试提醒",
                summary = if (health?.reachable() != true) {
                    "华为运动健康尚未应答，请先打开运动健康并确认手表已连接。"
                } else {
                    testStateSummary(testState, health.testDispatchMode(), health.testActionSource())
                },
                endActions = {
                    if (testRunning) {
                        TextButton("结束测试", onCancelTest, enabled = !state.testing)
                    } else {
                        TextButton("发送测试", onStartTest, enabled = !state.testing && health?.reachable() == true)
                    }
                }
            )
        }
    }

    item("api102-title") { SectionTitle("LSPosed API 102 与宿主状态") }
    item("api102-card") {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(title = "框架环境", summary = frameworkSummaryText(scope))
            HorizontalDivider()
            BasicComponent(title = "系统时钟进程", summary = targetRuntimeText(scope, TargetProfile.CLOCK))
            HorizontalDivider()
            BasicComponent(title = "系统日历进程（可选）", summary = targetRuntimeText(scope, TargetProfile.CALENDAR))
            HorizontalDivider()
            BasicComponent(title = "运动健康进程（:PhoneService）", summary = targetRuntimeText(scope, TargetProfile.HEALTH))
            HorizontalDivider()
            BasicComponent(
                title = "热重载运行中的宿主",
                summary = when {
                    scope?.supportsApi102() != true -> "需要 LSPosed API 102 框架支持；当前请手动重启目标宿主。"
                    !scope.canHotReloadAny() -> "当前没有可热重载的运行中宿主进程。"
                    scope.hasStaleTarget() -> "检测到旧版本宿主，点击即可热重载生效（响铃中会自动保护延迟）。"
                    else -> "当前宿主均为最新版本，也可手动触发重新加载（响铃中会自动保护延迟）。"
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
            Menu("立即刷新全部状态", "重新查询作用域、API 102 宿主状态与手表连接", onRefresh)
        }
    }

    item("resilience-title") { SectionTitle("防升级适配与通道遥测") }
    item("resilience-card") {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "时钟动态符号解析",
                summary = clockResilienceText(clock)
            )
            HorizontalDivider()
            BasicComponent(
                title = "日历动态符号解析（可选）",
                summary = calendarResilienceText(calendar)
            )
            HorizontalDivider()
            BasicComponent(
                title = "运动健康动态符号解析",
                summary = healthResilienceText(health)
            )
            HorizontalDivider()
            BasicComponent(
                title = "跨重启配置通道（RemotePreferences）",
                summary = remotePrefsDetail(state)
            )
            HorizontalDivider()
            BasicComponent(
                title = "最近提醒下发方式",
                summary = dispatchModeText(health?.lastDispatchMode() ?: clock?.dispatchMode())
            )
            HorizontalDivider()
            BasicComponent(
                title = "最近表端按键回程",
                summary = actionSourceText(health?.lastActionSource() ?: clock?.actionSource())
            )
        }
    }

    item("share-title") { SectionTitle("诊断日志") }
    item("share-card") {
        Card(Modifier.fillMaxWidth()) {
            if (state.sharing) {
                BasicComponent(title = "正在打包脱敏日志…", summary = "正在收集 WatchLink、系统时钟、系统日历和运动健康的允许列表事件。")
            } else {
                Menu("导出并分享诊断日志", "生成脱敏运行记录（ZIP），不含个人日程/闹钟内容与设备号。", onShare)
            }
        }
    }
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
        scope?.requestPending() == true -> "请在 LSPosed 弹出的授权提示中同意系统时钟和华为运动健康。"
        scope?.requestResult() == "DENIED" -> "上次申请未通过，可点此重试，或直接在 LSPosed 中勾选这两个应用。"
        scope?.frameworkConnected() != true -> "请打开 LSPosed 管理器，启用 WatchLink 并勾选「时钟」和「华为运动健康」（如需日历强提醒可额外勾选「日历」）。"
        scope.configured() -> "作用域已勾选。请打开或重启下方尚未生效的应用，再返回刷新。"
        else -> "点击通过 LSPosed 自动申请系统时钟和华为运动健康的作用域。"
    }
    if (scope?.requestPending() == true) {
        BasicComponent(title = "等待 LSPosed 授权", summary = guidance)
    } else if (scope?.configured() == true) {
        Menu("刷新生效状态", guidance, onRefresh)
    } else {
        Menu("一键申请作用域", guidance, onRequest)
    }
    if (scope?.configured() == true && !scope.clockActive()) {
        HorizontalDivider()
        Menu("打开系统时钟", "启动系统时钟以加载模块，完成后返回刷新。", onClock)
    }
    if (scope?.configured() == true && !scope.healthActive()) {
        HorizontalDivider()
        Menu("打开华为运动健康", "启动运动健康并保持手表连接，完成后返回刷新。", onHealth)
    }
}

private fun LazyListScope.guidePage(onCoolapk: () -> Unit) {
    item("steps-title") { SectionTitle("三步完成配置") }
    item("steps-card") {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "1. 在 LSPosed 勾选目标宿主",
                summary = "启用 WatchLink 模块，核心勾选「系统时钟」和「华为运动健康」；如需同步日历强提醒闹钟，再勾选「系统日历」。无需 Magisk 模块，也无需勾选 SystemUI。"
            )
            HorizontalDivider()
            BasicComponent(
                title = "2. 开启同步开关即可退出",
                summary = "重启一次已勾选宿主（或使用 API 102 热重载），打开首页对应的同步开关后，即可关闭 WatchLink，日常无需后台保活。"
            )
            HorizontalDivider()
            BasicComponent(
                title = "3. 零进程与防升级失效原理",
                summary = "开关通过 LSPosed RemotePreferences 持久化，手机重启后时钟、日历与运动健康直接协同推送原生手表提醒，全程不唤醒 WatchLink。模块内置 DEX 结构解析与按版本缓存机制，即使宿主更新变更混淆类名，也能自动重新定位适配。"
            )
        }
    }
    item("about-title") { SectionTitle("关于 WatchLink") }
    item("about-card") {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "版本信息",
                summary = "WatchLink ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\n" +
                    "包名：${BuildConfig.APPLICATION_ID}\n" +
                    "框架标准：LSPosed API 102（兼容 API 101）\n" +
                    "已验证基线：一加时钟 $VERIFIED_CLOCK · 一加日历 $VERIFIED_CALENDAR · 华为运动健康 $VERIFIED_HEALTH"
            )
            HorizontalDivider()
            Menu("作者的酷安主页", "查看更新日志、适配说明与问题反馈", onCoolapk)
        }
    }
}

@Composable
private fun CheckLine(label: String, value: String, ink: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = ink.copy(alpha = .85f), fontSize = 13.5.sp)
        Text(value, color = ink, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}

private fun hostStatusSimple(scope: ModuleScope.View?, target: TargetProfile): String {
    if (scope == null) return "检查中"
    val active = when (target) {
        TargetProfile.CLOCK -> scope.clockActive()
        TargetProfile.CALENDAR -> scope.calendarActive()
        TargetProfile.HEALTH -> scope.healthActive()
    }
    val granted = when (target) {
        TargetProfile.CLOCK -> scope.clockGranted()
        TargetProfile.CALENDAR -> scope.calendarGranted()
        TargetProfile.HEALTH -> scope.healthGranted()
    }
    val runtime = when (target) {
        TargetProfile.CLOCK -> scope.clockRuntime()
        TargetProfile.CALENDAR -> scope.calendarRuntime()
        TargetProfile.HEALTH -> scope.healthRuntime()
    }
    return when {
        scope.frameworkConnected() && !granted -> "未勾选"
        scope.supportsApi102() && runtime.isStale() -> "待热重载（旧版本）"
        scope.supportsApi102() && runtime.isReloading() -> "热重载中…"
        active -> "已连接"
        scope.checking() -> "检查中…"
        granted -> "已勾选，待重启加载"
        else -> "未确认"
    }
}

private fun calendarStatusSimple(scope: ModuleScope.View?, checks: HomeChecks): String {
    if (!checks.calendarTrusted) return "未安装或签名不匹配"
    if (scope == null) return "检查中"
    if (scope.frameworkConnected() && !scope.calendarGranted()) return "未启用（可选）"
    return hostStatusSimple(scope, TargetProfile.CALENDAR)
}

private fun watchStateSimple(health: HealthHostControl.Reply?): String = when {
    health == null || !health.reachable() -> "等待运动健康上线"
    else -> when (health.watchState()) {
        "READY_SUPPORTED_ON" -> "已连接，支持协同"
        "READY_SUPPORTED_UNSET" -> "已连接，首次响铃自动启用"
        "READY_SUPPORTED_OFF" -> "已连接，但协同开关关闭"
        "READY_UNSUPPORTED" -> "设备不支持原生协同"
        "READY_DEVICE_MISMATCH" -> "多设备连接或主设备不一致"
        "WATCH_DISCONNECTED" -> "未连接手表"
        "DEGRADED" -> "兼容模式（交由原生服务处理）"
        else -> "状态待确认"
    }
}

private fun remotePrefsSimple(state: WatchLinkUiState): String = when {
    state.scope?.remotePrefsSupported() == true -> "已就绪（框架持久化）"
    state.clockHost?.remotePrefs() == true || state.healthHost?.remotePrefs() == true -> "宿主已就绪"
    state.scope?.frameworkConnected() == true -> "仅本地保存"
    else -> "检查中"
}

private fun frameworkSummaryText(scope: ModuleScope.View?): String {
    if (scope == null || !scope.frameworkConnected()) return "未连接到 LSPosed 服务"
    val name = scope.frameworkName().ifEmpty { "LSPosed" }
    val ver = if (scope.frameworkVersion().isEmpty()) "" else " ${scope.frameworkVersion()}"
    val apiLabel = if (scope.supportsApi102()) "API ${scope.frameworkApiVersion()}（支持热重载与进程检测）"
    else "API ${scope.frameworkApiVersion()}（兼容模式）"
    return "$name$ver · $apiLabel"
}

private fun targetRuntimeText(scope: ModuleScope.View?, target: TargetProfile): String {
    if (scope == null) return "等待检查"
    val active = when (target) {
        TargetProfile.CLOCK -> scope.clockActive()
        TargetProfile.CALENDAR -> scope.calendarActive()
        TargetProfile.HEALTH -> scope.healthActive()
    }
    val runtime = when (target) {
        TargetProfile.CLOCK -> scope.clockRuntime()
        TargetProfile.CALENDAR -> scope.calendarRuntime()
        TargetProfile.HEALTH -> scope.healthRuntime()
    }
    if (target == TargetProfile.CALENDAR && scope.frameworkConnected() && !scope.calendarGranted()) {
        return "未勾选作用域（可选）"
    }
    val witness = if (active) "宿主已应答" else "宿主未应答"
    if (!scope.supportsApi102() || runtime.state() == "UNSUPPORTED") {
        return "$witness（当前框架未提供 API 102 进程状态）"
    }
    val codeNote = if (runtime.loadedVersionCode() > 0) " · code ${runtime.loadedVersionCode()}" else ""
    val stateLabel = when (runtime.state()) {
        "UP_TO_DATE" -> "运行最新版本"
        "STALE" -> "运行旧版本，建议热重载"
        "RELOADING" -> "正在热重载"
        "FAILED" -> "上次加载失败，建议热重载或重启"
        "ABSENT" -> "进程未运行"
        else -> runtime.state()
    }
    return "$stateLabel$codeNote · $witness"
}

private fun clockResilienceText(clock: ClockHostControl.Reply?): String {
    if (clock == null || !clock.reachable()) return "待系统时钟上线后读取"
    val source = when (clock.symbolSource()) {
        "CACHE" -> "本地缓存命中（零扫描启动）"
        "DEX_SCAN" -> "DEX 结构自动解析（已写入缓存）"
        else -> "状态未知"
    }
    val contract = if (clock.localClockControl()) "反控合约已核验" else "反控合约未匹配（已保护禁用）"
    return "$source · $contract"
}

private fun calendarResilienceText(calendar: CalendarHostControl.Reply?): String {
    if (calendar == null || !calendar.reachable()) return "未启用或待系统日历上线后读取"
    val source = when (calendar.symbolSource()) {
        "CACHE" -> "本地缓存命中（零扫描启动）"
        "DEX_SCAN" -> "DEX 结构自动解析（已写入缓存）"
        else -> "状态未知"
    }
    val contract = if (calendar.localCalendarControl()) "反控合约已核验" else "反控合约未匹配（仅单向提醒）"
    return "$source · $contract"
}

private fun healthResilienceText(health: HealthHostControl.Reply?): String {
    if (health == null || !health.reachable()) return "待华为运动健康上线后读取"
    val source = when (health.symbolSource()) {
        "CACHE" -> "本地缓存命中（零扫描启动）"
        "FAST_PATH" -> "已知基线快速匹配"
        "DEX_SCAN" -> "DEX 结构自动解析（已适配新版本）"
        "DEGRADED" -> "零混淆兼容模式（核心原生服务可用）"
        else -> "状态未知"
    }
    val mode = if (health.serviceWitness() && health.sdkObserver()) "原生服务 + SDK 完整观测"
    else if (health.serviceWitness()) "原生服务在线（SDK 降级）"
    else "未就绪"
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
    return "设置页：$uiSupport · 时钟：$clockSupport$calendarPart · 运动健康：$healthSupport"
}

private fun dispatchModeText(mode: String?): String = when (mode) {
    "START_SERVICE" -> "运动健康原生服务自启（零混淆）"
    "DIRECT_SDK" -> "SDK 直发回退通道"
    "BG_RESTRICTED" -> "后台启动受限且直发回退不可用"
    "DENIED" -> "系统拒绝调用"
    "FAILED" -> "发送失败"
    else -> "暂无发送记录"
}

private fun actionSourceText(source: String?): String = when (source) {
    "SDK_AND_INTENT" -> "SDK + 原生服务双通道核对一致"
    "INTENT_ONLY" -> "原生服务回程（零混淆通道）"
    "SDK_ONLY" -> "SDK 协议报文观测"
    else -> "暂无按键回报"
}

private fun testStateSummary(state: String, mode: String, actionSource: String): String = when (state) {
    "IDLE" -> "发送一条独立的「WatchLink 测试」提醒到手表，验证弹窗显示与停止/延后按键回传。"
    "SENDING" -> "正在发送测试提醒到手表…"
    "SENT" -> "已交由运动健康原生服务处理，等待手表响铃…"
    "SENT_DIRECT" -> "已通过 SDK 直发回退提交，等待手表响铃…"
    "SERVICE_RECEIVED" -> "运动健康已受理提醒，等待手表显示与点击…"
    "OUTBOUND" -> "已下发到手表，请在手表上点击「停止」或「延后」。"
    "SNOOZE" -> "测试成功！已收到手表「延后」（${actionSourceText(actionSource)} · ${dispatchModeText(mode)}）。"
    "DISMISS" -> "测试成功！已收到手表「停止」（${actionSourceText(actionSource)} · ${dispatchModeText(mode)}）。"
    "CLOSED" -> "本轮测试已结束（${dispatchModeText(mode)}）。"
    "WATCH_UNAVAILABLE" -> "未检测到支持协同的已连接手表，请检查蓝牙连接。"
    "BUSY" -> "当前已有进行中的闹钟或测试，请稍后再试。"
    "BACKGROUND_RESTRICTED" -> "后台服务受限且直发回退不可用。"
    "DENIED" -> "发送被系统拒绝，可导出诊断日志查看原因。"
    else -> "测试未完成，请确认手表连接后重试。"
}

private fun deliverySummarySimple(status: String, subject: String): String = when (status) {
    "SENDING" -> "正在推送${subject}响铃到手表…"
    "SENT", "SENT_DIRECT", "SERVICE_RECEIVED" -> "已发往华为运动健康推送至手表。"
    "OUTBOUND" -> "已成功推送原生提醒到手表。"
    "ENDED_BEFORE_OPEN" -> "提醒送达手表前，${subject}已结束。"
    "DISABLED" -> "当时同步开关处于关闭状态，未推送。"
    "WATCH_UNAVAILABLE" -> "响铃时手表未连接或不支持协同提醒。"
    "HEALTH_UNANSWERED" -> "华为运动健康未应答，请确保其后台正常运行。"
    "HEALTH_STOPPED" -> "响铃时华为运动健康处于停止状态。"
    "BACKGROUND_RESTRICTED" -> "运动健康后台受限，未能推送到手表。"
    "DENIED" -> "系统拒绝了本次推送请求。"
    "BUSY" -> "已有另一轮提醒正在进行，本次已跳过。"
    "FAILED", "UNAVAILABLE" -> "本次未能推送到手表，可在「测试与诊断」导出日志排查。"
    else -> "已检测到${subject}响铃。"
}

@Composable
private fun Menu(title: String, summary: String, action: () -> Unit) {
    BasicComponent(title = title, summary = summary, onClick = action, endActions = {
        Icon(Next, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(18.dp))
    })
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
