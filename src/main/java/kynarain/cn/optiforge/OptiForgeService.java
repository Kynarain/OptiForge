package kynarain.cn.optiforge;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * OptiForge's ModLauncher entry point: it takes OptiFine over completely.
 *
 * <p>Forge finds this class because the jar declares
 * {@code META-INF/services/cpw.mods.modlauncher.api.ITransformationService}. Forge's
 * {@code ModDirTransformerDiscoverer} hands every such jar in {@code mods/} to ModLauncher as a
 * transformation service and excludes it from ordinary mod discovery - which is why this project has
 * no {@code mods.toml} and no {@code @Mod}.
 *
 * <p><b>State is static on purpose.</b> Measured: ModLauncher instantiates the service more than once
 * for one launch (one instance ran {@code initialize}, wrote the pack and then its
 * {@code additionalClassesLocator} was never consulted, while another instance's {@code onLoad} saw
 * {@code pack == null} and its locator answered "empty" for every name). That split turned into
 * {@code NoClassDefFoundError: net/optifine/util/MathUtils} while {@code net.minecraft.Util}
 * initialised. One process, one pack: everything below is process-wide and prepared exactly once.
 *
 * <p>The user's OptiFine jar must live in {@code <game>/optiforge/}, not in {@code mods/}: if it is in
 * {@code mods/}, OptiFine's own service runs as well and both pipelines patch the same classes.
 */
public final class OptiForgeService implements ITransformationService {

    public static final String NAME = "optiforge";

    private static final StringBuilder NOTES = new StringBuilder();
    private static volatile boolean prepared;
    private static volatile Path gameDir;
    private static volatile Path reportFile;
    private static volatile RuntimeInfo info;
    private static volatile OptifinePack pack;
    private static volatile OptifineClassTransformer transformer;
    private static volatile IEnvironment environment;
    private static final java.util.concurrent.atomic.AtomicBoolean READS_INSTALLED = new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(IEnvironment environment) {
        try {
            OptiForgeService.environment = environment;
            try {
                RuntimeInfo info = RuntimeInfo.fromLaunchArguments(null);
                OptifinePack.mcVersion = info.mcVersion;
            } catch (Throwable ignored) {
                // the version is only used to gate version-specific fixes
            }
            rememberParentModules(environment);
            if (gameDir == null) {
                gameDir = environment.getProperty(IEnvironment.Keys.GAMEDIR.get()).orElse(null);
            }
            if (reportFile == null && gameDir != null) {
                reportFile = gameDir.resolve("optiforge").resolve("diagnose.txt");
            }
            if (info == null && gameDir != null) {
                info = RuntimeInfo.fromLaunchArguments(gameDir);
            }
            ensurePack();
        } catch (Throwable t) {
            synchronized (NOTES) {
                NOTES.append("OptiForge failed in initialize: ").append(t).append('\n');
            }
            t.printStackTrace(System.out);
        }
    }

    /** Builds the pack once per process and writes the report. Idempotent. */
    static OptifinePack ensurePack() {
        if (prepared) return pack;
        synchronized (OptiForgeService.class) {
            if (prepared) return pack;
            // The first call can come from additionalClassesLocator()/transformers(), before any
            // initialize() has handed us an IEnvironment - so recover the game directory from the
            // launch arguments (--gameDir) first. Without this the pack was built with gameDir=null,
            // "no OptiFine jar" was reported, and the launch looked successful while OptiFine was absent.
            if (gameDir == null || info == null) {
                RuntimeInfo parsed = RuntimeInfo.fromLaunchArguments(gameDir);
                if (gameDir == null) gameDir = parsed.gameDir;
                if (info == null) info = parsed;
                if (reportFile == null && gameDir != null) {
                    reportFile = gameDir.resolve("optiforge").resolve("diagnose.txt");
                }
            }
            if (gameDir == null) {
                // No environment at all yet: leave prepared=false so a later call can still build.
                return null;
            }
            prepared = true;
            try {
                if (info == null) {
                    info = RuntimeInfo.fromLaunchArguments(gameDir);
                }
                NOTES.append("OptiForge ").append(Diagnose.VERSION).append('\n')
                        .append(info.describe()).append('\n');

                OptifinePack.Namespace runtime = OptifinePack.detectRuntimeNamespace(NOTES);
                Path mapping = OptifinePack.pickMapping(info.mcVersion, NOTES);
                // Remap only when the runtime speaks official names: an SRG payload on a SRG runtime is
                // already correct, and renaming it would be the bug. When the payload is official too,
                // the rename matches zero tokens and is a no-op.
                boolean remapEnabled = runtime == OptifinePack.Namespace.OFFICIAL && mapping != null;
                NOTES.append("remap: ").append(remapEnabled ? "enabled" : "disabled")
                        .append(" (runtime=").append(runtime)
                        .append(", mapping=").append(mapping == null ? "none" : mapping.getFileName())
                        .append(")\n");

                Path optifineJar = OptifinePack.findOptifineJar(gameDir,
                        System.getProperty("optiforge.optifine"), NOTES);
                if (optifineJar == null) {
                    NOTES.append("no OptiFine jar: put it in <game>/optiforge/ (or pass -Doptiforge.optifine=)\n");
                    finish();
                    return null;
                }
                Path baseJar = OptifinePack.findBaseJar(info, gameDir, optifineJar,
                        System.getProperty("optiforge.vanilla"), NOTES);
                if (baseJar == null) {
                    finish();
                    return null;
                }
                pack = OptifinePack.prepare(gameDir, optifineJar, baseJar, mapping, remapEnabled,
                        info == null ? "" : info.mcVersion, NOTES);
                NOTES.append(pack.summary()).append('\n');
            } catch (Throwable t) {
                NOTES.append("OptiForge failed to prepare the pack: ").append(t).append('\n');
                t.printStackTrace(System.out);
            }
            finish();
            return pack;
        }
    }

    private static void finish() {
        String text;
        synchronized (NOTES) {
            text = NOTES.toString();
        }
        System.out.println("[OptiForge] " + text.replace("\n", "\n[OptiForge] "));
        if (reportFile != null) {
            try {
                Files.createDirectories(reportFile.getParent());
                Files.writeString(reportFile, text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.out.println("[OptiForge] could not write " + reportFile + ": " + e);
            }
        }
    }

    /**
     * Records which Forge/libraries modules sit in the layers that are parents of the mod layer, so the
     * staged jar's module descriptor can require exactly those (see OptifinePack.parentModules).
     */
    private static void rememberParentModules(IEnvironment environment) {
        try {
            java.util.List<String> names = new java.util.ArrayList<>();
            environment.findModuleLayerManager().ifPresent(manager -> manager
                    .getLayer(cpw.mods.modlauncher.api.IModuleLayerManager.Layer.GAME)
                    .ifPresent(game -> {
                        java.util.List<java.lang.ModuleLayer> parents = game.parents();
                        for (java.lang.ModuleLayer parent : parents) {
                            for (java.lang.Module module : parent.modules()) {
                                String name = module.getName();
                                if (name.startsWith("net.minecraftforge") || name.startsWith("net.neoforged")
                                        || name.startsWith("org.apache.logging.log4j")
                                        || name.equals("org.slf4j")) {
                                    names.add(name);
                                }
                            }
                        }
                    }));
            if (!names.isEmpty()) {
                OptifinePack.parentModules = java.util.List.copyOf(names);
                System.out.println("[OptiForge] parent modules: " + names);
            }
        } catch (Throwable t) {
            System.out.println("[OptiForge] parent module discovery failed: " + t);
        }
    }
    /**
     * Installs the module reads the first time a class is actually transformed: the GAME layer (which
     * owns the minecraft module) does not exist yet when initialize()/onLoad() run, so the earlier
     * attempt reported "could not identify the game module".
     */
    static void ensureModuleReads() {
        if (!READS_INSTALLED.compareAndSet(false, true)) return;
        IEnvironment env = environment;
        if (env == null) {
            System.out.println("[OptiForge] module reads: no environment captured");
            return;
        }
        try {
            String outcome = ModuleReads.install(env);
            System.out.println("[OptiForge] " + outcome);
        } catch (Throwable t) {
            System.out.println("[OptiForge] module reads failed: " + t);
        }
    }

    @Override
    public List<ITransformer> transformers() {
        OptifinePack current = ensurePack();
        if (current == null) {
            System.out.println("[OptiForge] transformers(): no pack prepared, nothing to transform");
            return List.of();
        }
        if (transformer == null) {
            transformer = new OptifineClassTransformer(current);
        }
        System.out.println("[OptiForge] transformers(): " + current.patchedClasses.size()
                + " target classes, " + current.ownClasses.size() + " own classes");
        return List.of(transformer);
    }

    @Override
    public Map.Entry<Set<String>, Supplier<Function<String, Optional<URL>>>> additionalClassesLocator() {
        OptifinePack current = ensurePack();
        if (current == null) return null;
        System.out.println("[OptiForge] additionalClassesLocator() requested, prefixes " + OptifinePack.CLASS_PREFIXES);
        return Map.entry(OptifinePack.CLASS_PREFIXES, () -> {
            System.out.println("[OptiForge] class locator supplier created");
            return current::ownClassUrl;
        });
    }

    @Override
    public Map.Entry<Set<String>, Supplier<Function<String, Optional<URL>>>> additionalResourcesLocator() {
        OptifinePack current = ensurePack();
        if (current == null) return null;
        return Map.entry(OptifinePack.RESOURCE_PREFIXES, () -> current::resourceUrl);
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices)
            throws IncompatibleEnvironmentException {
        OptifinePack current = ensurePack();
        System.out.println("[OptiForge] onLoad, alongside " + otherServices
                + (transformer == null ? " (no transformer)" : " (transformer applied to "
                + transformer.appliedCount() + " classes so far)"));
        // OptiFine patches reference Forge classes directly; the game module has to be able to read
        // the Forge modules for those references to resolve (see ModuleReads).
        try {
            String outcome = ModuleReads.install(environment);
            System.out.println("[OptiForge] " + outcome);
            if (reportFile != null) {
                java.nio.file.Files.createDirectories(reportFile.getParent());
                java.nio.file.Files.writeString(reportFile, outcome + System.lineSeparator(),
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (Throwable t) {
            System.out.println("[OptiForge] module reads failed: " + t);
        }
        if (current == null) {
            System.out.println("[OptiForge] NOT ACTIVE: no pack was prepared - see optiforge/diagnose.txt");
        } else if (otherServices.stream().anyMatch(s -> s.equalsIgnoreCase("OptiFine"))) {
            System.out.println("[OptiForge] WARNING: OptiFine's own transformation service is also loaded;"
                    + " move the OptiFine jar out of mods/ into optiforge/");
        }
    }

    public Optional<RuntimeInfo> lastEnvironment() {
        return Optional.ofNullable(info);
    }

    public Optional<OptifinePack> pack() {
        return Optional.ofNullable(pack);
    }
}
