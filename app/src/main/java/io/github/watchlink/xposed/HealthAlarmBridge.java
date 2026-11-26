package io.github.watchlink.xposed;

import android.content.BroadcastReceiver;
import io.github.watchlink.timer.TimerRing;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.validation.DailyAlarmWire;
import io.github.watchlink.validation.HealthHostControl;
import io.github.watchlink.validation.HuaweiReminderService;
import io.github.watchlink.validation.PhoneRingWire;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Health :PhoneService bridge.
 * Receives authenticated Clock and Calendar strong-reminder rings and dispatches Huawei's
 * ExternalPhoneService directly under Health's own UID (zero AUTH_STATE_REQ on external apps,
 * zero Magisk, zero WatchLink process after boot).
 */
@RequiresApi(34)
final class HealthAlarmBridge extends BroadcastReceiver {
    private static final int TEST_ID_BASE = 0x60000000;
    private final Context app;
    private final int ownerUid;
    private final HostSettings settings;
    private final String[] sources;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "WatchLink-health-bridge");
        thread.setDaemon(true);
        return thread;
    });
    private final DailyAlarmObservation daily = new DailyAlarmObservation();
    private final AtomicInteger testSequence = new AtomicInteger(TEST_ID_BASE);

    private HealthExternalSender sender;
    private HealthCooperativeReadiness readiness;
    private HealthWifiBridge wifiBridge;
    private HealthLiveWindowBridge liveBridge;
    private HealthRawCommandBridge rawCommandBridge;
    private BroadcastReceiver controlReceiver;
    private boolean registered;
    private volatile boolean installed;
    private volatile boolean serviceWitnessReady;
    private volatile boolean sdkObserverReady;
    private volatile String symbolSource = "DEGRADED";

    private volatile TargetProfile activeDailySource;
    private volatile String activeDailyRun;
    private volatile int activeDailyReminder;
    private volatile String activeDailyDevice;
    private ScheduledFuture<?> dailySafetyClose;

    private volatile String lastDispatchMode = "NONE";
    private volatile String lastActionSource = "NONE";

    private TestSession activeTest;
    private volatile String testState = "IDLE";
    private volatile String testDispatchMode = "NONE";
    private volatile String testActionSource = "NONE";

    private static final class TestSession {
        final String run;
        final int reminder;
        final String identifier;
        final String device;
        final CooperativeSessionMatch openMatch, closeMatch;
        final boolean sdkAvailable;
        boolean serviceReceived, directDispatched, outbound, closed;
        String answeredAction;
        boolean sdkAnswered, intentAnswered;
        ScheduledFuture<?> timeout;

        TestSession(String run, int reminder, String identifier, String device, boolean sdkAvailable) {
            this.run = run;
            this.reminder = reminder;
            this.identifier = identifier;
            this.device = device;
            this.sdkAvailable = sdkAvailable && device != null && !device.isEmpty();
            this.openMatch = this.sdkAvailable ? new CooperativeSessionMatch(device, reminder, false) : null;
            this.closeMatch = this.sdkAvailable ? new CooperativeSessionMatch(device, reminder, true) : null;
        }

        boolean dispatched() { return serviceReceived || directDispatched; }
    }

    HealthAlarmBridge(Context app, int ownerUid, HostSettings settings, String[] sources) {
        this.app = app;
        this.ownerUid = ownerUid;
        this.settings = settings;
        this.sources = sources;
        worker.setRemoveOnCancelPolicy(true);
    }

    void install(AlarmObserverInstaller installer) throws ReflectiveOperationException {
        if (ownerUid < 0) throw new IllegalStateException("HEALTH_BRIDGE_PLATFORM");

        // Mandatory unobfuscated hook on ExternalPhoneService.onStartCommand.
        serviceWitnessReady = installExternalServiceHook(installer);
        if (!serviceWitnessReady) throw new IllegalStateException("EXTERNAL_PHONE_SERVICE_UNAVAILABLE");

        // Resolve optional obfuscated SDK symbols dynamically (cache -> fast path -> one-time DEX scan).
        HealthSymbolResolver.Symbols symbols = HealthSymbolResolver.resolve(
                installer, new ApkSymbolCache(app, "health"), sources);
        sender = new HealthExternalSender(app, installer, symbols);

        // Optional SDK hooks & device catalog: degrade cleanly across future Health updates.
        sdkObserverReady = symbols != null && symbols.completeSdk() && installOptionalSdkHooks(installer, symbols);
        symbolSource = sdkObserverReady && symbols != null ? symbols.source() : "DEGRADED";
        ProbeLog.info("HEALTH_SYMBOLS_RESOLVED source=HEALTH resolver=" + symbolSource);
        ProbeLog.info((sdkObserverReady ? "HEALTH_SDK_OBSERVER_READY" : "HEALTH_SDK_OBSERVER_DEGRADED")
                + " sdk_observer=" + sdkObserverReady);

        settings.onDisabled(() -> worker.execute(() -> cancelActiveDailyFor(TargetProfile.CLOCK, false)));
        settings.onCalendarDisabled(() -> worker.execute(() -> cancelActiveDailyFor(TargetProfile.CALENDAR, false)));

        HealthWifiBridge wifi = new HealthWifiBridge(app, ownerUid, settings, sender, readiness, sdkObserverReady);
        wifi.install(installer, symbols);
        wifiBridge = wifi;

        HealthLiveWindowBridge live = new HealthLiveWindowBridge(app, settings, worker, readiness);
        live.install(installer, symbols);
        liveBridge = live;

        HealthRawCommandBridge raw = new HealthRawCommandBridge(app, settings, worker, sender, readiness);
        raw.install(installer, symbols);
        rawCommandBridge = raw;
        settings.onTimerChanged(() -> worker.execute(() -> {
            if (!settings.isTimerEnabled()) cancelActiveDailyFor(TargetProfile.CLOCK, true);
            if (!settings.isTimerEnabled() || !settings.isTimerLiveWindowEnabled()) live.closeGroupLocked(true);
        }));

        IntentFilter filter = new IntentFilter(DailyAlarmWire.OPEN);
        filter.addAction(DailyAlarmWire.END);
        app.registerReceiver(this, filter, Context.RECEIVER_EXPORTED);
        registered = true;
        controlReceiver = HealthHostControl.register(app, ownerUid, worker, this::handleControl);
        installed = true;

        notifyAlertSourcesReady();
    }

    private void notifyAlertSourcesReady() {
        for (TargetProfile target : new TargetProfile[]{TargetProfile.CLOCK, TargetProfile.CALENDAR}) {
            try {
                DailyAlarmWire.send(app, DailyAlarmWire.route(DailyAlarmWire.HEALTH_READY, target.packageName));
            } catch (RuntimeException unavailable) {
                ProbeLog.failure("DAILY_HEALTH_REPORT_UNAVAILABLE", unavailable);
            }
        }
    }

    private boolean installExternalServiceHook(AlarmObserverInstaller installer) {
        try {
            ComponentName expected = HuaweiReminderService.serviceComponent();
            ServiceInfo info = app.getPackageManager().getServiceInfo(expected, 0);
            if (!info.exported || !HuaweiReminderService.PERMISSION.equals(info.permission)
                    || !TargetProfile.HEALTH.isReminderProcess(info.processName)) {
                return false;
            }
            Class<?> serviceType = installer.target(HuaweiReminderService.SERVICE);
            if (!android.app.Service.class.isAssignableFrom(serviceType)) return false;
            Method onStart = installer.method(serviceType, "onStartCommand", int.class, false,
                    Intent.class, int.class, int.class);
            installer.observeAround("health.external.start", onStart, chain -> {
                Intent incoming = (Intent) chain.getArg(0);
                if (incoming != null) observeServiceIntent(incoming, expected);
                return null;
            }, (chain, token, result) -> {});
            return true;
        } catch (Exception | LinkageError unavailable) {
            ProbeLog.failure("HEALTH_SERVICE_WITNESS_UNAVAILABLE", unavailable);
            return false;
        }
    }

    private boolean installOptionalSdkHooks(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        try {
            HealthCooperativeReadiness catalog = new HealthCooperativeReadiness(installer, symbols);
            Class<?> device = installer.target("com.huawei.unitedevice.entity.UniteDevice");
            Method key = installer.method(device, "getIdentify", String.class, false);
            Class<?> channel = ReminderMethodShape.implementation(installer::target, List.of(
                    installer.target("com.huawei.unitedevice.api.EngineChannelInterface"),
                    installer.target("com.huawei.unitedevice.api.UniteChannelInterface")),
                    symbols.sendFacadeClass());
            Method submit = ReminderMethodShape.packetMethod(channel, "sendCommand", 1, device, null);
            Method bytes = ReminderMethodShape.packetBytes(submit.getParameterTypes()[1]);
            WatchIngressProbe.installSdk(installer, symbols, this::anySessionArmed, (mac, frame) -> observeSdk(true, mac, frame));
            installer.observeAround("health.sdk.send", submit, chain -> {
                if (anySessionArmed()) {
                    Object packet = chain.getArg(1);
                    Object target = chain.getArg(0);
                    if (packet != null && target != null) {
                        observeSdk(false, (String) key.invoke(target), (byte[]) bytes.invoke(packet));
                    }
                }
                return null;
            }, (chain, token, result) -> {});
            readiness = catalog;
            return true;
        } catch (Exception | LinkageError degraded) {
            readiness = null;
            return false;
        }
    }

    boolean hasActiveSession() {
        HealthWifiBridge wifi = wifiBridge;
        HealthLiveWindowBridge live = liveBridge;
        HealthRawCommandBridge raw = rawCommandBridge;
        return anySessionArmed()
                || (wifi != null && wifi.isBusy())
                || (live != null && live.isBusy())
                || (raw != null && raw.isCapturing());
    }

    private boolean anySessionArmed() {
        HealthWifiBridge wifi = wifiBridge;
        HealthRawCommandBridge raw = rawCommandBridge;
        return daily.active(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                || activeTest != null
                || (wifi != null && wifi.isAwaitingAck())
                || (raw != null && raw.isCapturing());
    }

    void close() {
        installed = false;
        settings.onDisabled(null);
        settings.onTimerChanged(null);
        settings.onCalendarDisabled(null);
        daily.stop();
        if (rawCommandBridge != null) {
            try { rawCommandBridge.close(); } catch (RuntimeException ignored) {}
            rawCommandBridge = null;
        }
        if (liveBridge != null) {
            try { liveBridge.close(); } catch (RuntimeException ignored) {}
            liveBridge = null;
        }
        if (wifiBridge != null) {
            try { wifiBridge.close(); } catch (RuntimeException ignored) {}
            wifiBridge = null;
        }
        if (registered) {
            try { app.unregisterReceiver(this); } catch (RuntimeException ignored) {}
            registered = false;
        }
        if (controlReceiver != null) {
            try { app.unregisterReceiver(controlReceiver); } catch (RuntimeException ignored) {}
            controlReceiver = null;
        }
        worker.shutdownNow();
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!installed || intent == null) return;
        String action = intent.getAction();
        if (!DailyAlarmWire.OPEN.equals(action) && !DailyAlarmWire.END.equals(action)) return;
        int uid = getSentFromUid();
        String pkg = getSentFromPackage();
        TargetProfile senderProfile = TargetProfile.fromPackage(pkg);
        if (uid < 0 || senderProfile == null || !senderProfile.isAlertSource() || intent.getComponent() != null
                || !TargetProfile.HEALTH.packageName.equals(intent.getPackage())
                || (intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
            return;
        }
        PendingResult pending = goAsync();
        try {
            worker.execute(() -> {
                try {
                    if (installed && SenderVerifier.verifyReminderSender(app, senderProfile, uid, pkg)) {
                        handleDailyMessage(senderProfile, intent);
                    }
                } catch (RuntimeException | LinkageError error) {
                    ProbeLog.failure("DAILY_HEALTH_CONTROL_UNAVAILABLE", error);
                } finally {
                    pending.finish();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            pending.finish();
        }
    }

    private void handleDailyMessage(TargetProfile source, Intent intent) {
        String run = intent.getStringExtra("run");
        int reminder = intent.getIntExtra("reminder", 0);
        long at = intent.getLongExtra("at", 0), start = intent.getLongExtra("elapsed", -1);
        if (!DailyAlarmWire.isRun(run) || reminder <= 0) return;
        boolean timer = TimerRing.TYPE.equals(intent.getStringExtra(TimerRing.TYPE_EXTRA));
        if (timer != TimerRing.timerNotice(reminder) || (timer && source != TargetProfile.CLOCK)) return;

        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        if (DailyAlarmWire.END.equals(intent.getAction())) {
            boolean closeWatch = intent.getBooleanExtra("closeWatch", true);
            boolean shouldClose = closeWatch && daily.shouldClose(source, run, reminder, wall, elapsed);
            daily.end(source, run, reminder, wall, elapsed);
            cancelDailyTimeout(run);
            if (shouldClose && source == activeDailySource && run.equals(activeDailyRun) && reminder == activeDailyReminder) {
                sender.close(run, reminder, activeDailyDevice);
            }
            if (source == activeDailySource && run.equals(activeDailyRun)) clearActiveDailyMeta();
            return;
        }

        if (!DailyAlarmWire.inWindow(at, start, PhoneRingWire.MAX_AGE, wall, elapsed)) return;
        if (!settings.isEnabledFor(source, timer)) {
            dailyReport(new DailyAlarmObservation.Evidence(source, run, reminder, "DISABLED", wall, elapsed), "");
            return;
        }

        // A real phone alarm or calendar strong reminder always preempts a standalone manual test reminder.
        if (activeTest != null) cancelTestLocked(true);

        String title = DailyReminderTitle.forWatch(intent.getStringExtra("title"));
        if (title == null) {
            title = source == TargetProfile.CALENDAR
                    ? HuaweiReminderService.CALENDAR_TITLE : timer ? "倒计时" : HuaweiReminderService.ALARM_TITLE;
        }
        int snoozeMinutes = intent.getIntExtra("snoozeMinutes", 0);
        if (snoozeMinutes != 0 && !PhoneRingWire.validSnooze(snoozeMinutes)) snoozeMinutes = 0;
        String identifier = intent.getStringExtra("openIdentifier");

        String deviceMac = null;
        if (sdkObserverReady && readiness != null) {
            WatchCheck check = checkWatch();
            if (!check.canSend()) {
                dailyReport(new DailyAlarmObservation.Evidence(source, run, reminder, "WATCH_UNAVAILABLE", wall, elapsed), "");
                return;
            }
            deviceMac = check.rawDevice();
        }

        String armed = daily.arm(source, run, reminder, at, start, deviceMac, identifier, wall, elapsed,
                sdkObserverReady && deviceMac != null);
        if (armed == null) return;
        if (!"READY".equals(armed)) {
            dailyReport(new DailyAlarmObservation.Evidence(source, run, reminder, "BUSY", wall, elapsed), "");
            return;
        }

        activeDailySource = source;
        activeDailyRun = run;
        activeDailyReminder = reminder;
        activeDailyDevice = deviceMac;
        lastActionSource = "NONE";

        HealthExternalSender.Dispatch dispatch = sender.open(
                run, reminder, at, title, snoozeMinutes, deviceMac,
                () -> daily.markDirectDispatched(run, reminder));
        lastDispatchMode = dispatch.mode();
        if (!dispatch.accepted()) {
            daily.abort(run, reminder);
            clearActiveDailyMeta();
            dailyReport(new DailyAlarmObservation.Evidence(source, run, reminder, dispatch.error(), wall, elapsed), "");
            return;
        }

        scheduleDailyTimeout(source, run, reminder, deviceMac);
        String sentKind = dispatch.isDirect() ? "SENT_DIRECT" : "SENT";
        dailyReport(new DailyAlarmObservation.Evidence(source, run, reminder, sentKind, wall, elapsed), dispatch.mode());
    }

    private void scheduleDailyTimeout(TargetProfile source, String run, int reminder, String deviceMac) {
        if (dailySafetyClose != null) dailySafetyClose.cancel(false);
        dailySafetyClose = worker.schedule(() -> {
            long nowWall = System.currentTimeMillis(), nowElapsed = SystemClock.elapsedRealtime();
            if (source == activeDailySource && run.equals(activeDailyRun)
                    && daily.shouldClose(source, run, reminder, nowWall, nowElapsed)) {
                daily.end(source, run, reminder, nowWall, nowElapsed);
                sender.close(run, reminder, deviceMac);
                clearActiveDailyMeta();
            }
        }, DailyAlarmWire.MAX_WINDOW, TimeUnit.MILLISECONDS);
    }

    private void cancelDailyTimeout(String run) {
        if (run.equals(activeDailyRun) && dailySafetyClose != null) {
            dailySafetyClose.cancel(false);
            dailySafetyClose = null;
        }
    }

    private void cancelActiveDailyFor(TargetProfile source, boolean timer) {
        if (source == null || source != activeDailySource || timer != TimerRing.timerNotice(activeDailyReminder)) return;
        String run = activeDailyRun;
        int reminder = activeDailyReminder;
        String deviceMac = activeDailyDevice;
        if (run == null || reminder <= 0) return;
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        boolean shouldClose = daily.shouldClose(source, run, reminder, wall, elapsed);
        daily.end(source, run, reminder, wall, elapsed);
        cancelDailyTimeout(run);
        if (shouldClose) sender.close(run, reminder, deviceMac);
        clearActiveDailyMeta();
    }

    private void clearActiveDailyMeta() {
        activeDailySource = null;
        activeDailyRun = null;
        activeDailyReminder = 0;
        activeDailyDevice = null;
    }

    private void observeServiceIntent(Intent incoming, ComponentName expected) {
        String action = incoming.getAction();
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        if (HuaweiReminderService.ACTION.equals(action) && expected.equals(incoming.getComponent())) {
            Bundle attributes = incoming.getBundleExtra("attribute");
            if (attributes == null || attributes.getInt("type", -1) != 0) return;
            int id = attributes.getInt("notification_id", 0);
            String identifier = incoming.getIdentifier();
            DailyAlarmObservation.Evidence evidence = daily.serviceReceived(id, identifier, wall, elapsed);
            if (evidence != null) {
                worker.execute(() -> dailyReport(evidence, ""));
                return;
            }
            worker.execute(() -> onTestServiceReceived(id, identifier));
            return;
        }
        if (HuaweiReminderService.DEVICE_ACTION.equals(action)) {
            int id = incoming.getIntExtra("notification_id", 0);
            int code = incoming.getIntExtra("action", 0);
            if (id <= 0 || (code != 1 && code != 2)) return;
            String op = code == 1 ? "SNOOZE" : "DISMISS";
            ProbeLog.info("HEALTH_DEVICE_ACTION_INTENT operation=" + op);
            DailyAlarmObservation.Evidence evidence = daily.serviceAction(id, code, wall, elapsed);
            if (evidence != null) {
                worker.execute(() -> {
                    if ("ACTION_DUAL_CONFIRMED".equals(evidence.kind())) {
                        lastActionSource = "SDK_AND_INTENT";
                        ProbeLog.info("HEALTH_ACTION_DUAL_CONFIRMED operation=" + op);
                    } else {
                        lastActionSource = evidence.actionSource();
                    }
                    dailyReport(evidence, "");
                });
                return;
            }
            worker.execute(() -> onTestAction(id, op, false));
        }
    }

    private synchronized void observeSdk(boolean received, String device, byte[] frame) {
        HealthRawCommandBridge raw = rawCommandBridge;
        if (raw != null) {
            raw.observeSdkFrame(received, frame);
        }
        HealthWifiBridge wifi = wifiBridge;
        if (wifi != null) {
            wifi.observeSdkFrame(received, frame);
        }
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        DailyAlarmObservation.Evidence evidence = daily.observe(received, device, frame, wall, elapsed);
        if (evidence != null) {
            try {
                worker.execute(() -> {
                    if ("SNOOZE".equals(evidence.kind()) || "DISMISS".equals(evidence.kind())) {
                        lastActionSource = evidence.actionSource();
                        ProbeLog.info("HEALTH_SDK_ACTION operation=" + evidence.kind());
                    } else if ("ACTION_DUAL_CONFIRMED".equals(evidence.kind())) {
                        lastActionSource = "SDK_AND_INTENT";
                        ProbeLog.info("HEALTH_ACTION_DUAL_CONFIRMED");
                    }
                    dailyReport(evidence, "");
                });
            } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {}
        }
        TestSession test = activeTest;
        if (test != null && test.sdkAvailable && test.dispatched()) {
            String closeKind = test.closeMatch.observe(received, device, frame);
            if (closeKind != null) {
                test.closed = true;
                worker.execute(() -> finishTestLocked("CLOSED"));
                return;
            }
            if (!test.closed) {
                String kind = test.openMatch.observe(received, device, frame);
                if ("OUTBOUND".equals(kind)) {
                    test.outbound = true;
                    testState = "OUTBOUND";
                    ProbeLog.info("HEALTH_TEST_STATUS result=OUTBOUND");
                } else if ("SNOOZE".equals(kind) || "DISMISS".equals(kind)) {
                    ProbeLog.info("HEALTH_SDK_ACTION operation=" + kind);
                    worker.execute(() -> onTestAction(test.reminder, kind, true));
                }
            }
        }
    }

    private void onTestServiceReceived(int id, String identifier) {
        TestSession test = activeTest;
        if (test == null || test.closed || test.serviceReceived
                || test.reminder != id || !test.identifier.equals(identifier)) return;
        test.serviceReceived = true;
        if (!"OUTBOUND".equals(testState) && !"SNOOZE".equals(testState) && !"DISMISS".equals(testState)) {
            testState = "SERVICE_RECEIVED";
        }
        ProbeLog.info("HEALTH_TEST_STATUS result=SERVICE_RECEIVED");
    }

    private void onTestAction(int id, String op, boolean fromSdk) {
        TestSession test = activeTest;
        if (test == null || test.reminder != id || !test.dispatched()) return;
        if (test.answeredAction != null) {
            if (op.equals(test.answeredAction)) {
                if (fromSdk) test.sdkAnswered = true;
                else test.intentAnswered = true;
                if (test.sdkAnswered && test.intentAnswered) {
                    testActionSource = "SDK_AND_INTENT";
                    lastActionSource = "SDK_AND_INTENT";
                    ProbeLog.info("HEALTH_ACTION_DUAL_CONFIRMED operation=" + op);
                }
            }
            return;
        }
        test.answeredAction = op;
        if (fromSdk) test.sdkAnswered = true;
        else test.intentAnswered = true;
        testActionSource = fromSdk ? "SDK_ONLY" : "INTENT_ONLY";
        lastActionSource = testActionSource;
        testState = op;
        ProbeLog.info("HEALTH_TEST_STATUS result=" + op + " action_source=" + testActionSource);
        // Close the test reminder on watch after a brief grace window so dual-channel confirmation can arrive.
        worker.schedule(() -> {
            if (activeTest == test) {
                sender.close(test.run, test.reminder, test.device);
                finishTestLocked(test.answeredAction);
            }
        }, 400, TimeUnit.MILLISECONDS);
    }

    private void dailyReport(DailyAlarmObservation.Evidence evidence, String dispatchMode) {
        try {
            if (!installed || evidence.source() == null) return;
            Intent status = DailyAlarmWire.route(DailyAlarmWire.STATUS, evidence.source().packageName)
                    .putExtra("run", evidence.run())
                    .putExtra("reminder", evidence.reminder())
                    .putExtra(TimerRing.TYPE_EXTRA, TimerRing.timerNotice(evidence.reminder()) ? TimerRing.TYPE : "ALARM")
                    .putExtra("kind", evidence.kind())
                    .putExtra("at", evidence.wall())
                    .putExtra("elapsed", evidence.elapsed());
            if (dispatchMode != null && !dispatchMode.isEmpty()) status.putExtra("dispatchMode", dispatchMode);
            if (evidence.actionSource() != null && !evidence.actionSource().isEmpty()) {
                status.putExtra("actionSource", evidence.actionSource());
            }
            DailyAlarmWire.send(app, status);
            if (!"ACTION_DUAL_CONFIRMED".equals(evidence.kind())) {
                ProbeLog.info("DAILY_HEALTH_" + evidence.kind() + " source=" + evidence.source());
            }
        } catch (RuntimeException error) {
            ProbeLog.failure("DAILY_HEALTH_REPORT_UNAVAILABLE", error);
        }
    }

    private record WatchCheck(String state, String rawDevice) {
        boolean canSend() {
            return "READY_SUPPORTED_ON".equals(state) || "READY_SUPPORTED_UNSET".equals(state);
        }
    }

    private WatchCheck checkWatch() {
        if (!sdkObserverReady || readiness == null) return new WatchCheck("DEGRADED", null);
        String token = readiness.connectedToken();
        if (token == null) return new WatchCheck("WATCH_DISCONNECTED", null);
        HealthCooperativeReadiness.Result result = readiness.readSingle(app, token);
        if (result == null) return new WatchCheck("UNKNOWN", null);
        return new WatchCheck(result.kind(), result.matchingRawDevice());
    }

    private HealthHostControl.Reply handleControl(String command) {
        switch (command) {
            case HealthHostControl.ENABLE -> settings.setLocalFallback(true);
            case HealthHostControl.DISABLE -> {
                settings.setLocalFallback(false);
                cancelActiveDailyFor(TargetProfile.CLOCK, false);
            }
            case HealthHostControl.ENABLE_CALENDAR -> settings.setLocalCalendarFallback(true);
            case HealthHostControl.DISABLE_CALENDAR -> {
                settings.setLocalCalendarFallback(false);
                cancelActiveDailyFor(TargetProfile.CALENDAR, false);
            }
            case HealthHostControl.ENABLE_WIFI_AUTO -> {
                settings.setLocalWifiAutoSyncFallback(true);
                if (wifiBridge != null) wifiBridge.triggerCurrentWifiSync("SETTING_ENABLED", false);
            }
            case HealthHostControl.DISABLE_WIFI_AUTO -> settings.setLocalWifiAutoSyncFallback(false);
            case HealthHostControl.SYNC_CURRENT_WIFI -> {
                if (wifiBridge != null) wifiBridge.triggerCurrentWifiSync("MANUAL", true);
            }
            case HealthHostControl.ENABLE_LIVE_WINDOW -> {
                settings.setLocalLiveWindowFallback(true);
                if (liveBridge != null) liveBridge.onSettingsFallbackChanged(true);
            }
            case HealthHostControl.DISABLE_LIVE_WINDOW -> {
                settings.setLocalLiveWindowFallback(false);
                if (liveBridge != null) liveBridge.onSettingsFallbackChanged(false);
            }
            case HealthHostControl.TEST_LIVE_WINDOW -> {
                if (liveBridge != null) liveBridge.startLiveTest();
            }
            case HealthHostControl.CANCEL_LIVE_WINDOW -> {
                if (liveBridge != null) liveBridge.cancelLiveTest();
            }
            case HealthHostControl.ENABLE_RAW_DEBUG -> {
                settings.setLocalRawCommandDebugFallback(true);
                if (rawCommandBridge != null) rawCommandBridge.onSettingsFallbackChanged(true);
            }
            case HealthHostControl.DISABLE_RAW_DEBUG -> {
                settings.setLocalRawCommandDebugFallback(false);
                if (rawCommandBridge != null) rawCommandBridge.onSettingsFallbackChanged(false);
            }
            case HealthHostControl.TEST -> startManualTestLocked();
            case HealthHostControl.CANCEL_TEST -> cancelTestLocked(true);
            default -> {}
        }
        WatchCheck watch = checkWatch();
        HealthWifiBridge.StatusSnapshot wifi = wifiBridge != null
                ? wifiBridge.statusSnapshot()
                : new HealthWifiBridge.StatusSnapshot("UNKNOWN", false, 0, "NONE", "NONE", 0L);
        HealthLiveWindowBridge.StatusSnapshot live = liveBridge != null
                ? liveBridge.statusSnapshot()
                : new HealthLiveWindowBridge.StatusSnapshot("UNKNOWN", 0, "NONE", "NONE", "NONE", "IDLE");
        return new HealthHostControl.Reply(
                true,
                settings.isEnabled(),
                settings.isCalendarEnabled(),
                settings.remoteAvailable(),
                serviceWitnessReady,
                sdkObserverReady,
                symbolSource,
                watch.state(),
                lastDispatchMode,
                lastActionSource,
                testState,
                testDispatchMode,
                testActionSource,
                settings.isWifiAutoSyncEnabled(),
                wifi.watchSupport(),
                wifi.ackReady(),
                wifi.syncedCount(),
                wifi.lastResult(),
                wifi.lastSource(),
                wifi.lastAt(),
                settings.isLiveWindowEnabled(),
                live.watchSupport(),
                live.activeCount(),
                live.lastResult(),
                live.lastSource(),
                live.dispatchMode(),
                live.testState(),
                settings.isRawCommandDebugEnabled());
    }

    private void startManualTestLocked() {
        if (daily.active(System.currentTimeMillis(), SystemClock.elapsedRealtime()) || activeTest != null) {
            testState = "BUSY";
            return;
        }
        WatchCheck watch = checkWatch();
        if (sdkObserverReady && !watch.canSend()) {
            testState = "WATCH_UNAVAILABLE";
            ProbeLog.info("HEALTH_TEST_STATUS result=WATCH_UNAVAILABLE");
            return;
        }
        String run = UUID.randomUUID().toString();
        int reminder = testSequence.incrementAndGet();
        if (reminder <= TEST_ID_BASE || reminder >= 0x6fffffff) {
            testSequence.set(TEST_ID_BASE + 1);
            reminder = TEST_ID_BASE + 1;
        }
        String identifier = HuaweiReminderService.dailyIdentifier(run, reminder, 1);
        TestSession session = new TestSession(run, reminder, identifier, watch.rawDevice(), sdkObserverReady);
        activeTest = session;
        testState = "SENDING";
        testDispatchMode = "NONE";
        testActionSource = "NONE";
        ProbeLog.info("HEALTH_TEST_REQUESTED");

        long now = System.currentTimeMillis();
        HealthExternalSender.Dispatch dispatch = sender.open(
                run, reminder, now, HuaweiReminderService.TEST_TITLE, HuaweiReminderService.SNOOZE_MINUTES,
                watch.rawDevice(), () -> session.directDispatched = true);
        testDispatchMode = dispatch.mode();
        lastDispatchMode = dispatch.mode();
        if (!dispatch.accepted()) {
            activeTest = null;
            testState = dispatch.error();
            ProbeLog.info("HEALTH_TEST_FINISHED result=" + dispatch.error());
            return;
        }
        if ("SENDING".equals(testState)) testState = dispatch.isDirect() ? "SENT_DIRECT" : "SENT";
        session.timeout = worker.schedule(() -> {
            if (activeTest == session) cancelTestLocked(true);
        }, DailyAlarmWire.TEST_WINDOW, TimeUnit.MILLISECONDS);
    }

    private void cancelTestLocked(boolean sendClose) {
        TestSession session = activeTest;
        if (session == null) {
            if (!"SNOOZE".equals(testState) && !"DISMISS".equals(testState)) testState = "IDLE";
            return;
        }
        if (sendClose && !session.closed) {
            sender.close(session.run, session.reminder, session.device);
        }
        finishTestLocked("CLOSED");
    }

    private void finishTestLocked(String terminalState) {
        TestSession session = activeTest;
        if (session == null) return;
        if (session.timeout != null) session.timeout.cancel(false);
        session.closed = true;
        activeTest = null;
        if (!"SNOOZE".equals(testState) && !"DISMISS".equals(testState)) {
            testState = terminalState;
        }
        ProbeLog.info("HEALTH_TEST_FINISHED result=" + testState);
    }
}
