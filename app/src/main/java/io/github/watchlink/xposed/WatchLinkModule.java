package io.github.watchlink.xposed;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import androidx.annotation.RequiresApi;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.setup.WatchLinkPrefs;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Installs the real-time alarm reminder adapter in Clock main and Huawei Health :PhoneService.
 * Supports LSPosed API 102 named hook IDs and controlled hot reload with active-alarm protection.
 */
public final class WatchLinkModule extends XposedModule {
    private volatile String processName;
    private volatile TargetProfile activeProfile;
    private volatile ClassLoader hostLoader;
    private volatile String[] hostSources;
    private volatile Context hostContext;
    private volatile XposedInterface.HookHandle bootstrapHandle;
    private volatile WirelessSettingsBridge earlyWirelessBridge;
    private volatile AlarmObserverInstaller currentInstaller;
    private final AtomicBoolean scheduled = new AtomicBoolean();

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        if (param.isSystemServer()) return;
        processName = param.getProcessName();
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (Build.VERSION.SDK_INT < 34 || processName == null || !param.isFirstPackage()) return;
        TargetProfile profile = TargetProfile.fromPackage(param.getPackageName());
        if (profile == null || !profile.isReminderProcess(processName)) return;
        if (!scheduled.compareAndSet(false, true)) return;

        ProbeLog.info("MODULE_LOADED source=" + profile + " api=" + getApiVersion());
        ApplicationInfo owner = getModuleApplicationInfo();
        if (owner == null || owner.uid < 0 || !BuildConfig.APPLICATION_ID.equals(owner.packageName)) {
            ProbeLog.info("ALARM_INSTALL_OWNER_MISMATCH");
            return;
        }
        ClassLoader loader = param.getClassLoader();
        String source = param.getApplicationInfo().sourceDir;
        String[] splits = param.getApplicationInfo().splitSourceDirs;
        String[] sources = new String[1 + (splits == null ? 0 : splits.length)];
        sources[0] = source;
        if (splits != null) System.arraycopy(splits, 0, sources, 1, splits.length);

        activeProfile = profile;
        hostLoader = loader;
        hostSources = sources;
        int ownerUid = owner.uid;

        // Arm the unobfuscated WirelessSettings wake hook synchronously so cold-start 1B queries never race.
        if (profile == TargetProfile.WIRELESS_SETTINGS) {
            earlyWirelessBridge = WirelessSettingsBridge.installEarlyHook(this, loader, openRemotePreferences());
        }

        Consumer<Application> start = app -> {
            if (app != null) hostContext = app.getApplicationContext();
            launchInstall(profile, loader, sources, ownerUid, app, "WatchLink-hook-install");
        };
        XposedInterface.HookHandle handle = HostApplicationReady.observe(
                this, profile.packageName, param.getApplicationInfo().className, loader, start);
        if (handle != null) {
            bootstrapHandle = handle;
            return;
        }
        ProbeLog.info("REMINDER_APPLICATION_CONTEXT_FALLBACK");
        start.accept(null);
    }

    @Override public boolean onHotReloading(HotReloadingParam param) {
        if (Build.VERSION.SDK_INT < 34) return false;
        AlarmObserverInstaller installer = currentInstaller;
        if (installer != null && installer.isBusy()) {
            ProbeLog.info("MODULE_HOT_RELOAD_DEFERRED" + (activeProfile != null ? " source=" + activeProfile : ""));
            return false;
        }
        ProbeLog.info("MODULE_HOT_RELOADING" + (activeProfile != null ? " source=" + activeProfile : ""));
        XposedInterface.HookHandle boot = bootstrapHandle;
        bootstrapHandle = null;
        if (boot != null) {
            try { boot.unhook(); } catch (RuntimeException ignored) {}
        }
        WirelessSettingsBridge earlyWireless = earlyWirelessBridge;
        earlyWirelessBridge = null;
        if (earlyWireless != null) {
            try { earlyWireless.close(); } catch (RuntimeException ignored) {}
        }
        if (installer != null) {
            currentInstaller = null;
            installer.close();
        }
        // Pass only JDK / Android types so the new module ClassLoader can unpack without ClassCastException.
        param.setSavedInstanceState(new Object[]{
                activeProfile == null ? null : activeProfile.packageName,
                processName,
                hostContext,
                hostLoader,
                hostSources
        });
        return true;
    }

    @Override public void onHotReloaded(HotReloadedParam param) {
        List<XposedInterface.HookHandle> oldHandles = param.getOldHookHandles();
        if (oldHandles != null) {
            for (XposedInterface.HookHandle handle : oldHandles) {
                try { handle.unhook(); } catch (RuntimeException ignored) {}
            }
        }
        if (param.isSystemServer() || Build.VERSION.SDK_INT < 34) return;
        processName = param.getProcessName();

        Object raw = param.getSavedInstanceState();
        if (!(raw instanceof Object[] saved) || saved.length < 5) return;
        String pkg = saved[0] instanceof String s ? s : null;
        Context savedContext = saved[2] instanceof Context c ? c : null;
        ClassLoader savedLoader = saved[3] instanceof ClassLoader cl ? cl : null;
        String[] savedSources = saved[4] instanceof String[] arr ? arr : null;

        TargetProfile profile = TargetProfile.fromPackage(pkg);
        if (profile == null || !profile.isReminderProcess(processName) || savedLoader == null) return;

        ApplicationInfo owner = getModuleApplicationInfo();
        if (owner == null || owner.uid < 0 || !BuildConfig.APPLICATION_ID.equals(owner.packageName)) {
            ProbeLog.info("ALARM_INSTALL_OWNER_MISMATCH");
            return;
        }
        activeProfile = profile;
        hostContext = savedContext;
        hostLoader = savedLoader;
        hostSources = savedSources;
        scheduled.set(true);

        ProbeLog.info("MODULE_HOT_RELOADED source=" + profile + " api=" + getApiVersion());
        launchInstall(profile, savedLoader, savedSources, owner.uid, savedContext, "WatchLink-hot-reload-install");
    }

    @RequiresApi(34)
    private void launchInstall(TargetProfile profile, ClassLoader loader, String[] sources,
                               int ownerUid, Context readyContext, String threadName) {
        SharedPreferences remote = openRemotePreferences();
        WirelessSettingsBridge preInstalledWireless = earlyWirelessBridge;
        earlyWirelessBridge = null;
        AlarmObserverInstaller installer = new AlarmObserverInstaller(
                this, profile, loader, ownerUid, remote, preInstalledWireless);
        currentInstaller = installer;
        Thread worker = new Thread(() -> {
            try {
                installer.installReminder(sources, readyContext, ctx -> hostContext = ctx);
            } catch (RuntimeException | LinkageError error) {
                ProbeLog.failure("ALARM_INSTALL_WORKER_FAILED", error);
            }
        }, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    private SharedPreferences openRemotePreferences() {
        try {
            return getRemotePreferences(WatchLinkPrefs.GROUP);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
