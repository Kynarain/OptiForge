package kynarain.cn.optiforge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every path the launch put on the JVM, whether as a classpath or as a module path.
 *
 * <p>Both are needed: Forge 51 puts its universal jar (the one with
 * {@code META-INF/accesstransformer.cfg}) on the classpath, but Forge 1.20.1 hands it to
 * {@code --module-path}. Scanning only {@code java.class.path} therefore found zero access-transformers
 * on 1.20.1 (measured), which left {@code RenderStateShard.f_110131_} private and crashed Forge's own
 * {@code ForgeRenderTypes}.
 */
public final class LaunchPaths {

    private LaunchPaths() {
    }

    public static List<Path> jars() {
        return jars(null);
    }

    /**
     * The same list, plus Forge's own jars found next to the instance ({@code <game>/../libraries}).
     *
     * <p>Forge 1.20.1 assembles its module path at runtime with BootstrapLauncher, so neither
     * {@code java.class.path}, {@code jdk.module.path} nor {@code sun.java.command} mentions the forge
     * universal jar - measured: zero access-transformer configs found there, and every prepared class
     * kept vanilla access flags. The launcher layout ({@code .minecraft/libraries}) is the reliable
     * place, with {@code -Doptiforge.libraries=} as the override.
     */
    public static List<Path> jars(Path gameDir) {
        Set<Path> out = new LinkedHashSet<>();
        addSplit(out, System.getProperty("java.class.path", ""));
        addSplit(out, System.getProperty("jdk.module.path", ""));
        // ModLauncher itself is started with --module-path; that argument is in sun.java.command even
        // when jdk.module.path has not been set for the process.
        String command = System.getProperty("sun.java.command", "");
        String[] tokens = command.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].equals("--module-path") || tokens[i].equals("-p")) {
                if (i + 1 < tokens.length) addSplit(out, tokens[i + 1]);
            } else if (tokens[i].startsWith("--module-path=")) {
                addSplit(out, tokens[i].substring("--module-path=".length()));
            }
        }
        String override = System.getProperty("optiforge.libraries", "");
        List<Path> libraryRoots = new ArrayList<>();
        if (!override.isBlank()) libraryRoots.add(Path.of(override));
        if (gameDir != null) {
            // Version-isolated launchers (PCL and friends) put the game directory at versions/<id>, so the
            // libraries live two levels up - measured: only checking the parent left the index without any
            // Forge class on such an instance, which silently disabled the fixes that need it.
            Path cursor = gameDir.toAbsolutePath();
            for (int depth = 0; depth < 4 && cursor != null; depth++) {
                libraryRoots.add(cursor.resolve("libraries"));
                cursor = cursor.getParent();
            }
        }
        // The launcher's own layout, which is where libraries really live even when the game directory
        // was moved somewhere else (a rig, a modpack instance, ...).
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            libraryRoots.add(Path.of(home).resolve(".minecraft").resolve("libraries"));
        }
        for (Path root : libraryRoots) {
            Path forge = root.resolve("net").resolve("minecraftforge").resolve("forge");
            if (!Files.isDirectory(forge)) continue;
            try (var versions = Files.newDirectoryStream(forge)) {
                for (Path version : versions) {
                    if (!Files.isDirectory(version)) continue;
                    try (var files = Files.newDirectoryStream(version, "*.jar")) {
                        for (Path jar : files) out.add(jar);
                    }
                }
            } catch (java.io.IOException ignored) {
                // fall through with what we have
            }
        }
        List<Path> jars = new ArrayList<>();
        for (Path path : out) {
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                jars.add(path);
            }
        }
        return jars;
    }

    private static void addSplit(Set<Path> out, String value) {
        if (value == null || value.isBlank()) return;
        for (String entry : value.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            try {
                out.add(Path.of(entry));
            } catch (RuntimeException ignored) {
                // skip malformed entries
            }
        }
    }
}
