package kynarain.cn.optiforge.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The pack: OptiFine's payload applied to the obfuscated client jar, cached under
 * {@code <game>/optiforge/pack/legacy-v1-<key>/}, plus the classpath jar that carries OptiFine's own
 * classes and assets.
 *
 * <p>Why the payload base is the vanilla jar: OptiFine's own transformer re-reads the base resource from
 * the class path and ignores the bytes it is handed (measured from
 * {@code optifine.OptiFineClassTransformer.transform} bytecode, docs/PHASE3.md §1.1), and all 248 deltas
 * reproduce their recorded md5 against the vanilla jar (248/248). Forge's binpatches are applied later
 * by FML's own {@code PatchingTransformer}, which is registered before any coremod transformer and calls
 * {@code System.exit(1)} when the adler32 of its input does not match the patch - so patched game
 * classes must never be put on the class path; they are delivered through
 * {@link LegacyTransformer} only.
 *
 * <p>The staged jar therefore contains {@code net/optifine/**} (already in the runtime namespace: 0 SRG
 * tokens measured) and {@code assets/**}; no game class.
 */
public final class LegacyPack {

    /** Bump when the pack contents change, so old caches are rebuilt (same rule as the modern pipeline). */
    private static final String PACK_VERSION = "legacy-v1";
    private static final int PACK_FORMAT_1_12 = 3;

    private final Path gameDir;
    private final Path optifineJar;
    private final Path baseJar;
    private final Path cacheDir;
    private final Path stagedJar;
    private final String key;
    private final Map<String, byte[]> patched;
    private final Map<String, byte[]> ownClasses;
    private final int patchCount;
    private final java.util.Set<String> overrideNames;
    private final int overrideCount;
    private final int md5Ok;
    private int md5Mismatch;
    private int md5Failure;

    private LegacyPack(Path gameDir, Path optifineJar, Path baseJar, Path cacheDir, Path stagedJar,
                       String key, Map<String, byte[]> patched, Map<String, byte[]> ownClasses,
                       int patchCount, java.util.Set<String> overrideNames, int md5Ok, int md5Mismatch,
                       int md5Failure) {
        this.gameDir = gameDir;
        this.optifineJar = optifineJar;
        this.baseJar = baseJar;
        this.cacheDir = cacheDir;
        this.stagedJar = stagedJar;
        this.key = key;
        this.patched = patched;
        this.ownClasses = ownClasses;
        this.patchCount = patchCount;
        this.overrideNames = overrideNames;
        this.overrideCount = overrideNames.size();
        this.md5Ok = md5Ok;
        this.md5Mismatch = md5Mismatch;
        this.md5Failure = md5Failure;
    }

    public Path gameDir() {
        return gameDir;
    }

    public Path optifineJar() {
        return optifineJar;
    }

    public Path baseJar() {
        return baseJar;
    }

    public Path cacheDir() {
        return cacheDir;
    }

    public Path stagedJar() {
        return stagedJar;
    }

    public String key() {
        return key;
    }

    public int patchCount() {
        return patchCount;
    }

    /**
     * Complete game-class files the jar shipped instead of an xdelta (measured: {@code awu$Builder},
     * the {@code BlockStateContainer$Builder} vanilla class compiled in Forge's MCP environment). They are
     * delivered through the transformer like patched classes.
     */
    public int overrideCount() {
        return overrideCount;
    }

    /** Obfuscated internal names of the complete-override classes ({@code awu$Builder} on 1.12.2). */
    public java.util.Set<String> overrideNames() {
        return overrideNames;
    }

    public int md5Ok() {
        return md5Ok;
    }

    public int md5Mismatch() {
        return md5Mismatch;
    }

    public int md5Failure() {
        return md5Failure;
    }

    public Map<String, byte[]> ownClasses() {
        return ownClasses;
    }

    /** Packed class bytes for a launch-wrapper name (dotted); keys are obfuscated internal names. */
    public byte[] patched(String name) {
        if (name == null) {
            return null;
        }
        return patched.get(name.replace('.', '/'));
    }

    /** Packed class bytes by obfuscated internal name ({@code aki}, {@code buk$a}); probe and tests. */
    public byte[] patchedBytes(String internalName) {
        return patched.get(internalName);
    }

    /** The obfuscated internal names the payload patches, sorted. */
    public List<String> patchedNames() {
        List<String> names = new ArrayList<String>(patched.keySet());
        java.util.Collections.sort(names);
        return names;
    }

    public int patchedSize() {
        int total = 0;
        for (byte[] bytes : patched.values()) {
            total += bytes.length;
        }
        return total;
    }

    /**
     * Builds (or reuses) the pack for one OptiFine jar and one base jar.
     *
     * <p>The cache key covers both inputs plus {@link #PACK_VERSION}; the cache itself stores the patched
     * bytes, so a second launch only has to hash the inputs.
     */
    public static LegacyPack prepare(Path gameDir, Path optifineJar, Path baseJar, StringBuilder notes)
            throws IOException {
        Files.createDirectories(gameDir);
        String key = PACK_VERSION + "-" + sha256(optifineJar, 8) + "-" + sha256(baseJar, 8);
        Path cacheRoot = gameDir.resolve("optiforge").resolve("pack").resolve(key);
        Path classesDir = cacheRoot.resolve("classes");
        Path stagedJar = gameDir.resolve("mods").resolve(stagedJarName());
        Files.createDirectories(classesDir);
        Files.createDirectories(stagedJar.getParent());

        LegacyPayload payload = new LegacyPayload(optifineJar);
        try {
            LegacyPayload.Layout layout = payload.layout();
            if (layout != LegacyPayload.Layout.FLAT) {
                throw new IOException("unsupported OptiFine payload layout: " + layout
                        + " (this legacy artifact implements the flat patch/<obf>.class.xdelta layout"
                        + " used by 1.8-1.12.2; measured on 1.12.2 HD_U_G5)");
            }
            List<String> targets = payload.flatTargets();
            Map<String, byte[]> patched = new LinkedHashMap<String, byte[]>();
            int md5Ok = 0;
            int md5Mismatch = 0;
            int md5Failure = 0;
            List<String> failures = new ArrayList<String>();

            ZipFile base = new ZipFile(baseJar.toFile());
            try {
                Object provider = payload.newProvider(base);
                for (String classFileName : targets) {
                    byte[] bytes;
                    try {
                        bytes = payload.apply(provider, classFileName);
                    } catch (IOException e) {
                        md5Failure++;
                        if (failures.size() < 10) {
                            failures.add(classFileName + " : " + e.getMessage());
                        }
                        continue;
                    }
                    String expected = payload.expectedMd5(classFileName);
                    String actual = md5(bytes);
                    if (!expected.equals(actual)) {
                        md5Mismatch++;
                        if (failures.size() < 10) {
                            failures.add(classFileName + " : md5 " + actual + " != " + expected);
                        }
                        continue;
                    }
                    md5Ok++;
                    // keys are obfuscated internal names ("aki", "buk$a"), which is what launchwrapper
                    // hands to a class transformer in 1.12.2 (game classes keep obfuscated names, measured)
                    String internal = classFileName.substring(0, classFileName.length() - ".class".length());
                    patched.put(internal, bytes);
                }
            } finally {
                base.close();
            }
            if (!failures.isEmpty()) {
                notes.append("payload failures:\n");
                for (String failure : failures) {
                    notes.append("  ").append(failure).append('\n');
                }
            }
            // Complete class files the jar ships for game classes (measured: awu$Builder). They go into the
            // same patched map because the transformer delivers them; they have no md5 and are counted
            // separately from the xdelta patches.
            Map<String, byte[]> overrides = payload.completeOverrides();
            for (Map.Entry<String, byte[]> entry : overrides.entrySet()) {
                patched.put(entry.getKey(), entry.getValue());
            }
            Map<String, byte[]> staged = payload.stagedClasses();
            LegacyPack pack = new LegacyPack(gameDir, optifineJar, baseJar, cacheRoot, stagedJar, key,
                    patched, staged, md5Ok, overrides.keySet(), md5Ok, md5Mismatch, md5Failure);
            pack.writeCache(classesDir);
            pack.writeStagedJar();
            pack.writeManifest(classesDir.getParent());
            notes.append("complete overrides: ").append(overrides.size()).append('\n');
            notes.append("staged classes: ").append(staged.size()).append('\n');
            notes.append("cache: ").append(cacheRoot).append('\n');
            notes.append("staged jar: ").append(stagedJar).append('\n');
            return pack;
        } finally {
            payload.close();
        }
    }

    /**
     * Lazy singleton for the runtime: {@code injectData} only records the request, and the pack is built on
     * the first class load - by then {@code FMLInjectionData} is populated (measured in a real 1.12.2
     * launch, see {@link LegacyBootstrap}).
     */
    public static LegacyPack current() {
        return LegacyBootstrap.current();
    }

    private void writeCache(Path classesDir) throws IOException {
        // The cache is a convenience, not a requirement: the next process rebuilds it if this fails.
        for (Map.Entry<String, byte[]> entry : patched.entrySet()) {
            Path file = classesDir.resolve(entry.getKey().replace('/', '_') + ".class");
            Files.write(file, entry.getValue());
        }
    }

    private void writeManifest(Path cacheRoot) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("key=").append(key).append('\n');
        text.append("optifine=").append(optifineJar).append('\n');
        text.append("base=").append(baseJar).append('\n');
        text.append("layout=flat\n");
        text.append("patches=").append(patchCount).append('\n');
        text.append("overrides=").append(overrideCount).append('\n');
        text.append("md5_ok=").append(md5Ok).append('\n');
        text.append("md5_mismatch=").append(md5Mismatch).append('\n');
        text.append("md5_failure=").append(md5Failure).append('\n');
        text.append("staged_classes=").append(ownClasses.size()).append('\n');
        Files.write(cacheRoot.resolve("manifest.txt"), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The classpath jar: OptiFine's own classes (including {@code Config.class}, which this build ships in
     * the default package and {@code net/optifine/**} references as bare {@code Config}), the two
     * resource-locator contracts, its assets and a {@code pack.mcmeta}.
     *
     * <p>It deliberately contains no game class - not even the complete override {@code awu$Builder} that
     * the payload ships at its root: such a class would be read by {@code LaunchClassLoader} and handed to
     * FML's {@code PatchingTransformer}, whose adler32 check only accepts the untouched vanilla bytes and
     * calls {@code System.exit(1)} otherwise (measured, docs/PHASE3.md §2).
     */
    private void writeStagedJar() throws IOException {
        Path temp = stagedJar.resolveSibling(stagedJar.getFileName() + ".tmp");
        Files.deleteIfExists(temp);
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp));
        try {
            put(out, "META-INF/MANIFEST.MF", (""
                    + "Manifest-Version: 1.0\r\n"
                    + "Implementation-Title: OptiForge OptiFine classes (legacy)\r\n"
                    + "Implementation-Version: " + version() + "\r\n"
                    + "Implementation-Vendor: Kynarain\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> entry : ownClasses.entrySet()) {
                put(out, entry.getKey(), entry.getValue());
            }
            LegacyPayload payload = new LegacyPayload(optifineJar);
            try {
                for (Map.Entry<String, byte[]> entry : payload.assets().entrySet()) {
                    put(out, entry.getKey(), entry.getValue());
                }
            } finally {
                payload.close();
            }
            String mcmeta = "{\"pack\":{\"description\":\"OptiForge OptiFine assets (legacy)\","
                    + "\"pack_format\":" + PACK_FORMAT_1_12 + "}}";
            put(out, "pack.mcmeta", mcmeta.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        Files.move(temp, stagedJar, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void put(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }

    /** File name of the staged jar; the modern pipeline uses {@code OptiForge-<ver>-classes.jar}. */
    public static String stagedJarName() {
        return "OptiForge-" + version() + "-optifine.jar";
    }

    /** Version written into the legacy jar by {@code tools/build-legacy.ps1}. */
    public static String version() {
        try {
            InputStream in = LegacyPack.class.getResourceAsStream("/optiforge/version.txt");
            if (in != null) {
                try {
                    return new String(LegacyPaths.readAll(in), StandardCharsets.UTF_8).trim();
                } finally {
                    in.close();
                }
            }
        } catch (IOException ignored) {
            // fall through to the unknown marker
        }
        return "0.0.0-unknown";
    }

    public String summary() {
        return "layout=flat / patches=" + patchCount
                + " (+" + overrideCount + " complete overrides)"
                + " / md5=" + md5Ok + " ok, " + md5Mismatch + " mismatch, " + md5Failure + " failure"
                + " / staged=" + ownClasses.size() + " classes"
                + " / cache=" + cacheDir
                + " / jar=" + stagedJar.getFileName() + " (" + (filesize(stagedJar)) + " B)";
    }

    private static long filesize(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    /** Writes the answer to "what did the loader see", next to the pack, like the modern diagnose.txt. */
    public void writeDiagnose(Path gameDir, String text) {
        try {
            Path out = gameDir.resolve("optiforge").resolve("diagnose-legacy.txt");
            Files.createDirectories(out.getParent());
            Files.write(out, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // a diagnose file that cannot be written must not break the launch
        }
    }

    static String sha256(Path file, int prefixChars) throws IOException {
        return digest(file, "SHA-256", prefixChars);
    }

    static String sha1(Path file, int prefixChars) throws IOException {
        return digest(file, "SHA-1", prefixChars);
    }

    private static String digest(Path file, String algorithm, int prefixChars) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            InputStream in = Files.newInputStream(file);
            try {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    md.update(buffer, 0, read);
                }
            } finally {
                in.close();
            }
            StringBuilder builder = new StringBuilder();
            for (byte b : md.digest()) {
                builder.append(String.format(java.util.Locale.ROOT, "%02x", b));
            }
            String hex = builder.toString();
            return hex.length() > prefixChars ? hex.substring(0, prefixChars) : hex;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(algorithm + " is missing", e);
        }
    }

    static String md5(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder builder = new StringBuilder();
            for (byte b : md.digest(bytes)) {
                builder.append(String.format(java.util.Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
