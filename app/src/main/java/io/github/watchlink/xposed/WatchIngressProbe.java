package io.github.watchlink.xposed;

import androidx.annotation.RequiresApi;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Optional SDK receive hook inside Health :PhoneService for enhanced wire-level verification. */
final class WatchIngressProbe {
    private WatchIngressProbe() {}

    @RequiresApi(34)
    static void installSdk(AlarmObserverInstaller installer, HealthSymbolResolver.Symbols symbols,
                           BooleanSupplier armed, BiConsumer<String, byte[]> received,
                           BiConsumer<String, byte[]> batteryReceived)
            throws ReflectiveOperationException {
        if (symbols == null || symbols.receiveCallbackClass() == null || symbols.receiveCallbackClass().isEmpty()) {
            throw new NoSuchMethodException("Missing Health receive callback symbol");
        }
        Class<?> sdkDevice = installer.target("com.huawei.devicesdk.entity.DeviceInfo");
        Method identity = installer.method(sdkDevice, "getDeviceMac", String.class, false);
        Class<?> callback = ReminderMethodShape.implementation(installer::target, List.of(
                installer.target("com.huawei.devicesdk.callback.MessageReceiveCallback")),
                symbols.receiveCallbackClass());
        Method sdkReceive = ReminderMethodShape.packetMethod(callback, "onDataReceived", 1, sdkDevice, null, int.class);
        Method data = ReminderMethodShape.packetBytes(sdkReceive.getParameterTypes()[1]);
        installer.observeAround("health.sdk.receive", sdkReceive, chain -> {
            if (((Integer) chain.getArg(2)) != 0) return null;
            boolean sessionArmed = armed.getAsBoolean();
            Object packet = chain.getArg(1);
            byte[] bytes = null;
            boolean isBattery = false;
            if (batteryReceived != null && packet != null) {
                try {
                    bytes = (byte[]) data.invoke(packet);
                    isBattery = HealthWatchStatusCollector.isBatteryResponseFrame(bytes);
                } catch (ReflectiveOperationException ignored) {
                    bytes = null;
                }
            }
            if (!sessionArmed && !isBattery) return null;
            Object source = chain.getArg(0);
            String key = null;
            try {
                key = source == null ? null : (String) identity.invoke(source);
                if (bytes == null && packet != null) {
                    bytes = (byte[]) data.invoke(packet);
                }
            } catch (ReflectiveOperationException e) {
                if (sessionArmed) received.accept(null, null);
                return null;
            }
            if (isBattery) {
                batteryReceived.accept(key, bytes);
            }
            if (sessionArmed) {
                received.accept(key, bytes);
            }
            return null;
        }, (chain, token, result) -> {});
    }
}
