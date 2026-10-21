package kynarain.cn.optiforge;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 *
 * <p>{@link #jars(Path)} returns everything (the access-transformer and interface indexes apply their own
 * version filter). {@link #runtimeJars(Path, String, String)} returns only the jars this launch really
 * uses - the class/module path plus the libraries of the <b>running</b> version tag - and is what
 * namespace detection must use: a shared rig keeps every Forge line under
 * {@code -Doptiforge.libraries}, and scanning all of them made a 1.16.5 launch report the namespace of
 * {@code forge-1.17.1-37.0.103-client.jar} (measured on the Lead's first real 1.16.5 run).
 */
public final class LaunchPaths {

    private LaunchPaths() {
    }

    public static List<Path> jars() {
        return jars(null);
    }

    /** Everything the launch made visible, including every Forge version found under the library roots. */
    public static List<Path> jars(Path gameDir) {
        Set<Path> out = new LinkedHashSet<>();
        addLaunchPathEntries(out);
        for (Path root : libraryRoots(gameDir)) {
            Path forge = root.resolve("net").resolve("minecraftforge").resolve("forge");
            if (!Files.isDirectory(forge)) continue;
            try (DirectoryStream<Path> versions = Files.newDirectoryStream(forge)) {
                for (Path version : versions) {
                    if (!Files.isDirectory(version)) continue;
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(version, "*.jar")) {
                        for (Path jar : files) out.add(jar);
                    }
                }
            } catch (java.io.IOException ignored) {
                // fall through with what we have
            }
        }
        return existingFiles(out);
    }

    /**
     * The jars that belong to this launch: classpath, module path, and the library jars of exactly the
     * running version - {@code libraries/net/minecraftforge/forge/<mc>-<forge>/} plus the srg client jar
     * {@code libraries/net/minecraft/client/<mc>-.../}. No jar from another Minecraft/Forge line is
     * returned, so a namespace probe cannot be decided by a sibling rig's jars.
     */
    public static List<Path> runtimeJars(Path gameDir, String mcVersion, String forgeVersion) {
        Set<Path> out = new LinkedHashSet<>();
        addLaunchPathEntries(out, mcVersion, forgeVersion);
        boolean hasMc = !J8.isBlank(mcVersion);
        boolean hasTag = hasMc && !J8.isBlank(forgeVersion);
        String tag = hasTag ? mcVersion + "-" + forgeVersion : "";
        for (Path root : libraryRoots(gameDir)) {
            if (hasTag) {
                addJars(out, root.resolve("net").resolve("minecraftforge").resolve("forge").resolve(tag));
            }
            if (hasMc) {
                Path clients = root.resolve("net").resolve("minecraft").resolve("client");
                if (!Files.isDirectory(clients)) continue;
                try (DirectoryStream<Path> versions = Files.newDirectoryStream(clients)) {
                    for (Path version : versions) {
                        String name = version.getFileName().toString();
                        // the srg client jar lives in a directory named <mc>-<timestamp>
                        if (!name.startsWith(mcVersion + "-")) continue;
                        addJars(out, version);
                    }
                } catch (java.io.IOException ignored) {
                    // fall through with what we have
                }
            }
        }
        return existingFiles(out);
    }

    /**
     * The {@code client-*-extra.jar} of the running line: the only jar that carries the vanilla
     * {@code assets/} tree. OptiFine's asset payloads ({@code patch/assets/**}, e.g. the CTM default
     * textures under {@code assets/minecraft/optifine/ctm/default/...}) are deltas against those vanilla
     * resources, so staging them needs this jar as the patcher's resource provider.
     *
     * @return the extra jar, or {@code null} when the runtime was not installed the usual way
     */
    public static Path assetsJar(Path gameDir, String mcVersion) {
        if (J8.isBlank(mcVersion)) return null;
        for (Path root : libraryRoots(gameDir)) {
            Path clients = root.resolve("net").resolve("minecraft").resolve("client");
            if (!Files.isDirectory(clients)) continue;
            try (DirectoryStream<Path> versions = Files.newDirectoryStream(clients)) {
                for (Path version : versions) {
                    String name = version.getFileName().toString();
                    // The extra jar sits either in the plain "<mc>" directory (1.13.2/1.14.4/1.15.2 on this
                    // rig: client-1.14.4-extra-stable.jar) or in the timestamped "<mc>-<stamp>" one.
                    if (!name.equals(mcVersion) && !name.startsWith(mcVersion + "-")) continue;
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(version, "client-*extra*.jar")) {
                        for (Path jar : files) {
                            if (Files.isRegularFile(jar)) return jar;
                        }
                    }
                }
            } catch (java.io.IOException ignored) {
                // fall through with what we have
            }
        }
        return null;
    }

    private static void addLaunchPathEntries(Set<Path> out) {
        addLaunchPathEntries(out, "", "");
    }

    /**
     * Adds the launch's own classpath/module-path entries. With a known running version the entries are
     * filtered to that line as well: a shared rig can hand the process jars of other lines (measured: a
     * 1.16.5 launch reporting the namespace of {@code forge-1.17.1-37.0.103-client.jar}), and those must
     * never decide the namespace.
     */
    private static void addLaunchPathEntries(Set<Path> out, String mcVersion, String forgeVersion) {
        String classpath = System.getProperty("java.class.path", "");
        addSplitFiltered(out, classpath, mcVersion, forgeVersion);
        addSplitFiltered(out, System.getProperty("jdk.module.path", ""), mcVersion, forgeVersion);
        // ModLauncher itself is started with --module-path; that argument is in sun.java.command even
        // when jdk.module.path has not been set for the process.
        String command = System.getProperty("sun.java.command", "");
        String[] tokens = command.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].equals("--module-path") || tokens[i].equals("-p")) {
                if (i + 1 < tokens.length) addSplitFiltered(out, tokens[i + 1], mcVersion, forgeVersion);
            } else if (tokens[i].startsWith("--module-path=")) {
                addSplitFiltered(out, tokens[i].substring("--module-path=".length()), mcVersion, forgeVersion);
            }
        }
    }

    private static void addJars(Set<Path> out, Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path jar : files) out.add(jar);
        } catch (java.io.IOException ignored) {
            // fall through with what we have
        }
    }

    private static List<Path> existingFiles(Set<Path> candidates) {
        List<Path> jars = new ArrayList<>();
        for (Path path : candidates) {
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                jars.add(path);
            }
        }
        return jars;
    }

    private static List<Path> libraryRoots(Path gameDir) {
        List<Path> libraryRoots = new ArrayList<>();
        String override = System.getProperty("optiforge.libraries", "");
        if (!J8.isBlank(override)) libraryRoots.add(Paths.get(override));
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
        if (!J8.isBlank(home)) {
            libraryRoots.add(Paths.get(home).resolve(".minecraft").resolve("libraries"));
        }
        return libraryRoots;
    }

    private static void addSplit(Set<Path> out, String value) {
        if (J8.isBlank(value)) return;
        for (String entry : value.split(java.io.File.pathSeparator)) {
            if (J8.isBlank(entry)) continue;
            try {
                out.add(Paths.get(entry));
            } catch (RuntimeException ignored) {
                // skip malformed entries
            }
        }
    }

    /** Like {@link #addSplit}, but with a known running version only that line's entries are kept. */
    private static void addSplitFiltered(Set<Path> out, String value, String mcVersion,
                                         String forgeVersion) {
        if (J8.isBlank(value)) return;
        boolean filter = !J8.isBlank(mcVersion) && !J8.isBlank(forgeVersion);
        String tag = filter ? mcVersion + "-" + forgeVersion : "";
        for (String entry : value.split(java.io.File.pathSeparator)) {
            if (J8.isBlank(entry)) continue;
            if (filter && !belongsToLine(entry.replace('\\', '/'), mcVersion, tag)) continue;
            try {
                out.add(Paths.get(entry));
            } catch (RuntimeException ignored) {
                // skip malformed entries
            }
        }
    }

    /** True for paths of the running line: versions/<tag>/, versions/<mc>/, forge/<tag>/ or client/<mc>-*. */
    private static boolean belongsToLine(String path, String mcVersion, String tag) {
        if (path.contains("/versions/" + tag + "/")) return true;
        if (path.contains("/versions/" + mcVersion + "/")) return true;
        if (path.contains("/net/minecraftforge/forge/" + tag + "/")) return true;
        int at = path.indexOf("/net/minecraft/client/");
        if (at >= 0) {
            String rest = path.substring(at + "/net/minecraft/client/".length());
            return rest.startsWith(mcVersion + "-");
        }
        return false;
    }
}
