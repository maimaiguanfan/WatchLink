# WatchLink

一个面向 **一加 / OPPO（ColorOS）系统时钟 & 日历强提醒 ↔ 华为手表** 的纯 LSPosed 实时闹钟协同模块。

当手机系统闹钟或 ColorOS 日历「强提醒」闹钟真实响铃时，WatchLink 会通过华为运动健康向已连接的华为手表发送原生闹钟协同提醒，支持**手表端停止 / 延后反控手机**、**手机端停止 / 延后自动关闭手表提醒**，以及按手机时钟/日历日程复响。

## 特性亮点

- **纯 LSPosed 模块，免 Magisk / 免特权应用**：无需转换系统应用、无需写入权限 XML、无需勾选 `com.android.systemui`，核心仅需勾选**系统时钟**与**华为运动健康**（如需同步日历强提醒，可选额外勾选**系统日历**）。
- **跨重启零后台进程**：开关配置通过 LSPosed `RemotePreferences` 持久化在框架侧（不支持时自动回退至宿主本地存储）。首次开启后即可退出 WatchLink，整机重启后全程无需再启动模块自身进程。
- **ColorOS 日历「强提醒」闹钟同步（可选）**：自动同步 ColorOS 日历强提醒至华为手表原生闹钟界面，完整支持表端一键关闭与延后 5 分钟稍后提醒，按锁屏页 / 顶栏浮窗 / 流体云 / 监听回退链路安全反控。
- **运动健康进程内原生下发**：手机起铃时，一加时钟与日历通过 OS UID 与签名校验后的点对点广播直连华为运动健康 `:PhoneService`，由健康进程以自身身份调用 `ExternalPhoneService` 发起原生协同提醒（并在后台受限时自动回退至进程内 `HwDeviceMgrInterface.sendDeviceData`）。
- **LSPosed API 102 支持（兼容 API 101）**：
  - 全部 Hook 使用 API 102 命名 Hook ID（`HookBuilder.setId`）。
  - 实现受控热重载（`onHotReloading` / `onHotReloaded`）：响铃或测试进行中自动延后保护，空闲时完整卸载广播接收器、工作线程与 Hook 句柄后再无缝重载。
  - 集成 `XposedService.getRunningTargets()` 与 `hotReloadModule()`，模块更新后可在应用内一键热重载旧版本宿主，无需重启手机。
- **防升级失效动态适配**：
  - 内置基于 `dexlib2` 的结构化符号解析器（`ClockSymbolResolver` / `CalendarSymbolResolver` / `HealthSymbolResolver`）与按 APK 路径/大小/修改时间建立的指纹缓存（`ApkSymbolCache`）。
  - 同一版本启动零 DEX 扫描；时钟、日历或运动健康更新导致混淆类名变化时自动重扫适配。若反控字节码发生变化，仅保护性关闭表端反控，手机→手表响铃同步仍保持可用；运动健康主下发与表端按键回程通道本身不依赖混淆类。
- **隐私友好**：无网络权限、不持久化或记录个人闹钟/日程标签与设备 MAC，诊断日志仅导出严格白名单内的结构化状态事件。

## 运行要求

- Android 14（API 34）及以上
- 一加 / OPPO 官方签名 ColorOS 系统时钟（`com.coloros.alarmclock`），可选 ColorOS 系统日历（`com.coloros.calendar`）
- 官方签名华为运动健康（`com.huawei.health`）及支持原生协同闹钟的华为手表
- 支持 Modern Xposed API 101 / 102 的 LSPosed 框架

> 已验证基线：ColorOS 时钟 `17.6.100`、ColorOS 日历 `17.6.30`、华为运动健康 `17.0.10.330`、HUAWEI WATCH 6 Pro。

## 快速开始

1. 安装 WatchLink（包名 `io.github.maimaiguanfan.watchlink`）。
2. 在 LSPosed 管理器中启用 WatchLink，勾选：
   - **时钟**（`com.coloros.alarmclock`）
   - **华为运动健康**（`com.huawei.health`）
   - **日历**（`com.coloros.calendar`，可选，用于同步日历强提醒闹钟）
   - **智慧服务**（`com.oplus.pantanal.ums`，可选，用于同步流体云订单与行程）
   - **无线设置**（`com.oplus.wirelesssettings`，可选，用于自动同步 Wi-Fi）
3. 重启一次已勾选宿主。更新模块后，支持 API 102 的框架也可从「测试与诊断」热重载宿主。
4. 打开 WatchLink：
   - 确认顶部状态卡显示就绪，可先在「测试与诊断 → 立即发送测试提醒」验证手表弹窗与停止/延后按键回传；
   - 首页显示灰色「待刷新」时，可点击状态卡重新查询。
   - 在「闹钟与日历」中开启手机闹钟或日历强提醒，之后即可关闭 WatchLink。
   - 在「闹钟与日历 → 倒计时同步」开启后，手表实况窗会展示预计结束时间、进度和暂停状态。手机时钟到期响铃时，手表收到强提醒，可在任一端停止本轮响铃。可单独关闭运行中卡片，或开启「手表延后键映射为再次计时」。倒计时仅使用时钟和运动健康两个作用域。
   - 「实况通知」可将流体云和 Android 实况通知中的取餐码、打车行程、配送与排号进度同步到手表。手机模式切换和助手收藏会被过滤。

## 工作原理简述

```text
一加时钟 (com.coloros.alarmclock) / 一加日历 (com.coloros.calendar，可选)
  │ 1. Hook 时钟 AlarmKlaxon/AlarmService 或日历强提醒弹窗生命周期捕获真实起铃与结束
  │ 2. 通过 OS UID + 证书校验的 DailyAlarmWire 发送 OPEN / END
  ▼
华为运动健康 (com.huawei.health:PhoneService)
  │ 3. 进程内读取 RemotePreferences 开关，以自身 UID 调用 ExternalPhoneService
  │    （受限时自动回退 HwDeviceMgrInterface.sendDeviceData）
  │ 4. 监听 ExternalPhoneService (alarm_clock_msg_from_device) + 可选 SDK 双通道回程
  ▼
华为手表 ◄── 原生闹钟协同提醒 / 停止 / 延后 ──► 回传对应宿主执行经字节码核验的原生动作
```

## 本地构建与测试

环境要求：JDK 17、Android SDK（`compileSdk = 37`、`minSdk = 26`、`targetSdk = 37`）。

```sh
# 运行全部 JVM 单元测试
./gradlew test

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK 与 Lint 检查
./gradlew assembleRelease :app:lintRelease
```

## 开源组件致谢

- [libxposed/api](https://github.com/libxposed/api) & [libxposed/service](https://github.com/libxposed/service)（Modern Xposed API 102）
- [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)（Apache-2.0）
- [smali-dexlib2](https://github.com/google/smali)
