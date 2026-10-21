package kynarain.cn.optiforge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/**
 * Offline driver for {@link OptifinePatcher}: "does this OptiFine build's payload apply to this base
 * jar, and what does the result look like?"
 *
 * <pre>
 * java -cp OptiForge.jar kynarain.cn.optiforge.PatchApply \
 *      --optifine OptiFine_1.21_HD_U_J1_pre9.jar \
 *      --base forge-1.21-51.0.33-client.jar \
 *      --class net/minecraft/CrashReport [--out CrashReport.class] [--all]
 * </pre>
 *
 * <p>{@code --base} may be the vanilla client jar: the configuration map inside the OptiFine jar
 * resolves the logical name to the obfuscated entry for that form. {@code --all} applies every payload
 * entry instead of one, which is how a whole-line compatibility check is made before launching.
 */
public final class PatchApply {

    public static void main(String[] args) throws Exception {
        Path optifine = null;
        Path base = null;
        Path out = null;
        Path map = null;
        String one = null;
        boolean all = false;
        boolean namespace = false;
        int sample = 40;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--optifine":
                    optifine = Paths.get(args[++i]);
                    break;
                case "--base":
                    base = Paths.get(args[++i]);
                    break;
                case "--class":
                    one = args[++i];
                    break;
                case "--out":
                    out = Paths.get(args[++i]);
                    break;
                case "--map":
                    map = Paths.get(args[++i]);
                    break;
                case "--all":
                    all = true;
                    break;
                case "--namespace":
                    namespace = true;
                    break;
                case "--sample":
                    sample = Integer.parseInt(args[++i]);
                    break;
                case "--help":
                case "-h":
                    System.out.println("usage: PatchApply --optifine <jar> --base <jar> "
                            + "(--class <logical> [--out <file>] [--map <srg-to-official.tsv>] "
                            + "| --all | --namespace [--sample n])");
                    return;
                default:
                    throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (optifine == null || base == null || (!all && !namespace && one == null)) {
            throw new IllegalArgumentException("--optifine, --base and one of --class/--all/--namespace are required");
        }

        try (OptifinePatcher patcher = new OptifinePatcher(optifine)) {
            System.out.println("optifine : " + optifine.getFileName());
            System.out.println("base     : " + base.getFileName());
            Map<String, Integer> payload = patcher.payload();
            System.out.println("payload  : " + payload.size() + " patch/srg entries");

            if (namespace) {
                OptifinePatcher.Namespace stats = patcher.namespace(base, sample);
                System.out.println("sample   : " + stats.describe());
                for (String failure : stats.failures) {
                    System.out.println("  failed: " + failure);
                }
                String verdict;
                if (stats.srg()) {
                    verdict = "SRG member names - needs an SRG->official remap on an official-name runtime";
                } else if (stats.mcp()) {
                    verdict = "MCP/SRG func_/field_ names - matches the 1.14-1.16 Forge runtime, no remap";
                } else {
                    verdict = "official member names - usable as-is on an official-name runtime";
                }
                System.out.println("RESULT   : " + verdict);
                return;
            }

            if (all) {
                int ok = 0;
                int bad = 0;
                int missingBase = 0;
                for (String logical : payload.keySet()) {
                    try {
                        byte[] patched = patcher.apply(logical, base);
                        String expected = patcher.expectedMd5(logical);
                        String actual = md5(patched);
                        if (expected.equals(actual)) {
                            ok++;
                        } else {
                            bad++;
                            if (bad <= 15) {
                                System.out.println("  MD5 MISMATCH " + logical + " expected " + expected + " got " + actual);
                            }
                        }
                    } catch (Exception e) {
                        missingBase++;
                        if (missingBase <= 15) {
                            String message = e.getMessage() == null ? e.toString() : e.getMessage();
                            System.out.println("  FAILED       " + logical + " : " + message);
                        }
                    }
                }
                System.out.println("RESULT   : " + ok + " match, " + bad + " md5-mismatch, " + missingBase + " failed, of " + payload.size());
                if (ok + bad + missingBase != payload.size()) {
                    System.out.println("RESULT   : INCONSISTENT COUNT - treat as a bug in this tool");
                }
                return;
            }

            byte[] patched = patcher.apply(one, base);
            // The MD5 OptiFine records is the hash of the patched class BEFORE any remap, so it is
            // computed here - otherwise a correct remap would report as "mismatch" forever.
            String expected = patcher.expectedMd5(one);
            String actual = md5(patched);
            System.out.println("class    : " + one);
            System.out.println("md5      : expected " + expected + " / actual " + actual
                    + (expected.equals(actual) ? "  (MATCH)" : "  (MISMATCH)"));
            if (map != null) {
                SrgRemap remap = SrgRemap.load(map);
                SrgRemap.Result result = remap.remap(patched);
                System.out.println("remap    : table " + remap.size() + " rows, renamed " + result.renamed
                        + ", unmapped srg tokens " + result.unmapped);
                patched = result.bytes;
            }
            System.out.println("size     : " + patched.length + " bytes");
            if (out != null) {
                Files.createDirectories(out.toAbsolutePath().getParent());
                Files.write(out, patched);
                System.out.println("written  : " + out);
            }
        }
    }

    static String md5(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(data)) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
