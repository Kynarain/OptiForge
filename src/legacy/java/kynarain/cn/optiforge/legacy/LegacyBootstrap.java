package kynarain.cn.optiforge.legacy;

import net.minecraft.launchwrapper.Launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The "prepare the pack" request, and its lazy execution.
 *
 * <p>Measured in a real 1.12.2 launch (Forge 1.12.2-14.23.5.2860, JRE 1.8.0_503, Lead):
 * {@code IFMLLoadingPlugin.injectData} runs before {@code FMLInjectionData} is populated, so the MC
 * version is unknown there ({@code diagnose-legacy.txt} said "MC null") and the base jar lookup fails.
 * Both are available by the time the first game class is loaded, so the request is recorded here and the
 * pack is built on demand from {@link LegacyTransformer}.
 *
 * <p>Failure classes are kept apart on purpose: "version or base jar not known yet" is retried on the next
 * class load, while a payload/integrity failure is fatal and never retried (a payload whose md5 does not
 * match cannot start working later).
 */
public final class LegacyBootstrap {

    private static final String TAG = "[OptiForge/legacy]";
    private static final Object LOCK = new Object();

    private static volatile LegacyPack pack;
    private static volatile boolean fatal;
    private static volatile Path gameDir;
    private static volatile Path optifineJar;
    private static volatile String vanillaOverride;
    private static volatile String mcVersionOverride;
    private static volatile String diagnose = "not run" + '\n';
    private static int attempts;

    private LegacyBootstrap() {
    }

    /** Called from {@code injectData}; it must not need the MC version or the base jar yet. */
    public static void request(Path dir, Path optifine, String vanilla, String mcVersion) {
        gameDir = dir;
        optifineJar = optifine;
        vanillaOverride = vanilla;
        mcVersionOverride = mcVersion;
    }

    public static LegacyPack pack() {
        return pack;
    }

    public static String diagnoseText() {
        return diagnose;
    }

    /**
     * Builds the pack once. Returns {@code null} while the version/base jar is still unknown (the caller
     * keeps the class unchanged and tries again on the next class) or after a fatal payload failure.
     */
    public static LegacyPack current() {
        LegacyPack ready = pack;
        if (ready != null) {
            return ready;
        }
        if (fatal || gameDir == null || optifineJar == null) {
            return null;
        }
        synchronized (LOCK) {
            if (pack != null) {
                return pack;
            }
            if (fatal || gameDir == null || optifineJar == null) {
                return null;
            }
            attempts++;
            StringBuilder notes = new StringBuilder();
            try {
                String mc = LegacyPaths.detectMcVersion(gameDir, mcVersionOverride, notes);
                if (mc == null) {
                    deferred(notes, "MC version not known yet");
                    return null;
                }
                Path base = LegacyPaths.findBaseJar(gameDir, mc, vanillaOverride, notes);
                if (base == null) {
                    deferred(notes, "no base client jar found for MC " + mc);
                    return null;
                }
                for (String line : ("discovery: " + notes).split("\n")) {
                    System.out.println(TAG + " " + line);
                }
                System.out.println(TAG + " optifine=" + optifineJar.getFileName()
                        + " base=" + base.getFileName() + " mc=" + mc);
                long start = System.nanoTime();
                LegacyPack built = LegacyPack.prepare(gameDir, optifineJar, base, notes);
                long millis = (System.nanoTime() - start) / 1000000L;
                // The staged jar only carries OptiFine's own classes and assets; game classes stay out of
                // the class path on purpose (see LegacyPack.writeStagedJar).
                try {
                    Launch.classLoader.addURL(built.stagedJar().toUri().toURL());
                } catch (Throwable t) {
                    System.out.println(TAG + " could not add " + built.stagedJar().getFileName()
                            + " to the launch class loader: " + t);
                }
                LegacyResourceLocator.install(built.stagedJar());
                pack = built;
                diagnose = header(optifineJar, base, mc) + built.summary() + '\n'
                        + "prepared lazily in " + millis + " ms (attempt " + attempts + ")\n"
                        + notes;
                writeDiagnose(gameDir, diagnose);
                System.out.println(TAG + " pack ready in " + millis + " ms: " + built.summary());
                if (built.md5Mismatch() > 0) {
                    // A jar accepted by name from java.class.path can still be a Forge-patched copy; the
                    // payload md5 is the authority on the base, so say exactly what to do about it.
                    System.out.println(TAG + " WARNING: " + built.md5Mismatch()
                            + " payload class(es) did not match this base jar;"
                            + " pass -Doptiforge.vanilla=<vanilla 1.12.2 client jar>");
                }
                return built;
            } catch (Throwable t) {
                fatal = true;
                diagnose = "legacy pack failed after " + attempts + " attempt(s): " + t + '\n' + notes;
                writeDiagnose(gameDir, diagnose);
                System.out.println(TAG + " pack failed: " + t);
                t.printStackTrace(System.out);
                return null;
            }
        }
    }

    private static void deferred(StringBuilder notes, String reason) {
        diagnose = "optiforge " + LegacyPack.version() + " (legacy)\n"
                + "optifine: " + optifineJar + '\n'
                + "deferred: " + reason + " (attempt " + attempts + ")\n"
                + notes;
        writeDiagnose(gameDir, diagnose);
        // Class loading calls this for every class; only the first few attempts are worth a line.
        if (attempts <= 2) {
            System.out.println(TAG + " pack deferred: " + reason + " (will retry on the next class load)");
        }
    }

    private static String header(Path optifine, Path base, String mc) {
        return "optiforge " + LegacyPack.version() + " (legacy)\n"
                + "optifine: " + optifine + '\n'
                + "base: " + base + '\n'
                + "mc: " + mc + '\n';
    }

    public static void writeDiagnose(Path dir, String text) {
        if (dir == null) {
            return;
        }
        try {
            Path out = dir.resolve("optiforge").resolve("diagnose-legacy.txt");
            Files.createDirectories(out.getParent());
            Files.write(out, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // a diagnose file that cannot be written must not break the launch
        }
    }
}
