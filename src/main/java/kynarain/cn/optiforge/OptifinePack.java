package kynarain.cn.optiforge;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Everything OptiForge hands to the game: OptiFine's patch payload applied to the vanilla jar, plus
 * OptiFine's own classes, with SRG member names rewritten to the names the running Forge uses.
 *
 * <p>This is the "takeover" the M0 evidence forced (docs/DESIGN.md §4.3): OptiFine's own classes are
 * delivered by its {@code additionalClassesLocator}, which never passes through an
 * {@code ITransformer}, so a transformer added after OptiFine could not fix the 346 SRG-named classes
 * in the 1.21 build. Preparing the pack ourselves also means the OptiFine jar must <b>not</b> be in
 * {@code mods/}: if it is, OptiFine's own transformation service runs as well and patches the same
 * classes a second time.
 *
 * <p>The pack is cached by {@code sha256(OptiFine jar) + mcVersion + forgeVersion}, so the first
 * launch pays for ~440 patches and later launches just read files.
 */
public final class OptifinePack {

    private static final Pattern SRG = Pattern.compile("[fm]_\\d+_");

    public final Path optifineJar;
    public final Path baseJar;
    public final Path cacheDir;

    /** Dotted class name -> prepared (patched + remapped) bytes. */
    public final Map<String, byte[]> patchedClasses;
    /** Dotted class name -> prepared OptiFine class file. */
    public final Map<String, Path> ownClasses;

    public final int patchedCount;
    public final int patchFailures;
    public final int renamedTokens;
    public final int unmappedTokens;
    public final boolean remapApplied;
    public final String key;

    private OptifinePack(Path optifineJar, Path baseJar, Path cacheDir,
                         Map<String, byte[]> patchedClasses, Map<String, Path> ownClasses,
                         int patchFailures, int renamedTokens, int unmappedTokens, boolean remapApplied,
                         String key) {
        this.optifineJar = optifineJar;
        this.baseJar = baseJar;
        this.cacheDir = cacheDir;
        this.patchedClasses = patchedClasses;
        this.ownClasses = ownClasses;
        this.patchedCount = patchedClasses.size();
        this.patchFailures = patchFailures;
        this.renamedTokens = renamedTokens;
        this.unmappedTokens = unmappedTokens;
        this.remapApplied = remapApplied;
        this.key = key;
    }

    public byte[] patched(String dottedClassName) {
        return patchedClasses.get(dottedClassName);
    }

    /**
     * Prefixes handed to ModLauncher.
     *
     * <p>ModLauncher 10.0.9 validates them: measured on Forge 1.20.1, a set containing "optifine.",
     * "net.optifine." and slash-separated forms made the whole launch die with
     * {@code IllegalArgumentException: Bad prefixes specified}. The accepted form is the dotted package
     * without a trailing dot - ModLauncher converts the dots to slashes itself before matching.
     */
    public static final java.util.Set<String> CLASS_PREFIXES =
            java.util.Set.of("optifine.", "net.optifine.");
    public static final java.util.Set<String> RESOURCE_PREFIXES = java.util.Set.of("assets/");

    /**
     * Modules found in the layers that are parents of the mod layer (Forge's own, plus their libraries).
     *
     * <p>An explicit module descriptor has to name what it reads; these names are observed at launch, so a
     * hard {@code requires} can never point at a module the line does not have. The game module is
     * deliberately not listed: it lives in a <b>child</b> layer and Forge grants that read at runtime.
     */
    public static volatile java.util.List<String> parentModules = java.util.List.of();

    /** MC version of the current launch (set by the service before preparing); drives version-gated fixes. */
    public static volatile String mcVersion = "";
    public static volatile String forgeVersion = "";

    /**
     * URL for one of OptiFine's own classes.
     *
     * <p>ModLauncher hands the locator a name ending in {@code .class} (from
     * {@code TransformationServiceDecorator.getOptionalURL}, which only consults the class locator for
     * names with that suffix) and does not say whether the rest is dotted or slashed, so both are
     * accepted here - the first attempt was dotted-only and the game died on
     * {@code NoClassDefFoundError: net/optifine/util/MathUtils}.
     */
    public Optional<URL> ownClassUrl(String name) {
        String normalized = name;
        if (normalized.endsWith(".class")) normalized = normalized.substring(0, normalized.length() - 6);
        normalized = normalized.replace('/', '.');
        Path file = ownClasses.get(normalized);
        if (file == null) {
            if (locatorMisses < 5) {
                locatorMisses++;
                System.out.println("[OptiForge] class locator miss: requested \"" + name
                        + "\" (normalized " + normalized + "), ownClasses=" + ownClasses.size());
            }
            return Optional.empty();
        }
        if (locatorHits < 3) {
            locatorHits++;
            System.out.println("[OptiForge] class locator hit: " + normalized);
        }
        try {
            return Optional.of(file.toUri().toURL());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private int locatorHits;
    private int locatorMisses;

    /** URL for a resource inside the OptiFine jar (assets and anything else it ships). */
    public Optional<URL> resourceUrl(String path) {
        try (ZipFile zip = new ZipFile(optifineJar.toFile())) {
            if (zip.getEntry(path) == null) return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
        try {
            return Optional.of(new URL("jar:" + optifineJar.toUri().toURL() + "!/" + path));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * What member names the running game uses, read from the runtime itself rather than inferred from a
     * version number: the classpath jar that carries {@code net/minecraft/CrashReport.class} either has
     * {@code forThrowable} (official names, Forge 1.20.6+) or {@code m_127521_} (SRG, Forge 1.20.4-).
     */
    public enum Namespace {OFFICIAL, SRG, UNKNOWN}

    public static Namespace detectRuntimeNamespace(StringBuilder notes) {
        for (Path p : LaunchPaths.jars(null)) {
            try (ZipFile zip = new ZipFile(p.toFile())) {
                ZipEntry crashReport = zip.getEntry("net/minecraft/CrashReport.class");
                if (crashReport == null) continue;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(crashReport)) {
                    bytes = in.readAllBytes();
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (text.contains("forThrowable")) {
                    notes.append("runtime namespace: official names (").append(p.getFileName()).append(")\n");
                    return Namespace.OFFICIAL;
                }
                if (SRG.matcher(text).find()) {
                    notes.append("runtime namespace: SRG names (").append(p.getFileName()).append(")\n");
                    return Namespace.SRG;
                }
            } catch (IOException ignored) {
                // not a readable jar; try the next one
            }
        }
        notes.append("runtime namespace: unknown (no net/minecraft/CrashReport.class on the classpath)\n");
        return Namespace.UNKNOWN;
    }

    /**
     * Picks the SRG->official table to use: exact Minecraft version first, else the only table bundled
     * (with a note), else none. Renaming is a no-op when the payload is already official, so a table
     * built for another line can only hurt when it is applied to an SRG payload of a different line -
     * which is why {@code remapEnabled} is decided by the runtime namespace, not by this lookup.
     */
    public static Path pickMapping(String mcVersion, StringBuilder notes) {
        java.util.List<String> available = new ArrayList<>();
        try (var in = OptifinePack.class.getResourceAsStream("/optiforge/mappings/index.txt")) {
            if (in != null) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    if (!line.isBlank()) available.add(line.trim());
                }
            }
        } catch (IOException ignored) {
            // fall through with an empty list
        }
        String chosen = null;
        if (!mcVersion.isBlank() && available.contains(mcVersion)) {
            chosen = mcVersion;
        } else if (available.size() == 1) {
            chosen = available.get(0);
            notes.append("mapping table: no table for ").append(mcVersion.isEmpty() ? "<unknown>" : mcVersion)
                    .append(", using the only bundled one (").append(chosen).append(")\n");
        }
        if (chosen == null) {
            notes.append("mapping table: none available for ")
                    .append(mcVersion.isEmpty() ? "<unknown>" : mcVersion).append('\n');
            return null;
        }
        try (var in = OptifinePack.class.getResourceAsStream("/optiforge/mappings/" + chosen + "-srg-to-official.tsv")) {
            if (in == null) return null;
            Path target = Files.createTempFile("optiforge-map-", ".tsv");
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            notes.append("mapping table: could not extract: ").append(e).append('\n');
            return null;
        }
    }

    /**
     * Locates the OptiFine jar: {@code -Doptiforge.optifine=}, then {@code <game>/optiforge/*.jar}.
     * A jar left in {@code mods/} is reported, not used - OptiFine's own service would run too.
     */
    public static Path findOptifineJar(Path gameDir, String override, StringBuilder notes) {
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (Files.isRegularFile(p)) return p;
            notes.append("optiforge.optifine points at ").append(p).append(" but it is not a file\n");
        }
        if (gameDir != null) {
            Path dir = gameDir.resolve("optiforge");
            if (Files.isDirectory(dir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
                    for (Path candidate : stream) {
                        return candidate;
                    }
                } catch (IOException e) {
                    notes.append("could not list ").append(dir).append(": ").append(e).append('\n');
                }
            }
            Path mods = gameDir.resolve("mods");
            if (Files.isDirectory(mods)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(mods, "*.jar")) {
                    for (Path candidate : stream) {
                        if (candidate.getFileName().toString().toLowerCase(Locale.ROOT).contains("optifine")) {
                            notes.append("WARNING: ").append(candidate.getFileName())
                                    .append(" is in mods/ - OptiFine's own transformation service will run too.")
                                    .append(" Move it to optiforge/ so OptiForge can prepare it alone.\n");
                        }
                    }
                } catch (IOException ignored) {
                    // nothing to add
                }
            }
        }
        return null;
    }

    /**
     * Finds the vanilla client jar. On Forge the game classes come from Forge's remapped client jar,
     * but OptiFine's payloads are deltas against the <b>vanilla</b> bytes (their patch2.cfg maps to
     * obfuscated names), so the vanilla jar is what they must be applied to.
     */
    public static Path findBaseJar(RuntimeInfo info, Path gameDir, Path optifineJar, String override,
                                   StringBuilder notes) {
        List<Path> candidates = new ArrayList<>();
        if (override != null && !override.isBlank()) candidates.add(Path.of(override));
        if (gameDir != null && !info.mcVersion.isEmpty()) {
            candidates.add(gameDir.resolve("versions").resolve(info.mcVersion).resolve(info.mcVersion + ".jar"));
        }
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path p = Path.of(entry);
            if (!Files.isRegularFile(p)) continue;
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar")) continue;
            if (optifineJar != null && p.toAbsolutePath().normalize().equals(optifineJar.toAbsolutePath().normalize())) continue;
            candidates.add(p);
        }
        try (OptifinePatcher patcher = new OptifinePatcher(optifineJar)) {
            // Prefer the jar the game actually loads. Forge ships a client jar that already carries its
            // own binary patches (measured on 1.21.1: Direction.getUpdateOrder and its data come from
            // there). Patching the *vanilla* jar instead produced classes without those additions and the
            // world died with "Cannot invoke List.iterator() because Direction.getUpdateOrder() is null".
            // The MD5 probe below still decides, this only changes the order.
            List<Path> ordered = new ArrayList<>(candidates);
            ordered.sort((a, b) -> Boolean.compare(isForgeClientJar(a), isForgeClientJar(b)) * -1);
            for (Path candidate : ordered) {
                if (!Files.isRegularFile(candidate)) continue;
                try {
                    byte[] patched = patcher.apply("net/minecraft/CrashReport", candidate);
                    if (OptifinePatcher.sha(patched, "MD5").equals(patcher.expectedMd5("net/minecraft/CrashReport"))) {
                        return candidate;
                    }
                } catch (Exception e) {
                    // not this one
                }
            }
        } catch (IOException e) {
            notes.append("could not open the OptiFine patcher: ").append(e).append('\n');
        }
        notes.append("could not find the vanilla client jar; pass -Doptiforge.vanilla=<path>\n");
        return null;
    }

    /** @return true when the jar looks like Forge's patched client jar (the one the game loads). */
    private static boolean isForgeClientJar(Path jar) {
        String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.contains("-forge-")) return true;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            return zip.getEntry("net/minecraftforge/client/extensions/IForgeBlockModel") != null
                    || zip.getEntry("net/minecraftforge/common/ForgeMod.class") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Builds or loads the pack.
     *
     * @param remapEnabled whether the payload needs SRG->official rewriting (see docs/VERSIONS.md);
     *                     harmless to leave on when the payload is already official
     */
    public static OptifinePack prepare(Path gameDir, Path optifineJar, Path baseJar, Path mapping,
                                       boolean remapEnabled, String mcVersionForFixes, StringBuilder notes)
            throws IOException {
        // Threaded through instead of a static field: the version has to be known at the moment the pack is
        // built, and the first build can happen before the service has parsed the launch arguments.
        mcVersion = mcVersionForFixes == null ? "" : mcVersionForFixes;
        String key = key(optifineJar, baseJar);
        Path cacheRoot = gameDir == null ? null : gameDir.resolve("optiforge").resolve("pack").resolve(key);
        SrgRemap remap = null;
        if (remapEnabled && mapping != null && Files.isRegularFile(mapping)) {
            remap = SrgRemap.load(mapping);
        } else if (remapEnabled) {
            notes.append("remap requested but no mapping table is available; emitting payload as-is\n");
        }

        if (cacheRoot != null) {
            OptifinePack cached = loadFromCache(optifineJar, baseJar, cacheRoot, key, remap, notes);
            if (cached != null) {
                if (gameDir != null && !cached.ownClasses.isEmpty() ) {
                    // Staging must also happen on a cache hit: the pack cache and the library mod are
                    // two different things, and a cached pack on the first launch after this code was
                    // added would otherwise leave mods/ without the own classes.
                    writeLibraryMod(gameDir, key, cached.ownClasses, optifineJar, notes);
                }
                return cached;
            }
        }

        Map<String, byte[]> raw = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();

        try (OptifinePatcher patcher = new OptifinePatcher(optifineJar)) {
            Map<String, Integer> payload = patcher.payload();
            Map<String, String> configuration = patcher.configurationMap();
            Pattern[] patterns = patcher.patterns(configuration);
            try (ZipFile base = new ZipFile(baseJar.toFile())) {
                Object provider = patcher.newResourceProvider(base);
                for (String logical : payload.keySet()) {
                    try {
                        raw.put(logical.replace('/', '.'), patcher.applyWith(provider, patterns, configuration, logical));
                    } catch (Exception e) {
                        failures.add(logical + " : " + e.getMessage());
                    }
                }
            }
        }
        // Collision handling: a payload can declare both the SRG original and an OptiFine-added wrapper
        // under the official name (ModelPart: m_171324_ next to a deep-search getChild). The wrapper is
        // dropped and the original is renamed onto the official name, because vanilla callers expect the
        // original implementation; leaving the wrapper in place produced "this.head is null" in
        // DragonHeadModel. See CollisionFixer for the two failed attempts.
        Map<String, byte[]> patched = new LinkedHashMap<>();
        int renamed = 0;
        int unmapped = 0;
        int fixedClasses = 0;
        for (Map.Entry<String, byte[]> entry : raw.entrySet()) {
            byte[] bytes = entry.getValue();
            if (remap != null) {
                byte[] fixedBytes = CollisionFixer.fix(bytes, remap);
                if (fixedBytes != bytes) fixedClasses++;
                bytes = fixedBytes;
                SrgRemap.Result result = remap.remap(bytes);
                renamed += result.renamed;
                unmapped += result.unmapped;
                bytes = result.bytes;
            }
            patched.put(entry.getKey(), bytes);
        }
        notes.append("collision fixes: ").append(fixedClasses).append(" class(es)\n");
        java.util.Set<String> blocked = java.util.Set.of();
        // Forge widens members through its access transformer before transformers run; a payload
        // applied to vanilla bytes brings the vanilla (private) flags back, which crashed the game
        // with IllegalAccessError from Forge's own ForgeRenderTypes. Replay those grants.
        int widened = 0;
        ForgeAccessTransformers accessTransformers = ForgeAccessTransformers.load(remap, gameDir, mcVersion.startsWith("26."), notes);
        if (!accessTransformers.isEmpty()) {
            for (Map.Entry<String, byte[]> entry : patched.entrySet()) {
                byte[] updated = accessTransformers.transform(entry.getValue(), entry.getKey());
                if (updated != entry.getValue()) {
                    patched.put(entry.getKey(), updated);
                    widened++;
                }
            }
        }
        // give the game module the module reads OptiFine patches need (see ModuleReadsInjector).
        // Default on: measured harmless on 1.21 (no read is added that matters) and required by the
        // 1.21.6+ investigation; the switch exists so a regression can be isolated quickly.
        if (!"false".equalsIgnoreCase(System.getProperty("optiforge.moduleReads", "true"))) {
            for (Map.Entry<String, byte[]> entry : patched.entrySet()) {
                byte[] updated = ModuleReadsInjector.apply(entry.getValue(), entry.getKey());
                if (updated != entry.getValue()) patched.put(entry.getKey(), updated);
            }
        }
        // Forge 26 added a parameter to ImmediateWindowHandler.setupMinecraftWindow; OptiFine 26.x still
        // calls it with four arguments (measured: wrong number of arguments: 4 expected: 5).
        boolean modernWindow = mcVersion != null && mcVersion.startsWith("26.");
        notes.append("modern window fix: ").append(modernWindow).append(" (mc=").append(mcVersion).append(")\n");
        // OptiFine's payload can declare interfaces the running Forge does not have (measured on 26.1.2,
        // where IForgeVertexFormat is gone from Forge 64). Dropping them is the only option: keeping them
        // makes the class unloadable.
        // Without both versions the tag cannot identify this launch's Forge jars, and a partial index would
        // report real interfaces as absent (measured: it stripped IForgeVertexFormat on 1.21.7 and broke a
        // verified line). Strip only when the launch versions are known.
        boolean stripInterfaces = !mcVersion.isEmpty() && !forgeVersion.isEmpty();
        String versionTag = stripInterfaces ? mcVersion + "-" + forgeVersion : "";
        java.util.Set<String> available = stripInterfaces
                ? ForgeInterfaces.availableClasses(gameDir, versionTag)
                : java.util.Set.of();
        visibleClasses = available;
        // OptiFine 26.1.2 passes six arguments to fireRenderNameTagEvent while Forge ships five, so its
        // reflector reports "exists" but returns null. Probe the real arity and bypass the call if it cannot match.
        if (modernWindow && stripInterfaces) {
            int arity = ForgeInterfaces.maxParameterCount(
                    "net/minecraftforge/client/event/ForgeEventFactoryClient", "fireRenderNameTagEvent",
                    gameDir, versionTag);
            CompatFixes.bypassNameplateEvent = arity > 0 && arity < 6;
            notes.append("nameplate event arity: ").append(arity)
                    .append(CompatFixes.bypassNameplateEvent ? " (reflector bypassed)" : "").append('\n');
        }
        int strippedInterfaces = 0;
        for (Map.Entry<String, byte[]> entry : patched.entrySet()) {
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(entry.getValue()).accept(node, 0);
            var dropped = ForgeInterfaces.strip(node, available);
            if (dropped.isEmpty()) continue;
            org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
            node.accept(writer);
            patched.put(entry.getKey(), writer.toByteArray());
            strippedInterfaces++;
            System.out.println("[OptiForge] dropped absent interface(s) " + dropped + " from " + entry.getKey());
        }
        notes.append(stripInterfaces
                        ? "classpath index: " + available.size() + " classes; absent Forge interfaces dropped: "
                                + strippedInterfaces + " class(es)\n"
                        : "classpath index: skipped (launch versions unknown), no interface dropped\n");
        // Diagnostics escape hatch: -Doptiforge.skipClasses=a.b.C,d.e.F leaves those classes to Forge.
        // Used to isolate classes where OptiFine's whole-class replacement drops something Forge adds at
        // runtime (measured on 1.21.1: Direction.getUpdateOrder() returned null because the mixin-added
        // data was missing from the replaced class).
        String skip = System.getProperty("optiforge.skipClasses", "");
        if (!skip.isBlank()) {
            for (String name : skip.split(",")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty() && patched.remove(trimmed) != null) {
                    notes.append("skipped (per -Doptiforge.skipClasses): ").append(trimmed).append('\n');
                    System.out.println("[OptiForge] skipped " + trimmed);
                }
            }
        }
        int compatFixed = 0;
        for (Map.Entry<String, byte[]> entry : patched.entrySet()) {
            byte[] updated = CompatFixes.apply(entry.getValue(), entry.getKey(), modernWindow);
            if (updated != entry.getValue()) {
                patched.put(entry.getKey(), updated);
                compatFixed++;
            }
        }
        notes.append("compat fixes: ").append(compatFixed).append(" class(es)\n");
        notes.append("access transformers re-applied to ").append(widened).append(" class(es)\n");
        notes.append("patched ").append(patched.size()).append(" classes, ")
                .append(failures.size()).append(" failure(s)\n");        for (int i = 0; i < Math.min(5, failures.size()); i++) {
            notes.append("  failed: ").append(failures.get(i)).append('\n');
        }
        if (remap != null) {
            notes.append("remapped ").append(renamed).append(" SRG token(s), ")
                    .append(unmapped).append(" unmapped, ")
                    .append(blocked.size()).append(" left unrenamed to avoid collisions\n");
        }

        Map<String, Path> own = new LinkedHashMap<>();
        if (cacheRoot != null) {
            writeCache(cacheRoot, key, patched, optifineJar, remap, notes, blocked);
            own = collectOwnClasses(cacheRoot, notes);
        }
        if (own.isEmpty()) {
            own = writeOwnToTemp(optifineJar, remap, notes, blocked);
        }
        if (gameDir != null && !own.isEmpty() ) {
            // The game can only see net.optifine.** if it is part of a module in the GAME layer. On
            // Forge 1.20.1 that happened because the OptiFine jar itself sat in mods/ and was mounted
            // into the SERVICE layer; in the takeover the jar is outside mods/, so its classes are
            // invisible (measured: NoClassDefFoundError: net/optifine/util/MathUtils while
            // net.minecraft.Util initialised). Staging the remapped own classes as a library mod
            // puts them in the GAME layer the same way any mod's classes get there.
            writeLibraryMod(gameDir, key, own, optifineJar, notes);
        }
        return new OptifinePack(optifineJar, baseJar, cacheRoot, patched, own, failures.size(),
                renamed, unmapped, remap != null, key);
    }

    /**
     * Writes {@code mods/optiforge-optifine-<key>.jar}: OptiFine's own (already remapped) classes plus
     * its assets, as a {@code FMLModType: LIBRARY} mod. No {@code META-INF/services} entry, so Forge
     * mounts it as a plain library and never treats it as a transformation service.
     */
    /** Classes visible on this launch; used to decide which foreign class literals are safe to keep. */
    private static volatile java.util.Set<String> visibleClasses = java.util.Set.of();

    private static void writeLibraryMod(Path gameDir, String key, Map<String, Path> own,
                                        Path optifineJar, StringBuilder notes) throws IOException {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        Path target = mods.resolve("optiforge-optifine-classes.jar");
        // Rewritten on every launch on purpose: the file is derived data (it changes whenever the
        // OptiFine jar or the remap table changes), and a fixed name keeps mods/ free of stale copies
        // for other OptiFine builds. Writing ~2 MB costs a few tens of milliseconds.
        notes.append("staging library mod ").append(target.getFileName()).append('\n');
        String toml = "modLoader=\"javafml\"\n"
                + "loaderVersion=\"[1,)\"\n"
                + "license=\"All rights reserved\"\n"
                + "\n"
                + "[[mods]]\n"
                + "modId=\"optiforge_optifine_classes\"\n"
                + "version=\"1.0.0\"\n"
                + "displayName=\"OptiForge - OptiFine classes\"\n"
                + "description=\"OptiFine's own classes, prepared by OptiForge from the user's OptiFine jar.\"\n";

        boolean stageClasses = !"false".equalsIgnoreCase(System.getProperty("optiforge.stageClasses", "true"));
        Path tmp = mods.resolve(target.getFileName() + ".tmp");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(tmp))) {
            put(out, "META-INF/MANIFEST.MF",
                    ("Manifest-Version: 1.0\r\n"
                            + "Implementation-Title: OptiForge\r\n"
                            + "\r\n").getBytes(StandardCharsets.UTF_8));
            put(out, "META-INF/mods.toml", toml.getBytes(StandardCharsets.UTF_8));
            // A real javafml mod, not a library and not a transformer: measured here, a
            // FMLModType: LIBRARY jar lands in the PLUGIN layer whose parent is the app loader, so it
            // resolved the UNTRANSFORMED game classes and died with "IllegalAccessError ... private
            // field Direction.VALUES" (OptiForge's patched Direction widens it, the app-loader copy does
            // not). A service-declaring jar is mounted as a transformation service and ends up with two
            // modules exporting the same package. A plain javafml mod is mounted with the other mods and
            // sees the patched classes. The @Mod annotation is generated here because OptiForge itself
            // must not compile against Forge.
            // Optional explicit module descriptor. Measured on Forge 56: the staged automatic module
            // does not read net.minecraftforge.eventbus, and OptiFine's Reflector references
            // net.minecraftforge.eventbus.api.Event directly, so the launch dies with
            // NoClassDefFoundError. "requires static" asks for the Forge modules without making the
            // layer fail when a line does not have one.
            if (Boolean.getBoolean("optiforge.moduleInfo")) {
                put(out, "module-info.class", moduleDescriptor());
            }            put(out, "optiforge/generated/GeneratedMod.class", generatedModClass());
            int stagedClasses = 0;
            if (stageClasses) {
                for (Map.Entry<String, Path> entry : own.entrySet()) {
                    String path = entry.getKey().replace('.', '/') + ".class";
                    put(out, path, OwnRefs.apply(Files.readAllBytes(entry.getValue()), entry.getKey(),
                            visibleClasses));
                    stagedClasses++;
                }
            }
            int resources = 0;
            try (ZipFile source = new ZipFile(optifineJar.toFile())) {
                var entries = source.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (!name.startsWith("assets/")) continue;
                    try (InputStream in = source.getInputStream(entry)) {
                        put(out, name, in.readAllBytes());
                    }
                    resources++;
                }
            }
            notes.append("library mod: ").append(target.getFileName()).append(" (")
                    .append(stagedClasses).append(" classes, ").append(resources).append(" assets)\n");
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void put(java.util.zip.ZipOutputStream out, String name, byte[] data) throws IOException {
        java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(name);
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    /**
     * Bytes of a marker class annotated with Forge's {@code @Mod("optiforge_optifine_classes")}.
     *
     * <p>Generated at runtime so that OptiForge keeps its no-Forge-compile-dependency rule: the only
     * thing needed is the annotation descriptor, which ASM can emit without the annotation class on
     * the compile classpath.
     */
    static byte[] generatedModClass() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL,
                "optiforge/generated/GeneratedMod", null, "java/lang/Object", null);
        // Forge instantiates the mod class, so it needs a constructor: measured without one,
        // "Failed to create mod instance. ModID: optiforge_optifine_classes, class
        // optiforge.generated.GeneratedMod" is logged during mod loading.
        var constructor = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        var annotation = writer.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        annotation.visit("value", "optiforge_optifine_classes");
        annotation.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    // ---- cache ----

    /** Bumped whenever the pack contents change shape, so stale caches are never reused. */
    /**
     * Bump when anything about <b>preparation</b> changes (remap, collision handling, AT replay, compat
     * fixes, own-class processing). The key only hashes the input jars, so without this a pack built by an
     * older OptiForge kept being reused and new fixes silently never ran - measured on 26.1.2, whose pack
     * still dated from before OwnRefs existed.
     */
    private static final String PACK_VERSION = "v3";

    private static String key(Path optifineJar, Path baseJar) {
        String a = hashFile(optifineJar, 16);
        String b = hashFile(baseJar, 8);
        return PACK_VERSION + "-" + a + "-" + b;
    }

    private static String hashFile(Path file, int chars) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.substring(0, Math.min(chars, sb.length()));
        } catch (Exception e) {
            return "nohash";
        }
    }

    private static OptifinePack loadFromCache(Path optifineJar, Path baseJar, Path cacheRoot, String key,
                                              SrgRemap remap, StringBuilder notes) {
        Path manifest = cacheRoot.resolve("manifest.txt");
        if (!Files.isRegularFile(manifest)) return null;
        try {
            String text = Files.readString(manifest, StandardCharsets.UTF_8);
            Map<String, byte[]> patched = new LinkedHashMap<>();
            Path patchedDir = cacheRoot.resolve("patched");
            if (Files.isDirectory(patchedDir)) {
                try (var walk = Files.walk(patchedDir)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        String rel = patchedDir.relativize(file).toString().replace('\\', '/');
                        String className = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
                        patched.put(className, Files.readAllBytes(file));
                    }
                }
            }
            Map<String, Path> own = collectOwnClasses(cacheRoot, notes);
            notes.append("pack cache hit: ").append(cacheRoot).append('\n');
            notes.append(text);
            return new OptifinePack(optifineJar, baseJar, cacheRoot, patched, own, 0, 0, 0,
                    remap != null, key);
        } catch (IOException e) {
            notes.append("could not read pack cache: ").append(e).append('\n');
            return null;
        }
    }

    private static Map<String, Path> collectOwnClasses(Path cacheRoot, StringBuilder notes) {
        Map<String, Path> own = new LinkedHashMap<>();
        Path ownDir = cacheRoot.resolve("own");
        if (!Files.isDirectory(ownDir)) return own;
        try (var walk = Files.walk(ownDir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String rel = ownDir.relativize(file).toString().replace('\\', '/');
                if (!rel.endsWith(".class")) continue;
                own.put(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'), file);
            }
        } catch (IOException e) {
            notes.append("could not read cached own classes: ").append(e).append('\n');
        }
        if (own.isEmpty()) notes.append("pack cache has no own classes\n");
        return own;
    }

    private static void writeCache(Path cacheRoot, String key, Map<String, byte[]> patched,
                                   Path optifineJar, SrgRemap remap, StringBuilder notes,
                                   java.util.Set<String> blocked) {
        try {
            Files.createDirectories(cacheRoot.resolve("patched"));
            for (Map.Entry<String, byte[]> entry : patched.entrySet()) {
                Path target = cacheRoot.resolve("patched")
                        .resolve(entry.getKey().replace('.', java.io.File.separatorChar) + ".class");
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            }
            int own = writeOwnClasses(optifineJar, remap, cacheRoot.resolve("own"), notes, blocked);
            Files.writeString(cacheRoot.resolve("manifest.txt"),
                    "optifine=" + optifineJar.getFileName() + "\n"
                            + "patched=" + patched.size() + "\n"
                            + "own=" + own + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            notes.append("could not write pack cache: ").append(e).append('\n');
        }
    }

    private static Map<String, Path> writeOwnToTemp(Path optifineJar, SrgRemap remap, StringBuilder notes,
                                                    java.util.Set<String> blocked) throws IOException {
        Path dir = Files.createTempDirectory("optiforge-own-");
        writeOwnClasses(optifineJar, remap, dir, notes, blocked);
        Map<String, Path> own = new LinkedHashMap<>();
        try (var walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String rel = dir.relativize(file).toString().replace('\\', '/');
                if (!rel.endsWith(".class")) continue;
                own.put(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'), file);
            }
        }
        return own;
    }

    /** Extracts OptiFine's own {@code srg/**} classes, remapped, into {@code targetDir}. */
    private static int writeOwnClasses(Path optifineJar, SrgRemap remap, Path targetDir, StringBuilder notes,
                                       java.util.Set<String> blocked) throws IOException {
        int written = 0;
        int renamed = 0;
        try (ZipFile zip = new ZipFile(optifineJar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("srg/") || !name.endsWith(".class")) continue;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                if (remap != null) {
                    SrgRemap.Result result = remap.remap(bytes, blocked);
                    renamed += result.renamed;
                    bytes = result.bytes;
                }
                Path target = targetDir.resolve(name.substring("srg/".length()).replace('/', java.io.File.separatorChar));
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
                written++;
            }
        }
        notes.append("own classes: ").append(written).append(", remapped tokens ").append(renamed).append('\n');
        return written;
    }

    public String summary() {
        return "pack " + key + ": " + patchedCount + " patched classes (failures " + patchFailures + "), "
                + ownClasses.size() + " own classes, remap " + (remapApplied ? "on" : "off")
                + " [" + renamedTokens + " renamed, " + unmappedTokens + " unmapped], base "
                + baseJar.getFileName();
    }

    /**
     * A module descriptor for the staged jar: open (FML reflects on the generated @Mod class) and
     * requires the Forge modules as optional, so a missing name cannot break layer resolution.
     */
    static byte[] moduleDescriptor() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_MODULE,
                "module-info", null, null, null);
        var visitor = writer.visitModule("optiforge_optifine_classes", org.objectweb.asm.Opcodes.ACC_OPEN, null);
        visitor.visitRequire("java.base", org.objectweb.asm.Opcodes.ACC_MANDATED, null);
        // Hard requires for the Forge modules that live in a parent layer of the mod layer. The names
        // are stable across Forge 56+ (the first line where eventbus became its own module); the list
        // stays opt-in behind -Doptiforge.moduleInfo=true so a line without one cannot be broken by it.
        for (String module : new String[]{"net.minecraftforge.eventbus", "net.minecraftforge.forgespi",
                "net.minecraftforge.fmlloader", "net.minecraftforge.coremod", "net.minecraftforge.mergetool.api",
                "net.minecraftforge.earlydisplay", "org.apache.logging.log4j",
                "org.apache.logging.log4j.core"}) {
            visitor.visitRequire(module, 0, null);
        }
        for (String module : new String[]{"minecraft", "net.minecraftforge.forge",
                "net.minecraftforge.eventbus", "net.minecraftforge.fmlcore", "net.minecraftforge.fmlloader",
                "net.minecraftforge.coremod", "net.neoforged.bus", "net.neoforged.neoforge",
                // libraries OptiFine's own classes use; an explicit module does not inherit the
                // automatic "read everything" behaviour, so each one has to be named.
                "org.apache.logging.log4j", "org.slf4j", "com.google.gson", "com.google.common",
                "org.objectweb.asm", "org.objectweb.asm.tree", "it.unimi.dsi.fastutil", "datafixerupper",
                "brigadier", "authlib", "org.joml", "org.lwjgl", "org.lwjgl.opengl"}) {
            // ACC_STATIC_PHASE (0x40), not ACC_STATIC (0x20, which is ACC_TRANSITIVE here): only the
            // former makes the dependency optional at runtime, and a line without (say) NeoForge must
            // not fail layer resolution - measured: without it, "Module net.neoforged.neoforge not
            // found, required by optiforge_optifine_classes".
            visitor.visitRequire(module, org.objectweb.asm.Opcodes.ACC_STATIC_PHASE, null);
        }
        visitor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }}
