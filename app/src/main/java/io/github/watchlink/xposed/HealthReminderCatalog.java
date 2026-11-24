package io.github.watchlink.xposed;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Resolves the singleton used by ExternalPhoneService without initializing a candidate while matching. */
final class HealthReminderCatalog {
    private HealthReminderCatalog() {}

    static Method factory(Class<?> proxy, Class<?> api, Class<?>... candidates) throws NoSuchMethodException {
        Class<?> catalog = null;
        for (Class<?> candidate : candidates) {
            if (candidate == null || candidate.getSuperclass() != proxy || !api.isAssignableFrom(candidate)
                    || candidate.getClassLoader() != api.getClassLoader()) continue;
            if (catalog != null) throw new NoSuchMethodException("Ambiguous Health device catalog");
            catalog = candidate;
        }
        if (catalog == null) throw new NoSuchMethodException("Missing Health device catalog");

        Method factory = null;
        for (Method method : catalog.getDeclaredMethods()) {
            int flags = method.getModifiers();
            if (!Modifier.isPublic(flags) || !Modifier.isStatic(flags) || method.isSynthetic()
                    || method.getParameterCount() != 0 || method.getReturnType() != catalog) continue;
            if (factory != null) throw new NoSuchMethodException("Ambiguous Health device catalog singleton");
            factory = method;
        }
        if (factory == null) throw new NoSuchMethodException("Missing Health device catalog singleton");
        return factory;
    }
}
