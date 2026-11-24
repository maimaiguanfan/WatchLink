package io.github.watchlink.xposed;

import android.app.BackgroundServiceStartNotAllowedException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.RequiresApi;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.validation.HuaweiReminderService;
import io.github.watchlink.validation.PhoneRingWire;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

/**
 * Dispatches native cooperative 8/9 OPEN and 8/10 CLOSE from INSIDE com.huawei.health:PhoneService.
 * Primary path: self-startService(ExternalPhoneService) — same-app UID bypasses AUTH_STATE_REQ and uses zero
 * obfuscated symbols. Automatic fallback: direct HwDeviceMgrInterface.sendDeviceData(DeviceCommand) if Android
 * blocks startService under background restrictions.
 */
final class HealthExternalSender {
    record Dispatch(boolean accepted, String mode, String error) {
        boolean isDirect() { return "DIRECT_SDK".equals(mode); }
    }

    private final Context app;
    private final DirectChannel direct;

    private record DirectChannel(
            Method managerFactory,
            Method sendDeviceData,
            Constructor<?> commandCtor,
            Method setServiceId,
            Method setCommandId,
            Method setDataContent,
            Method setDataLen,
            Method setIdentify) {

        void send(Context app, String deviceMac, int commandId, byte[] payload) throws ReflectiveOperationException {
            Object manager = managerFactory.invoke(null, app);
            if (manager == null) throw new IllegalStateException("Health device manager null");
            Object command = commandCtor.newInstance();
            setServiceId.invoke(command, 8);
            setCommandId.invoke(command, commandId);
            setDataContent.invoke(command, (Object) payload);
            setDataLen.invoke(command, payload.length);
            setIdentify.invoke(command, deviceMac);
            sendDeviceData.invoke(manager, command);
        }
    }

    @RequiresApi(34)
    HealthExternalSender(Context app, AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols) {
        this.app = app;
        String factoryClass = symbols == null ? "" : symbols.deviceMgrFactoryClass();
        this.direct = resolveDirectChannel(installer, factoryClass);
    }

    boolean directFallbackReady() {
        return direct != null;
    }

    @RequiresApi(34)
    private static DirectChannel resolveDirectChannel(AlarmObserverInstaller installer, String factoryClass) {
        if (factoryClass == null || factoryClass.isEmpty()) return null;
        try {
            Class<?> managerApi = installer.target(
                    "com.huawei.hwdevice.phoneprocess.framework.hwdevicemgr.dmsmanager.HwDeviceMgrInterface");
            Class<?> commandType = installer.target("com.huawei.datatype.DeviceCommand");
            Class<?> provider = installer.target(factoryClass);
            Method factory = findManagerFactory(provider, managerApi);
            if (factory == null) return null;
            Method send = installer.method(managerApi, "sendDeviceData", void.class, false, commandType);
            Constructor<?> ctor = commandType.getDeclaredConstructor();
            Method setServiceId = installer.method(commandType, "setServiceID", void.class, false, int.class);
            Method setCommandId = installer.method(commandType, "setCommandID", void.class, false, int.class);
            Method setDataContent = installer.method(commandType, "setDataContent", void.class, false, byte[].class);
            Method setDataLen = installer.method(commandType, "setDataLen", void.class, false, int.class);
            Method setIdentify = installer.method(commandType, "setmIdentify", void.class, false, String.class);
            return new DirectChannel(factory, send, ctor, setServiceId, setCommandId, setDataContent, setDataLen, setIdentify);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    static Method findManagerFactory(Class<?> holder, Class<?> managerInterface) {
        Method match = null;
        for (Method method : holder.getDeclaredMethods()) {
            int flags = method.getModifiers();
            if (!Modifier.isPublic(flags) || !Modifier.isStatic(flags) || method.isSynthetic()) continue;
            if (method.getReturnType() != managerInterface) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != Context.class) continue;
            if (match != null) return null;
            match = method;
        }
        return match;
    }

    @RequiresApi(34)
    Dispatch open(String run, int reminder, long firedAt, String title, int snoozeMinutes,
                  String deviceMac, Runnable beforeDirectFallback) {
        Intent intent = HuaweiReminderService.openIntent(run, reminder, firedAt, title, snoozeMinutes);
        Dispatch primary = tryStartService(intent);
        if (primary.accepted()) return primary;
        return tryDirect(deviceMac, 9, encodeOpenPayload(reminder, title, snoozeMinutes),
                beforeDirectFallback, primary.error());
    }

    @RequiresApi(34)
    Dispatch close(String run, int reminder, String deviceMac) {
        Intent intent = HuaweiReminderService.closeIntent(run, reminder);
        Dispatch primary = tryStartService(intent);
        if (primary.accepted()) return primary;
        return tryDirect(deviceMac, 10, encodeClosePayload(reminder), null, primary.error());
    }

    @RequiresApi(34)
    private Dispatch tryStartService(Intent intent) {
        try {
            if (intent.getComponent().equals(app.startService(intent))) {
                ProbeLog.info("HEALTH_DISPATCH_START_SERVICE mode=START_SERVICE");
                return new Dispatch(true, "START_SERVICE", "");
            }
            ProbeLog.info("HEALTH_DISPATCH_FAILED result=FAILED");
            return new Dispatch(false, "FAILED", "FAILED");
        } catch (RuntimeException error) {
            if (error instanceof BackgroundServiceStartNotAllowedException) {
                ProbeLog.failure("HEALTH_DISPATCH_BG_RESTRICTED", error);
                return new Dispatch(false, "BG_RESTRICTED", "BACKGROUND_RESTRICTED");
            }
            if (error instanceof SecurityException) {
                ProbeLog.failure("HEALTH_DISPATCH_DENIED", error);
                return new Dispatch(false, "DENIED", "DENIED");
            }
            ProbeLog.failure("HEALTH_DISPATCH_FAILED", error);
            return new Dispatch(false, "FAILED", "FAILED");
        }
    }

    private Dispatch tryDirect(String deviceMac, int commandId, byte[] payload,
                               Runnable beforeDirectFallback, String primaryError) {
        if (direct == null || deviceMac == null || deviceMac.isEmpty()) {
            return new Dispatch(false, primaryError.equals("BACKGROUND_RESTRICTED") ? "BG_RESTRICTED" : primaryError, primaryError);
        }
        try {
            if (beforeDirectFallback != null) beforeDirectFallback.run();
            direct.send(app, deviceMac, commandId, payload);
            ProbeLog.info("HEALTH_DISPATCH_FALLBACK_DIRECT mode=DIRECT_SDK");
            return new Dispatch(true, "DIRECT_SDK", "");
        } catch (Exception | LinkageError fallbackError) {
            ProbeLog.failure("HEALTH_DISPATCH_FAILED", fallbackError);
            return new Dispatch(false, "FAILED", primaryError);
        }
    }

    /**
     * TLV payload for Huawei Service 8 / Command 9 (OPEN), matching ExternalPhoneService.formatCommandByOpen:
     * tag 1 (4B big-endian notification_id), tag 2 (UTF-8 alarmName, <=127B), optional tag 4 (1B snooze_delay).
     */
    static byte[] encodeOpenPayload(int notificationId, String title, int snoozeMinutes) {
        if (notificationId <= 0) throw new IllegalArgumentException("Invalid notificationId");
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        writeIdTag(out, notificationId);
        if (title != null && !title.isEmpty()) {
            byte[] utf8 = title.getBytes(StandardCharsets.UTF_8);
            if (utf8.length <= 127) {
                out.write(0x02);
                out.write(utf8.length);
                out.write(utf8, 0, utf8.length);
            }
        }
        if (PhoneRingWire.validSnooze(snoozeMinutes)) {
            out.write(0x04);
            out.write(0x01);
            out.write(snoozeMinutes & 0xff);
        }
        return out.toByteArray();
    }

    /**
     * TLV payload for Huawei Service 8 / Command 10 (CLOSE type=1), matching ExternalPhoneService.formatCommandByStopAndPause:
     * tag 1 (4B big-endian notification_id), tag 2 (1B action=1).
     */
    static byte[] encodeClosePayload(int notificationId) {
        if (notificationId <= 0) throw new IllegalArgumentException("Invalid notificationId");
        ByteArrayOutputStream out = new ByteArrayOutputStream(9);
        writeIdTag(out, notificationId);
        out.write(0x02);
        out.write(0x01);
        out.write(0x01);
        return out.toByteArray();
    }

    private static void writeIdTag(ByteArrayOutputStream out, int id) {
        out.write(0x01);
        out.write(0x04);
        out.write((id >>> 24) & 0xff);
        out.write((id >>> 16) & 0xff);
        out.write((id >>> 8) & 0xff);
        out.write(id & 0xff);
    }
}
