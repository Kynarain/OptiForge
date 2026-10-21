package kynarain.cn.optiforge.oldml;

import cpw.mods.modlauncher.api.ITransformer;
import kynarain.cn.optiforge.TargetFactory;

import java.util.Collections;
import java.util.Set;

/**
 * The Java-8 artifact's target factory: ModLauncher 2-8 (1.13-1.16) has only {@code targetClass}.
 *
 * <p>{@code targetClass} runs in the normal class-transform phase instead of ModLauncher 7+'s
 * {@code targetPreClass} pre-class phase, which is the documented difference for these lines:
 * OptiFine's own transformer logs "Target.PRE_CLASS is available" only from ModLauncher 7 on, and on
 * 1.14-1.16 it registers plain class targets as well.
 */
final class OldMlTargets implements TargetFactory {

    @Override
    public Set<ITransformer.Target> targetsFor(String className) {
        return Collections.singleton(ITransformer.Target.targetClass(className));
    }
}
