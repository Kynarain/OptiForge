package kynarain.cn.optiforge.legacy;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Offline build + checks for the legacy pack; runs without starting Minecraft.
 *
 * <pre>
 * java -cp OptiForge-&lt;ver&gt;-legacy.jar kynarain.cn.optiforge.legacy.LegacyProbe \
 *      --optifine build/optifine-pristine/OptiFine_1.12.2_HD_U_G5.jar \
 *      --base     build/optifine-pristine/1.12.2.jar \
 *      --forge-home build/phase3/forge-install-1.12.2/mc \
 *      --gamedir  build/phase3/packtest
 * </pre>
 *
 * <p>Prints the four numbers the design rests on (docs/PHASE3.md): the payload applies to the vanilla
 * obf client jar with all md5s matching, OptiFine's own classes carry no SRG tokens, and the payload
 * class loses some members that the running Forge adds (the "drift" the transformer has to merge back).
 */
public final class LegacyProbe {

    private static final String MILESTONE = "layout=flat / patches %d / md5 %d OK / own %d / SRG %d / drift %d / RESULT: %s";

    public static void main(String[] args) throws Exception {
        Path optifine = null;
        Path base = null;
        Path forgeHome = Paths.get("build", "phase3", "forge-install-1.12.2", "mc");
        Path forgeJar = null;
        Path binpatchesJar = null;
        Path gameDir = Paths.get("build", "phase3", "packtest");
        String pluginClass = "kynarain.cn.optiforge.legacy.OptiForgeLegacyCorePlugin";
        String mc = "1.12.2";
        boolean noDrift = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--optifine".equals(arg)) {
                optifine = Paths.get(args[++i]);
            } else if ("--base".equals(arg)) {
                base = Paths.get(args[++i]);
            } else if ("--forge-home".equals(arg)) {
                forgeHome = Paths.get(args[++i]);
            } else if ("--forge-jar".equals(arg)) {
                forgeJar = Paths.get(args[++i]);
            } else if ("--binpatches".equals(arg)) {
                binpatchesJar = Paths.get(args[++i]);
            } else if ("--gamedir".equals(arg)) {
                gameDir = Paths.get(args[++i]);
            } else if ("--plugin-class".equals(arg)) {
                pluginClass = args[++i];
            } else if ("--mc".equals(arg)) {
                mc = args[++i];
            } else if ("--no-drift".equals(arg)) {
                noDrift = true;
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                System.out.println("usage: LegacyProbe --optifine <jar> [--base <jar>] [--forge-home <mc root>]"
                        + " [--forge-jar <universal jar>] [--binpatches <unpacked jar>] [--gamedir <dir>]"
                        + " [--plugin-class <fqcn>] [--mc <version>] [--no-drift]");
                return;
            } else {
                throw new IllegalArgumentException("unknown argument: " + arg);
            }
        }
        if (optifine == null) {
            throw new IllegalArgumentException("--optifine is required");
        }
        if (!Files.isRegularFile(optifine)) {
            throw new IllegalArgumentException("optifine jar not found: " + optifine);
        }

        StringBuilder notes = new StringBuilder();
        if (base == null) {
            base = LegacyPaths.findBaseJar(forgeHome, null, notes);
        }
        if (base == null) {
            throw new IllegalArgumentException("no base jar: pass --base <vanilla client jar>");
        }
        if (forgeJar == null) {
            forgeJar = LegacyPaths.findForgeJar(forgeHome);
        }
        if (binpatchesJar == null && forgeJar != null) {
            binpatchesJar = unpackBinpatches(forgeJar, forgeHome, gameDir, notes);
        }

        System.out.println("[OptiForge/legacy] probe");
        System.out.println("optifine jar  : " + describe(optifine));
        System.out.println("base jar      : " + describe(base));
        System.out.println("forge jar     : " + (forgeJar == null ? "(not found under " + forgeHome + ")"
                : describe(forgeJar)));
        System.out.println("binpatches    : " + (binpatchesJar == null ? "(not available, drift skipped)"
                : describe(binpatchesJar)));

        LegacyPack pack = LegacyPack.prepare(gameDir, optifine, base, notes);
        System.out.println("staged jar    : " + describe(pack.stagedJar()));
        System.out.println("cache         : " + pack.cacheDir());
        System.out.println("payload       : patches=" + pack.patchCount()
                + " overrides=" + pack.overrideCount()
                + " md5_ok=" + pack.md5Ok() + " mismatch=" + pack.md5Mismatch()
                + " failure=" + pack.md5Failure()
                + " packed_bytes=" + pack.patchedSize());
        System.out.println("staged        : classes=" + pack.ownClasses().size()
                + (pack.ownClasses().containsKey("Config.class") ? " (includes root Config.class)" : ""));
        for (String override : overrides(pack)) {
            System.out.println("override      : " + override + " (complete game class, delivered by the"
                    + " transformer; not on the class path)");
        }

        int srg = 0;
        int own = 0;
        for (Map.Entry<String, byte[]> entry : pack.ownClasses().entrySet()) {
            // OptiFine's own classes are net/optifine/** on 1.8+, but sit in the default package (plus
            // shadersmod/** and net/optifine/entity/model/anim/**) on 1.7.10. Forge API stubs shipped by
            // the 1.8+ jars are not OptiFine code.
            if (entry.getKey().startsWith("net/minecraftforge/")) {
                continue;
            }
            own++;
            srg += LegacyPayload.srgTokens(entry.getValue());
        }
        String style = pack.ownClasses().containsKey("optifine/IOptiFineResourceLocator.class")
                ? "1.8+-style (net/optifine classes, resource-locator contract)"
                : "1.7.10-style (own classes in the default package, no resource-locator contract)";
        System.out.println("style         : " + style);

        Drift drift = new Drift();
        if (binpatchesJar != null && forgeJar != null) {
            drift = measureDrift(pack, base, binpatchesJar, forgeJar);
        }

        boolean ok = pack.patchCount() > 0
                && pack.md5Mismatch() == 0
                && pack.md5Failure() == 0
                && pack.md5Ok() == pack.patchCount()
                && srg == 0
                && (drift.available || noDrift);

        System.out.println("drift         : " + (drift.available
                ? drift.forgeExtraClasses + " classes where the Forge runtime has members the payload lacks"
                + " (" + drift.identical + " identical, " + drift.payloadExtraClasses
                + " with payload-only members, " + drift.withoutPatch + " without a binpatch)"
                : "not measured"));
        for (String line : drift.details) {
            System.out.println("                " + line);
        }
        String coremod = coremodCheck(forgeHome, drift, pluginClass);
        System.out.println("coremod       : " + coremod);
        ok = ok && coremod.startsWith("OK");
        String discovery = discoveryCheck(gameDir, base, mc);
        System.out.println("discovery     : " + discovery);
        ok = ok && discovery.startsWith("OK");
        if (notes.length() > 0) {
            System.out.println("notes         :");
            System.out.print(notes);
        }
        System.out.println();
        System.out.println(String.format(java.util.Locale.ROOT, MILESTONE,
                pack.patchCount(), pack.md5Ok(), own, srg, drift.forgeExtraClasses,
                ok ? "OK" : "FAIL"));
        if (!ok) {
            System.exit(1);
        }
    }

    /** One line per complete game-class override the jar shipped (measured: awu$Builder only). */
    private static List<String> overrides(LegacyPack pack) {
        List<String> out = new ArrayList<String>();
        for (String name : pack.overrideNames()) {
            byte[] bytes = pack.patchedBytes(name);
            out.add(name + " (" + (bytes == null ? 0 : bytes.length) + " B)");
        }
        return out;
    }

    private static String describe(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            return String.valueOf(path);
        }
        return path + " (" + Files.size(path) + " B, sha1 " + LegacyPack.sha1(path, 12) + ")";
    }

    // ------------------------------------------------------------------ drift

    private static final class Drift {
        boolean available;
        int identical;
        int forgeExtraClasses;
        int payloadExtraClasses;
        int withoutPatch;
        final List<String> details = new ArrayList<String>();
        // one representative (incoming = Forge-patched, prepared = payload) pair, used to prove the
        // merge path against the exact ASM release FML 1.12.2 runs
        String sampleName;
        byte[] samplePrepared;
        byte[] sampleIncoming;
        int sampleMissing;
    }

    /**
     * Compares the payload class with what Forge actually loads: the binpatch applied to the vanilla
     * class with FML's own {@code GDiffPatcher}.
     *
     * <p>Measured on 1.12.2 G5 vs Forge 2860 (docs/PHASE3.md §3): 36 classes carry Forge members the
     * payload does not, which is the failure surface of a build pinned to an older Forge.
     */
    private static Drift measureDrift(LegacyPack pack, Path base, Path binpatches, Path forgeJar)
            throws IOException {
        Drift drift = new Drift();
        Map<String, byte[]> patches = readClientBinpatches(binpatches);
        if (patches.isEmpty()) {
            return drift;
        }
        drift.available = true;
        Map<String, Integer> worst = new TreeMap<String, Integer>();
        // The payload classes are the patched map; read them back through the pack accessors so the probe
        // and the runtime use exactly the same bytes.
        for (String internal : pack.patchedNames()) {
            byte[] payloadBytes = pack.patchedBytes(internal);
            byte[] patch = patches.get(internal);
            if (patch == null) {
                drift.withoutPatch++;
                continue;
            }
            ZipFile zip = new ZipFile(base.toFile());
            byte[] vanillaBytes;
            try {
                ZipEntry entry = zip.getEntry(internal + ".class");
                if (entry == null) {
                    drift.withoutPatch++;
                    continue;
                }
                InputStream in = zip.getInputStream(entry);
                try {
                    vanillaBytes = LegacyPaths.readAll(in);
                } finally {
                    in.close();
                }
            } finally {
                zip.close();
            }
            byte[] forgeBytes = gdiff(forgeJar, vanillaBytes, patch);
            if (forgeBytes == null) {
                drift.withoutPatch++;
                continue;
            }
            ClassMeta payloadMeta = ClassMeta.read(payloadBytes);
            ClassMeta forgeMeta = ClassMeta.read(forgeBytes);
            Set<String> missing = new TreeSet<String>(forgeMeta.members);
            missing.removeAll(payloadMeta.members);
            Set<String> extra = new TreeSet<String>(payloadMeta.members);
            extra.removeAll(forgeMeta.members);
            if (missing.isEmpty() && extra.isEmpty()) {
                drift.identical++;
            } else {
                if (!missing.isEmpty()) {
                    drift.forgeExtraClasses++;
                    worst.put(internal, missing.size());
                    if (drift.sampleIncoming == null || missing.size() > drift.sampleMissing) {
                        drift.sampleName = internal;
                        drift.samplePrepared = payloadBytes;
                        drift.sampleIncoming = forgeBytes;
                        drift.sampleMissing = missing.size();
                    }
                    if (drift.details.size() < 8) {
                        drift.details.add(internal + " : forge-only members " + missing.size()
                                + " (e.g. " + first(missing) + ")");
                    }
                }
                if (!extra.isEmpty()) {
                    drift.payloadExtraClasses++;
                }
            }
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<Map.Entry<String, Integer>>(worst.entrySet());
        java.util.Collections.sort(ranked, new java.util.Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        drift.details.clear();
        for (int i = 0; i < ranked.size() && i < 8; i++) {
            drift.details.add(ranked.get(i).getKey() + " : forge-only members " + ranked.get(i).getValue());
        }
        return drift;
    }

    private static String first(Set<String> values) {
        for (String value : values) {
            return value;
        }
        return "?";
    }

    /** Reads {@code binpatch/client/<name>.binpatch} entries into notch name -> patch bytes. */
    private static Map<String, byte[]> readClientBinpatches(Path jar) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zip = new ZipFile(jar.toFile());
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("binpatch/client/") || !name.endsWith(".binpatch")) {
                    continue;
                }
                InputStream in = zip.getInputStream(entry);
                try {
                    DataInputStream data = new DataInputStream(in);
                    data.readUTF();                      // logical (MCP) name, bookkeeping only
                    String source = data.readUTF();      // notch name, the launch wrapper's name
                    data.readUTF();                      // target (MCP) name, bookkeeping only
                    boolean exists = data.readBoolean();
                    if (exists) {
                        data.readInt();                  // adler32 of the vanilla input
                    }
                    int length = data.readInt();
                    byte[] patch = new byte[length];
                    data.readFully(patch);
                    out.put(source, patch);
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
     * {@code binpatches.pack.lzma} is LZMA over a Pack200 archive (measured, docs/PHASE3.md §2).
     *
     * <p>Decompression uses the {@code lzma-0.0.1.jar} FML itself depends on, Pack200 uses the Java 8
     * class library. Both are optional: a platform without them falls back to {@code --binpatches}.
     */
    private static Path unpackBinpatches(Path forgeJar, Path forgeHome, Path gameDir, StringBuilder notes) {
        Path out = gameDir.resolve("binpatches-unpacked.jar");
        try {
            if (Files.isRegularFile(out) && Files.size(out) > 0) {
                return out;
            }
            Files.createDirectories(gameDir);
            ZipFile zip = new ZipFile(forgeJar.toFile());
            byte[] lzma;
            try {
                ZipEntry entry = zip.getEntry("binpatches.pack.lzma");
                if (entry == null) {
                    notes.append("forge jar has no binpatches.pack.lzma\n");
                    return null;
                }
                InputStream in = zip.getInputStream(entry);
                try {
                    lzma = LegacyPaths.readAll(in);
                } finally {
                    in.close();
                }
            } finally {
                zip.close();
            }
            Path lzmaJar = LegacyPaths.findLzmaJar(forgeHome);
            if (lzmaJar == null) {
                notes.append("lzma-0.0.1.jar not found under ").append(forgeHome).append('\n');
                return null;
            }
            byte[] pack200 = lzmaDecompress(lzmaJar, lzma);
            if (pack200 == null) {
                return null;
            }
            File temp = Files.createTempFile("optiforge-binpatches", ".pack").toFile();
            Files.write(temp.toPath(), pack200);
            OutputStream jarOut = Files.newOutputStream(out);
            java.util.jar.JarOutputStream zos = new java.util.jar.JarOutputStream(jarOut);
            try {
                java.util.jar.Pack200.newUnpacker().unpack(temp, zos);
            } finally {
                zos.close();
                temp.delete();
            }
            notes.append("binpatches unpacked from ").append(forgeJar.getFileName()).append('\n');
            return out;
        } catch (Throwable t) {
            notes.append("binpatches unpack unavailable (").append(t).append("), pass --binpatches\n");
            return null;
        }
    }

    private static byte[] lzmaDecompress(Path lzmaJar, byte[] compressed) throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[]{lzmaJar.toUri().toURL()},
                LegacyProbe.class.getClassLoader());
        try {
            Class<?> streamClass = Class.forName("LZMA.LzmaInputStream", true, loader);
            InputStream in = (InputStream) streamClass.getConstructor(InputStream.class)
                    .newInstance(new java.io.ByteArrayInputStream(compressed));
            try {
                return LegacyPaths.readAll(in);
            } finally {
                in.close();
            }
        } finally {
            loader.close();
        }
    }

    /**
     * Applies one FML binpatch with FML's own {@code GDiffPatcher}.
     *
     * <p>Measured: 1.8.9-1.12.2 repackage it as {@code net.minecraftforge.fml.repackage.com.nothome.delta},
     * 1.7.10 as {@code cpw.mods.fml.repackage.com.nothome.delta}; both jar layouts are tried.
     */
    private static byte[] gdiff(Path forgeJar, byte[] vanillaBytes, byte[] patch) {
        String[] classNames = {
                "net.minecraftforge.fml.repackage.com.nothome.delta.GDiffPatcher",
                "cpw.mods.fml.repackage.com.nothome.delta.GDiffPatcher"};
        try {
            URLClassLoader loader = new URLClassLoader(new URL[]{forgeJar.toUri().toURL()},
                    LegacyProbe.class.getClassLoader());
            try {
                for (String className : classNames) {
                    try {
                        Class<?> gdiffClass = Class.forName(className, true, loader);
                        Object patcher = gdiffClass.getConstructor().newInstance();
                        Method patchMethod = gdiffClass.getMethod("patch", byte[].class, byte[].class);
                        return (byte[]) patchMethod.invoke(patcher, vanillaBytes, patch);
                    } catch (Throwable ignored) {
                        // try the next repackage layout
                    }
                }
                return null;
            } finally {
                loader.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------- discovery

    /**
     * The discovery rules that a real launch needed, exercised offline for the given MC version: the
     * profile-id suffix cut from {@code --version}, the ancestor walk from an instance directory to a
     * shared {@code versions/}, the class-path jar named {@code <mc>-forge-<ver>.jar} (content-validated)
     * and the {@code -Doptiforge.vanilla} fallback.
     */
    private static String discoveryCheck(Path gameDir, Path base, String mc) {
        try {
            String fromCommand = LegacyPaths.mcVersionFromLaunchCommand(
                    "net.minecraft.launchwrapper.Launch --username Dev --version " + mc
                            + "-forge-1.0.0 --gameDir .");
            if (!mc.equals(fromCommand)) {
                return "FAIL --version parse gave " + fromCommand;
            }
            String fromUppercase = LegacyPaths.mcVersionFromLaunchCommand(
                    "Launch --version " + mc + "-Forge_1.0.0");
            if (!mc.equals(fromUppercase)) {
                return "FAIL -Forge_ parse gave " + fromUppercase;
            }

            Path root = gameDir.resolve("selftest-rig");
            Path instance = root.resolve("instance");
            Path vanilla = root.resolve("versions").resolve(mc).resolve(mc + ".jar");
            Files.createDirectories(vanilla.getParent());
            Files.createDirectories(instance);
            if (!Files.isRegularFile(vanilla) || Files.size(vanilla) < 1000) {
                Files.deleteIfExists(vanilla);
                Files.copy(base, vanilla);
            }
            StringBuilder notes = new StringBuilder();
            Path found = LegacyPaths.findBaseJar(instance, mc, null, notes);
            if (found == null || !found.equals(vanilla)) {
                return "FAIL ancestor walk gave " + found;
            }
            StringBuilder overrideNotes = new StringBuilder();
            Path viaOverride = LegacyPaths.findBaseJar(instance, "0.0", vanilla.toString(), overrideNotes);
            if (viaOverride == null || !viaOverride.equals(vanilla)) {
                return "FAIL -Doptiforge.vanilla fallback gave " + viaOverride;
            }
            // no FMLInjectionData and no --version in this JVM: the versions dir one level up must win
            String scanned = LegacyPaths.detectMcVersion(instance);
            if (!mc.equals(scanned)) {
                return "FAIL ancestor versions scan gave " + scanned;
            }
            String override = LegacyPaths.detectMcVersion(instance, mc, new StringBuilder());
            if (!mc.equals(override)) {
                return "FAIL -Doptiforge.mcversion gave " + override;
            }

            // Class path rule: a copy named like the profile jar, not an ancestor of the instance, and
            // validated by content. The JVM's own class path is read through System.getProperty every call.
            if (!LegacyPaths.looksLikeClientJar(base)) {
                return "FAIL base jar does not look like a client jar";
            }
            Path cpRoot = Files.createDirectories(gameDir.resolve("selftest-classpath-" + mc));
            Path profileJar = cpRoot.resolve(mc + "-forge-1.0.0.jar");
            if (!Files.isRegularFile(profileJar) || Files.size(profileJar) < 1000) {
                Files.deleteIfExists(profileJar);
                Files.copy(base, profileJar);
            }
            Path emptyJar = cpRoot.resolve(mc + "-forge-0.0.0.jar");
            if (!Files.isRegularFile(emptyJar)) {
                Files.write(emptyJar, new byte[0]);
            }
            String saved = System.getProperty("java.class.path", "");
            try {
                System.setProperty("java.class.path", profileJar.toString());
                Path cpInstance = Files.createDirectories(cpRoot.resolve("instance"));
                StringBuilder cpNotes = new StringBuilder();
                Path fromClassPath = LegacyPaths.findBaseJar(cpInstance, mc, null, cpNotes);
                if (fromClassPath == null || !fromClassPath.equals(profileJar)
                        || !cpNotes.toString().contains("java.class.path")) {
                    return "FAIL class path rule gave " + fromClassPath + " / " + cpNotes;
                }
                System.setProperty("java.class.path", emptyJar.toString());
                Path fromBroken = LegacyPaths.findBaseJar(cpInstance, mc, null, new StringBuilder());
                if (fromBroken != null) {
                    return "FAIL a zero-byte class path jar was accepted: " + fromBroken;
                }
            } finally {
                System.setProperty("java.class.path", saved);
            }
            return "OK --version=" + mc + ", ancestor versions walk, java.class.path profile jar,"
                    + " -Doptiforge.vanilla and -Doptiforge.mcversion";
        } catch (Throwable t) {
            return "FAIL " + t;
        }
    }

    // ---------------------------------------------------------------- coremod

    /**
     * Proves FML can use the artifact, without starting the game: loads the plugin through a class loader
     * containing the real Forge/launchwrapper/ASM jars, resolves whichever {@code IFMLLoadingPlugin}
     * package the target FML ships (1.7.10: {@code cpw.mods.fml}, 1.8+: {@code net.minecraftforge.fml}),
     * checks the annotation set by instantiating the plugin, and (when a drifted class was measured) runs
     * the merge with the exact ASM release that runtime provides, then parses the result with it.
     */
    @SuppressWarnings("unchecked")
    private static String coremodCheck(Path forgeHome, Drift drift, String pluginClassName) {
        try {
            Path forgeJar = LegacyPaths.findForgeJar(forgeHome);
            Path launchwrapper = LegacyPaths.findLaunchWrapperJar(forgeHome);
            Path asm = LegacyPaths.findAsmJar(forgeHome);
            if (forgeJar == null || launchwrapper == null || asm == null) {
                return "FAIL forge/launchwrapper/asm not found under " + forgeHome;
            }
            Path self = Paths.get(LegacyProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            URLClassLoader loader = new URLClassLoader(new URL[]{
                    self.toUri().toURL(),
                    forgeJar.toUri().toURL(),
                    launchwrapper.toUri().toURL(),
                    asm.toUri().toURL()},
                    ClassLoader.getSystemClassLoader().getParent());
            try {
                Class<?> plugin = Class.forName(pluginClassName, true, loader);
                String[] packages = {"net.minecraftforge.fml.relauncher", "cpw.mods.fml.relauncher"};
                Class<?> pluginContract = null;
                String contractName = null;
                for (String pkg : packages) {
                    try {
                        Class<?> candidate = Class.forName(pkg + ".IFMLLoadingPlugin", false, loader);
                        if (candidate.isAssignableFrom(plugin)) {
                            pluginContract = candidate;
                            contractName = pkg + ".IFMLLoadingPlugin";
                            break;
                        }
                    } catch (Throwable ignored) {
                        // this FML package layout is not present in the target runtime
                    }
                }
                if (pluginContract == null) {
                    return "FAIL plugin does not implement either IFMLLoadingPlugin layout";
                }
                String annotationPrefix = contractName.substring(0, contractName.lastIndexOf('.'));
                Class<?> sortingAnnotation = Class.forName(
                        annotationPrefix + ".IFMLLoadingPlugin$SortingIndex", true, loader);
                // @MCVersion is deliberately absent: FML 1.12.2 rejected the bracketed range against
                // Loader.MC_VERSION and ignored the coremod entirely, so only @SortingIndex is required.
                Object sorting = plugin.getAnnotation((Class) sortingAnnotation);
                if (sorting == null) {
                    return "FAIL plugin is missing @SortingIndex";
                }
                int sortingIndex = ((Integer) sortingAnnotation.getMethod("value").invoke(sorting)).intValue();
                String mcVersion = "none";
                try {
                    Class<?> mcVersionAnnotation = Class.forName(
                            annotationPrefix + ".IFMLLoadingPlugin$MCVersion", true, loader);
                    Object annotation = plugin.getAnnotation((Class) mcVersionAnnotation);
                    if (annotation != null) {
                        mcVersion = (String) mcVersionAnnotation.getMethod("value").invoke(annotation);
                    }
                } catch (Throwable ignored) {
                    // older FML may not have @MCVersion at all
                }

                Object instance = plugin.getConstructor().newInstance();
                String[] transformers = (String[]) plugin.getMethod("getASMTransformerClass").invoke(instance);
                if (transformers == null || transformers.length != 1) {
                    return "FAIL getASMTransformerClass did not return exactly one transformer";
                }
                Class<?> transformerContract = Class.forName(
                        "net.minecraft.launchwrapper.IClassTransformer", true, loader);
                Class<?> transformer = Class.forName(transformers[0], true, loader);
                if (!transformerContract.isAssignableFrom(transformer)) {
                    return "FAIL transformer does not implement IClassTransformer";
                }
                transformer.getConstructor().newInstance();

                String merged = "";
                if (drift != null && drift.sampleIncoming != null) {
                    Method merge = transformer.getDeclaredMethod("merge", byte[].class, byte[].class);
                    merge.setAccessible(true);
                    Object result = merge.invoke(null, drift.sampleIncoming, drift.samplePrepared);
                    java.lang.reflect.Field bytesField = result.getClass().getDeclaredField("bytes");
                    bytesField.setAccessible(true);
                    byte[] bytes = (byte[]) bytesField.get(result);
                    java.lang.reflect.Field addedField = result.getClass().getDeclaredField("added");
                    addedField.setAccessible(true);
                    int added = ((Integer) addedField.get(result)).intValue();

                    Class<?> readerClass = Class.forName("org.objectweb.asm.ClassReader", true, loader);
                    Class<?> visitorClass = Class.forName("org.objectweb.asm.ClassVisitor", true, loader);
                    Class<?> nodeClass = Class.forName("org.objectweb.asm.tree.ClassNode", true, loader);
                    Object reader = readerClass.getConstructor(byte[].class).newInstance((Object) bytes);
                    Object node = nodeClass.newInstance();
                    // a full parse, frames included: a malformed merge fails here
                    readerClass.getMethod("accept", visitorClass, int.class).invoke(reader, node, 0);

                    ClassMeta expected = ClassMeta.read(drift.sampleIncoming);
                    expected.members.removeAll(ClassMeta.read(bytes).members);
                    if (!expected.members.isEmpty()) {
                        return "FAIL merged " + drift.sampleName + " still lacks "
                                + expected.members.size() + " member(s)";
                    }
                    merged = "; merge " + drift.sampleName + " adopted " + added + " member(s)";
                }
                return "OK plugin=" + plugin.getSimpleName() + " contract=" + pluginContract.getSimpleName()
                        + " (" + contractName + ") mc=\"" + mcVersion + "\" sorting="
                        + sortingIndex + " transformer=" + transformer.getSimpleName()
                        + " asm=parsed" + merged;
            } finally {
                loader.close();
            }
        } catch (Throwable t) {
            return "FAIL " + t;
        }
    }

    // ------------------------------------------------------------ class file

    /** Minimal class-file reader: member sets only, no ASM dependency in the legacy artifact. */
    static final class ClassMeta {
        String name;
        final Set<String> members = new TreeSet<String>();

        static ClassMeta read(byte[] bytes) throws IOException {
            ClassMeta meta = new ClassMeta();
            DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
            if (in.readInt() != 0xCAFEBABE) {
                throw new IOException("not a class file");
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            int cpCount = in.readUnsignedShort();
            String[] utf8 = new String[cpCount];
            int[] classNames = new int[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1:
                        utf8[i] = in.readUTF();
                        break;
                    case 7:
                        classNames[i] = in.readUnsignedShort();
                        break;
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
                        throw new IOException("constant pool tag " + tag);
                }
            }
            in.readUnsignedShort();                    // access
            int thisClass = in.readUnsignedShort();
            meta.name = utf8[classNames[thisClass]];
            in.readUnsignedShort();                    // super class
            int interfaceCount = in.readUnsignedShort();
            for (int i = 0; i < interfaceCount; i++) {
                int index = in.readUnsignedShort();
                meta.members.add("I:" + utf8[classNames[index]]);
            }
            readMembers(in, meta.members, utf8, 'F');
            readMembers(in, meta.members, utf8, 'M');
            return meta;
        }

        private static void readMembers(DataInputStream in, Set<String> members, String[] utf8, char kind)
                throws IOException {
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                in.readUnsignedShort();                // access
                int nameIndex = in.readUnsignedShort();
                int descIndex = in.readUnsignedShort();
                // Resolve through the class's own constant pool: indices are not comparable between two
                // class files, names and descriptors are.
                members.add(kind + ":" + utf8[nameIndex] + ":" + utf8[descIndex]);
                skipAttributes(in);
            }
        }

        private static void skipAttributes(DataInputStream in) throws IOException {
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                in.readUnsignedShort();
                long length = in.readInt() & 0xFFFFFFFFL;
                while (length > 0) {
                    long skipped = in.skip(length);
                    if (skipped <= 0) {
                        in.readByte();
                        length--;
                    } else {
                        length -= skipped;
                    }
                }
            }
        }
    }
}
