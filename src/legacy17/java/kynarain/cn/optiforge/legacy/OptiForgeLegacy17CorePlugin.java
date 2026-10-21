package kynarain.cn.optiforge.legacy;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * Entry point for Minecraft 1.7.10: {@code FMLCorePlugin} in the jar manifest.
 *
 * <p>Measured from the Forge 1.7.10-10.13.0.1157 universal jar: FML lives under {@code cpw.mods.fml.*}
 * (452 entries; there is no {@code net.minecraftforge.fml} package at all), and its
 * {@code IFMLLoadingPlugin} has the same five methods as the 1.8.9-1.12.2 one plus the same
 * {@code @Name}/{@code @SortingIndex} annotations. A class compiled against the
 * {@code net.minecraftforge.fml} interface cannot be loaded there, which is why this binding exists; the
 * payload, staging, transformer, resource handling and lazy bootstrap are all shared with
 * {@link OptiForgeLegacyCorePlugin} through {@link LegacyCoreRuntime}.
 *
 * <p>No {@code @MCVersion}: FML rejected the bracketed range in a real 1.12.2 launch, so this binding has
 * none either - the payload md5 check is the real version guard.
 */
@IFMLLoadingPlugin.Name("OptiForge (legacy 1.7.10)")
@IFMLLoadingPlugin.SortingIndex(1000)
public final class OptiForgeLegacy17CorePlugin implements IFMLLoadingPlugin {

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
}
