package kynarain.cn.optiforge.legacy;

import java.nio.file.Path;
import java.util.Map;

/**
 * Everything a coremod entry point does, minus the FML package names.
 *
 * <p>Measured from the Forge universal jars: 1.7.10 ships FML under {@code cpw.mods.fml.*} (452
 * entries, zero under {@code net.minecraftforge.fml}), while 1.8.9-1.12.2 use
 * {@code net.minecraftforge.fml.*}; the two {@code IFMLLoadingPlugin} interfaces have the same shape
 * ({@code getASMTransformerClass}, {@code getModContainerClass}, {@code getSetupClass},
 * {@code injectData}, {@code getAccessTransformerClass}) but cannot both be implemented by one class
 * compiled against one of them. So the interface binding stays in two tiny classes - one per source set -
 * and this class holds the single shared implementation.
 */
public final class LegacyCoreRuntime {

    private static final String TAG = "[OptiForge/legacy]";

    private static volatile boolean server;

    private LegacyCoreRuntime() {
    }

    /** The transformer class name; unchanged between the two FML package layouts. */
    public static String[] transformerClasses() {
        return new String[]{LegacyTransformer.class.getName()};
    }

    public static boolean onServer() {
        return server;
    }

    public static LegacyPack pack() {
        return LegacyBootstrap.pack();
    }

    /** Both {@code IFMLLoadingPlugin.injectData} bindings call this. */
    public static void injectData(Map<String, Object> data) {
        StringBuilder notes = new StringBuilder();
        Path gameDir = null;
        try {
            gameDir = LegacyPaths.gameDirFrom(data);
            reportJavaMismatch(gameDir);
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

    /**
     * 1.8.9-1.12.2 (and 1.7.10) are Java 8 lines. This jar still loads on a newer JVM because it is
     * class-file 52, but Forge on those lines is not supported there - say so once instead of leaving the
     * user with a mysterious render or mixin failure.
     */
    private static void reportJavaMismatch(Path gameDir) {
        String spec = System.getProperty("java.specification.version", "");
        int major;
        try {
            major = spec.startsWith("1.") ? Integer.parseInt(spec.substring(2)) : Integer.parseInt(spec.split("\\.")[0]);
        } catch (RuntimeException e) {
            return;
        }
        if (major > 8) {
            String warning = TAG + " WARNING: 1.8.9-1.12.2 Forge only supports Java 8, but this runtime is Java "
                    + major + "; switch the instance's Java to 8 if anything looks wrong";
            System.out.println(warning);
            try {
                java.nio.file.Files.write(
                        LegacyPaths.optiforgeDir(gameDir).resolve("diagnose-legacy.txt"),
                        (warning + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                // the log line above is the useful part
            }
        }
    }}