package com.tom500101.rlcompass.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Minimal reflection helpers for reaching into third-party mods.
 *
 * <h2>Why not direct imports</h2>
 * The mod dependencies in {@code libs/} are loaded with {@code implementation}, so their classes are
 * on the compile classpath and could be referenced directly. Doing so would turn an optional mod
 * into a hard runtime dependency: on a server without RLCraft's mod set our locator classes would
 * fail to link. Everything here therefore goes through {@link Class#forName(String)}, which lets the
 * caller catch the failure and simply report "not installed".
 *
 * <h2>Why field names are safe to hard-code here</h2>
 * These are the mods' own classes, not Minecraft's. Reobfuscation only rewrites references to
 * Minecraft members; a mod's own field names are preserved in the shipped jar. Contrast with
 * {@code MapGenLocator}, which must use SRG names because it reaches into vanilla classes.
 */
public final class Reflect {

    private Reflect() {
    }

    /** Loads a class, or returns {@code null} when it is not installed. */
    public static Class<?> findClass(String className) {
        try {
            return Class.forName(className);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Walks up the hierarchy for a declared field, including private ones. */
    public static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // keep walking
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** Locates a method, searching the hierarchy; returns {@code null} when absent. */
    public static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // keep walking
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** Reads a static field, or returns {@code null}. */
    public static Object readStatic(Class<?> type, String name) {
        Field field = findField(type, name);
        if (field == null) {
            return null;
        }
        try {
            return field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reads an instance field, or returns {@code null}. */
    public static Object read(Object target, String name) {
        if (target == null) {
            return null;
        }
        Field field = findField(target.getClass(), name);
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reads an int field, or returns {@code fallback}. */
    public static int readInt(Object target, String name, int fallback) {
        Object value = read(target, name);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    /** Invokes a no-argument method, or returns {@code null} on any failure. */
    public static Object invoke(Object target, String name) {
        if (target == null) {
            return null;
        }
        Method method = findMethod(target.getClass(), name);
        return method == null ? null : invoke(target, method);
    }

    /** Invokes an already-resolved method, or returns {@code null} on any failure. */
    public static Object invoke(Object target, Method method, Object... args) {
        if (target == null || method == null) {
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Invokes a static method, or returns {@code null} on any failure. */
    public static Object invokeStatic(Class<?> type, String name, Class<?>[] parameterTypes, Object... args) {
        Method method = findMethod(type, name, parameterTypes);
        return method == null ? null : invoke(null, method, args);
    }

    /** True when {@code target}'s class matches {@code className} (loaded reflectively). */
    public static boolean isInstance(Object target, String className) {
        if (target == null) {
            return false;
        }
        Class<?> type = findClass(className);
        return type != null && type.isInstance(target);
    }

    /** Constructs an instance, or returns {@code null} on any failure. */
    public static Object newInstance(Class<?> type, Class<?>[] parameterTypes, Object... args) {
        if (type == null) {
            return null;
        }
        try {
            java.lang.reflect.Constructor<?> constructor = type.getConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (Throwable t) {
            return null;
        }
    }
}
