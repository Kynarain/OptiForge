package kynarain.cn.optiforge.legacy;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Drives OptiFine's own patcher for the pre-1.13 payload layout.
 *
 * <p>Measured on OptiFine_1.12.2_HD_U_G5 (docs/PHASE3.md §1): the payload is a flat
 * {@code patch/<obf>.class.xdelta} + {@code patch/<obf>.class.md5} directory (248 classes) and there is
 * no {@code patch/srg/} or {@code patch/notch/}. {@code patch.cfg} carries only resource rules and its
 * first rule is {@code [a-z$0-9]+.class=*}, so the base resource name a class patch resolves to is the
 * class's own name, which is exactly the jar entry the launch class loader serves at runtime.
 *
 * <p>{@code optifine.Patcher} is loaded from the user's jar into a loader whose parent is the bootstrap
 * loader: its constant pool references only JDK and {@code optifine.*} types (checked with javap), so no
 * Minecraft or Forge class can leak into the pack build.
 */
public final class LegacyPayload implements AutoCloseable {

    /** Which layout a jar carries; only FLAT is hand-written today, the others are 1.13+ shapes. */
    public enum Layout {
        FLAT, SRG, NOTCH, UNKNOWN
    }

    private final Path jar;
    private final URLClassLoader loader;
    private final Class<?> patcherClass;
    private final Class<?> providerClass;
    private final Class<?> zipProviderClass;
    private final Method getConfigurationMap;
    private final Method getConfigurationPatterns;
    private final Method applyPatch;
    private Map<String, String> configuration;
    private Pattern[] patterns;

    public LegacyPayload(Path jar) throws IOException {
        this.jar = jar;
        this.loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
        try {
            this.patcherClass = loader.loadClass("optifine.Patcher");
            this.providerClass = loader.loadClass("optifine.IResourceProvider");
            this.zipProviderClass = loader.loadClass("optifine.ZipResourceProvider");
            this.getConfigurationMap = patcherClass.getMethod("getConfigurationMap", ZipFile.class);
            this.getConfigurationPatterns = patcherClass.getMethod("getConfigurationPatterns", Map.class);
            this.applyPatch = patcherClass.getMethod("applyPatch", String.class, byte[].class,
                    Pattern[].class, Map.class, providerClass);
        } catch (ReflectiveOperationException e) {
            throw new IOException("OptiFine's patcher API is not where it should be in " + jar, e);
        }
    }

    public Path jar() {
        return jar;
    }

    /** Payload shape, decided by which directory the deltas live in. */
    public Layout layout() throws IOException {
        boolean srg = false;
        boolean notch = false;
        boolean flat = false;
        ZipFile zip = new ZipFile(jar.toFile());
        try {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("patch/srg/") && name.endsWith(".class.xdelta")) {
                    srg = true;
                } else if (name.startsWith("patch/notch/") && name.endsWith(".class.xdelta")) {
                    notch = true;
                } else if (name.startsWith("patch/") && name.endsWith(".class.xdelta")
                        && name.indexOf('/', "patch/".length()) < 0) {
                    flat = true;
                }
            }
        } finally {
            zip.close();
        }
        if (flat) {
            return Layout.FLAT;
        }
        if (srg) {
            return Layout.SRG;
        }
        if (notch) {
            return Layout.NOTCH;
        }
        return Layout.UNKNOWN;
    }

    /**
     * The class-file names the flat payload patches, e.g. {@code aki.class}.
     *
     * <p>These are true obfuscated names, not logical names: the 1.12.2 client jar keeps
     * {@code aki.class} at its root (measured by listing the payload and the vanilla jar).
     */
    public List<String> flatTargets() throws IOException {
        List<String> out = new ArrayList<String>();
        ZipFile zip = new ZipFile(jar.toFile());
        try {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("patch/") && name.endsWith(".class.xdelta")
                        && name.indexOf('/', "patch/".length()) < 0) {
                    out.add(name.substring("patch/".length(), name.length() - ".xdelta".length()));
                }
            }
        } finally {
            zip.close();
        }
        Collections.sort(out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> configuration() throws IOException {
        if (configuration == null) {
            ZipFile zip = new ZipFile(jar.toFile());
            try {
                try {
                    configuration = (Map<String, String>) getConfigurationMap.invoke(null, zip);
                } catch (ReflectiveOperationException e) {
                    throw unwrap("getConfigurationMap", e);
                }
            } finally {
                zip.close();
            }
        }
        return configuration;
    }

    private Pattern[] patterns() throws IOException {
        if (patterns == null) {
            try {
                patterns = (Pattern[]) getConfigurationPatterns.invoke(null, configuration());
            } catch (ReflectiveOperationException e) {
                throw unwrap("getConfigurationPatterns", e);
            }
        }
        return patterns;
    }

    /** Opens the base jar as OptiFine's resource provider; keep the returned zip open for the whole run. */
    public Object newProvider(ZipFile base) throws IOException {
        try {
            return zipProviderClass.getConstructor(ZipFile.class).newInstance(base);
        } catch (ReflectiveOperationException e) {
            throw unwrap("ZipResourceProvider", e);
        }
    }

    /**
     * Applies one class patch; the caller checks {@link #expectedMd5(String)} against the result.
     *
     * <p>Measured (docs/PHASE3.md §1.2): every one of the 248 flat deltas applies to the vanilla 1.12.2
     * jar and reproduces the recorded md5, which is what pins the payload base to the obf client jar
     * rather than to anything Forge has already patched.
     */
    public byte[] apply(Object provider, String classFileName) throws IOException {
        byte[] delta = readEntry("patch/" + classFileName + ".xdelta");
        try {
            return (byte[]) applyPatch.invoke(null, classFileName, delta, patterns(), configuration(), provider);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("patcher refused " + classFileName + ": "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException e) {
            throw unwrap("applyPatch", e);
        }
    }

    public String expectedMd5(String classFileName) throws IOException {
        byte[] raw = readEntry("patch/" + classFileName + ".md5");
        return new String(raw, StandardCharsets.US_ASCII).trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** OptiFine's own classes; on 1.12.2 they are already in the runtime namespace (measured: 0 SRG tokens). */
    public int ownClassCount() throws IOException {
        int count = 0;
        ZipFile zip = new ZipFile(jar.toFile());
        try {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("net/optifine/") && name.endsWith(".class")) {
                    count++;
                }
            }
        } finally {
            zip.close();
        }
        return count;
    }

    /**
     * Complete class files the jar ships for <b>game</b> classes instead of an xdelta.
     *
     * <p>Measured on 1.12.2 G5: the jar has exactly two root-level classes, {@code Config.class} and
     * {@code awu$Builder.class}. {@code Config} is OptiFine's own class - OptiFine's own sources were
     * remapped into the default package for this build, and {@code net/optifine/**} references bare
     * {@code Config.getVersion()} - while {@code awu$Builder} is the vanilla
     * {@code BlockStateContainer$Builder} compiled in Forge's MCP environment (it has
     * {@code IUnlistedProperty} members). Its outer name {@code awu} is a flat patch target, so the rule is:
     * a root class whose outer name is a patch target is a complete game-class override and must be
     * delivered through the transformer, never on the class path. {@code Config} keeps its own-class role.
     */
    public Map<String, byte[]> completeOverrides() throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        List<String> targets = flatTargets();
        java.util.Set<String> targetNames = new java.util.HashSet<String>();
        for (String target : targets) {
            targetNames.add(target.substring(0, target.length() - ".class".length()));
        }
        ZipFile zip = new ZipFile(jar.toFile());
        try {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.indexOf('/') >= 0) {
                    continue;
                }
                String internal = name.substring(0, name.length() - ".class".length());
                int inner = internal.indexOf('$');
                String outer = inner < 0 ? internal : internal.substring(0, inner);
                if (!targetNames.contains(outer)) {
                    continue;
                }
                InputStream in = zip.getInputStream(entry);
                try {
                    out.put(internal, LegacyPaths.readAll(in));
                } finally {
                    in.close();
                }
            }
        } finally {
            zip.close();
        }
        return out;
    }

    /**
     * Everything that belongs on the class path: every class file of the jar except the payload
     * directories ({@code patch/}, {@code notch/}, {@code srg/}), the installer package
     * ({@code optifine/}, except the two resource-locator contracts {@code net/optifine} needs) and the
     * complete game-class overrides from {@link #completeOverrides()}.
     *
     * <p>This is what makes {@code Config} reachable: it is not under {@code net/optifine/} at all.
     * Keeping game classes out is what keeps FML's {@code PatchingTransformer} from hashing a modified
     * class and calling {@code System.exit(1)} (docs/PHASE3.md §2).
     */
    public Map<String, byte[]> stagedClasses() throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        java.util.Set<String> overrides = completeOverrides().keySet();
        ZipFile zip = new ZipFile(jar.toFile());
        try {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                if (name.startsWith("patch/") || name.startsWith("notch/") || name.startsWith("srg/")) {
                    continue;
                }
                if (name.endsWith("/")) {
                    continue;
                }
                if (name.indexOf('/') < 0) {
                    String internal = name.substring(0, name.length() - ".class".length());
                    if (overrides.contains(internal)) {
                        continue;
                    }
                }
                boolean installer = name.startsWith("optifine/");
                boolean locatorContract = name.equals("optifine/OptiFineResourceLocator.class")
                        || name.equals("optifine/IOptiFineResourceLocator.class");
                if (installer && !locatorContract) {
                    continue;
                }
                InputStream in = zip.getInputStream(entry);
                try {
                    out.put(name, LegacyPaths.readAll(in));
                } finally {
                    in.close();
                }
            }
        } finally {
            zip.close();
        }
        return out;
    }

    /** {@code assets/**} of the jar, used when staging a resource pack. */
    public Map<String, byte[]> assets() throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zip = new ZipFile(jar.toFile());
        try {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("assets/") || entry.isDirectory()) {
                    continue;
                }
                InputStream in = zip.getInputStream(entry);
                try {
                    out.put(entry.getName(), LegacyPaths.readAll(in));
                } finally {
                    in.close();
                }
            }
        } finally {
            zip.close();
        }
        return out;
    }

    public byte[] readEntry(String name) throws IOException {
        ZipFile zip = new ZipFile(jar.toFile());
        try {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                throw new IOException("no entry " + name + " in " + jar);
            }
            InputStream in = zip.getInputStream(entry);
            try {
                return LegacyPaths.readAll(in);
            } finally {
                in.close();
            }
        } finally {
            zip.close();
        }
    }

    /**
     * Counts SRG-style member tokens ({@code f_123_}/{@code m_123_}) in a class file.
     *
     * <p>Used as the namespace check that decided the design: OptiFine 1.12.2's own classes carry 0 of
     * them, so no remap is needed for this line (docs/PHASE3.md §1.2).
     */
    public static int srgTokens(byte[] classBytes) {
        int count = 0;
        try {
            DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(classBytes));
            if (in.readInt() != 0xCAFEBABE) {
                return 0;
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            int cpCount = in.readUnsignedShort();
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1:
                        int length = in.readUnsignedShort();
                        byte[] utf = new byte[length];
                        in.readFully(utf);
                        String text = new String(utf, StandardCharsets.UTF_8);
                        if (text.matches("[fm]_\\d+_")) {
                            count++;
                        }
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
                        return count;
                }
            }
        } catch (IOException ignored) {
            // count what could be read; a malformed class cannot reach this point in practice
        }
        return count;
    }

    private static IOException unwrap(String what, ReflectiveOperationException e) {
        Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
        return new IOException(what + " failed: " + cause, cause);
    }

    public void close() throws IOException {
        loader.close();
    }
}
