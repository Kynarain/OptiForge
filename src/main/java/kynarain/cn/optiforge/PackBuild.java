package kynarain.cn.optiforge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * Offline driver for {@link OptifinePack}: builds the pack without launching Minecraft and checks the
 * two things that decide whether it can work at all.
 *
 * <pre>
 * java -cp OptiForge.jar kynarain.cn.optiforge.PackBuild \
 *      --optifine optiforge/OptiFine_1.21_HD_U_J1_pre9.jar \
 *      --base versions/1.21/1.21.jar \
 *      --mapping data/mappings/1.21-srg-to-official.tsv \
 *      --gamedir build/packtest
 * </pre>
 */
public final class PackBuild {

    private static final Pattern SRG = Pattern.compile("[fm]_\\d+_");
    /** The 1.13-1.16 MCP/SRG spelling ({@code func_}/{@code field_}); already the runtime namespace there. */
    private static final Pattern MCP = Pattern.compile("(?:func|field)_\\d+_");

    public static void main(String[] args) throws Exception {
        Path optifine = null;
        Path base = null;
        Path mapping = null;
        Path gameDir = null;
        String mc = "1.21";
        String forge = "51.0.33";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--optifine":
                    optifine = Paths.get(args[++i]);
                    break;
                case "--base":
                    base = Paths.get(args[++i]);
                    break;
                case "--mapping":
                    mapping = Paths.get(args[++i]);
                    break;
                case "--gamedir":
                    gameDir = Paths.get(args[++i]);
                    break;
                case "--mc":
                    mc = args[++i];
                    break;
                case "--forge":
                    forge = args[++i];
                    break;
                case "--help":
                case "-h":
                    System.out.println("usage: PackBuild --optifine <jar> --base <vanilla jar> "
                            + "[--mapping <tsv>] [--gamedir <dir>] [--mc <v>] [--forge <v>]");
                    return;
                default:
                    throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (optifine == null || base == null) {
            throw new IllegalArgumentException("--optifine and --base are required");
        }
        if (gameDir != null) Files.createDirectories(gameDir);

        StringBuilder notes = new StringBuilder();
        RuntimeInfo info = RuntimeInfo.offline(mc, forge, gameDir);
        long start = System.nanoTime();
        OptifinePack pack = OptifinePack.prepare(gameDir, optifine, base, mapping,
                mapping != null, info.mcVersion, notes);
        long millis = (System.nanoTime() - start) / 1_000_000;

        System.out.println("=== notes ===");
        System.out.print(notes);
        System.out.println("=== summary ===");
        System.out.println(pack.summary());
        System.out.println("prepared in " + millis + " ms");

        // Three-way namespace: modern SRG (m_/f_), the 1.13-1.16 MCP/SRG spelling (func_/field_) or
        // official. The legacy lines need no remap, so there func_/field_ is the *expected* output.
        boolean legacy = isLegacyNamespace(mc);
        System.out.println("=== checks ===");
        if (legacy) {
            checkPatched(pack, "net.minecraft.CrashReport", "func_", "m_");
        } else {
            checkPatched(pack, "net.minecraft.CrashReport", "forThrowable", "m_127521_");
        }
        checkPatched(pack, "net.minecraft.Minecraft", null, null);
        int ownSample = 0;
        int ownWithSrg = 0;
        int ownWithMcp = 0;
        for (String name : pack.ownClasses.keySet()) {
            if (ownSample >= 40) break;
            ownSample++;
            byte[] bytes = Files.readAllBytes(pack.ownClasses.get(name));
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (SRG.matcher(text).find()) ownWithSrg++;
            if (MCP.matcher(text).find()) ownWithMcp++;
        }
        System.out.println("own classes sampled " + ownSample + ", modern SRG tokens: " + ownWithSrg
                + ", MCP/SRG func_/field_ tokens: " + ownWithMcp);
        boolean ok = ownWithSrg == 0;
        System.out.println("RESULT   : " + (ok ? "OK (" + (legacy ? "MCP/SRG namespace, no remap expected"
                : "official payload, remap applied") + ")" : "FAIL - own classes still SRG-named"));
        if (!ok) System.exit(1);
    }

    /** True for the 1.13-1.16 lines, whose Forge runtime speaks the func_/field_ MCP/SRG spelling. */
    private static boolean isLegacyNamespace(String mcVersion) {
        if (mcVersion == null) return false;
        String v = mcVersion.trim();
        return v.startsWith("1.13") || v.startsWith("1.14") || v.startsWith("1.15") || v.startsWith("1.16");
    }

    private static void checkPatched(OptifinePack pack, String className, String expect, String forbid) {
        byte[] bytes = pack.patched(className);
        if (bytes == null) {
            System.out.println(className + " : not in pack");
            return;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        String expectState = expect == null ? "n/a" : (text.contains(expect) ? "present" : "MISSING");
        String forbidState = forbid == null ? "n/a" : (text.contains(forbid) ? "PRESENT" : "absent");
        System.out.println(className + " : " + bytes.length + " bytes, " + expect + "=" + expectState
                + ", " + forbid + "=" + forbidState);
    }
}
