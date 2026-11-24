package io.github.watchlink.xposed;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/** Resolve the cooperative SDK roles using their contracts in the installed target loader. */
final class ReminderMethodShape {
    private ReminderMethodShape() {}

    interface Lookup { Class<?> find(String name) throws ClassNotFoundException; }

    static Class<?> implementation(Lookup lookup, List<Class<?>> roles, String... names)
            throws ReflectiveOperationException {
        Class<?> selected = null;
        for (String name : names) {
            Class<?> type;
            try { type = lookup.find(name); }
            catch (ClassNotFoundException | NoClassDefFoundError absent) { continue; }
            // On .330 the former send facade name is occupied by its one-interface delegate.
            if (Modifier.isAbstract(type.getModifiers()) || !Arrays.asList(type.getInterfaces()).containsAll(roles))
                continue;
            if (selected != null) throw new NoSuchMethodException("Ambiguous reminder SDK role");
            selected = type;
        }
        if (selected == null) throw new NoSuchMethodException("Missing reminder SDK role");
        return selected;
    }

    static Method packetBytes(Class<?> packet) throws NoSuchMethodException {
        Method selected = null;
        for (Method method : packet.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (method.getReturnType() != byte[].class || method.getParameterCount() != 0
                    || !Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers) || Modifier.isAbstract(modifiers)
                    || method.isBridge() || method.isSynthetic()) continue;
            if (selected != null) throw new NoSuchMethodException("Ambiguous SDK frame accessor");
            selected = method;
        }
        if (selected == null) throw new NoSuchMethodException("Missing SDK frame accessor");
        return selected;
    }

    static Method packetMethod(Class<?> owner, String name, int packetIndex, Class<?>... parameters)
            throws NoSuchMethodException {
        if (owner.getClassLoader() == null || packetIndex < 0 || packetIndex >= parameters.length
                || parameters[packetIndex] != null) throw new IllegalArgumentException("Packet position");
        Method selected = null;
        for (Method method : owner.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (!method.getName().equals(name) || method.getReturnType() != void.class
                    || !Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers) || Modifier.isAbstract(modifiers)
                    || method.isBridge() || method.isSynthetic()) continue;
            Class<?>[] actual = method.getParameterTypes();
            if (actual.length != parameters.length || actual[packetIndex].getClassLoader() != owner.getClassLoader())
                continue;
            boolean matches = true;
            for (int i = 0; i < actual.length; i++) {
                if (i != packetIndex && actual[i] != parameters[i]) matches = false;
            }
            if (!matches) continue;
            if (selected != null) throw new NoSuchMethodException("Ambiguous SDK packet method");
            selected = method;
        }
        if (selected == null) throw new NoSuchMethodException("Missing SDK packet method");
        return selected;
    }
}
