package kynarain.cn.optiforge.legacy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Where things are on a pre-1.13 Forge install.
 *
 * <p>Measured on Forge 1.12.2-14.23.5.2860 (docs/PHASE3.md section 2): the profile inherits from the vanilla
 * 1.12.2 version, so the obfuscated client jar is {@code versions/1.12.2/1.12.2.jar} and there is no
 * Forge client jar; Forge's own changes only exist as {@code binpatches.pack.lzma} inside the universal
 * jar. The coremod's {@code injectData} map carries {@code mcLocation} (the game directory) but no
 * version, so the version is read from {@code FMLInjectionData} (built before coremods are loaded) and
 * falls back to the {@code versions/} directory listing.
 *
 * <p>Server detection copies OptiFine's own {@code LaunchUtils.isForgeServer} (measured from its
 * bytecode): no {@code --accessToken} and {@code --version == "UnknownFMLProfile"} means a Forge server.
 * OptiFine skips its class transformer there, and so must we: the payload patches client rendering
 * classes, which are not on a server.
 */
public final class LegacyPaths {

    private static final String[] VERSION_DIR = {"versions"};

    private LegacyPaths() {
    }

    /** {@code mcLocation} from IFMLLoadingPlugin.injectData, or the working directory. */
    public static Path gameDirFrom(Map<String, Object> data) {
        Object location = data == null ? null : data.get("mcLocation");
        if (location instanceof File) {
            return ((File) location).toPath().toAbsolutePath();
        }
        if (location instanceof Path) {
            return ((Path) location).toAbsolutePath();
        }
        return Paths.get(".").toAbsolutePath().normalize();
    }

    public static Path optiforgeDir(Path gameDir) {
        return gameDir.resolve("optiforge");
    }

    public static Path modsDir(Path gameDir) {
        return gameDir.resolve("mods");
    }

    /**
     * The user's OptiFine jar under {@code <game>/optiforge/}.
     *
     * <p>Same selection rule as the modern pipeline: prefer a file name containing "OptiFine", otherwise
     * any jar that looks like an OptiFine build ({@code buildof.txt} plus {@code patch/} or {@code
     * patch.cfg}). Everything else is reported and ignored, never guessed.
     */
    public static Path findOptiFineJar(Path gameDir, String override, StringBuilder notes) throws IOException {
        if (override != null && !override.isEmpty()) {
            Path path = Paths.get(override);
            return Files.isRegularFile(path) ? path : null;
        }
        Path dir = optiforgeDir(gameDir);
        if (!Files.isDirectory(dir)) {
            notes.append("optiforge dir missing: ").append(dir).append('\n');
            return null;
        }
        List<Path> jars = new ArrayList<Path>();
        DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar");
        try {
            for (Path path : stream) {
                if (looksLikeOptifine(path)) {
                    jars.add(path);
                } else {
                    notes.append("ignored (not an OptiFine build): ").append(path.getFileName()).append('\n');
                }
            }
        } finally {
            stream.close();
        }
        if (jars.isEmpty()) {
            return null;
        }
        Path best = null;
        for (Path path : jars) {
            String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("optifine")) {
                best = path;
                break;
            }
        }
        if (best == null) {
            best = jars.get(0);
        }
        return best;
    }

    private static boolean looksLikeOptifine(Path jar) {
        java.util.zip.ZipFile zip = null;
        try {
            zip = new java.util.zip.ZipFile(jar.toFile());
            boolean buildof = zip.getEntry("buildof.txt") != null;
            boolean patches = zip.getEntry("patch.cfg") != null;
            if (!patches) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith("patch/") && name.endsWith(".class.xdelta")) {
                        patches = true;
                        break;
                    }
                }
            }
            return buildof && patches;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(zip);
        }
    }

    /**
     * The obfuscated client jar the payload is patched against.
     *
     * <p>For 1.12.2 that is the vanilla version jar, and every payload md5 matches only against it
     * (measured 248/248 on OptiFine_1.12.2_HD_U_G5, docs/PHASE3.md section 1.2). A real 1.12.2 launch measured
     * that the game directory is the instance directory while the vanilla jar lives in a shared
     * {@code versions/} one or more levels above it, so the search walks ancestors and the class path.
     */
    public static Path findBaseJar(Path gameDir, String override, StringBuilder notes) throws IOException {
        return findBaseJar(gameDir, detectMcVersion(gameDir), override, notes);
    }

    /**
     * Discovery order: explicit {@code -Doptiforge.vanilla} first (an operator override must win, and it
     * was the working workaround in the 1.12.2 launch), then gameDir versions, ancestors (up to 4),
     * {@code java.class.path}, then the newest-profile last resort.
     */
    public static Path findBaseJar(Path gameDir, String mc, String vanillaOverride, StringBuilder notes)
            throws IOException {
        if (vanillaOverride != null && !vanillaOverride.isEmpty()) {
            Path path = Paths.get(vanillaOverride);
            if (Files.isRegularFile(path)) {
                notes.append("base jar from -Doptiforge.vanilla: ").append(path).append('\n');
                return path;
            }
            notes.append("ignoring -Doptiforge.vanilla (not a file): ").append(path).append('\n');
        }
        if (mc != null) {
            Path direct = versionsJar(gameDir, mc);
            if (direct != null) {
                notes.append("base jar from version id ").append(mc).append(": ").append(direct).append('\n');
                return direct;
            }
            Path root = gameDir;
            for (int level = 1; level <= 4; level++) {
                root = root == null ? null : root.getParent();
                if (root == null) {
                    break;
                }
                Path candidate = versionsJar(root, mc);
                if (candidate != null) {
                    notes.append("base jar from ancestor versions (").append(level).append(" up): ")
                            .append(candidate).append('\n');
                    return candidate;
                }
            }
            Path fromClassPath = classPathJar(mc);
            if (fromClassPath != null) {
                notes.append("base jar from java.class.path: ").append(fromClassPath).append('\n');
                return fromClassPath;
            }
        }
        // Last resort, and the only case where the version is not needed: the newest non-Forge profile
        // under the instance's own versions dir (or one of its ancestors).
        Path newest = newestVersionJar(gameDir);
        if (newest != null) {
            notes.append("base jar from newest non-Forge profile: ").append(newest).append('\n');
        }
        return newest;
    }

    private static Path versionsJar(Path root, String mc) {
        if (root == null) {
            return null;
        }
        Path candidate = root.resolve(VERSION_DIR[0]).resolve(mc).resolve(mc + ".jar");
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    /**
     * A jar on {@code java.class.path} whose name starts with the MC version.
     *
     * <p>Measured in the 1.12.2 rig: the launcher puts a profile jar named
     * {@code 1.12.2-forge-14.23.5.2860.jar} on the class path, while the shared vanilla jar
     * ({@code versions/1.12.2/1.12.2.jar}) is not an ancestor of the game directory. The name is only a
     * hint, so the file is validated by content: a readable zip with more than 500 class entries
     * (1.12.2's client jar has ~2000; Forge's universal jar has none).
     */
    private static Path classPathJar(String mc) {
        String classPath = System.getProperty("java.class.path", "");
        Path prefixed = null;
        for (String entry : classPath.split(java.io.File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            try {
                Path path = Paths.get(entry);
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                boolean candidate = name.equals(mc + ".jar") || name.startsWith(mc);
                if (!candidate || !looksLikeClientJar(path)) {
                    continue;
                }
                if (name.equals(mc + ".jar")) {
                    return path;
                }
                if (prefixed == null) {
                    prefixed = path;
                }
            } catch (RuntimeException ignored) {
                // an unparsable class path entry cannot be the jar we are looking for
            }
        }
        return prefixed;
    }

    /** True when the file is a readable zip with more than 500 class entries (a client jar, not a mod). */
    static boolean looksLikeClientJar(Path jar) {
        java.util.zip.ZipFile zip = null;
        try {
            zip = new java.util.zip.ZipFile(jar.toFile());
            int classes = 0;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().endsWith(".class")) {
                    classes++;
                    if (classes > 500) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(zip);
        }
    }

    private static Path newestVersionJar(Path gameDir) throws IOException {
        Path root = gameDir;
        for (int level = 0; level <= 2 && root != null; level++) {
            Path versions = root.resolve(VERSION_DIR[0]);
            if (Files.isDirectory(versions)) {
                Path best = null;
                long bestStamp = -1L;
                DirectoryStream<Path> stream = Files.newDirectoryStream(versions);
                try {
                    for (Path dir : stream) {
                        String name = dir.getFileName().toString();
                        if (!Files.isDirectory(dir) || name.toLowerCase(java.util.Locale.ROOT).contains("forge")) {
                            continue;
                        }
                        if (!name.matches("\\d+\\.\\d+(\\.\\d+)?")) {
                            continue;
                        }
                        Path jar = dir.resolve(name + ".jar");
                        if (!Files.isRegularFile(jar)) {
                            continue;
                        }
                        long stamp = Files.getLastModifiedTime(jar).toMillis();
                        if (stamp > bestStamp) {
                            bestStamp = stamp;
                            best = jar;
                        }
                    }
                } finally {
                    stream.close();
                }
                if (best != null) {
                    return best;
                }
            }
            root = root.getParent();
        }
        return null;
    }

    /**
     * The Minecraft version, in the order that a real 1.12.2 launch needed (Lead, Forge 2860 + JRE 8):
     * {@code -Doptiforge.mcversion}, then {@code FMLInjectionData.mccversion}, then {@code --version} on
     * the launch command line, then a scan of the version directories.
     *
     * <p>{@code injectData} runs before FML populates {@code FMLInjectionData}, so the first two sources
     * are empty there; the launch command line already carries the profile id
     * ({@code --version 1.12.2-forge-14.23.5.2860}) and is the source that works that early.
     */
    public static String detectMcVersion(Path gameDir) {
        return detectMcVersion(gameDir, null, new StringBuilder());
    }

    public static String detectMcVersion(Path gameDir, String override, StringBuilder notes) {
        if (override != null && !override.trim().isEmpty()) {
            notes.append("mc version from -Doptiforge.mcversion: ").append(override.trim()).append('\n');
            return override.trim();
        }
        String fromInjection = mcVersionFromInjectionData();
        if (fromInjection != null) {
            notes.append("mc version from FMLInjectionData: ").append(fromInjection).append('\n');
            return fromInjection;
        }
        String fromCommand = mcVersionFromLaunchCommand(System.getProperty("sun.java.command", ""));
        if (fromCommand != null) {
            notes.append("mc version from sun.java.command --version: ").append(fromCommand).append('\n');
            return fromCommand;
        }
        Path root = gameDir;
        for (int level = 0; level <= 2 && root != null; level++) {
            String scanned = scanVersions(root);
            if (scanned != null) {
                notes.append("mc version from versions dir (")
                        .append(level == 0 ? "gameDir" : level + " up").append("): ")
                        .append(scanned).append('\n');
                return scanned;
            }
            root = root.getParent();
        }
        notes.append("mc version unknown: no -Doptiforge.mcversion, FMLInjectionData, --version"
                + " or versions dir\n");
        return null;
    }

    /** {@code FMLInjectionData.mccversion} via reflection; null until FML has built the injection data. */
    public static String mcVersionFromInjectionData() {
        try {
            Class<?> injection = Class.forName("net.minecraftforge.fml.relauncher.FMLInjectionData");
            Field field = injection.getField("mccversion");
            Object value = field.get(null);
            if (value instanceof String && !((String) value).isEmpty()) {
                return (String) value;
            }
        } catch (Throwable ignored) {
            // not on Forge (offline probe), or FMLInjectionData not populated yet
        }
        return null;
    }

    /**
     * {@code --version} from {@code sun.java.command}, e.g.
     * {@code ... --version 1.12.2-forge-14.23.5.2860 ...} gives {@code 1.12.2}.
     *
     * <p>The profile id is the launcher's version id, not a Minecraft version, so the suffix is cut at
     * {@code -forge}/{@code _forge} (case-insensitive) and the rest must look like a version.
     */
    public static String mcVersionFromLaunchCommand(String command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        String[] parts = command.trim().split("\\s+");
        for (int i = 0; i + 1 < parts.length; i++) {
            if (!"--version".equals(parts[i])) {
                continue;
            }
            String value = parts[i + 1];
            if (value.startsWith("--")) {
                continue;
            }
            String version = stripProfileSuffix(value);
            if (version != null) {
                return version;
            }
        }
        return null;
    }

    private static String stripProfileSuffix(String value) {
        String name = value;
        int cut = indexOfIgnoreCase(name, "-forge");
        if (cut < 0) {
            cut = indexOfIgnoreCase(name, "_forge");
        }
        if (cut > 0) {
            name = name.substring(0, cut);
        }
        return name.matches("\\d+\\.\\d+(\\.\\d+)?") ? name : null;
    }

    private static int indexOfIgnoreCase(String value, String needle) {
        int max = value.length() - needle.length();
        for (int i = 0; i <= max; i++) {
            if (value.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    private static String scanVersions(Path root) {
        Path versions = root.resolve(VERSION_DIR[0]);
        String best = null;
        long bestStamp = -1L;
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(versions);
            try {
                for (Path dir : stream) {
                    String name = dir.getFileName().toString();
                    if (!name.matches("\\d+\\.\\d+(\\.\\d+)?")) {
                        continue;
                    }
                    long stamp = Files.getLastModifiedTime(dir).toMillis();
                    if (stamp > bestStamp) {
                        bestStamp = stamp;
                        best = name;
                    }
                }
            } finally {
                stream.close();
            }
        } catch (IOException ignored) {
            // no versions dir under this root
        }
        return best;
    }

    /** OptiFine's own server test, copied from {@code LaunchUtils.isForgeServer} bytecode. */
    public static boolean isServer() {
        try {
            Class<?> launch = Class.forName("net.minecraft.launchwrapper.Launch");
            Field blackboard = launch.getField("blackboard");
            Object board = blackboard.get(null);
            Object args = ((Map<?, ?>) board).get("launchArgs");
            String token = (String) ((Map<?, ?>) args).get("--accessToken");
            String version = (String) ((Map<?, ?>) args).get("--version");
            return token == null && "UnknownFMLProfile".equals(version);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The Forge universal jar of the install (probe only). */
    public static Path findForgeJar(Path mcRoot) throws IOException {
        return newest(mcRoot.resolve("libraries").resolve("net").resolve("minecraftforge").resolve("forge"),
                "forge-", ".jar", "-installer");
    }

    /** {@code lzma-0.0.1.jar}, the library FML uses to read binpatches. */
    public static Path findLzmaJar(Path mcRoot) throws IOException {
        return newest(mcRoot.resolve("libraries").resolve("lzma").resolve("lzma"), "lzma-", ".jar", null);
    }

    /** {@code asm-debug-all-5.2.jar}; the ASM FML 1.12.2 itself runs (probe only). */
    public static Path findAsmJar(Path mcRoot) throws IOException {
        return newest(mcRoot.resolve("libraries").resolve("org").resolve("ow2").resolve("asm"), "asm-", ".jar", null);
    }

    /** {@code launchwrapper-1.12.jar} (probe only; the legacy jar compiles against it). */
    public static Path findLaunchWrapperJar(Path mcRoot) throws IOException {
        return newest(mcRoot.resolve("libraries").resolve("net").resolve("minecraft").resolve("launchwrapper"),
                "launchwrapper-", ".jar", null);
    }

    private static Path newest(Path root, String prefix, String suffix, String excludedContains) throws IOException {
        Path best = null;
        long bestStamp = -1L;
        if (!Files.isDirectory(root)) {
            return null;
        }
        List<Path> stack = new ArrayList<Path>();
        stack.add(root);
        while (!stack.isEmpty()) {
            Path dir = stack.remove(stack.size() - 1);
            DirectoryStream<Path> stream = Files.newDirectoryStream(dir);
            try {
                for (Path path : stream) {
                    if (Files.isDirectory(path)) {
                        stack.add(path);
                        continue;
                    }
                    String name = path.getFileName().toString();
                    if (!name.startsWith(prefix) || !name.endsWith(suffix)) {
                        continue;
                    }
                    if (excludedContains != null && name.contains(excludedContains)) {
                        continue;
                    }
                    long stamp = Files.getLastModifiedTime(path).toMillis();
                    if (stamp > bestStamp) {
                        bestStamp = stamp;
                        best = path;
                    }
                }
            } finally {
                stream.close();
            }
        }
        return best;
    }

    /** Deterministic listing for diagnose reports. */
    public static String sortByName(List<Path> paths) {
        java.util.Collections.sort(paths, new Comparator<Path>() {
            public int compare(Path a, Path b) {
                return a.toString().compareTo(b.toString());
            }
        });
        StringBuilder out = new StringBuilder();
        for (Path path : paths) {
            out.append(path).append('\n');
        }
        return out.toString();
    }

    static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // closing a zip file cannot fail in a useful way here
            }
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
