package kynarain.cn.optiforge;

import cpw.mods.modlauncher.api.IEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The pack/transform bootstrap shared by both entry points.
 *
 * <p>Two artifacts exist: the Java-17 one (ModLauncher 10, {@code OptiForgeService}) and the Java-8 one
 * for Forge 1.14.4-1.16.5 (ModLauncher 2-8, {@code kynarain.cn.optiforge.oldml.OldModLauncherService}).
 * The entry classes differ only in the API they expose; the "prepare the pack once per process, write the
 * diagnostic, build the transformer" orchestration is identical and lives here.
 *
 * <p><b>State is static on purpose.</b> Measured: ModLauncher instantiates the service more than once for
 * one launch (one instance ran {@code initialize}, wrote the pack and then its
 * {@code additionalClassesLocator} was never consulted, while another instance's {@code onLoad} saw
 * {@code pack == null} and its locator answered "empty" for every name). That split turned into
 * {@code NoClassDefFoundError: net/optifine/util/MathUtils} while {@code net.minecraft.Util} initialised.
 * One process, one pack.
 *
 * <p>Everything here must compile with {@code --release 8}: it is part of the legacy artifact too.
 */
public final class Bootstrap {

    private static final StringBuilder NOTES = new StringBuilder();
    private static volatile boolean prepared;
    private static volatile Path gameDir;
    private static volatile Path reportFile;
    private static volatile RuntimeInfo info;
    private static volatile OptifinePack pack;
    private static volatile OptifineClassTransformer transformer;
    private static volatile TargetFactory targetFactory;
    private static volatile ModuleReadsHook moduleReadsHook;
    private static volatile Runnable moduleReadsInstaller;
    private static final java.util.concurrent.atomic.AtomicBoolean READS_INSTALLED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile boolean legacy;

    private Bootstrap() {
    }

    /** Java-9-only fixup (module reads) as a hook, so the shared pack pipeline never links against it. */
    public interface ModuleReadsHook {
        byte[] apply(byte[] classBytes, String className);
    }

    /** True when the legacy (1.14.4-1.16.5) artifact is running: changes the staged mods.toml ranges. */
    public static void setLegacy(boolean value) {
        legacy = value;
        OptifinePack.minecraftRange = value ? "[1.13,)" : "[1.17,)";
        OptifinePack.loaderVersionRange = value ? "[14,)" : "[1,)";
    }

    public static boolean legacy() {
        return legacy;
    }

    /** Which {@code Target} constructor the running ModLauncher understands. */
    public static void setTargetFactory(TargetFactory factory) {
        targetFactory = factory;
    }

    /** Java-9-only per-class rewrite, installed by the Java-17 entry point. */
    public static void setModuleReadsHook(ModuleReadsHook hook) {
        moduleReadsHook = hook;
    }

    /** Java-9-only one-time "add module reads" action, installed by the Java-17 entry point. */
    public static void setModuleReadsInstaller(Runnable installer) {
        moduleReadsInstaller = installer;
    }

    /** Applied by {@link OptifinePack} to every prepared class; a no-op unless a hook is installed. */
    static byte[] moduleReads(byte[] classBytes, String className) {
        ModuleReadsHook hook = moduleReadsHook;
        return hook == null ? classBytes : hook.apply(classBytes, className);
    }

    /** Builds the pack once per process and writes the report. Idempotent. */
    public static OptifinePack ensurePack(IEnvironment environment) {
        if (prepared) return pack;
        synchronized (Bootstrap.class) {
            if (prepared) return pack;
            // The first call can come from additionalClassesLocator()/transformers(), before any
            // initialize() has handed us an IEnvironment - recover the game directory from the
            // launch arguments (--gameDir) first. Without this the pack was built with gameDir=null,
            // "no OptiFine jar" was reported, and the launch looked successful while OptiFine was absent.
            if (gameDir == null && environment != null) {
                try {
                    gameDir = environment.getProperty(IEnvironment.Keys.GAMEDIR.get()).orElse(null);
                } catch (Throwable ignored) {
                    // an environment shape we cannot read: the launch arguments still have --gameDir
                }
            }
            if (gameDir == null || info == null) {
                RuntimeInfo parsed = RuntimeInfo.fromLaunchArguments(gameDir);
                if (gameDir == null) gameDir = parsed.gameDir;
                if (info == null) info = parsed;
                if (reportFile == null && gameDir != null) {
                    reportFile = gameDir.resolve("optiforge").resolve("diagnose.txt");
                }
            }
            if (gameDir == null) {
                // No environment at all yet: leave prepared=false so a later call can still build.
                return null;
            }
            prepared = true;
            try {
                if (info == null) {
                    info = RuntimeInfo.fromLaunchArguments(gameDir);
                }
                OptifinePack.mcVersion = info.mcVersion;
                NOTES.append("OptiForge ").append(Diagnose.VERSION).append('\n')
                        .append(info.describe()).append('\n')
                        .append(legacy ? "artifact: oldml (Java 8, ModLauncher 2-8)\n" : "");

                Path optifineJar = OptifinePack.findOptifineJar(gameDir,
                        System.getProperty("optiforge.optifine"), NOTES);
                if (optifineJar == null) {
                    NOTES.append("no OptiFine jar: put it in <game>/optiforge/ (or pass -Doptiforge.optifine=)\n");
                    // The prompt is in-game (optiforge/generated/Notice), like OptiFabric's title-screen
                    // error: the folder is created so it can be pointed at, and the staged mod reports it.
                    System.setProperty("optiforge.missingOptifine", "true");
                    System.setProperty("optiforge.gameDir", gameDir.toAbsolutePath().toString());
                    try {
                        Files.createDirectories(gameDir.resolve("optiforge"));
                        OptifinePack.removeLegacyStagedJar(gameDir);
                        OptifinePack.stageNoticeOnly(gameDir, NOTES);
                    } catch (IOException e) {
                        NOTES.append("could not stage the notice mod: ").append(e).append('\n');
                    }
                    finish();
                    return null;
                }
                System.clearProperty("optiforge.missingOptifine");
                System.setProperty("optiforge.gameDir", gameDir.toAbsolutePath().toString());
                OptifinePack.removeLegacyStagedJar(gameDir);
                // The base jar is resolved first: it is the fallback evidence for the namespace probe, and
                // the payload identity is what decides remapping, not a version number.
                Path baseJar = OptifinePack.findBaseJar(info, gameDir, optifineJar,
                        System.getProperty("optiforge.vanilla"), NOTES);
                if (baseJar == null) {
                    finish();
                    return null;
                }
                OptifinePack.Namespace runtime = OptifinePack.detectRuntimeNamespace(info, gameDir, baseJar, NOTES);
                Path mapping = OptifinePack.pickMapping(info.mcVersion, NOTES);
                // Remap only when the runtime speaks official names: an SRG (or MCP/SRG func_/field_)
                // payload on such a runtime is already correct, and renaming it would be the bug. When
                // the payload is official too, the rename matches zero tokens and is a no-op.
                boolean remapEnabled = runtime == OptifinePack.Namespace.OFFICIAL && mapping != null;
                NOTES.append("remap: ").append(remapEnabled ? "enabled" : "disabled")
                        .append(" (runtime=").append(runtime)
                        .append(", mapping=").append(mapping == null ? "none" : mapping.getFileName())
                        .append(")\n");
                // Both versions have to be known *before* prepare(): the interface-availability index is
                // built from this launch's Forge jars, and a later assignment left it without the version
                // tag (measured: the index was skipped entirely on 1.21.7).
                OptifinePack.forgeVersion = info.forgeVersion;
                pack = OptifinePack.prepare(gameDir, optifineJar, baseJar, mapping, remapEnabled,
                        info.mcVersion, NOTES);
                NOTES.append(pack.summary()).append('\n');
            } catch (Throwable t) {
                NOTES.append("OptiForge failed to prepare the pack: ").append(t).append('\n');
                t.printStackTrace(System.out);
            }
            finish();
            return pack;
        }
    }

    /** The shared transformer, built on first use with the artifact's {@link TargetFactory}. */
    public static OptifineClassTransformer transformer() {
        OptifinePack current = ensurePack(null);
        if (current == null) return null;
        if (transformer == null) {
            TargetFactory factory = targetFactory;
            if (factory == null) {
                throw new IllegalStateException("no TargetFactory installed for this artifact");
            }
            transformer = new OptifineClassTransformer(current, factory);
        }
        return transformer;
    }

    public static OptifinePack pack() {
        return pack;
    }

    public static RuntimeInfo info() {
        return info;
    }

    public static Path reportFile() {
        return reportFile;
    }

    /** Appends one line to the diagnostic report (used by the Java-17 entry for its module-reads result). */
    public static void appendReport(String text) {
        Path file = reportFile;
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            J8.appendString(file, text + System.lineSeparator());
        } catch (IOException e) {
            System.out.println("[OptiForge] could not append to " + file + ": " + e);
        }
    }

    /**
     * Runs the Java-17 module-reads installer once. The legacy artifact never installs one, so this is a
     * no-op there (Java 8 has no modules).
     */
    public static void ensureModuleReads() {
        Runnable installer = moduleReadsInstaller;
        if (installer == null || !READS_INSTALLED.compareAndSet(false, true)) return;
        try {
            installer.run();
        } catch (Throwable t) {
            System.out.println("[OptiForge] module reads failed: " + t);
        }
    }

    private static void finish() {
        String text;
        synchronized (NOTES) {
            text = NOTES.toString();
        }
        System.out.println("[OptiForge] " + text.replace("\n", "\n[OptiForge] "));
        if (reportFile != null) {
            try {
                Files.createDirectories(reportFile.getParent());
                J8.writeString(reportFile, text);
            } catch (IOException e) {
                System.out.println("[OptiForge] could not write " + reportFile + ": " + e);
            }
        }
    }
}
