package io.github.watchlink.xposed;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in ADB-only raw watch command debug bridge inside com.huawei.health:PhoneService.
 * Disabled by default; only registers its DUMP-protected BroadcastReceiver while the user
 * explicitly enables the diagnostics toggle. Never exposes an unprivileged broadcast endpoint.
 */
@RequiresApi(34)
final class HealthRawCommandBridge extends BroadcastReceiver {
    static final String ACTION = BuildConfig.APPLICATION_ID + ".debug.SEND_RAW_COMMAND";
    private static final int MAX_CAPTURED_FRAMES = 16;
    private static final int MAX_FRAME_HEX_BYTES = 96;

    private record SampleEventChannel(
            Method apiFactory,
            Method sendSampleEvent,
            Constructor<?> eventCtor,
            Method setSrcPkg,
            Method setWearPkg,
            Method setCommandId,
            Method setEventId,
            Method setEventLevel,
            Method setEventData) {

        boolean send(Object deviceInfo, RawCommandRequest.Parsed req) throws ReflectiveOperationException {
            Object api = apiFactory.invoke(null);
            if (api == null) return false;
            Object event = eventCtor.newInstance();
            setSrcPkg.invoke(event, req.srcPkg());
            setWearPkg.invoke(event, req.wearPkg());
            setCommandId.invoke(event, req.commandId());
            setEventId.invoke(event, req.eventId());
            setEventLevel.invoke(event, req.eventLevel());
            setEventData.invoke(event, (Object) req.payload());
            Object res = sendSampleEvent.invoke(api, deviceInfo, event, "WatchLinkRawDebug");
            return !Boolean.FALSE.equals(res);
        }
    }

    private static final class CaptureSession {
        final List<String> frames = new ArrayList<>();

        synchronized void record(boolean received, byte[] frame) {
            if (frames.size() >= MAX_CAPTURED_FRAMES || frame == null || frame.length == 0) return;
            String dir = received ? "RX" : "TX";
            String sc = frame.length >= 2
                    ? "5." + (frame[0] & 0xff) + "." + (frame[1] & 0xff)
                    : "len=" + frame.length;
            frames.add(dir + "(" + sc + "):" + RawCommandRequest.toHex(frame, MAX_FRAME_HEX_BYTES));
        }

        synchronized String formatSummary() {
            if (frames.isEmpty()) return "none";
            return String.join("; ", frames);
        }
    }

    private final Context app;
    private final HostSettings settings;
    private final ScheduledExecutorService worker;
    private final HealthExternalSender sender;
    private final HealthCooperativeReadiness readiness;

    private SampleEventChannel sampleChannel;
    private boolean registered;
    private volatile boolean installed;
    private volatile CaptureSession activeCapture;

    HealthRawCommandBridge(Context app, HostSettings settings, ScheduledExecutorService worker,
                           HealthExternalSender sender, HealthCooperativeReadiness readiness) {
        this.app = app;
        this.settings = settings;
        this.worker = worker;
        this.sender = sender;
        this.readiness = readiness;
    }

    void install(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        sampleChannel = resolveSampleEventChannel(installer, symbols);
        installed = true;
        settings.onRawCommandDebugChanged(enabled ->
                worker.execute(() -> updateRegistrationLocked(Boolean.TRUE.equals(enabled))));
        updateRegistrationLocked(settings.isRawCommandDebugEnabled());
    }

    void onSettingsFallbackChanged(boolean enabled) {
        updateRegistrationLocked(enabled);
    }

    boolean isCapturing() {
        return activeCapture != null;
    }

    void observeSdkFrame(boolean received, byte[] frame) {
        CaptureSession capture = activeCapture;
        if (capture != null) {
            capture.record(received, frame);
        }
    }

    void close() {
        installed = false;
        activeCapture = null;
        settings.onRawCommandDebugChanged(null);
        unregisterReceiverLocked();
    }

    private void updateRegistrationLocked(boolean enabled) {
        if (!installed) return;
        if (enabled) {
            if (!registered) {
                try {
                    app.registerReceiver(this, new IntentFilter(ACTION),
                            Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED);
                    registered = true;
                    ProbeLog.info("RAW_CMD_DEBUG_ENABLED");
                } catch (RuntimeException error) {
                    ProbeLog.failure("RAW_CMD_DEBUG_REGISTER_FAILED", error);
                }
            }
        } else {
            unregisterReceiverLocked();
        }
    }

    private void unregisterReceiverLocked() {
        activeCapture = null;
        if (registered) {
            try { app.unregisterReceiver(this); } catch (RuntimeException ignored) {}
            registered = false;
            ProbeLog.info("RAW_CMD_DEBUG_DISABLED");
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!installed || intent == null || !ACTION.equals(intent.getAction())) return;
        if (!settings.isRawCommandDebugEnabled()) {
            if (isOrderedBroadcast()) setResult(0, "DISABLED", null);
            return;
        }
        RawCommandRequest.Parsed req = RawCommandRequest.parse(
                intent.getStringExtra("mode"),
                intent.getStringExtra("code"),
                intent.getIntExtra("service", 0),
                intent.getIntExtra("command", 0),
                intent.getStringExtra("hex"),
                intent.getStringExtra("json"),
                intent.getStringExtra("src"),
                intent.getStringExtra("wear"),
                intent.getLongExtra("event_id", 0L),
                intent.getIntExtra("event_level", 0),
                intent.getIntExtra("wait_ms", RawCommandRequest.DEFAULT_WAIT_MS));
        if (!req.valid()) {
            String msg = "ERROR:" + req.error();
            ProbeLog.info("RAW_CMD_REJECTED reason=" + req.error());
            if (isOrderedBroadcast()) setResult(0, msg, null);
            return;
        }

        PendingResult pending = goAsync();
        try {
            worker.execute(() -> handleRequestLocked(req, pending));
        } catch (RuntimeException shuttingDown) {
            pending.finish();
        }
    }

    private void handleRequestLocked(RawCommandRequest.Parsed req, PendingResult pending) {
        if (!installed || !settings.isRawCommandDebugEnabled()) {
            finish(pending, 0, "DISABLED");
            return;
        }
        if (activeCapture != null) {
            finish(pending, 0, "BUSY");
            return;
        }
        HealthCooperativeReadiness.ConnectedDevice device = readiness == null ? null : readiness.connectedPrimaryDevice();
        if (device == null || device.rawMac() == null || device.rawMac().isEmpty()) {
            finish(pending, 0, "WATCH_DISCONNECTED");
            return;
        }

        CaptureSession capture = new CaptureSession();
        activeCapture = capture;
        boolean dispatched;
        String error = "";
        try {
            if (RawCommandRequest.MODE_DEVICE_COMMAND.equals(req.mode())) {
                if (!sender.directFallbackReady()) {
                    dispatched = false;
                    error = "DIRECT_UNAVAILABLE";
                } else {
                    dispatched = sender.sendDeviceCommand(
                            device.rawMac(), req.serviceId(), req.commandId(), req.payload());
                    if (!dispatched) error = "SEND_FAILED";
                }
            } else {
                SampleEventChannel channel = sampleChannel;
                if (channel == null) {
                    dispatched = false;
                    error = "SAMPLE_CHANNEL_UNAVAILABLE";
                } else {
                    dispatched = channel.send(device.deviceInfo(), req);
                    if (!dispatched) error = "SAMPLE_SEND_FAILED";
                }
            }
        } catch (Exception | LinkageError err) {
            dispatched = false;
            error = "EXCEPTION:" + err.getClass().getSimpleName();
            ProbeLog.failure("RAW_CMD_DISPATCH_FAILED", err);
        }

        if (!dispatched) {
            activeCapture = null;
            String msg = "ERROR:" + error + " target=" + req.descriptor();
            ProbeLog.info("RAW_CMD_RESULT " + msg);
            finish(pending, 0, msg);
            return;
        }

        Runnable complete = () -> {
            if (activeCapture == capture) activeCapture = null;
            String summary = "SENT target=" + req.descriptor()
                    + " payload_len=" + req.payload().length
                    + " frames=[" + capture.formatSummary() + "]";
            ProbeLog.info("RAW_CMD_RESULT " + summary);
            finish(pending, 1, summary);
        };

        if (req.waitMs() <= 0) {
            complete.run();
        } else {
            try {
                worker.schedule(complete, req.waitMs(), TimeUnit.MILLISECONDS);
            } catch (RuntimeException shuttingDown) {
                activeCapture = null;
                pending.finish();
            }
        }
    }

    private static void finish(PendingResult pending, int code, String data) {
        try {
            pending.setResultCode(code);
            pending.setResultData(data);
        } catch (RuntimeException ignored) {
            // Non-ordered broadcast ignores result data.
        } finally {
            pending.finish();
        }
    }

    private static SampleEventChannel resolveSampleEventChannel(
            AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        if (installer == null || symbols == null || symbols.sampleCommandApiClass() == null
                || symbols.sampleCommandApiClass().isEmpty()) {
            return null;
        }
        try {
            Class<?> proxy = installer.target("com.huawei.haf.bundle.AppBundlePluginProxy");
            Class<?> api = installer.target("com.huawei.health.devicemgr.api.outofprocess.SendSampleCommandApi");
            Class<?> device = installer.target("com.huawei.health.devicemgr.business.entity.DeviceInfo");
            Class<?> impl = installer.target(symbols.sampleCommandApiClass());
            Method factory = HealthReminderCatalog.factory(proxy, api, impl);

            Method sendSample = null;
            for (Method m : api.getMethods()) {
                if (!"sendSampleEventCommand".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[0] == device && p[2] == String.class) {
                    sendSample = m;
                    break;
                }
            }
            if (sendSample == null) return null;

            Class<?> eventClass = sendSample.getParameterTypes()[1];
            Constructor<?> ctor = eventClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Method setSrc = eventClass.getMethod("setSrcPkgName", String.class);
            Method setWear = eventClass.getMethod("setWearPkgName", String.class);
            Method setCmd = eventClass.getMethod("setCommandId", int.class);

            Method setEventId = uniqueDeclaredSetter(eventClass, long.class);
            Method setEventLevel = uniqueDeclaredSetter(eventClass, int.class);
            Method setEventData = uniqueDeclaredSetter(eventClass, byte[].class);
            if (setEventId == null || setEventLevel == null || setEventData == null) return null;

            return new SampleEventChannel(factory, sendSample, ctor, setSrc, setWear, setCmd,
                    setEventId, setEventLevel, setEventData);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static Method uniqueDeclaredSetter(Class<?> cls, Class<?> paramType) {
        Method match = null;
        for (Method m : cls.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (!Modifier.isPublic(mod) || Modifier.isStatic(mod) || m.isSynthetic()) continue;
            if (m.getReturnType() != void.class || m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] != paramType) continue;
            if ("setCommandId".equals(m.getName())) continue;
            if (match != null) return null;
            match = m;
        }
        return match;
    }
}
