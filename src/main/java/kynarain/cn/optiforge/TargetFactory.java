package kynarain.cn.optiforge;

import cpw.mods.modlauncher.api.ITransformer;

import java.util.Set;

/**
 * Builds the {@link ITransformer.Target} set for one class.
 *
 * <p>The old and new ModLauncher lines disagree about how a class target is named: ModLauncher 7+ has
 * {@code targetPreClass} (and OptiFine itself uses it when available, which runs before Forge's own
 * transformers), while 1.13-1.15 only has {@code targetClass}. The transformer core is shared, so the
 * one-line difference is injected as this factory by each artifact's entry point.
 */
public interface TargetFactory {

    Set<ITransformer.Target> targetsFor(String className);
}
