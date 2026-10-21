package kynarain.cn.optiforge;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    /** Modern SRG member ids ({@code m_123_}/{@code f_123_}), used from the 1.17 line on. */
    private static final Pattern SRG = Pattern.compile("[fm]_\\d+_");
    /**
     * The older MCP/SRG spelling used by the 1.13-1.16 runtime ({@code func_123_}/{@code field_123_}).
     * It already matches those lines' Forge runtime, so it must be treated like SRG for the remap
     * decision: no remap.
     */
    private static final Pattern MCP = Pattern.compile("(?:func|field)_\\d+_");

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
            J8.setOf("optifine.", "net.optifine.");
    public static final java.util.Set<String> RESOURCE_PREFIXES = J8.setOf("assets/");

    /**
     * Modules found in the layers that are parents of the mod layer (Forge's own, plus their libraries).
     *
     * <p>An explicit module descriptor has to name what it reads; these names are observed at launch, so a
     * hard {@code requires} can never point at a module the line does not have. The game module is
     * deliberately not listed: it lives in a <b>child</b> layer and Forge grants that read at runtime.
     */
    public static volatile java.util.List<String> parentModules = J8.<String>listOf();

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
     * version number: the jar that carries {@code net/minecraft/CrashReport.class} has {@code forThrowable}
     * (official names, Forge 1.20.6+), {@code m_127521_} (modern SRG, Forge 1.17-1.20.4) or
     * {@code func_..._} (the MCP/SRG spelling of the 1.13-1.16 Forge runtime).
     *
     * <p>Only the jars this launch really uses are inspected: the class/module path entries plus the
     * library jars of the <b>running</b> version tag ({@link LaunchPaths#runtimeJars}). Scanning every
     * jar under {@code -Doptiforge.libraries} was measured to pick a sibling rig's
     * {@code forge-1.17.1-37.0.103-client.jar} on a 1.16.5 launch. When the runtime jars carry no
     * readable {@code net/minecraft/CrashReport.class} (e.g. the 1.16.5 launch classpath only has the
     * obfuscated copy), the already-resolved base jar is the documented fallback.
     */
    public enum Namespace {OFFICIAL, SRG, MCP, UNKNOWN}

    public static Namespace detectRuntimeNamespace(RuntimeInfo info, Path gameDir, Path baseJar,
                                                   StringBuilder notes) {
        String mcVersion = info == null ? "" : info.mcVersion;
        String forgeVersion = info == null ? "" : info.forgeVersion;
        List<Path> runtimeJars = LaunchPaths.runtimeJars(gameDir, mcVersion, forgeVersion);
        Namespace found = detectInJars(runtimeJars, "launch jars", notes);
        if (found != null) return found;
        if (baseJar != null) {
            found = detectInJars(J8.listOf(baseJar), "base jar fallback", notes);
            if (found != null) return found;
        }
        notes.append("runtime namespace: unknown - no srg-named net/minecraft/crash/CrashReport in the launch"
                        + " jars (checked ").append(runtimeJars.size()).append(" jar(s) of MC ")
                .append(mcVersion.isEmpty() ? "<unknown>" : mcVersion).append(" / Forge ")
                .append(forgeVersion.isEmpty() ? "<unknown>" : forgeVersion).append(")")
                .append(baseJar == null ? "" : "; base jar " + baseJar.getFileName()
                        + " is the vanilla obf form and cannot be classified")
                .append(" - remap stays disabled, which is the safe outcome for an srg payload\n");
        return Namespace.UNKNOWN;
    }

    private static Namespace detectInJars(List<Path> candidates, String source, StringBuilder notes) {
        // The class moved between the two naming generations:
        //   net/minecraft/crash/CrashReport  (1.13-1.16, the lower-case package)
        //   net/minecraft/CrashReport        (1.17+)
        // Looking only for the 1.17+ name made every 1.14-1.16 launch report "unknown" (measured on the
        // Lead's first real 1.16.5 run, even after the jar list was filtered to the running line).
        for (Path p : candidates) {
            try (ZipFile zip = new ZipFile(p.toFile())) {
                byte[] bytes = null;
                String entryName = null;
                for (String entry : new String[]{
                        "net/minecraft/CrashReport.class", "net/minecraft/crash/CrashReport.class"}) {
                    ZipEntry crashReport = zip.getEntry(entry);
                    if (crashReport == null) continue;
                    try (InputStream in = zip.getInputStream(crashReport)) {
                        bytes = J8.readAll(in);
                        entryName = entry;
                    }
                    break;
                }
                if (bytes == null) continue;
                // Judge by this class's own *members*, not by the raw bytes: 1.19.4 keeps parameter names such
                // as "forThrowable" in its local variable table, which made a byte scan claim official names
                // and switch on a remap that must not run there (Forge 1.17-1.19.4 use official class names
                // with SRG member names, which is exactly what OptiFine's patch/srg payload produces).
                org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
                new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
                int srgMembers = 0;
                int mcpMembers = 0;
                int plainMembers = 0;
                for (org.objectweb.asm.tree.MethodNode method : node.methods) {
                    if (method.name.startsWith("<")) continue;
                    if (SRG.matcher(method.name).find()) srgMembers++;
                    else if (MCP.matcher(method.name).find()) mcpMembers++;
                    else plainMembers++;
                }
                for (org.objectweb.asm.tree.FieldNode field : node.fields) {
                    if (SRG.matcher(field.name).find()) srgMembers++;
                    else if (MCP.matcher(field.name).find()) mcpMembers++;
                    else plainMembers++;
                }
                // The 1.14.4-1.16.5 runtime is func_/field_ plus a handful of plain synthetic members;
                // classifying it as MCP (no remap) is what the third state exists for.
                if (mcpMembers > 0 && mcpMembers >= srgMembers) {
                    notes.append("runtime namespace: MCP/SRG func_/field_ names (").append(p.getFileName())
                            .append(" via ").append(entryName)
                            .append(", ").append(mcpMembers).append(" mcp / ").append(srgMembers)
                            .append(" srg / ").append(plainMembers)
                            .append(" plain) - matches the payload, no remap\n")
                            .append("namespace decided by: ").append(p).append(" (").append(source).append(")\n");
                    return Namespace.MCP;
                }
                if (srgMembers > plainMembers) {
                    notes.append("runtime namespace: SRG names (").append(p.getFileName()).append(", ")
                            .append(srgMembers).append(" srg / ").append(plainMembers).append(" plain)\n")
                            .append("namespace decided by: ").append(p).append(" (").append(source).append(")\n");
                    return Namespace.SRG;
                }
                if (plainMembers > 0) {
                    notes.append("runtime namespace: official names (").append(p.getFileName()).append(", ")
                            .append(plainMembers).append(" plain / ").append(srgMembers).append(" srg)\n")
                            .append("namespace decided by: ").append(p).append(" (").append(source).append(")\n");
                    return Namespace.OFFICIAL;
                }
            } catch (IOException ignored) {
                // not a readable jar; try the next one
            }
        }
        return null;
    }

    /**
     * Picks the SRG->official table to use: exact Minecraft version first, else the only table bundled
     * (with a note), else none. Renaming is a no-op when the payload is already official, so a table
     * built for another line can only hurt when it is applied to an SRG payload of a different line -
     * which is why {@code remapEnabled} is decided by the runtime namespace, not by this lookup.
     */
    public static Path pickMapping(String mcVersion, StringBuilder notes) {
        java.util.List<String> available = new ArrayList<>();
        try (java.io.InputStream in = OptifinePack.class.getResourceAsStream("/optiforge/mappings/index.txt")) {
            if (in != null) {
                for (String line : new String(J8.readAll(in), StandardCharsets.UTF_8).split("\\R")) {
                    if (!J8.isBlank(line)) available.add(line.trim());
                }
            }
        } catch (IOException ignored) {
            // fall through with an empty list
        }
        String chosen = null;
        if (!J8.isBlank(mcVersion) && available.contains(mcVersion)) {
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
        try (java.io.InputStream in = OptifinePack.class.getResourceAsStream("/optiforge/mappings/" + chosen + "-srg-to-official.tsv")) {
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
    /** Cheap sanity check: does this zip actually carry OptiFine's classes? */
    static boolean looksLikeOptifineJar(Path jar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("net/optifine/") || name.startsWith("srg/net/optifine/")
                        || name.startsWith("notch/net/optifine/")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // unreadable: treat as "not OptiFine"
        }
        return false;
    }
    public static Path findOptifineJar(Path gameDir, String override, StringBuilder notes) {
        if (override != null && !J8.isBlank(override)) {
            Path p = Paths.get(override);
            if (Files.isRegularFile(p)) return p;
            notes.append("optiforge.optifine points at ").append(p).append(" but it is not a file\n");
        }
        if (gameDir != null) {
            Path dir = gameDir.resolve("optiforge");
            if (Files.isDirectory(dir)) {
                // OptiFabric picks the OptiFine jar out of a folder of jars; here the folder exists only for
                // it, but being picky keeps a stray jar from being patched: prefer names that say OptiFine,
                // then any jar that actually carries OptiFine classes.
                java.util.List<Path> jars = new java.util.ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
                    for (Path candidate : stream) jars.add(candidate);
                } catch (IOException e) {
                    notes.append("could not list ").append(dir).append(": ").append(e).append('\n');
                }
                jars.sort(java.util.Comparator.comparing(
                        (Path candidate) -> !candidate.getFileName().toString().toLowerCase(Locale.ROOT)
                                .contains("optifine")));
                for (Path candidate : jars) {
                    if (candidate.getFileName().toString().toLowerCase(Locale.ROOT).contains("optifine")
                            || looksLikeOptifineJar(candidate)) {
                        return candidate;
                    }
                    notes.append("ignoring ").append(candidate.getFileName())
                            .append(" (not an OptiFine jar)\n");
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
     *
     * <p>Search order, as the phase-3 legacy path uses and as the first real 1.16.5 launch demanded
     * ({@code gameDir} was not under the rig root, so only the explicit property or a sibling
     * {@code versions/<mc>/} layout could find it):
     * <ol>
     *   <li>{@code -Doptiforge.vanilla=<path>};</li>
     *   <li>{@code <gameDir>/versions/<mc>/<mc>.jar};</li>
     *   <li>the same path under every ancestor of {@code gameDir} (up to 4 levels), every library root's
     *       parent (a shared rig keeps {@code versions/} next to {@code libraries/}), and
     *       {@code ~/.minecraft};</li>
     *   <li>every {@code java.class.path} entry whose file name is exactly {@code <mc>.jar};</li>
     *   <li>the newest non-Forge profile under any of those {@code versions/} directories.</li>
     * </ol>
     * Forge's patched client jar is tried first on the modern lines (its binary patches have to survive),
     * last on the legacy lines (measured: the 1.16.5 payload's patch2.cfg resolves obfuscated entry names,
     * so the srg-named Forge jar cannot match; the vanilla jar matches 311/311, 299/299, 246/246).
     *
     * <p>A candidate is accepted only when OptiFine's own patcher applies a payload entry to it and the
     * resulting MD5 equals the {@code .class.md5} in the OptiFine jar - and the probe uses entries the
     * payload actually has (the fixed {@code net/minecraft/CrashReport} probe was absent from the
     * 1.14-1.16 payloads, which made every candidate look wrong, including a valid
     * {@code -Doptiforge.vanilla}).
     */
    public static Path findBaseJar(RuntimeInfo info, Path gameDir, Path optifineJar, String override,
                                   StringBuilder notes) {
        String mc = info == null ? "" : info.mcVersion;
        String forge = info == null ? "" : info.forgeVersion;
        List<Path> vanilla = new ArrayList<>();
        if (!J8.isBlank(override)) {
            Path p = Paths.get(override);
            if (Files.isRegularFile(p)) vanilla.add(p);
            else notes.append("optiforge.vanilla points at ").append(p).append(" but it is not a file\n");
        }

        List<Path> versionRoots = new ArrayList<>();
        if (gameDir != null) {
            Path cursor = gameDir.toAbsolutePath();
            for (int depth = 0; depth < 4 && cursor != null; depth++) {
                versionRoots.add(cursor);
                cursor = cursor.getParent();
            }
        }
        String libraries = System.getProperty("optiforge.libraries", "");
        if (!J8.isBlank(libraries)) {
            Path parent = Paths.get(libraries).toAbsolutePath().getParent();
            if (parent != null) versionRoots.add(parent);
        }
        String home = System.getProperty("user.home", "");
        if (!J8.isBlank(home)) versionRoots.add(Paths.get(home).resolve(".minecraft"));

        if (!J8.isBlank(mc)) {
            if (gameDir != null) {
                vanilla.add(gameDir.resolve("versions").resolve(mc).resolve(mc + ".jar"));
            }
            for (Path root : versionRoots) {
                vanilla.add(root.resolve("versions").resolve(mc).resolve(mc + ".jar"));
            }
            for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
                if (J8.isBlank(entry)) continue;
                Path p;
                try {
                    p = Paths.get(entry);
                } catch (RuntimeException ignored) {
                    continue;
                }
                String name = p.getFileName() == null ? "" : p.getFileName().toString();
                if (name.equalsIgnoreCase(mc + ".jar") && !sameFile(p, optifineJar)) vanilla.add(p);
            }
            // "Newest non-Forge profile": a shared rig keeps versions/<mc>/ next to libraries/, but a
            // launcher may also ship dated vanilla profiles. Pick the newest jar of a profile whose id
            // is not a Forge profile.
            for (Path root : versionRoots) {
                Path versions = root.resolve("versions");
                if (!Files.isDirectory(versions)) continue;
                Path newest = null;
                long newestTime = Long.MIN_VALUE;
                try (DirectoryStream<Path> streams = Files.newDirectoryStream(versions)) {
                    for (Path dir : streams) {
                        if (!Files.isDirectory(dir)) continue;
                        String id = dir.getFileName().toString();
                        if (id.toLowerCase(Locale.ROOT).contains("forge")) continue;
                        Path jar = dir.resolve(id + ".jar");
                        if (!Files.isRegularFile(jar)) continue;
                        long time = Files.getLastModifiedTime(jar).toMillis();
                        if (time > newestTime) {
                            newestTime = time;
                            newest = jar;
                        }
                    }
                } catch (IOException ignored) {
                    // keep what we have
                }
                if (newest != null) vanilla.add(newest);
            }
        }

        // Forge's patched client jar: the jar the game classes actually come from on the modern lines.
        List<Path> forgeClients = new ArrayList<>();
        if (!J8.isBlank(mc) && !J8.isBlank(forge)) {
            String tag = mc + "-" + forge;
            for (Path root : versionRoots) {
                Path client = root.resolve("libraries").resolve("net").resolve("minecraftforge")
                        .resolve("forge").resolve(tag).resolve("forge-" + tag + "-client.jar");
                if (Files.isRegularFile(client)) forgeClients.add(client);
            }
            for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
                if (J8.isBlank(entry)) continue;
                Path p;
                try {
                    p = Paths.get(entry);
                } catch (RuntimeException ignored) {
                    continue;
                }
                String name = p.getFileName() == null ? "" : p.getFileName().toString();
                if (name.equals("forge-" + tag + "-client.jar") && !sameFile(p, optifineJar)) forgeClients.add(p);
            }
        }

        List<Path> ordered = new ArrayList<>();
        if (Bootstrap.legacy()) {
            ordered.addAll(vanilla);
            ordered.addAll(forgeClients);
        } else {
            ordered.addAll(forgeClients);
            ordered.addAll(vanilla);
        }
        List<Path> candidates = dedupe(ordered, optifineJar);

        try (OptifinePatcher patcher = new OptifinePatcher(optifineJar)) {
            Map<String, Integer> payload = patcher.payload();
            List<String> probe = new ArrayList<>(payload.keySet());
            // Small classes first (fast probe); keep a CrashReport probe first where the payload has it.
            probe.sort((a, b) -> {
                Integer sa = payload.get(a);
                Integer sb = payload.get(b);
                int bySize = (sa == null ? 0 : sa) - (sb == null ? 0 : sb);
                return bySize != 0 ? bySize : a.compareTo(b);
            });
            if (probe.remove("net/minecraft/CrashReport")) probe.add(0, "net/minecraft/CrashReport");
            for (Path candidate : candidates) {
                String confirmed = confirmBase(patcher, probe, candidate);
                if (confirmed != null) {
                    notes.append("base jar: ").append(candidate.getFileName())
                            .append(" (confirmed by ")
                            .append(patcher.mode() == OptifinePatcher.Mode.NOTCH ? "patch/" : "patch/srg/")
                            .append(confirmed).append(".class MD5)\n");
                    return candidate;
                }
                if (J8.isBlank(override) || !sameFile(candidate, Paths.get(override))) {
                    notes.append("  base candidate rejected: ").append(candidate.getFileName()).append('\n');
                } else {
                    notes.append("  base candidate rejected: ").append(candidate.getFileName())
                            .append(" (this is the -Doptiforge.vanilla value; it does not carry the payload's"
                                    + " base bytes)\n");
                }
            }
        } catch (IOException e) {
            notes.append("could not open the OptiFine patcher: ").append(e).append('\n');
        }
        notes.append("could not find the vanilla client jar; tried ").append(candidates.size())
                .append(" candidate(s). pass -Doptiforge.vanilla=<path to versions/<mc>/<mc>.jar>\n");
        return null;
    }

    /** @return the payload entry that confirmed {@code candidate}, or null when nothing matched. */
    private static String confirmBase(OptifinePatcher patcher, List<String> probe, Path candidate) {
        int tried = 0;
        for (String logical : probe) {
            if (tried >= 8) break;
            tried++;
            try {
                byte[] patched = patcher.apply(logical, candidate);
                if (OptifinePatcher.sha(patched, "MD5").equals(patcher.expectedMd5(logical))) {
                    return logical;
                }
            } catch (Exception ignored) {
                // not this entry; try the next one
            }
        }
        return null;
    }

    private static boolean sameFile(Path a, Path b) {
        return a != null && b != null && a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    private static List<Path> dedupe(List<Path> candidates, Path optifineJar) {
        List<Path> out = new ArrayList<>();
        for (Path candidate : candidates) {
            if (candidate == null || !Files.isRegularFile(candidate)) continue;
            if (sameFile(candidate, optifineJar)) continue;
            boolean seen = false;
            for (Path existing : out) {
                if (sameFile(candidate, existing)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) out.add(candidate);
        }
        return out;
    }

    /** @return true when the jar looks like Forge's patched client jar (the one the game loads). */
    private static boolean isForgeClientJar(Path jar) {
        String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith("forge-") || name.contains("-forge-")) return true;
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
                    writeLibraryMod(gameDir, key, cached.ownClasses, optifineJar, cacheRoot, notes);
                }
                return cached;
            }
        }

        Map<String, byte[]> raw = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        // 1.13.2 is a different payload generation: flat patch/<obf>.class.xdelta and root net/optifine
        // classes, all in the obfuscated namespace. Its bytes have to be renamed into the Forge runtime
        // namespace (mcp_config joined.tsrg) before anything else touches them.
        ObfNamespaceRemap obfRemap = null;

        try (OptifinePatcher patcher = new OptifinePatcher(optifineJar)) {
            Map<String, Integer> payload = patcher.payload();
            Map<String, String> configuration = patcher.configurationMap();
            Pattern[] patterns = patcher.patterns(configuration);
            if (patcher.mode() == OptifinePatcher.Mode.NOTCH) {
                obfRemap = loadObfRemap(optifineJar, baseJar, notes);
                if (obfRemap == null) {
                    throw new IOException("the 1.13-style payload needs the obf->runtime mapping;"
                            + " build the oldml artifact with tools/build-oldml.ps1");
                }
            }
            try (ZipFile base = new ZipFile(baseJar.toFile())) {
                Object provider = patcher.newResourceProvider(base);
                for (String logical : payload.keySet()) {
                    try {
                        byte[] bytes = patcher.applyWith(provider, patterns, configuration, logical);
                        if (obfRemap != null) {
                            bytes = obfRemap.remap(bytes);
                        }
                        raw.put(classNameOf(bytes, logical), bytes);
                    } catch (Exception e) {
                        failures.add(logical + " : " + e.getMessage());
                    }
                }
            }
        }
        if (obfRemap != null) {
            notes.append("namespace bridge: ").append(obfRemap.describe()).append('\n');
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
        java.util.Set<String> blocked = J8.<String>setOf();
        // Forge widens members through its access transformer before transformers run; a payload
        // applied to vanilla bytes brings the vanilla (private) flags back, which crashed the game
        // with IllegalAccessError from Forge's own ForgeRenderTypes. Replay those grants.
        int widened = 0;
        List<String> widenedClasses = new ArrayList<>();
        ForgeAccessTransformers accessTransformers = ForgeAccessTransformers.load(remap, gameDir,
                mcVersion.startsWith("26."), mcVersion, forgeVersion, notes);
        if (!accessTransformers.isEmpty()) {
            for (Map.Entry<String, byte[]> entry : patched.entrySet()) {
                byte[] updated = accessTransformers.transform(entry.getValue(), entry.getKey());
                if (updated != entry.getValue()) {
                    patched.put(entry.getKey(), updated);
                    widened++;
                    if (widenedClasses.size() < 24) widenedClasses.add(entry.getKey());
                }
            }
        }
        // give the game module the module reads OptiFine patches need (see ModuleReadsInjector).
        // Default on: measured harmless on 1.21 (no read is added that matters) and required by the
        // 1.21.6+ investigation; the switch exists so a regression can be isolated quickly.
        if (!"false".equalsIgnoreCase(System.getProperty("optiforge.moduleReads", "true"))) {
            for (Map.Entry<String, byte[]> entry : patched.entrySet()) {
                byte[] updated = Bootstrap.moduleReads(entry.getValue(), entry.getKey());
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
                : J8.<String>setOf();
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
            java.util.List<String> dropped = ForgeInterfaces.strip(node, available);
            if (dropped.isEmpty()) continue;
            org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
            node.accept(writer);
            patched.put(entry.getKey(), writer.toByteArray());
            strippedInterfaces++;
            System.out.println("[OptiForge] dropped absent interface(s) " + dropped + " from " + entry.getKey());
        }
        // OptiFine's payload can call an interface default method through invokespecial without declaring that
        // interface, which the JVM rejects unless it is a direct superinterface (see SuperInterfaceFixer).
        int superInterfacesAdded = 0;
        for (Map.Entry<String, byte[]> entry : patched.entrySet()) {
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(entry.getValue()).accept(node, 0);
            java.util.List<String> added = SuperInterfaceFixer.fix(node, available);
            if (added.isEmpty()) continue;
            org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
            node.accept(writer);
            patched.put(entry.getKey(), writer.toByteArray());
            superInterfacesAdded += added.size();
        }
        notes.append("interface super-calls declared: ").append(superInterfacesAdded).append('\n');
        notes.append(stripInterfaces
                        ? "classpath index: " + available.size() + " classes; absent Forge interfaces dropped: "
                                + strippedInterfaces + " class(es)\n"
                        : "classpath index: skipped (launch versions unknown), no interface dropped\n");
        // Diagnostics escape hatch: -Doptiforge.skipClasses=a.b.C,d.e.F leaves those classes to Forge.
        // Used to isolate classes where OptiFine's whole-class replacement drops something Forge adds at
        // runtime (measured on 1.21.1: Direction.getUpdateOrder() returned null because the mixin-added
        // data was missing from the replaced class).
        String skip = System.getProperty("optiforge.skipClasses", "");
        if (!J8.isBlank(skip)) {
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
        notes.append("access transformers re-applied to ").append(widened).append(" class(es)");
        if (!widenedClasses.isEmpty()) {
            notes.append(": ").append(String.join(", ", widenedClasses));
            if (widened > widenedClasses.size()) notes.append(", ...");
        }
        notes.append('\n');
        notes.append("patched ").append(patched.size()).append(" classes, ")
                .append(failures.size()).append(" failure(s)\n");        for (int i = 0; i < Math.min(5, failures.size()); i++) {
            notes.append("  failed: ").append(failures.get(i)).append('\n');
        }
        if (remap != null) {
            notes.append("remapped ").append(renamed).append(" SRG token(s), ")
                    .append(unmapped).append(" unmapped, ")
                    .append(blocked.size()).append(" left unrenamed to avoid collisions\n");
        }

        SrgRemap srgRenumber = loadSrgRenumber(notes);
        if (srgRenumber != null) {
            Map<String, byte[]> renumbered = new LinkedHashMap<>();
            int renumberTokens = 0;
            for (Map.Entry<String, byte[]> entry : patched.entrySet()) {
                SrgRemap.Result result = srgRenumber.remap(entry.getValue());
                if (result.renamed > 0) {
                    renumbered.put(entry.getKey(), result.bytes);
                    renumberTokens += result.renamed;
                }
            }
            if (renumberTokens < 64) {
                // H1 and later builds already use the final ids. A handful of coincidental id overlaps must
                // not be renamed (they are different members in the final table).
                notes.append("SRG renumber: skipped (payload already uses final ids; ")
                        .append(renumberTokens).append(" coincidental token(s) left untouched)\n");
                srgRenumber = null;
            } else {
                patched.putAll(renumbered);
                notes.append("SRG renumber: ").append(renumberTokens).append(" token(s) in ")
                        .append(renumbered.size()).append(" patched class(es)\n");
            }
        }
        Map<String, Path> own = new LinkedHashMap<>();
        if (cacheRoot != null) {
            writeCache(cacheRoot, key, patched, optifineJar, remap, obfRemap, srgRenumber, notes, blocked);
            own = collectOwnClasses(cacheRoot, notes);
        }
        if (own.isEmpty()) {
            own = writeOwnToTemp(optifineJar, remap, obfRemap, srgRenumber, notes, blocked);
        }
        if (gameDir != null && !own.isEmpty() ) {
            // The game can only see net.optifine.** if it is part of a module in the GAME layer. On
            // Forge 1.20.1 that happened because the OptiFine jar itself sat in mods/ and was mounted
            // into the SERVICE layer; in the takeover the jar is outside mods/, so its classes are
            // invisible (measured: NoClassDefFoundError: net/optifine/util/MathUtils while
            // net.minecraft.Util initialised). Staging the remapped own classes as a library mod
            // puts them in the GAME layer the same way any mod's classes get there.
            writeLibraryMod(gameDir, key, own, optifineJar, cacheRoot, notes);
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
    private static volatile java.util.Set<String> visibleClasses = J8.<String>setOf();

    /**
     * Ranges for the staged mods.toml. The Java-17 artifact accepts 1.17+ with loaderVersion 1+; the
     * legacy artifact (1.14.4-1.16.5) declares {@code [1.13,)} for minecraft and the FML floor those lines
     * accept, {@code [14,)} - which is exactly what OptiFine's own legacy {@code META-INF/mods.toml}
     * declares. {@link Bootstrap#setLegacy(boolean)} sets both before the pack is prepared.
     */
    static volatile String minecraftRange = "[1.17,)";
    static volatile String loaderVersionRange = "[1,)";

    /**
     * mods.toml for the staged jar: the metadata Forge shows in the Mods list (name, author, licence, links).
     *
     * <p>Forge's javafml rejects unknown keys, so this sticks to the fields that exist on every 1.20+ line.
     */
    static String modToml() {
        return "modLoader=\"javafml\"\n"
                + "loaderVersion=\"" + loaderVersionRange + "\"\n"
                + "license=\"MPL 2.0\"\n"
                + "\n"
                + "[[mods]]\n"
                + "modId=\"optiforge\"\n"
                + "version=\"" + Diagnose.VERSION + "\"\n"
                + "displayName=\"OptiForge\"\n"
                + "authors=\"Kynarain\"\n"
                + "credits=\"OptiFine by sp614x\"\n"
                + "description=\"OptiFine on Forge, with Forge Mod Loader loaded alongside.\"\n"
                + "logoFile=\"optiforge_icon.png\"\n"
                + "displayURL=\"https://github.com/Kynarain/OptiForge\"\n"
                + "issueTrackerURL=\"https://github.com/Kynarain/OptiForge/issues\"\n"
                + "\n"
                + "[[dependencies.optiforge]]\n"
                + "modId=\"forge\"\n"
                + "mandatory=true\n"
                + "versionRange=\"[1,)\"\n"
                + "ordering=\"NONE\"\n"
                + "side=\"BOTH\"\n"
                + "\n"
                + "[[dependencies.optiforge]]\n"
                + "modId=\"minecraft\"\n"
                + "mandatory=true\n"
                + "versionRange=\"" + minecraftRange + "\"\n"
                + "ordering=\"NONE\"\n"
                + "side=\"BOTH\"\n";
    }
    /** Bytes of the mod icon shipped in OptiForge's own jar, written at the staged jar's root. */
    static byte[] iconBytes() throws IOException {
        try (java.io.InputStream in = OptifinePack.class.getResourceAsStream("/optiforge/optiforge_icon.png")) {
            if (in == null) throw new IOException("optiforge_icon.png is missing from the OptiForge jar");
            return J8.readAll(in);
        }
    }
    /** OptiFabric-style naming: a readable, versioned jar next to the OptiForge service jar. */
    static String stagedJarName() {
        return "OptiForge-" + Diagnose.VERSION + "-classes.jar";
    }

    /** The classes jar from older releases, removed so two mods cannot claim the same modId. */
    static void removeLegacyStagedJar(Path gameDir) {
        if (gameDir == null) return;
        try {
            java.nio.file.Files.deleteIfExists(gameDir.resolve("mods").resolve("optiforge-optifine-classes.jar"));
        } catch (java.io.IOException ignored) {
            // harmless: it is only dead weight
        }
    }

    /**
     * Writes a jar that carries only the in-game notice (no OptiFine classes).
     *
     * <p>Same shape as the full staged jar, so the mods list shows one entry, "OptiForge", whether or not
     * OptiFine was found; the notice itself is driven by {@code -Doptiforge.missingOptifine=true}.
     */
    static void stageNoticeOnly(Path gameDir, StringBuilder notes) throws IOException {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        Path target = mods.resolve(stagedJarName());
        notes.append("staging notice mod ").append(target.getFileName()).append('\n');
        String toml = modToml();
        Path tmp = mods.resolve(target.getFileName() + ".tmp");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(tmp))) {
            put(out, "META-INF/MANIFEST.MF",
                    ("Manifest-Version: 1.0\r\n"
                            + "Implementation-Title: OptiForge\r\n"
                            + "Implementation-Version: " + Diagnose.VERSION + "\r\n"
                            + "Implementation-Vendor: Kynarain\r\n"
                            + "\r\n").getBytes(StandardCharsets.UTF_8));
            put(out, "META-INF/mods.toml", toml.getBytes(StandardCharsets.UTF_8));
            put(out, "optiforge_icon.png", iconBytes());
            put(out, "pack.mcmeta", ("{\"pack\":{\"pack_format\":" + packFormat(mcVersion)
                    + ",\"description\":\"OptiForge\"}}\n").getBytes(StandardCharsets.UTF_8));
            put(out, "optiforge/generated/GeneratedMod.class", generatedModClass());
            put(out, "optiforge/generated/Notice.class", relocatedNotice());
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Bytes of {@link Notice}, renamed into the staged mod's own package. */
    static byte[] relocatedNotice() {
        try (java.io.InputStream in = OptifinePack.class.getResourceAsStream("/kynarain/cn/optiforge/Notice.class")) {
            if (in == null) throw new IOException("Notice.class is missing from the OptiForge jar");
            byte[] original = J8.readAll(in);
            // A plain name swap is not enough: self references (fields, methods) carry the old owner in
            // their constant-pool entries, so the relocated copy was still reaching for
            // kynarain.cn.optiforge.Notice - the copy in the service layer - and died with
            // "IllegalAccessError: ... tried to access private field". ClassRemapper rewrites them all.
            org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
            org.objectweb.asm.commons.Remapper remapper = new org.objectweb.asm.commons.Remapper() {
                @Override
                public String map(String internalName) {
                    return "kynarain/cn/optiforge/Notice".equals(internalName)
                            ? "optiforge/generated/Notice" : internalName;
                }
            };
            new org.objectweb.asm.ClassReader(original)
                    .accept(new org.objectweb.asm.commons.ClassRemapper(writer, remapper), 0);
            return writer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not read Notice.class", e);
        }
    }
    /**
     * Materialises OptiFine's {@code patch/assets/**} payload once per pack cache: those entries are
     * xdelta deltas against the vanilla assets, and without them the staged resource pack has no
     * {@code assets/minecraft/optifine/ctm/default/**.png} (1.14-1.16) or
     * {@code assets/minecraft/mcpatcher/ctm/default/**.png} (1.12.2/legacy) files, so the log fills with
     * "Using missing texture: ... optifine/ctm/default/10_glass_purple/44.png". The vanilla assets live in
     * {@code client-<mc>-<timestamp>-extra.jar}, which the launcher supplies through the library root.
     */
    /**
     * Whether OptiFine's {@code patch/assets/**} payloads are materialised and staged.
     *
     * <p>Default is {@code staged}: without the payloads OptiFine keeps asking for
     * {@code assets/minecraft/optifine/ctm/default/**.png} and the log fills with "Using missing texture",
     * which is what users see as broken connected textures. The payload tree is byte-identical to the
     * official mod-form OptiFine jar (1732/1732 files on 1.16.5), and it is produced through the line's
     * {@code client-<mc>-extra.jar} once per pack cache, so a second launch costs nothing.
     *
     * <p>Measured cost on the Lead's rig: with the payloads present, OptiFine compiles the default CTM set
     * during "Reloading custom textures" (1.16.5 took ~245 s before the first title screen, 1.13.2 stalled
     * long enough to look like a hang), so {@code -Doptiforge.assets=none} stays available as the cheap
     * fallback for slow machines or for users who do not use CTM.
     */
    static boolean stagedAssetsEnabled() {
        String mode = System.getProperty("optiforge.assets", "staged").trim().toLowerCase(java.util.Locale.ROOT);
        return !(mode.equals("none") || mode.equals("off") || mode.equals("false"));
    }

    private static void ensureAssetPayloads(Path gameDir, Path cacheRoot, Path optifineJar,
                                            StringBuilder notes) {
        if (!stagedAssetsEnabled()) return;
        if (gameDir == null || cacheRoot == null) return;
        Path dir = cacheRoot.resolve("assets");
        Path marker = dir.resolve("payload-count.txt");
        if (Files.isRegularFile(marker)) return;
        Path assetsJar = LaunchPaths.assetsJar(gameDir, mcVersion);
        if (assetsJar == null) {
            notes.append("OptiFine asset payloads: no client-*-extra.jar found for ").append(mcVersion)
                    .append("; CTM default textures stay unstaged\n");
            try {
                Files.createDirectories(dir);
                Files.write(marker, "0\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // the note above is the useful part
            }
            return;
        }
        int ok = 0;
        int failed = 0;
        try (OptifinePatcher patcher = new OptifinePatcher(optifineJar)) {
            java.util.List<String> targets = patcher.assetTargets();
            Map<String, String> configuration = patcher.configurationMap();
            Pattern[] patterns = patcher.patterns(configuration);
            Files.createDirectories(dir);
            try (ZipFile base = new ZipFile(assetsJar.toFile())) {
                Object provider = patcher.newResourceProvider(base);
                for (String target : targets) {
                    try {
                        byte[] bytes = patcher.applyAssetWith(provider, patterns, configuration, target);
                        Path out = dir.resolve(target.replace('/', java.io.File.separatorChar));
                        Files.createDirectories(out.getParent());
                        Files.write(out, bytes);
                        ok++;
                    } catch (Exception e) {
                        failed++;
                        if (failed <= 3) {
                            notes.append("  asset payload failed: ").append(target).append(" : ")
                                    .append(e.getMessage()).append('\n');
                        }
                    }
                }
            }
            Files.write(marker, (ok + "\n").getBytes(StandardCharsets.UTF_8));
            notes.append("OptiFine asset payloads: ").append(ok).append(" file(s) from ")
                    .append(assetsJar.getFileName()).append(", ").append(failed).append(" failure(s)\n");
        } catch (IOException e) {
            notes.append("OptiFine asset payloads skipped: ").append(e).append('\n');
        }
    }

    private static void writeLibraryMod(Path gameDir, String key, Map<String, Path> own,
                                        Path optifineJar, Path cacheRoot, StringBuilder notes) throws IOException {
        boolean stagePayloads = stagedAssetsEnabled();
        if (stagePayloads) {
            ensureAssetPayloads(gameDir, cacheRoot, optifineJar, notes);
        } else {
            notes.append("OptiFine asset payloads: disabled (-Doptiforge.assets=none); plain assets/ only\n");
        }
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        Path target = mods.resolve(stagedJarName());
        // Rewritten on every launch on purpose: the file is derived data (it changes whenever the
        // OptiFine jar or the remap table changes), and a fixed name keeps mods/ free of stale copies
        // for other OptiFine builds. Writing ~2 MB costs a few tens of milliseconds.
        notes.append("staging library mod ").append(target.getFileName()).append('\n');
        String toml = modToml();

        boolean stageClasses = !"false".equalsIgnoreCase(System.getProperty("optiforge.stageClasses", "true"));
        Path tmp = mods.resolve(target.getFileName() + ".tmp");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(tmp))) {
            put(out, "META-INF/MANIFEST.MF",
                    ("Manifest-Version: 1.0\r\n"
                            + "Implementation-Title: OptiForge\r\n"
                            + "Implementation-Version: " + Diagnose.VERSION + "\r\n"
                            + "Implementation-Vendor: Kynarain\r\n"
                            + "\r\n").getBytes(StandardCharsets.UTF_8));
            put(out, "META-INF/mods.toml", toml.getBytes(StandardCharsets.UTF_8));
            put(out, "optiforge_icon.png", iconBytes());
            // Without pack.mcmeta Forge refuses to expose this jar's assets as a resource pack ("failed to
            // load a valid ResourcePackInfo"), and OptiFine's own text is then shown as raw keys
            // (of.options.AO_LEVEL and friends) because its .lang files live in those assets.
            put(out, "pack.mcmeta", ("{\"pack\":{\"pack_format\":" + packFormat(mcVersion)
                    + ",\"description\":\"OptiForge - OptiFine resources\"}}\n")
                    .getBytes(StandardCharsets.UTF_8));
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
            if (!Bootstrap.legacy() && Boolean.getBoolean("optiforge.moduleInfo")) {
                put(out, "module-info.class", moduleDescriptor());
            }
            put(out, "optiforge/generated/GeneratedMod.class", generatedModClass());
            put(out, "optiforge/generated/Notice.class", relocatedNotice());
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
            java.util.Set<String> stagedResources = new java.util.HashSet<>();
            try (ZipFile source = new ZipFile(optifineJar.toFile())) {
                java.util.Enumeration<? extends ZipEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (!name.startsWith("assets/")) continue;
                    try (InputStream in = source.getInputStream(entry)) {
                        put(out, name, J8.readAll(in));
                    }
                    stagedResources.add(name);
                    resources++;
                }
            }
            // OptiFine's own asset payloads (CTM default textures, models, shader includes). They were
            // materialised into the pack cache above; stage every file that the jar root does not already
            // carry (the root copies are the plain files, the payload ones are the patched results).
            int payloadAssets = 0;
            Path payloadDir = stagePayloads && cacheRoot != null ? cacheRoot.resolve("assets") : null;
            if (payloadDir != null && Files.isDirectory(payloadDir)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(payloadDir)) {
                    for (Path file : walk.filter(Files::isRegularFile)
                            .collect(java.util.stream.Collectors.toList())) {
                        String name = payloadDir.relativize(file).toString().replace('\\', '/');
                        if (name.equals("payload-count.txt") || stagedResources.contains(name)) continue;
                        put(out, name, Files.readAllBytes(file));
                        stagedResources.add(name);
                        payloadAssets++;
                    }
                }
            }
            notes.append("library mod: ").append(target.getFileName()).append(" (")
                    .append(stagedClasses).append(" classes, ").append(resources).append(" jar assets, ")
                    .append(payloadAssets).append(" OptiFine payload assets)\n");
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
     * Bytes of a marker class annotated with Forge's {@code @Mod("optiforge")}.
     *
     * <p>Generated at runtime so that OptiForge keeps its no-Forge-compile-dependency rule: the only
     * thing needed is the annotation descriptor, which ASM can emit without the annotation class on
     * the compile classpath.
     */
    /** Class-file version 61 (Java 17); written as a literal because the legacy build compiles against ASM 6.2. */
    private static final int CLASS_FILE_JAVA_17 = 61;
    /** Class-file version 52 (Java 8), the only version a 1.14.4-1.16.5 JVM can load. */
    private static final int CLASS_FILE_JAVA_8 = 52;

    static byte[] generatedModClass() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        // The staged mod class is loaded by the running JVM, so its class-file version must match the
        // line: a Java 8 (1.14.4-1.16.5) JVM rejects a Java 17 class file.
        writer.visit(Bootstrap.legacy() ? CLASS_FILE_JAVA_8 : CLASS_FILE_JAVA_17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL,
                "optiforge/generated/GeneratedMod", null, "java/lang/Object", null);
        // Forge instantiates the mod class, so it needs a constructor: measured without one,
        // "Failed to create mod instance. ModID: optiforge_optifine_classes, class
        // optiforge.generated.GeneratedMod" is logged during mod loading.
        org.objectweb.asm.MethodVisitor constructor = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        // The in-game "OptiFine missing" prompt lives in Notice; it is a no-op when OptiFine was found.
        constructor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC,
                "optiforge/generated/Notice", "startWatch", "()V", false);
        constructor.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        org.objectweb.asm.AnnotationVisitor annotation = writer.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        annotation.visit("value", "optiforge");
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

    /** Vanilla resource pack format for the running line; mods only need it to be well formed. */
    static int packFormat(String mcVersion) {
        if (mcVersion == null) return 15;
        String v = mcVersion.trim();
        // 1.13-1.16 (the oldml artifact's lines) keep their era's resource-pack formats: 4 for 1.13-1.14,
        // 5 for 1.15, 6 for 1.16. A wrong number only means "incompatible pack" in the UI, but it is free
        // to get right.
        if (v.startsWith("1.13") || v.startsWith("1.14")) return 4;
        if (v.startsWith("1.15")) return 5;
        if (v.startsWith("1.16")) return 6;
        switch (v) {
            case "1.20":
            case "1.20.1":
                return 15;
            case "1.20.2":
                return 18;
            case "1.20.3":
            case "1.20.4":
                return 22;
            case "1.20.5":
            case "1.20.6":
                return 32;
            case "1.21":
            case "1.21.1":
                return 34;
            case "1.21.2":
            case "1.21.3":
                return 42;
            case "1.21.4":
                return 46;
            case "1.21.5":
                return 55;
            case "1.21.6":
                return 63;
            case "1.21.7":
            case "1.21.8":
                return 64;
            case "1.21.9":
                return 69;
            case "1.21.10":
                return 71;
            case "1.21.11":
                return 75;
            default:
                return v.startsWith("26.") ? 80 : 15;
        }
    }

    private static final String PACK_VERSION = "v10"; // v10: renumber own classes too; v9: 1.17.1 G9 renumber; v8: 1.13.2 bridge

    /** Dotted class name of a patched class, read from its own bytes (obf remap changes it). */
    private static String classNameOf(byte[] classBytes, String fallbackLogical) {
        try {
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(classBytes)
                    .accept(node, org.objectweb.asm.ClassReader.SKIP_CODE
                            | org.objectweb.asm.ClassReader.SKIP_DEBUG
                            | org.objectweb.asm.ClassReader.SKIP_FRAMES);
            return node.name.replace('/', '.');
        } catch (RuntimeException e) {
            return fallbackLogical.replace('/', '.');
        }
    }

    /**
     * Loads the 1.13.2 obf->runtime table shipped in the oldml artifact and the vanilla jar hierarchy
     * it needs for inherited-member resolution.
     */
    private static ObfNamespaceRemap loadObfRemap(Path optifineJar, Path baseJar, StringBuilder notes) {
        String resource = "/optiforge/mappings/1.13.2-obf-to-srg.tsrg";
        try (InputStream in = OptifinePack.class.getResourceAsStream(resource)) {
            if (in == null) {
                notes.append("no ").append(resource).append(" in this artifact; the 1.13.2 payload cannot be"
                        + " renamed into the Forge runtime namespace\n");
                return null;
            }
            return ObfNamespaceRemap.load(in, baseJar);
        } catch (Exception e) {
            notes.append("could not load the obf->runtime mapping: ").append(e).append('\n');
            return null;
        }
    }

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
            String text = J8.readString(manifest);
            Map<String, byte[]> patched = new LinkedHashMap<>();
            Path patchedDir = cacheRoot.resolve("patched");
            if (Files.isDirectory(patchedDir)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(patchedDir)) {
                    for (Path file : walk.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList())) {
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
        try (java.util.stream.Stream<Path> walk = Files.walk(ownDir)) {
            for (Path file : walk.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList())) {
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

    /**
     * The 1.17.1 G9 payload was compiled against provisional SRG ids. The table maps them to the ids of the
     * installed 1.17.1 runtime; it is opt-out through {@code -Doptiforge.renumber=off}.
     */
    private static SrgRemap loadSrgRenumber(StringBuilder notes) {
        if (!"1.17.1".equals(mcVersion)) return null;
        if ("off".equalsIgnoreCase(System.getProperty("optiforge.renumber", "auto").trim())) return null;
        String resource = "/optiforge/mappings/1.17.1-srg-renumber.tsv";
        try (InputStream in = OptifinePack.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            SrgRemap table = SrgRemap.loadRenumber(in);
            notes.append("SRG renumber table: ").append(resource).append(" (").append(table.size())
                    .append(" entries)\n");
            return table;
        } catch (IOException e) {
            notes.append("could not load ").append(resource).append(": ").append(e).append('\n');
            return null;
        }
    }
    private static void writeCache(Path cacheRoot, String key, Map<String, byte[]> patched,
                                   Path optifineJar, SrgRemap remap, ObfNamespaceRemap obfRemap,
                                   SrgRemap renumber, StringBuilder notes, java.util.Set<String> blocked) {
        try {
            Files.createDirectories(cacheRoot.resolve("patched"));
            for (Map.Entry<String, byte[]> entry : patched.entrySet()) {
                Path target = cacheRoot.resolve("patched")
                        .resolve(entry.getKey().replace('.', java.io.File.separatorChar) + ".class");
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            }
            int own = writeOwnClasses(optifineJar, remap, obfRemap, renumber, cacheRoot.resolve("own"), notes, blocked);
            J8.writeString(cacheRoot.resolve("manifest.txt"),
                    "optifine=" + optifineJar.getFileName() + "\n"
                            + "patched=" + patched.size() + "\n"
                            + "own=" + own + "\n");
        } catch (IOException e) {
            notes.append("could not write pack cache: ").append(e).append('\n');
        }
    }

    private static Map<String, Path> writeOwnToTemp(Path optifineJar, SrgRemap remap,
                                                    ObfNamespaceRemap obfRemap, SrgRemap renumber,
                                                    StringBuilder notes, java.util.Set<String> blocked) throws IOException {
        Path dir = Files.createTempDirectory("optiforge-own-");
        writeOwnClasses(optifineJar, remap, obfRemap, renumber, dir, notes, blocked);
        Map<String, Path> own = new LinkedHashMap<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList())) {
                String rel = dir.relativize(file).toString().replace('\\', '/');
                if (!rel.endsWith(".class")) continue;
                own.put(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'), file);
            }
        }
        return own;
    }

    /**
     * Extracts OptiFine's own classes, remapped, into {@code targetDir}.
     *
     * <p>1.14+ keeps them under {@code srg/} and they are already in the srg namespace; the 1.13.2
     * generation keeps them at the jar root under {@code net/optifine/} in the obfuscated namespace and
     * needs the {@link ObfNamespaceRemap} bridge. The root {@code net/minecraftforge/**} shims and the
     * {@code optifine/**} installer classes are not staged: the former duplicate Forge's own classes and
     * the latter are installer/tweaker code.
     */
    private static int writeOwnClasses(Path optifineJar, SrgRemap remap, ObfNamespaceRemap obfRemap,
                                       SrgRemap renumber, Path targetDir, StringBuilder notes,
                                       java.util.Set<String> blocked) throws IOException {
        int written = 0;
        int renamed = 0;
        String prefix = obfRemap != null ? "net/optifine/" : "srg/";
        try (ZipFile zip = new ZipFile(optifineJar.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(prefix) || !name.endsWith(".class")) continue;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = J8.readAll(in);
                }
                if (obfRemap != null) {
                    bytes = obfRemap.remap(bytes);
                } else if (remap != null) {
                    SrgRemap.Result result = remap.remap(bytes, blocked);
                    renamed += result.renamed;
                    bytes = result.bytes;
                }
                if (renumber != null) {
                    // OptiFine's own classes are compiled against the same (provisional) SRG numbering as its
                    // patched classes: measured on 1.17.1 HD_U_G9, net.optifine.CrashReporter calls
                    // SystemReport.m_300462_(String,String) while the installed runtime has no such id, so the
                    // crash report handler died on the first crash. Only the patched classes were renumbered
                    // before, hence this second application.
                    SrgRemap.Result result = renumber.remap(bytes);
                    renamed += result.renamed;
                    bytes = result.bytes;
                }
                // NOTCH own classes keep their full net/optifine/... path (the cache and the staged jar are
                // keyed by the dotted name); srg-mode names lose their leading srg/.
                String relative = obfRemap != null ? name : name.substring(prefix.length());
                Path target = targetDir.resolve(relative.replace('/', java.io.File.separatorChar));
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
                written++;
            }
        }
        notes.append("own classes: ").append(written)
                .append(obfRemap != null ? " (root net/optifine, obf->runtime remapped)" : "")
                .append(", remapped tokens ").append(renamed).append('\n');
        if (obfRemap != null) {
            // Assertion from the 1.13.2 join test: an own class that implements a runtime interface method
            // must carry that runtime id, otherwise the first resource reload dies with AbstractMethodError.
            notes.append("own class interface assertions: ").append(obfRemap.overrideMisses())
                    .append(" mismatch(es)");
            if (!obfRemap.overrideMissExamples().isEmpty()) {
                notes.append(' ').append(obfRemap.overrideMissExamples());
            }
            notes.append('\n');
        }
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
        writer.visit(CLASS_FILE_JAVA_17, org.objectweb.asm.Opcodes.ACC_MODULE,
                "module-info", null, null, null);
        org.objectweb.asm.ModuleVisitor visitor = writer.visitModule("optiforge", org.objectweb.asm.Opcodes.ACC_OPEN, null);
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
