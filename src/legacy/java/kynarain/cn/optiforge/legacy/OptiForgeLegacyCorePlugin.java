package kynarain.cn.optiforge.legacy;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.nio.file.Path;
import java.util.Map;

/**
 * Entry point for Minecraft 1.8-1.12.2: {@code FMLCorePlugin} in the jar manifest.
 *
 * <p>Those Forge versions run on launchwrapper and never load a ModLauncher
 * {@code ITransformationService} (measured from the 1.12.2 install profile: {@code mainClass =
 * net.minecraft.launchwrapper.Launch}, {@code --tweakClass FMLTweaker}), so the modern service cannot
 * even be discovered there. FML does register coremod transformers, and it registers them after
 * {@code PatchingTransformer} (docs/PHASE3.md §2.1), which is what keeps the payload delivery clear of
 * FML's adler32 {@code System.exit(1)}.
 *
 * <p>The default sorting index is 1000 and is written out explicitly: a coremod wrapper with index 1000
 * lands after FML's own patch transformer but before {@code FMLDeobfTweaker}, whose access transformers
 * must still run over the classes we deliver (same slot OptiFine itself uses).
 *
 * <p>{@code injectData} only records which jar to patch; the pack itself is built lazily on the first
 * class load because {@code FMLInjectionData} (and therefore the MC version) is not populated yet at this
 * point (measured in a real 1.12.2 launch, see {@link LegacyBootstrap}).
 */
// No @MCVersion: FML 1.12.2 compared the bracketed range against Loader.MC_VERSION ("1.12.2") and decided it
// did not match - "The coremod ... is requesting minecraft version [1.8,1.12.2] and minecraft is 1.12.2. It
// will be ignored." Without the annotation FML applies no constraint, which is what we want: the payload md5
// check already refuses a mismatched OptiFine jar.
@IFMLLoadingPlugin.Name("OptiForge (legacy)")
@IFMLLoadingPlugin.SortingIndex(1000)
public final class OptiForgeLegacyCorePlugin implements IFMLLoadingPlugin {

    private static final String TAG = "[OptiForge/legacy]";

    private static volatile boolean server;

    /**
     * Returns the transformer unconditionally: FML calls this from the plugin wrapper after
     * {@code injectData}, but keeping it independent of the pack state means the order in which those two
     * are called cannot matter. The transformer is a no-op until the lazy pack build succeeds.
     */
    public String[] getASMTransformerClass() {
        return new String[]{LegacyTransformer.class.getName()};
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
        StringBuilder notes = new StringBuilder();
        Path gameDir = null;
        try {
            gameDir = LegacyPaths.gameDirFrom(data);
            server = LegacyPaths.isServer();
            if (server) {
                // OptiFine skips its transformer on a Forge server (measured from OptiFineForgeTweaker
                // bytecode); the payload patches client classes that are not on a server.
                LegacyBootstrap.writeDiagnose(gameDir, "server detected, payload not built\n");
                System.out.println(TAG + " server detected, payload not built");
                return;
            }
            Path optifine = LegacyPaths.findOptiFineJar(gameDir,
                    System.getProperty("optiforge.optifine"), notes);
            if (optifine == null) {
                LegacyBootstrap.writeDiagnose(gameDir, "optiforge " + LegacyPack.version() + " (legacy)\n"
                        + "no OptiFine jar in " + LegacyPaths.optiforgeDir(gameDir) + '\n'
                        + "put the jar there (not in mods/); vanilla Forge will start normally\n"
                        + notes);
                System.out.println(TAG + " no OptiFine jar in " + LegacyPaths.optiforgeDir(gameDir));
                return;
            }
            // Keep the request only: at this point FMLInjectionData is still empty, so the MC version and
            // the base jar cannot be resolved (measured: diagnose said "MC null" and gave up).
            LegacyBootstrap.request(gameDir, optifine,
                    System.getProperty("optiforge.vanilla"),
                    System.getProperty("optiforge.mcversion"));
            if (LegacyBootstrap.current() == null) {
                System.out.println(TAG + " pack deferred to the first class load (diagnose: "
                        + LegacyPaths.optiforgeDir(gameDir).resolve("diagnose-legacy.txt") + ")");
            }
        } catch (Throwable t) {
            LegacyBootstrap.writeDiagnose(gameDir == null ? safeGameDir(data) : gameDir,
                    "legacy coremod failed: " + t + '\n' + notes);
            System.out.println(TAG + " failed: " + t);
            t.printStackTrace(System.out);
        }
    }

    private static Path safeGameDir(Map<String, Object> data) {
        try {
            return LegacyPaths.gameDirFrom(data);
        } catch (Throwable t) {
            return null;
        }
    }

    public static LegacyPack pack() {
        return LegacyBootstrap.pack();
    }

    public static boolean onServer() {
        return server;
    }
}
