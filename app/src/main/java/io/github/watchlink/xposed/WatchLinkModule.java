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
 * Installs WatchLink's host adapters in Clock, Calendar, WirelessSettings, UMS, Health :PhoneService,
 * and Health :DaemonService. Supports LSPosed API 102 named hook IDs and controlled hot reload.
 */
public final class WatchLinkModule extends XposedModule {
    private volatile String processName;
    private volatile TargetProfile activeProfile;
    private volatile ClassLoader hostLoader;
    private volatile String[] hostSources;
    private volatile Context hostContext;
    private volatile XposedInterface.HookHandle bootstrapHandle;
    private volatile WirelessSettingsBridge earlyWirelessBridge;
    private volatile MyDevicesBridge earlyMyDevicesBridge;
    private volatile MyDevicesCardsUiBridge myDevicesCardsUiBridge;
    private volatile AlarmObserverInstaller currentInstaller;
    private volatile HealthDaemonLiveBridge daemonLiveBridge;
    private final AtomicBoolean scheduled = new AtomicBoolean();

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        if (param.isSystemServer()) return;
        processName = param.getProcessName();
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (Build.VERSION.SDK_INT < 34 || processName == null || !param.isFirstPackage()) return;
        TargetProfile profile = TargetProfile.fromPackage(param.getPackageName());
        if (profile == null) return;

        boolean isDaemon = profile.isHealthDaemonProcess(processName);
        boolean isMyDevicesCards = profile.isMyDevicesCardsProcess(processName);
        if (!isDaemon && !isMyDevicesCards && !profile.isReminderProcess(processName)) return;
        if (!scheduled.compareAndSet(false, true)) return;

        ProbeLog.info("MODULE_LOADED source=" + (isDaemon ? "DAEMON" : profile) + " api=" + getApiVersion());
        ApplicationInfo owner = getModuleApplicationInfo();
        if (owner == null || owner.uid < 0 || !BuildConfig.APPLICATION_ID.equals(owner.packageName)) {
            ProbeLog.info("ALARM_INSTALL_OWNER_MISMATCH");
            return;
        }
        ClassLoader loader = param.getClassLoader();
        String[] sources = extractSources(param.getApplicationInfo());

        activeProfile = profile;
        hostLoader = loader;
        hostSources = sources;
        int ownerUid = owner.uid;

        if (isDaemon) {
            Consumer<Application> startDaemon = app -> {
                Context ctx = app != null ? app.getApplicationContext() : currentHostApplication();
                if (ctx == null) return;
                hostContext = ctx;
                launchDaemonBridge(loader, ctx, "WatchLink-daemon-live-install");
            };
            XposedInterface.HookHandle handle = HostApplicationReady.observe(
                    this, profile.packageName, param.getApplicationInfo().className, loader, startDaemon);
            if (handle != null) {
                bootstrapHandle = handle;
                return;
            }
            startDaemon.accept(null);
            return;
        }

        if (isMyDevicesCards) {
            Consumer<Application> startCardsUi = app -> {
                Context ctx = app != null ? app.getApplicationContext() : currentHostApplication();
                if (ctx == null) return;
                hostContext = ctx;
                launchMyDevicesCardsUiBridge(loader, ctx, "WatchLink-mydevices-cards-ui");
            };
            XposedInterface.HookHandle handle = HostApplicationReady.observe(
                    this, profile.packageName, param.getApplicationInfo().className, loader, startCardsUi);
            if (handle != null) {
                bootstrapHandle = handle;
                return;
            }
            startCardsUi.accept(null);
            return;
        }

        // Arm unobfuscated early hooks synchronously so cold-start initialization never races.
        if (profile == TargetProfile.WIRELESS_SETTINGS) {
            earlyWirelessBridge = WirelessSettingsBridge.installEarlyHook(this, loader, openRemotePreferences());
        } else if (profile == TargetProfile.MY_DEVICES) {
            earlyMyDevicesBridge = MyDevicesBridge.installEarlyHook(this, loader, openRemotePreferences());
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
        MyDevicesBridge earlyMyDevices = earlyMyDevicesBridge;
        earlyMyDevicesBridge = null;
        if (earlyMyDevices != null) {
            try { earlyMyDevices.close(); } catch (RuntimeException ignored) {}
        }
        MyDevicesCardsUiBridge cardsUi = myDevicesCardsUiBridge;
        myDevicesCardsUiBridge = null;
        if (cardsUi != null) {
            try { cardsUi.close(); } catch (RuntimeException ignored) {}
        }
        if (installer != null) {
            currentInstaller = null;
            installer.close();
        }
        HealthDaemonLiveBridge daemon = daemonLiveBridge;
        daemonLiveBridge = null;
        if (daemon != null) {
            try { daemon.close(); } catch (RuntimeException ignored) {}
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
        String pkg = null;
        Context savedContext = null;
        ClassLoader savedLoader = null;
        String[] savedSources = null;
        if (raw instanceof Object[] saved && saved.length >= 5) {
            pkg = saved[0] instanceof String s ? s : null;
            savedContext = saved[2] instanceof Context c ? c : null;
            savedLoader = saved[3] instanceof ClassLoader cl ? cl : null;
            savedSources = saved[4] instanceof String[] arr ? arr : null;
        }
        if (savedContext == null) savedContext = currentHostApplication();
        if (savedLoader == null && savedContext != null) savedLoader = savedContext.getClassLoader();
        if (pkg == null && savedContext != null) pkg = savedContext.getPackageName();
        if ((savedSources == null || savedSources.length == 0) && savedContext != null) {
            savedSources = extractSources(savedContext.getApplicationInfo());
        }
        if (savedLoader == null) return;

        TargetProfile profile = TargetProfile.fromPackage(pkg);
        if (profile == null) return;
        boolean isDaemon = profile.isHealthDaemonProcess(processName);
        boolean isMyDevicesCards = profile.isMyDevicesCardsProcess(processName);
        if (!isDaemon && !isMyDevicesCards && !profile.isReminderProcess(processName)) return;

        ApplicationInfo owner = getModuleApplicationInfo();
        if (owner == null || owner.uid < 0 || !BuildConfig.APPLICATION_ID.equals(owner.packageName)) {
            ProbeLog.info("ALARM_INSTALL_OWNER_MISMATCH");
            return;
        }
        activeProfile = profile;
        hostContext = savedContext != null ? savedContext.getApplicationContext() : null;
        hostLoader = savedLoader;
        hostSources = savedSources;
        scheduled.set(true);

        ProbeLog.info("MODULE_HOT_RELOADED source=" + (isDaemon ? "DAEMON" : profile) + " api=" + getApiVersion());
        if (isDaemon) {
            if (hostContext != null) {
                launchDaemonBridge(savedLoader, hostContext, "WatchLink-hot-reload-daemon");
            }
            return;
        }
        if (isMyDevicesCards) {
            if (hostContext != null) {
                launchMyDevicesCardsUiBridge(savedLoader, hostContext, "WatchLink-hot-reload-mydevices-cards");
            }
            return;
        }
        launchInstall(profile, savedLoader, savedSources, owner.uid, hostContext, "WatchLink-hot-reload-install");
    }

    @RequiresApi(34)
    private void launchInstall(TargetProfile profile, ClassLoader loader, String[] sources,
                               int ownerUid, Context readyContext, String threadName) {
        SharedPreferences remote = openRemotePreferences();
        WirelessSettingsBridge preInstalledWireless = earlyWirelessBridge;
        earlyWirelessBridge = null;
        MyDevicesBridge preInstalledMyDevices = earlyMyDevicesBridge;
        earlyMyDevicesBridge = null;
        AlarmObserverInstaller installer = new AlarmObserverInstaller(
                this, profile, loader, ownerUid, remote, preInstalledWireless, preInstalledMyDevices);
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

    @RequiresApi(34)
    private void launchMyDevicesCardsUiBridge(ClassLoader loader, Context ctx, String threadName) {
        MyDevicesCardsUiBridge bridge = new MyDevicesCardsUiBridge(this, loader, ctx, openRemotePreferences());
        myDevicesCardsUiBridge = bridge;
        Thread worker = new Thread(bridge::install, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    @RequiresApi(34)
    private void launchDaemonBridge(ClassLoader loader, Context ctx, String threadName) {
        HealthDaemonLiveBridge bridge = new HealthDaemonLiveBridge(this, loader, ctx, openRemotePreferences());
        daemonLiveBridge = bridge;
        Thread worker = new Thread(bridge::install, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    private static String[] extractSources(ApplicationInfo info) {
        if (info == null || info.sourceDir == null) return new String[0];
        String[] splits = info.splitSourceDirs;
        String[] sources = new String[1 + (splits == null ? 0 : splits.length)];
        sources[0] = info.sourceDir;
        if (splits != null) System.arraycopy(splits, 0, sources, 1, splits.length);
        return sources;
    }

    private static Context currentHostApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context c ? c.getApplicationContext() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private SharedPreferences openRemotePreferences() {
        try {
            return getRemotePreferences(WatchLinkPrefs.GROUP);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
