package kynarain.cn.optiforge;

import java.awt.Desktop;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * The in-game notice shown when no OptiFine jar was found.
 *
 * <p>OptiFabric answers the same problem with a screen on the title screen (its {@code MixinTitleScreen}: a
 * red-headed ConfirmScreen with "open mods folder" / "help" buttons). OptiForge has no Mixin, so the staged
 * mod starts a watcher: once a {@code TitleScreen} is up it posts a system toast naming the folder and the
 * download page, and prefers a ConfirmScreen when that screen type can be built.
 *
 * <p>This class is copied into the staged mod jar (relocated to {@code optiforge/generated/Notice}), so it may
 * only reference {@code java.*}: every Minecraft type is resolved reflectively and <b>by signature</b>, since
 * SRG and official runtimes disagree about member names. Any failure is swallowed - a missing hint must never
 * break the launch.
 */
public final class Notice {

    private static volatile boolean started;
    private static volatile boolean shown;
    private static volatile boolean previewed;

    private Notice() {
    }

    /** Called from the generated mod's constructor; cheap and idempotent. */
    public static void startWatch() {
        if (started) return;
        started = true;
        boolean debug = Boolean.getBoolean("optiforge.noticeDebug");
        if (debug) System.out.println("[OptiForge] notice watcher starting (missingOptifine="
                + System.getProperty("optiforge.missingOptifine") + ")");
        if (!"true".equalsIgnoreCase(System.getProperty("optiforge.missingOptifine"))) return;
        Thread thread = new Thread(Notice::watch, "OptiForge notice");
        thread.setDaemon(true);
        thread.start();
    }

    private static void watch() {
        for (int tick = 0; tick < 3600 && !shown; tick++) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Object minecraft = minecraft();
            if (Boolean.getBoolean("optiforge.noticeDebug") && tick % 50 == 0) {
                System.out.println("[OptiForge] notice tick " + tick + " mc=" + (minecraft != null));
            }
            if (minecraft == null) continue;
            Object screen = currentScreen(minecraft);
            if (Boolean.getBoolean("optiforge.noticeDebug") && tick % 50 == 0) {
                System.out.println("[OptiForge] notice tick " + tick + " screen="
                        + (screen == null ? "null" : screen.getClass().getName()));
            }
            if (screen == null || !screen.getClass().getName().endsWith("TitleScreen")) continue;
            shown = true;
            show(minecraft);
        }
    }

    private static void show(Object minecraft) {
        Path folder = gameDirectory().resolve("optiforge");
        String message = "OptiForge: 未找到 OptiFine。请把 OptiFine 的 jar 放到 " + folder
                + " 里，然后重新启动游戏（不要放 mods/）。下载：https://optifine.net/downloads";
        System.out.println("[OptiForge] " + message);
        previewed = showConfirm(minecraft, message) || showToast(minecraft, message);
        if (!previewed) {
            System.out.println("[OptiForge] could not display the in-game notice; see the line above");
        }
    }

    /** True when a notice was displayed (for the diagnostic report). */
    public static boolean previewed() {
        return previewed;
    }

    // ---- ConfirmScreen (the OptiFabric-style prompt) ----

    private static boolean showConfirm(Object minecraft, String message) {
        try {
            Class<?> screenClass = Class.forName("net.minecraft.client.gui.screens.ConfirmScreen");
            Class<?> componentType = Class.forName("net.minecraft.network.chat.Component");
            Object title = literal(componentType, "OptiForge 需要 OptiFine");
            Object body = literal(componentType, message);
            Object close = literal(componentType, "关闭 / Close");
            Object open = literal(componentType, "打开 optiforge 文件夹");
            for (Constructor<?> constructor : screenClass.getConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length < 3 || !types[0].isInterface()) continue;
                boolean components = true;
                for (int i = 1; i < types.length; i++) {
                    if (!types[i].isAssignableFrom(componentType)) components = false;
                }
                if (!components) continue;
                Object callback = java.lang.reflect.Proxy.newProxyInstance(
                        screenClass.getClassLoader(), new Class<?>[]{types[0]}, (proxy, method, args) -> {
                            if ("accept".equals(method.getName()) && args != null && args.length == 1
                                    && Boolean.TRUE.equals(args[0])) {
                                openFolder();
                            }
                            return null;
                        });
                Object[] arguments = new Object[types.length];
                arguments[0] = callback;
                arguments[1] = title;
                arguments[2] = body;
                if (types.length > 3) arguments[3] = close;
                if (types.length > 4) arguments[4] = open;
                setScreen(minecraft, constructor.newInstance(arguments));
                return true;
            }
        } catch (Throwable ignored) {
            // fall through to the toast
        }
        return false;
    }

    private static void openFolder() {
        try {
            Path dir = gameDirectory().resolve("optiforge");
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir.toFile());
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private static Path gameDirectory() {
        try {
            String value = System.getProperty("optiforge.gameDir");
            if (value != null && !value.trim().isEmpty()) return java.nio.file.Paths.get(value);
        } catch (Throwable ignored) {
            // fall through
        }
        return java.nio.file.Paths.get(".");
    }

    // ---- toast fallback ----

    private static boolean showToast(Object minecraft, String message) {
        try {
            Class<?> componentType = Class.forName("net.minecraft.network.chat.Component");
            Object toasts = toasts(minecraft);
            if (toasts == null) return false;
            Class<?> toastType = Class.forName("net.minecraft.client.gui.components.toasts.Toast");
            Class<?> systemToast = Class.forName("net.minecraft.client.gui.components.toasts.SystemToast");
            Object enumValue = firstEnumConstant(systemToast);
            Object first = literal(componentType, "OptiForge: 未找到 OptiFine");
            Object second = literal(componentType, message);
            Object toast = null;
            for (Method method : systemToast.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length == 4 && types[2].isAssignableFrom(componentType)
                        && types[3].isAssignableFrom(componentType)) {
                    toast = method.invoke(null, minecraft, enumValue, first, second);
                    break;
                }
                if (types.length == 3 && types[1].isEnum() && types[2].isAssignableFrom(componentType)) {
                    toast = method.invoke(null, minecraft, enumValue, first);
                    break;
                }
            }
            if (toast == null) return false;
            for (Method method : toasts.getClass().getMethods()) {
                if (method.getParameterCount() == 1 && method.getParameterTypes()[0].isAssignableFrom(toastType)) {
                    method.invoke(toasts, toast);
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // nothing else to try
        }
        return false;
    }

    // ---- reflection helpers (signature based: SRG and official runtimes differ) ----

    private static Object minecraft() {
        try {
            Class<?> type = Class.forName("net.minecraft.client.Minecraft");
            for (Method method : type.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                if (method.getParameterCount() != 0) continue;
                if (method.getReturnType() != type) continue;
                Object value = method.invoke(null);
                if (value != null) return value;
            }
        } catch (Throwable ignored) {
            // not started yet
        }
        return null;
    }

    /**
     * The open screen, by getter or by field.
     *
     * <p>1.20.1 has no getter: {@code Minecraft.screen} is a public field, so looking only for a
     * no-argument method returning a Screen left the watcher blind (measured: "screen=null" for 90 s while
     * the title screen was clearly up).
     */
    private static Object currentScreen(Object minecraft) {
        Class<?> screenType = null;
        try {
            screenType = Class.forName("net.minecraft.client.gui.screens.Screen");
        } catch (Throwable ignored) {
            // extremely unlikely; the name check below still works
        }
        try {
            for (Method method : minecraft.getClass().getMethods()) {
                if (method.getParameterCount() != 0) continue;
                if (!isScreenType(method.getReturnType(), screenType)) continue;
                Object value = method.invoke(minecraft);
                if (value != null) return value;
            }
        } catch (Throwable ignored) {
            // fall through to the fields
        }
        for (Class<?> type = minecraft.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                // Name-only matching picked Minecraft.virtualScreen (VirtualScreen ends in "Screen" but is
                // not a GUI screen), so the type has to be checked against the real Screen class.
                if (!isScreenType(field.getType(), screenType)) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(minecraft);
                    if (value != null) return value;
                } catch (Throwable ignored) {
                    // try the next field
                }
            }
        }
        return null;
    }

    private static boolean isScreenType(Class<?> type, Class<?> screenType) {
        if (screenType != null) return screenType.isAssignableFrom(type);
        return type.getName().endsWith("screens.Screen") || type.getName().contains("screens.");
    }
    private static void setScreen(Object minecraft, Object screen) {
        for (Method method : minecraft.getClass().getMethods()) {
            if (method.getParameterCount() != 1) continue;
            if (!method.getParameterTypes()[0].isInstance(screen)) continue;
            try {
                method.invoke(minecraft, screen);
                return;
            } catch (Throwable ignored) {
                // try the next overload
            }
        }
    }

    private static Object toasts(Object minecraft) {
        for (String name : new String[]{"getToasts", "m_91296_", "getToastManager"}) {
            try {
                Method method = minecraft.getClass().getMethod(name);
                Object value = method.invoke(minecraft);
                if (value != null) return value;
            } catch (Throwable ignored) {
                // next name
            }
        }
        for (Method method : minecraft.getClass().getMethods()) {
            if (method.getParameterCount() != 0) continue;
            if (!method.getReturnType().getName().contains("Toast")) continue;
            try {
                Object value = method.invoke(minecraft);
                if (value != null) return value;
            } catch (Throwable ignored) {
                // next
            }
        }
        return null;
    }

    private static Object literal(Class<?> componentType, String text) throws Exception {
        for (Method method : componentType.getMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != String.class) continue;
            Class<?> returns = method.getReturnType();
            if (returns.isAssignableFrom(componentType) || componentType.isAssignableFrom(returns)) {
                return method.invoke(null, text);
            }
        }
        throw new IllegalStateException("no Component(String) factory found");
    }

    private static Object firstEnumConstant(Class<?> owner) {
        for (Class<?> nested : owner.getDeclaredClasses()) {
            if (!nested.isEnum()) continue;
            Object[] constants = nested.getEnumConstants();
            if (constants != null && constants.length > 0) return constants[0];
        }
        return null;
    }
}
