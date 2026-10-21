package kynarain.cn.optiforge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs OptiFine's own patcher, offline, against a base jar we choose.
 *
 * <p>OptiFine ships {@code optifine.Patcher} in every build, and its constant pool contains no
 * ModLauncher, Forge or Minecraft reference (measured in {@code docs/DESIGN.md} §3), so the class can
 * be loaded out of the OptiFine jar and driven directly: configuration map from the OptiFine zip,
 * patch bytes from {@code patch/srg/<class>.class.xdelta}, base bytes from whatever jar we hand it.
 *
 * <p>Nothing is loaded from the base jar - only bytes - so this works on the obfuscated vanilla jar
 * (where {@code patch2.cfg} supplies the obfuscated entry name) and on Forge's remapped client jar
 * (where the logical name is the entry name) without touching either.
 *
 * <p>This is the M1 seam: it answers "does this build's payload apply to this base at all, and what
 * does the result look like" before any transformer exists.
 */
public final class OptifinePatcher implements AutoCloseable {

    /** The class loader feeding OptiFine's own classes; platform parent, so no Forge/Minecraft leaks in. */
    private final URLClassLoader loader;
    private final Class<?> patcherClass;
    private final Class<?> providerClass;
    private final Method getConfigurationMap;
    private final Method getConfigurationPatterns;
    private final Method applyPatch;
    private final Path optifineJar;
    private final Mode mode;

    /**
     * How this OptiFine build names its payload. {@code SRG} (1.14+) has {@code patch/srg/<official
     * name>.class.xdelta} and applies to the vanilla jar by mapping through {@code patch2.cfg};
     * {@code NOTCH} (1.13.2 and older) has flat {@code patch/<obf name>.class.xdelta} entries and its
     * own classes at the jar root. The patched bytes of a NOTCH payload are in the obfuscated
     * namespace and need {@link ObfNamespaceRemap} before the Forge runtime can load them.
     */
    public enum Mode {SRG, NOTCH}

    public OptifinePatcher(Path optifineJar) throws IOException {
        this.optifineJar = optifineJar;
        this.loader = new URLClassLoader(new URL[]{optifineJar.toUri().toURL()},
                J8.platformOrBootstrap());
        try {
            this.patcherClass = loader.loadClass("optifine.Patcher");
            this.providerClass = loader.loadClass("optifine.IResourceProvider");
            this.getConfigurationMap = patcherClass.getMethod("getConfigurationMap", ZipFile.class);
            this.getConfigurationPatterns = patcherClass.getMethod("getConfigurationPatterns", Map.class);
            this.applyPatch = patcherClass.getMethod("applyPatch", String.class, byte[].class,
                    Pattern[].class, Map.class, providerClass);
            this.mode = detectMode(optifineJar);
        } catch (ReflectiveOperationException e) {
            throw new IOException("OptiFine's patcher API is not where it should be in " + optifineJar, e);
        }
    }

    /** @return {@code SRG} when a {@code patch/srg/} payload exists, {@code NOTCH} for flat 1.13-style ones. */
    private static Mode detectMode(Path optifineJar) throws IOException {
        boolean flat = false;
        try (ZipFile zip = new ZipFile(optifineJar.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("patch/srg/") && name.endsWith(".class.xdelta")) return Mode.SRG;
                if (name.startsWith("patch/") && name.endsWith(".class.xdelta")
                        && name.indexOf('/', "patch/".length()) < 0) {
                    flat = true;
                }
            }
        }
        return flat ? Mode.NOTCH : Mode.SRG;
    }

    public Mode mode() {
        return mode;
    }

    private String payloadPath(String logicalName) {
        return mode == Mode.SRG
                ? "patch/srg/" + logicalName + ".class.xdelta"
                : "patch/" + logicalName + ".class.xdelta";
    }

    private String md5Path(String logicalName) {
        return mode == Mode.SRG
                ? "patch/srg/" + logicalName + ".class.md5"
                : "patch/" + logicalName + ".class.md5";
    }

    private String targetName(String logicalName) {
        return mode == Mode.SRG ? "srg/" + logicalName + ".class" : logicalName + ".class";
    }

    /** Configuration map from the OptiFine zip (the {@code patch.cfg} / {@code patch2.cfg} / {@code patch3.cfg} rules). */
    @SuppressWarnings("unchecked")
    public Map<String, String> configurationMap() throws IOException {
        try (ZipFile zip = new ZipFile(optifineJar.toFile())) {
            return (Map<String, String>) getConfigurationMap.invoke(null, zip);
        } catch (ReflectiveOperationException e) {
            throw unwrap("getConfigurationMap", e);
        }
    }

    public Pattern[] patterns(Map<String, String> configuration) throws IOException {
        try {
            return (Pattern[]) getConfigurationPatterns.invoke(null, configuration);
        } catch (ReflectiveOperationException e) {
            throw unwrap("getConfigurationPatterns", e);
        }
    }

    /**
     * Applies one logical class's patch to {@code baseJar}.
     *
     * @param logicalName e.g. {@code net/minecraft/CrashReport} (the name between {@code patch/srg/}
     *                    and {@code .class.xdelta})
     * @return the patched class bytes
     */
    public byte[] apply(String logicalName, Path baseJar) throws IOException {
        Map<String, String> configuration = configurationMap();
        Pattern[] patterns = patterns(configuration);
        byte[] delta = readEntry(optifineJar, payloadPath(logicalName));
        try (ZipFile base = new ZipFile(baseJar.toFile())) {
            Object provider;
            try {
                provider = loader.loadClass("optifine.ZipResourceProvider")
                        .getConstructor(ZipFile.class).newInstance(base);
            } catch (ReflectiveOperationException e) {
                throw unwrap("ZipResourceProvider", e);
            }
            try {
                return (byte[]) applyPatch.invoke(null, targetName(logicalName), delta,
                        patterns, configuration, provider);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new IOException("patcher refused " + logicalName + " against " + baseJar.getFileName()
                        + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
            } catch (ReflectiveOperationException e) {
                throw unwrap("applyPatch", e);
            }
        }
    }

    /** Expected MD5 of the patched result, as OptiFine records it ({@code .class.md5}). */
    public String expectedMd5(String logicalName) throws IOException {
        byte[] raw = readEntry(optifineJar, md5Path(logicalName));
        return new String(raw, StandardCharsets.US_ASCII).trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Opens a base jar as the patcher's resource provider. Kept public so a whole-payload run can open
     * the base once instead of once per class.
     */
    public Object newResourceProvider(ZipFile base) throws IOException {
        try {
            return loader.loadClass("optifine.ZipResourceProvider")
                    .getConstructor(ZipFile.class).newInstance(base);
        } catch (ReflectiveOperationException e) {
            throw unwrap("ZipResourceProvider", e);
        }
    }

    /** Applies one payload entry with a provider opened by {@link #newResourceProvider(ZipFile)}. */
    public byte[] applyWith(Object provider, Pattern[] patterns, Map<String, String> configuration,
                            String logicalName) throws IOException {
        byte[] delta = readEntry(optifineJar, payloadPath(logicalName));
        try {
            return (byte[]) applyPatch.invoke(null, targetName(logicalName), delta,
                    patterns, configuration, provider);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException(cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException e) {
            throw unwrap("applyPatch", e);
        }
    }

    /**
     * Which resource (non-class) payload targets the jar carries, e.g.
     * {@code assets/minecraft/optifine/ctm/default/10_glass_purple/0.png}. These are deltas against the
     * vanilla asset files, which is why {@link #applyAssetWith} needs the {@code client-*-extra.jar}.
     */
    public java.util.List<String> assetTargets() throws IOException {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (ZipFile zip = new ZipFile(optifineJar.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("patch/assets/") || !name.endsWith(".xdelta")) continue;
                out.add(name.substring("patch/".length(), name.length() - ".xdelta".length()));
            }
        }
        return out;
    }

    /** Applies one asset payload entry; the provider must be the vanilla assets jar. */
    public byte[] applyAssetWith(Object provider, Pattern[] patterns, Map<String, String> configuration,
                                 String target) throws IOException {
        byte[] delta = readEntry(optifineJar, "patch/" + target + ".xdelta");
        try {
            return (byte[]) applyPatch.invoke(null, target, delta, patterns, configuration, provider);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException(cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException e) {
            throw unwrap("applyPatch", e);
        }
    }

    /** Which logical names the jar carries a payload for, in jar order. */
    public Map<String, Integer> payload() throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        String prefix = mode == Mode.SRG ? "patch/srg/" : "patch/";
        try (ZipFile zip = new ZipFile(optifineJar.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.startsWith(prefix) || !name.endsWith(".class.xdelta")) continue;
                if (mode == Mode.NOTCH && name.indexOf('/', prefix.length()) >= 0) continue;
                String logical = name.substring(prefix.length(), name.length() - ".class.xdelta".length());
                out.put(logical, (int) e.getSize());
            }
        }
        return out;
    }

    /**
     * What member namespace the payload emits, measured by actually applying it.
     *
     * <p>This is the fact that decides whether a build can run on a modern Forge: {@code 1.20.x} and
     * {@code 1.21} payloads produce <b>SRG</b> member names ({@code m_127521_}) while the Forge runtime
     * of 1.20.6+ is <b>official</b> names ({@code forThrowable}). A payload that emits SRG names cannot
     * be used against an official-name runtime without a remap, which is exactly what "not compatible
     * with Forge" turns out to mean for the 1.21 line.
     */
    public static final class Namespace {
        public int sampled;
        public int applied;
        public int md5Mismatch;
        public int failed;
        public int srgMemberTokens;
        /** {@code func_}/{@code field_} ids: the MCP/SRG spelling used by the 1.13-1.16 runtime. */
        public int mcpMemberTokens;
        public int officialMemberTokens;
        public final java.util.List<String> failures = new java.util.ArrayList<>();

        /** True when the sample shows modern SRG member names ({@code m_}/{@code f_}) in the output. */
        public boolean srg() {
            return srgMemberTokens > 0;
        }

        /**
         * True when the sample shows {@code func_}/{@code field_} member names. That is the
         * 1.13-1.16 (MCP/SRG) spelling and it already matches the Forge runtime of those lines, so it
         * behaves like the SRG case for the remap decision: no remap.
         */
        public boolean mcp() {
            return mcpMemberTokens > 0;
        }

        public String describe() {
            return sampled + " sampled, " + applied + " applied, " + md5Mismatch + " md5-mismatch, "
                    + failed + " failed; srg tokens " + srgMemberTokens
                    + ", mcp tokens " + mcpMemberTokens
                    + ", official tokens " + officialMemberTokens
                    + (srg() ? " -> SRG namespace" : mcp() ? " -> MCP/SRG func_/field_ namespace"
                            : " -> official namespace");
        }
    }

    public Namespace namespace(Path baseJar, int sampleLimit) throws IOException {
        Namespace stats = new Namespace();
        Map<String, Integer> payload = payload();
        int index = 0;
        try (ZipFile base = new ZipFile(baseJar.toFile())) {
            Object provider;
            try {
                provider = loader.loadClass("optifine.ZipResourceProvider")
                        .getConstructor(ZipFile.class).newInstance(base);
            } catch (ReflectiveOperationException e) {
                throw unwrap("ZipResourceProvider", e);
            }
            Map<String, String> configuration = configurationMap();
            Pattern[] patterns = patterns(configuration);

            for (String logical : payload.keySet()) {
                if (index >= sampleLimit) break;
                index++;
                stats.sampled++;
                byte[] delta = readEntry(optifineJar, payloadPath(logical));
                byte[] patched;
                try {
                    patched = (byte[]) applyPatch.invoke(null, "srg/" + logical + ".class", delta,
                            patterns, configuration, provider);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    stats.failed++;
                    if (stats.failures.size() < 10) {
                        stats.failures.add(logical + " : " + cause.getMessage());
                    }
                    continue;
                } catch (ReflectiveOperationException e) {
                    throw unwrap("applyPatch", e);
                }
                String expected = expectedMd5(logical);
                if (!expected.equals(sha(patched, "MD5"))) {
                    stats.md5Mismatch++;
                    continue;
                }
                stats.applied++;
                int[] tokens = memberTokens(patched);
                stats.srgMemberTokens += tokens[0];
                stats.mcpMemberTokens += tokens[1];
                stats.officialMemberTokens += tokens[2];
            }
        }
        return stats;
    }

    /**
     * Counts member-name spellings in a class file: modern SRG ({@code m_123_}/{@code f_123_}), the
     * older MCP/SRG spelling used by 1.13-1.16 ({@code func_123_}/{@code field_123_}), and plain
     * official names. Returned as {@code [modernSrg, mcp, official]}.
     */
    static int[] memberTokens(byte[] classBytes) {
        int srg = 0;
        int mcp = 0;
        int official = 0;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(classBytes))) {
            if (in.readInt() != 0xCAFEBABE) return new int[]{0, 0, 0};
            in.readUnsignedShort();
            in.readUnsignedShort();
            int cpCount = in.readUnsignedShort();
            String[] utf8 = new String[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1:
                        utf8[i] = new String(J8.readN(in, in.readUnsignedShort()),
                                java.nio.charset.StandardCharsets.UTF_8);
                        break;
                    case 7:
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        in.skipBytes(2);
                        break;
                    case 15:
                        in.skipBytes(3);
                        break;
                    case 3:
                    case 4:
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                        in.skipBytes(4);
                        break;
                    case 5:
                    case 6:
                        in.skipBytes(8);
                        i++;
                        break;
                    default:
                        return new int[]{srg, mcp, official};
                }
            }
            for (int i = 1; i < cpCount; i++) {
                String s = utf8[i];
                if (s == null) continue;
                if (s.matches("[fm]_\\d+_")) srg++;
                else if (s.matches("(?:func|field)_\\d+_")) mcp++;
                else if (s.matches("[a-z][a-zA-Z0-9_]{2,}") && !s.contains("/")) official++;
            }
        } catch (IOException ignored) {
            // count what was read
        }
        return new int[]{srg, mcp, official};
    }

    public void close() throws IOException {
        loader.close();
    }

    static byte[] readEntry(Path jar, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry(entry);
            if (e == null) throw new IOException("no entry " + entry + " in " + jar);
            try (InputStream in = zip.getInputStream(e)) {
                return J8.readAll(in);
            }
        }
    }

    static String sha(byte[] data, String algorithm) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance(algorithm);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static IOException unwrap(String what, ReflectiveOperationException e) {
        Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
        return new IOException(what + " failed: " + cause, cause);
    }
}
