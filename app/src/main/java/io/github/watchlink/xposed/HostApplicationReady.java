package io.github.watchlink.xposed;

import android.app.Application;
import io.github.libxposed.api.XposedInterface;
import io.github.watchlink.alarm.probe.ProbeLog;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Starts the adapter after the host's own Application has been created. */
final class HostApplicationReady {
    static XposedInterface.HookHandle observe(XposedInterface module, String packageName, String className,
                                              ClassLoader loader, Consumer<Application> start) {
        try {
            if (className == null) return null;
            Class<?> application = Class.forName(className, false, loader);
            Method onCreate = onCreate(application, loader);
            if (onCreate == null) return null;
            AtomicReference<Consumer<Application>> pending = new AtomicReference<>(start);
            AtomicReference<XposedInterface.HookHandle> handleRef = new AtomicReference<>();
            XposedInterface.HookBuilder builder = module.hook(onCreate);
            if (module.getApiVersion() >= XposedInterface.API_102) {
                builder.setId("host.app.onCreate");
            }
            XposedInterface.HookHandle handle = builder.intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (chain.getThisObject() instanceof Application app && packageName.equals(app.getPackageName())) {
                        Consumer<Application> first = pending.getAndSet(null);
                        if (first != null) {
                            XposedInterface.HookHandle installed = handleRef.getAndSet(null);
                            if (installed != null) {
                                try { installed.unhook(); } catch (RuntimeException ignored) {}
                            }
                            first.accept(app);
                        }
                    }
                } catch (RuntimeException | LinkageError failure) {
                    ProbeLog.failure("REMINDER_APPLICATION_START_FAILED", failure);
                }
                return result;
            });
            handleRef.set(handle);
            if (pending.get() == null && handleRef.getAndSet(null) != null) {
                try { handle.unhook(); } catch (RuntimeException ignored) {}
            }
            return handle;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            ProbeLog.failure("REMINDER_APPLICATION_CALLBACK_UNAVAILABLE", unavailable);
            return null;
        }
    }

    static Method onCreate(Class<?> type, ClassLoader loader) throws NoSuchMethodException {
        if (type == Application.class || type.getClassLoader() != loader || !Application.class.isAssignableFrom(type))
            return null;
        Method method = type.getDeclaredMethod("onCreate");
        int flags = method.getModifiers();
        return Modifier.isPublic(flags) && !Modifier.isStatic(flags) && !Modifier.isAbstract(flags)
                && !Modifier.isNative(flags) && method.getReturnType() == void.class ? method : null;
    }

    private HostApplicationReady() {}
}
