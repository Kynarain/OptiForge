package kynarain.cn.optiforge;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * OptiForge's ModLauncher entry point.
 *
 * <p>Forge finds this class because the jar declares
 * {@code META-INF/services/cpw.mods.modlauncher.api.ITransformationService}. The same rule that makes
 * the user's OptiFine jar reachable to Forge makes this jar reachable: Forge's
 * {@code ModDirTransformerDiscoverer} collects every jar in {@code mods/} that provides one of a few
 * service interfaces and hands it to ModLauncher as a transformation service, excluded from ordinary
 * mod discovery. That is why OptiForge ships no {@code mods.toml} and no {@code @Mod} class.
 *
 * <p>Right now this service only diagnoses ({@link Diagnose}); {@link #transformers()} is deliberately
 * empty. The reason is recorded in {@code docs/STATE.md}: which fix is needed depends on what the
 * first real Forge launch of an incompatible OptiFine build reports, and that observation is what the
 * diagnostic produces.
 */
public final class OptiForgeService implements ITransformationService {

    public static final String NAME = "optiforge";

    private RuntimeInfo info;
    private Path reportFile;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(IEnvironment environment) {
        try {
            Path gameDir = environment.getProperty(IEnvironment.Keys.GAMEDIR.get()).orElse(null);
            info = RuntimeInfo.fromLaunchArguments(gameDir);
            if (gameDir != null) {
                reportFile = gameDir.resolve("optiforge").resolve("diagnose.txt");
            }
            // The diagnostic is the whole of this build; keep it cheap and never let it break a launch.
            Diagnose.run(info, info.gameDir == null ? null : info.gameDir.resolve("mods"), reportFile);
        } catch (Throwable t) {
            System.out.println("[OptiForge] diagnosis failed: " + t);
            t.printStackTrace(System.out);
        }
    }

    @Override
    public List<ITransformer> transformers() {
        System.out.println("[OptiForge] transformers(): none in this build (diagnose-only skeleton)");
        return List.of();
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices)
            throws IncompatibleEnvironmentException {
        System.out.println("[OptiForge] onLoad, alongside " + otherServices);
        if (reportFile != null && Files.isReadable(reportFile)) {
            System.out.println("[OptiForge] diagnosis: " + reportFile);
        }
        if (info != null && !info.known()) {
            System.out.println("[OptiForge] " + info.problem);
        }
    }

    /** Exposed for tests and for the offline driver: the last environment this service saw. */
    public Optional<RuntimeInfo> lastEnvironment() {
        return Optional.ofNullable(info);
    }
}
