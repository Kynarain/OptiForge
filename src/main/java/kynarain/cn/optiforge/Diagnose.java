package kynarain.cn.optiforge;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The M0 deliverable: a report that says what OptiFine jar a Forge instance has, what its payload
 * contains, and whether OptiFine's own compatibility table covers the Forge version in use.
 *
 * <p>Two entry points, one report:
 * <ul>
 *   <li>{@link #run(RuntimeInfo, Path, Path)} — called from the transformation service inside a real
 *       Forge launch (this is the only way to see the versions Forge actually passes);</li>
 *   <li>{@link #main(String[])} — offline, for a jar on disk and a Forge version given on the command
 *       line. Used by {@code tools/diagnose.ps1} and by the repository's own checks.</li>
 * </ul>
 */
public final class Diagnose {

    /**
     * Build version, written into the jar as {@code /optiforge/version.txt} by both build paths.
     *
     * <p>It used to be a literal here and drifted: releases shipped 0.9.x while the report and the staged
     * mods.toml still said 0.1.0.
     */
    public static final String VERSION = readVersion();

    private static String readVersion() {
        try (java.io.InputStream in = Diagnose.class.getResourceAsStream("/optiforge/version.txt")) {
            if (in != null) {
                String text = new String(J8.readAll(in), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) return text;
            }
        } catch (java.io.IOException ignored) {
            // fall through to the placeholder
        }
        return "dev";
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Diagnose() {
    }

    /** Scans {@code modsDir}, renders the report, writes it next to the game and echoes a summary. */
    public static String run(RuntimeInfo info, Path modsDir, Path outFile) {
        List<OptifineJar> jars = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (modsDir != null && Files.isDirectory(modsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.jar")) {
                for (Path jar : stream) {
                    try {
                        OptifineJar candidate = OptifineJar.read(jar);
                        if (candidate.looksLikeOptifine()) jars.add(candidate);
                    } catch (IOException e) {
                        skipped.add(jar.getFileName() + " (" + e.getClass().getSimpleName() + ")");
                    }
                }
            } catch (IOException e) {
                skipped.add("mods/: " + e.getMessage());
            }
        } else {
            skipped.add("no mods/ directory at " + modsDir);
        }

        ForgeMatrix matrix = ForgeMatrix.loadBundled();
        String report = render(info, matrix, jars, skipped);

        PrintStream out = System.out;
        out.println(report);

        if (outFile != null) {
            try {
                Files.createDirectories(outFile.getParent());
                J8.writeString(outFile, report);
                out.println("[OptiForge] report written to " + outFile);
            } catch (IOException e) {
                out.println("[OptiForge] could not write report to " + outFile + ": " + e);
            }
        }
        return report;
    }

    public static String render(RuntimeInfo info, ForgeMatrix matrix, List<OptifineJar> jars,
                                List<String> skipped) {
        StringBuilder sb = new StringBuilder();
        sb.append("OptiForge ").append(VERSION).append(" - diagnosis\n");
        sb.append("generated ").append(LocalDateTime.now().format(STAMP)).append("\n");
        sb.append(J8.repeat("=", 72)).append("\n\n");

        sb.append("[environment]\n").append(info.describe()).append('\n');
        sb.append("Java            : ").append(System.getProperty("java.version"))
                .append(" (").append(System.getProperty("java.vm.name")).append(")\n");
        sb.append("OS              : ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(' ')
                .append(System.getProperty("os.arch")).append('\n');
        sb.append("matrix rows     : ").append(matrix.size()).append('\n');
        if (!info.known()) {
            sb.append("NOTE            : the Forge/Minecraft versions are unknown, so every matrix\n")
                    .append("                  comparison below is reported as inconclusive.\n");
        }
        sb.append('\n');

        sb.append("[OptiFine jars in mods] (").append(jars.size()).append(")\n");
        if (jars.isEmpty()) {
            sb.append("  none. OptiForge needs the user's own OptiFine jar in mods/.\n");
        }
        for (OptifineJar jar : jars) {
            sb.append("  ").append(jar.fileName()).append('\n');
            sb.append("    path        : ").append(jar.path).append('\n');
            sb.append("    size/sha256 : ").append(jar.size).append(" / ").append(jar.sha256).append('\n');
            sb.append("    buildof.txt : ").append(jar.buildDate == null ? "<none>" : jar.buildDate)
                    .append("   entries: ").append(jar.entryCount).append('\n');
            sb.append("    shape       : ").append(jar.hasInstaller
                            ? "installer form (optifine/Installer.class present)"
                            : "PROCESSED form - no optifine/Installer.class, some tool rewrote this jar")
                    .append('\n');
            sb.append("    payload     : patch/srg=").append(jar.patchSrg)
                    .append("  patch/notch=").append(jar.patchNotch)
                    .append("  patch/flat=").append(jar.patchFlat)
                    .append("  patch/assets=").append(jar.patchAssets).append('\n');
            sb.append("    own classes : srg/=").append(jar.srgOwnClasses)
                    .append(" (forge ").append(jar.srgForgeOwnClasses).append(')')
                    .append("  notch/=").append(jar.notchOwnClasses)
                    .append(" (forge ").append(jar.notchForgeOwnClasses).append(')')
                    .append("  root net/optifine=").append(jar.rootOwnClasses).append(")\n");
            sb.append("    flags       : patch.cfg=").append(jar.hasPatchCfg)
                    .append(" patch2.cfg=").append(jar.hasPatch2Cfg)
                    .append(" mods.toml=").append(jar.hasModsToml)
                    .append(" Installer.class=").append(jar.hasInstaller).append('\n');
            for (String service : jar.services) {
                sb.append("    service     : ").append(service).append('\n');
            }

            ForgeRefs refs = ForgeRefs.scan(jar);
            for (String line : refs.summary().split("\n")) {
                sb.append("    ").append(line).append('\n');
            }

            ForgeMatrix.Row row = matrix.lookup(jar.fileName());
            if (row == null) {
                sb.append("    matrix      : no row for this file name\n");
            } else {
                sb.append("    matrix      : mc ").append(row.mcVersion).append(", ")
                        .append(row.describe()).append('\n');
            }

            PatchPlan plan = PatchPlan.of(info, row, jar);
            sb.append("    plan        : ").append(plan.verdict()).append('\n');
            for (String line : plan.lines) {
                sb.append("                  ").append(line).append('\n');
            }
            sb.append('\n');
        }

        if (!skipped.isEmpty()) {
            sb.append("[skipped]\n");
            for (String s : skipped) sb.append("  ").append(s).append('\n');
            sb.append('\n');
        }

        sb.append("[what this means]\n");
        sb.append("  Forge does not load OptiFine as a mod: every jar in mods/ that declares\n")
                .append("  META-INF/services/cpw.mods.modlauncher.api.ITransformationService is taken as a\n")
                .append("  transformation service and excluded from mod discovery (Forge's\n")
                .append("  ModDirTransformerDiscoverer). OptiFine's mods.toml therefore never takes part in\n")
                .append("  any Forge version check; what a build can or cannot patch is decided by its payload\n")
                .append("  and by its precompiled net/minecraftforge-facing classes, which are pinned to the\n")
                .append("  Forge version OptiFine's downloads page names (the \"Forge\" column).\n");
        return sb.toString();
    }

    /**
     * Offline driver.
     *
     * <pre>
     * java -cp OptiForge.jar kynarain.cn.optiforge.Diagnose \
     *      --jar mods/OptiFine_1.21_HD_U_J1_pre9.jar --forge 51.0.33 --mc 1.21 [--out report.txt]
     * </pre>
     *
     * <p>{@code --jar} may repeat; {@code --gamedir} is accepted as an alternative and scans its mods/.
     */
    public static void main(String[] args) throws Exception {
        String mc = "";
        String forge = "";
        Path gameDir = null;
        Path out = null;
        List<Path> jars = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mc":
                    mc = args[++i];
                    break;
                case "--forge":
                    forge = args[++i];
                    break;
                case "--gamedir":
                    gameDir = Paths.get(args[++i]);
                    break;
                case "--out":
                    out = Paths.get(args[++i]);
                    break;
                case "--jar":
                    jars.add(Paths.get(args[++i]));
                    break;
                case "--help":
                case "-h":
                    System.out.println("usage: Diagnose --jar <file>... [--forge <version>] [--mc <version>]"
                            + " [--gamedir <dir>] [--out <file>]");
                    return;
                default:
                    throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (jars.isEmpty() && gameDir != null && Files.isDirectory(gameDir.resolve("mods"))) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(gameDir.resolve("mods"), "*.jar")) {
                stream.forEach(jars::add);
            }
        }
        if (jars.isEmpty()) {
            throw new IllegalArgumentException("nothing to inspect: pass --jar <file> or --gamedir <dir>");
        }

        RuntimeInfo info = RuntimeInfo.offline(mc, forge, gameDir);
        ForgeMatrix matrix = ForgeMatrix.loadBundled();

        List<OptifineJar> found = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Path jar : jars) {
            try {
                OptifineJar candidate = OptifineJar.read(jar);
                if (candidate.looksLikeOptifine()) found.add(candidate);
                else skipped.add(jar.getFileName() + " (not an OptiFine build)");
            } catch (IOException e) {
                skipped.add(jar + " (" + e + ")");
            }
        }

        String report = render(info, matrix, found, skipped);
        System.out.println(report);
        if (out != null) {
            Files.createDirectories(out.getParent());
            J8.writeString(out, report);
            System.out.println("[OptiForge] report written to " + out);
        }
    }
}
