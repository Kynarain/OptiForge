package kynarain.cn.optiforge.oldml;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import kynarain.cn.optiforge.Bootstrap;
import kynarain.cn.optiforge.OptifineClassTransformer;
import kynarain.cn.optiforge.OptifinePack;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * OptiForge's ModLauncher entry point for the Java-8 artifact (Forge 1.14.4, 1.15.2, 1.16.5).
 *
 * <p>This is a separate class from {@code OptiForgeService} because the two ModLauncher API
 * generations are source-incompatible: 1.13-1.16 declare {@code void beginScanning(IEnvironment)} as
 * an <b>abstract</b> method (ModLauncher 10 made it a default returning {@code List<Resource>}), and
 * 1.13-1.15 have no {@code Target.targetPreClass}. One class cannot implement both shapes - measured:
 * compiling the old shape against ModLauncher 10 fails with "return type void is not compatible with
 * List<Resource>", and the new shape against 4.1.0 fails with "does not override abstract method
 * beginScanning".
 *
 * <p>What is intentionally absent, because old ModLauncher has no equivalent or the Java-8 line does
 * not need it: {@code additionalClassesLocator}/{@code additionalResourcesLocator} (1.13.2's
 * ModLauncher 2.1.1 does not define them at all; OptiFine's own classes are delivered by the staged
 * javafml mod jar instead), {@code completeScan}/{@code IModuleLayerManager} (ModLauncher 10 only) and
 * the module-reads injection (Java 8 has no JPMS modules).
 *
 * <p>The mods/ discovery mechanism is the same as on the modern lines: Forge 1.14-1.16 ships
 * {@code ModDirTransformerDiscoverer}, which hands every jar declaring
 * {@code META-INF/services/cpw.mods.modlauncher.api.ITransformationService} to ModLauncher as a
 * transformation service (measured in both the 1.14.4 and 1.16.5 universal jars).
 */
public final class OldModLauncherService implements ITransformationService {

    public static final String NAME = "optiforge";

    static {
        // Set before anything can prepare a pack: they pick the legacy mods.toml ranges and the target
        // constructor this ModLauncher generation understands (targetClass, not targetPreClass).
        Bootstrap.setLegacy(true);
        Bootstrap.setTargetFactory(new OldMlTargets());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(IEnvironment environment) {
        Bootstrap.ensurePack(environment);
    }

    /**
     * Required abstract on ModLauncher 2/4/5/8. The pack is already prepared by {@code initialize}, so
     * this only retries when that first attempt could not resolve a game directory yet.
     */
    @Override
    public void beginScanning(IEnvironment environment) {
        Bootstrap.ensurePack(environment);
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices)
            throws IncompatibleEnvironmentException {
        OptifinePack current = Bootstrap.ensurePack(environment);
        System.out.println("[OptiForge] onLoad (oldml/Java 8), alongside " + otherServices
                + (current == null ? " (no pack)" : " (pack " + current.key + ")"));

        if (current == null) {
            System.out.println("[OptiForge] NOT ACTIVE: no pack was prepared - see optiforge/diagnose.txt");
        } else {
            System.out.println("[OptiForge] oldml artifact active: " + current.patchedClasses.size()
                    + " target classes, " + current.ownClasses.size() + " own classes (staged mod jar)");
        }
        for (String service : otherServices) {
            if (service.equalsIgnoreCase("OptiFine")) {
                System.out.println("[OptiForge] WARNING: OptiFine's own transformation service is also loaded;"
                        + " move the OptiFine jar out of mods/ into optiforge/");
            }
        }
    }

    @Override
    public List<ITransformer> transformers() {
        OptifinePack current = Bootstrap.ensurePack(null);
        if (current == null) {
            System.out.println("[OptiForge] transformers(): no pack prepared, nothing to transform");
            return Collections.emptyList();
        }
        OptifineClassTransformer transformer = Bootstrap.transformer();
        if (transformer == null) return Collections.emptyList();
        System.out.println("[OptiForge] transformers(): " + current.patchedClasses.size()
                + " target classes, " + current.ownClasses.size() + " own classes");
        return Collections.<ITransformer>singletonList(transformer);
    }
}
