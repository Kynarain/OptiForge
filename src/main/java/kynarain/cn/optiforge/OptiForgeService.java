package kynarain.cn.optiforge;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * OptiForge's ModLauncher entry point for the Java-17 artifact (Forge 1.17+, ModLauncher 9/10).
 *
 * <p>Forge finds this class because the jar declares
 * {@code META-INF/services/cpw.mods.modlauncher.api.ITransformationService}. Forge's
 * {@code ModDirTransformerDiscoverer} hands every such jar in {@code mods/} to ModLauncher as a
 * transformation service and excludes it from ordinary mod discovery - which is why this project has
 * no {@code mods.toml} and no {@code @Mod}.
 *
 * <p>The legacy artifact (Forge 1.14.4-1.16.5, Java 8, ModLauncher 2-8) has its own entry point,
 * {@code kynarain.cn.optiforge.oldml.OldModLauncherService}; both delegate the pack/transform
 * orchestration to {@link Bootstrap}. The user's OptiFine jar must live in {@code <game>/optiforge/},
 * not in {@code mods/}: if it is in {@code mods/}, OptiFine's own service runs as well and both
 * pipelines patch the same classes.
 */
public final class OptiForgeService implements ITransformationService {

    public static final String NAME = "optiforge";

    private static volatile IEnvironment environment;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(IEnvironment environment) {
        OptiForgeService.environment = environment;
        // Java-9-only pieces are installed as hooks, so the shared pipeline never links against them.
        Bootstrap.setTargetFactory(new ModernTargets());
        Bootstrap.setModuleReadsHook(ModuleReadsInjector::apply);
        Bootstrap.setModuleReadsInstaller(OptiForgeService::ensureModuleReads);
        try {
            RuntimeInfo info = RuntimeInfo.fromLaunchArguments(null);
            OptifinePack.mcVersion = info.mcVersion;
        } catch (Throwable ignored) {
            // the version is only used to gate version-specific fixes
        }
        rememberParentModules(environment);
        Bootstrap.ensurePack(environment);
    }

    /** Kept for the transform path and the diagnostics: one pack per process, built by {@link Bootstrap}. */
    static OptifinePack ensurePack() {
        return Bootstrap.ensurePack(environment);
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
     * owns the minecraft module) does not exist yet when initialize()/onLoad() run.
     */
    static void ensureModuleReads() {
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
        OptifineClassTransformer transformer = Bootstrap.transformer();
        if (transformer == null) return List.of();
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
                + (current == null ? " (no pack)" : " (pack " + current.key + ")"));
        // Wrong artifact or wrong Java for this line: say which one is needed instead of letting the
        // failure surface later as a missing class or an access error.
        RuntimeInfo info = Bootstrap.info();
        String mc = info == null ? null : info.mcVersion;
        JarGuide.check(null, "jdk17", mc, 17);
        // OptiFine patches reference Forge classes directly; the game module has to be able to read
        // the Forge modules for those references to resolve (see ModuleReads).
        try {
            String outcome = ModuleReads.install(environment);
            System.out.println("[OptiForge] " + outcome);
            Bootstrap.appendReport(outcome);
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
        return Optional.ofNullable(Bootstrap.info());
    }

    public Optional<OptifinePack> pack() {
        return Optional.ofNullable(Bootstrap.pack());
    }
}
