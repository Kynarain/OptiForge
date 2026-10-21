package kynarain.cn.optiforge.legacy;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * Entry point for Minecraft 1.8.9-1.12.2: {@code FMLCorePlugin} in the jar manifest.
 *
 * <p>These Forge versions carry FML under {@code net.minecraftforge.fml.*} and run on launchwrapper (the
 * 1.12.2 install profile says {@code mainClass = net.minecraft.launchwrapper.Launch},
 * {@code --tweakClass FMLTweaker}), so a ModLauncher {@code ITransformationService} is never loaded.
 * FML registers coremod transformers after {@code PatchingTransformer} (docs/PHASE3.md §2.1), which keeps
 * this payload delivery clear of FML's adler32 {@code System.exit(1)}.
 *
 * <p>1.7.10 needs {@link OptiForgeLegacy17CorePlugin} instead: FML there is packaged under
 * {@code cpw.mods.fml.*} and this interface does not exist (measured from the 1.7.10 universal jar).
 * All behaviour is shared through {@link LegacyCoreRuntime}.
 *
 * <p>{@code injectData} only records which jar to patch; the pack is built lazily on the first class
 * load because {@code FMLInjectionData} (and therefore the MC version) is not populated yet at this point.
 */
// No @MCVersion: FML 1.12.2 compared the bracketed range against Loader.MC_VERSION ("1.12.2") and decided it
// did not match - "The coremod ... is requesting minecraft version [1.8,1.12.2] and minecraft is 1.12.2. It
// will be ignored." Without the annotation FML applies no constraint, which is what we want: the payload md5
// check already refuses a mismatched OptiFine jar.
@IFMLLoadingPlugin.Name("OptiForge (legacy)")
@IFMLLoadingPlugin.SortingIndex(1000)
public final class OptiForgeLegacyCorePlugin implements IFMLLoadingPlugin {

    public String[] getASMTransformerClass() {
        return LegacyCoreRuntime.transformerClasses();
    }

    public String getModContainerClass() {
        return null;
    }

    public String getSetupClass() {
        return null;
    }

    public String getAccessTransformerClass() {
        return null;
    }

    public void injectData(Map<String, Object> data) {
        LegacyCoreRuntime.injectData(data);
    }

    public static LegacyPack pack() {
        return LegacyCoreRuntime.pack();
    }

    public static boolean onServer() {
        return LegacyCoreRuntime.onServer();
    }
}
