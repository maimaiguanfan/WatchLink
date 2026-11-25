package io.github.watchlink.diagnostics;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.setup.ModuleScope;
import io.github.watchlink.validation.ClockActionWire;
import io.github.watchlink.validation.DailyAlarmWire;
import io.github.watchlink.validation.HuaweiReminderService;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** User-initiated, read-only collection from WatchLink, Clock, and Health (:PhoneService) journals. */
public final class DiagnosticShare {
    private static final String OWNER = BuildConfig.APPLICATION_ID;
    private static final String QQ = "com.tencent.mobileqq";
    private static final String REQUEST = OWNER + ".diagnostics.READ_V1";
    private static final String REPLY = OWNER + ".diagnostics.REPORT_V1";
    private static Runnable hostCleanup;

    public static synchronized AutoCloseable installHost(Context context, int ownerUid) {
        if (Build.VERSION.SDK_INT < 34) return () -> {};
        return installHostApi34(context, ownerUid);
    }

    @RequiresApi(34)
    private static AutoCloseable installHostApi34(Context context, int ownerUid) {
        if (hostCleanup != null) {
            hostCleanup.run();
            hostCleanup = null;
        }
        try {
            Context app = context.getApplicationContext();
            DiagnosticJournal.install(app);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (!REQUEST.equals(intent.getAction()) || !app.getPackageName().equals(intent.getPackage())
                            || !OWNER.equals(getSentFromPackage()) || getSentFromUid() != ownerUid) return;
                    String request = intent.getStringExtra("request");
                    if (request == null || request.length() != 36) return;
                    PendingResult pending = goAsync();
                    DiagnosticJournal.onWorker(() -> {
                        try {
                            Intent reply = DailyAlarmWire.route(REPLY, OWNER).putExtra("request", request);
                            try { reply.putExtra("events", DiagnosticJournal.snapshot()); }
                            catch (IOException | RuntimeException unavailable) { /* Report read failure. */ }
                            DailyAlarmWire.send(app, reply);
                        } catch (RuntimeException unavailable) {
                            Log.w("WatchLink", "DIAGNOSTIC_REPLY_UNAVAILABLE");
                        } finally { pending.finish(); }
                    });
                }
            };
            app.registerReceiver(receiver, new IntentFilter(REQUEST), ClockActionWire.PERMISSION, null, Context.RECEIVER_EXPORTED);
            Runnable cleanup = () -> {
                try { app.unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
            };
            hostCleanup = cleanup;
            return () -> unregisterHost(cleanup);
        } catch (RuntimeException unavailable) {
            Log.w("WatchLink", "DIAGNOSTIC_HOST_UNAVAILABLE");
            return () -> {};
        }
    }

    private static synchronized void unregisterHost(Runnable cleanup) {
        if (hostCleanup == cleanup) hostCleanup = null;
        cleanup.run();
    }

    public static Intent prepare(Context context) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Requires background IO");
        Context app = context.getApplicationContext();
        Map<TargetProfile, String> results = Build.VERSION.SDK_INT >= 34 ? collect(app) : Collections.emptyMap();
        ModuleScope.View scope = ModuleScope.inspect();
        HuaweiReminderService.HealthEndpoint health = HuaweiReminderService.inspectHealthEndpoint(app);
        StringBuilder report = new StringBuilder("WatchLink 闹钟联动诊断\n导出时间（UTC） ")
                .append(Instant.now()).append("\nAndroid API ").append(Build.VERSION.SDK_INT)
                .append("\n模块包名 ").append(OWNER)
                .append("\n模块版本 ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(')')
                .append("\n框架信息 ").append(frameworkSummary(scope))
                .append("\nRemotePreferences ").append(scope.remotePrefsSupported() ? "框架支持" : "不可用")
                .append("\n运动健康协同服务 ").append(health.ready() ? "可用" : health.stopped() ? "已停止" : "不可用")
                .append("\n系统时钟作用域 ").append(scopeSummary(scope, TargetProfile.CLOCK))
                .append("\n系统日历作用域（可选） ").append(scopeSummary(scope, TargetProfile.CALENDAR))
                .append("\n无线设置作用域（可选） ").append(scopeSummary(scope, TargetProfile.WIRELESS_SETTINGS))
                .append("\n运动健康作用域 ").append(scopeSummary(scope, TargetProfile.HEALTH))
                .append("\n每个来源最多包含最近 ").append(DiagnosticEvents.LIMIT)
                .append(" 条闹钟联动事件，时间统一为 UTC。\n");
        String local;
        try { local = DiagnosticJournal.snapshot(); }
        catch (IOException | RuntimeException unavailable) { local = null; }
        section(report, "WatchLink", version(app, OWNER), local, "本地记录读取失败");
        for (TargetProfile target : TargetProfile.values()) {
            String unavailable = Build.VERSION.SDK_INT < 34 ? "此 Android 版本不支持验证宿主来源"
                    : results.containsKey(target) ? "宿主已回复，记录读取失败"
                    : "未收到宿主回复，可在对应应用已运行且作用域生效后重新导出";
            String label = switch (target) {
                case CLOCK -> "系统时钟";
                case CALENDAR -> "系统日历";
                case HEALTH -> "华为运动健康（PhoneService）";
                case WIRELESS_SETTINGS -> "无线设置（Wi-Fi 自动同步）";
            };
            section(report, label, version(app, target.packageName), results.get(target), unavailable);
        }
        return chooser(app, write(app.getCacheDir(), report.toString()));
    }

    private static String frameworkSummary(ModuleScope.View scope) {
        if (!scope.frameworkConnected()) return "未连接";
        String name = scope.frameworkName().isEmpty() ? "LSPosed" : scope.frameworkName();
        String ver = scope.frameworkVersion().isEmpty() ? "" : " " + scope.frameworkVersion();
        return name + ver + " (API " + scope.frameworkApiVersion() + ")";
    }

    private static String scopeSummary(ModuleScope.View scope, TargetProfile target) {
        boolean granted = switch (target) {
            case CLOCK -> scope.clockGranted();
            case CALENDAR -> scope.calendarGranted();
            case HEALTH -> scope.healthGranted();
            case WIRELESS_SETTINGS -> scope.wirelessGranted();
        };
        boolean active = switch (target) {
            case CLOCK -> scope.clockActive();
            case CALENDAR -> scope.calendarActive();
            case HEALTH -> scope.healthActive();
            case WIRELESS_SETTINGS -> scope.wirelessActive();
        };
        ModuleScope.TargetRuntime runtime = switch (target) {
            case CLOCK -> scope.clockRuntime();
            case CALENDAR -> scope.calendarRuntime();
            case HEALTH -> scope.healthRuntime();
            case WIRELESS_SETTINGS -> scope.wirelessRuntime();
        };
        String grant = scope.frameworkConnected() ? (granted ? "已勾选" : "未勾选") : "未读到框架设置";
        String witness = active ? "已收到当前版本宿主应答" : "尚未收到当前版本宿主应答";
        if (scope.supportsApi102() && runtime != null && !"UNSUPPORTED".equals(runtime.state())) {
            return grant + "，" + witness + "（API 102: " + runtime.state()
                    + (runtime.loadedVersionCode() > 0 ? " code=" + runtime.loadedVersionCode() : "") + "）";
        }
        return grant + "，" + witness;
    }

    @RequiresApi(34)
    private static Map<TargetProfile, String> collect(Context app) {
        String request = UUID.randomUUID().toString();
        Map<TargetProfile, String> replies = new EnumMap<>(TargetProfile.class);
        CountDownLatch latch = new CountDownLatch(TargetProfile.values().length);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                String pkg = getSentFromPackage();
                TargetProfile target = TargetProfile.fromPackage(pkg);
                if (!REPLY.equals(intent.getAction()) || !OWNER.equals(intent.getPackage()) || target == null
                        || !SenderVerifier.verifyReminderSender(app, target, getSentFromUid(), pkg)) return;
                try {
                    if (!request.equals(intent.getStringExtra("request"))) return;
                    String events = intent.getStringExtra("events");
                    if (events != null && events.length() > DiagnosticEvents.MAX_TEXT) events = null;
                    synchronized (replies) {
                        if (!replies.containsKey(target)) {
                            replies.put(target, events);
                            latch.countDown();
                        }
                    }
                } catch (RuntimeException malformed) { /* Ignore malformed extras. */ }
            }
        };
        boolean registered = false;
        try {
            app.registerReceiver(receiver, new IntentFilter(REPLY), Context.RECEIVER_EXPORTED);
            registered = true;
            for (TargetProfile target : TargetProfile.values()) {
                try {
                    DailyAlarmWire.send(app, DailyAlarmWire.route(REQUEST, target.packageName)
                            .putExtra("request", request));
                } catch (RuntimeException unavailable) { /* The other source can still be read. */ }
            }
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException unavailable) {
            // Preserve local records and any authenticated replies already received.
        } finally {
            if (registered) app.unregisterReceiver(receiver);
        }
        synchronized (replies) { return new EnumMap<>(replies); }
    }

    private static String version(Context app, String pkg) {
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(pkg, 0);
            return info.versionName != null && info.versionName.matches("[A-Za-z0-9._-]{1,40}")
                    ? info.versionName : "版本未知";
        } catch (PackageManager.NameNotFoundException | RuntimeException absent) { return "未安装或不可见"; }
    }

    static void section(StringBuilder report, String label, String version, String events, String unavailable) {
        report.append("\n[").append(label).append("] ").append(version).append('\n');
        if (events == null) {
            report.append(unavailable).append('\n');
            return;
        }
        java.util.ArrayDeque<String> rows = DiagnosticEvents.read(events);
        if (rows.isEmpty()) report.append("尚无可导出的联动事件\n");
        for (String row : rows) {
            long at = Long.parseLong(row.substring(0, 13));
            report.append(Instant.ofEpochMilli(at)).append(row.substring(13)).append('\n');
        }
    }

    static File write(File cacheDir, String report) throws IOException {
        File dir = new File(cacheDir, "shared-diagnostics");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("Cannot create share directory");
        File[] prior = dir.listFiles();
        long expiry = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        if (prior != null) for (File old : prior) {
            if (old.getName().startsWith("WatchLink-") && old.lastModified() < expiry) old.delete();
        }
        File file = File.createTempFile("WatchLink-", ".zip", dir);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            out.putNextEntry(new ZipEntry("WatchLink.txt"));
            out.write(report.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        } catch (IOException failed) {
            file.delete();
            throw failed;
        }
        return file;
    }

    private static Intent chooser(Context app, File file) {
        Uri uri = FileProvider.getUriForFile(app, OWNER + ".diagnostics.files", file);
        Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("WatchLink 闹钟诊断", uri));
        Intent qq = new Intent(send).setPackage(QQ);
        if (qq.resolveActivity(app.getPackageManager()) != null)
            return Intent.createChooser(qq, "分享诊断日志到 QQ");
        return Intent.createChooser(send, "分享 WatchLink 诊断日志");
    }

    private DiagnosticShare() {}
}
