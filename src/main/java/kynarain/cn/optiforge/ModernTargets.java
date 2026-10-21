package kynarain.cn.optiforge;

import cpw.mods.modlauncher.api.ITransformer;

import java.util.Set;

/**
 * The Java-17 artifact's target factory: ModLauncher 7+ has {@code targetPreClass}.
 *
 * <p>OptiFine itself uses {@code targetPreClass} when the ModLauncher spec allows it (its log says
 * "Target.PRE_CLASS is available"), because that runs before Forge's own transformers and keeps the
 * OptiFine-on-Forge layering a working run has. This class is compiled into the Java-17 jar only; the
 * legacy artifact uses {@link kynarain.cn.optiforge.oldml.OldMlTargets} (same interface,
 * {@code targetClass}) because ModLauncher 2-8 has no {@code targetPreClass}.
 */
final class ModernTargets implements TargetFactory {

    @Override
    public Set<ITransformer.Target> targetsFor(String className) {
        return J8.setOf(ITransformer.Target.targetPreClass(className));
    }
}
