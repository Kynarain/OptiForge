import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import kynarain.cn.optiforge.OptifinePatcher;

/**
 * Applies one OptiFine payload patch to several candidate base jars and writes the result, so the caller can check
 * with javap which base jar the payload actually targets. Used to diagnose 26.2 K2_pre5 ("Bad tag (99)").
 */
public final class PatchProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: PatchProbe <optifine.jar> <logical/class/Name.class> <base.jar> [<base.jar> ...]");
            return;
        }
        Path optifine = Paths.get(args[0]);
        String logical = args[1];
        try (OptifinePatcher patcher = new OptifinePatcher(optifine)) {
            System.out.println("expected md5: " + patcher.expectedMd5(logical));
            for (int i = 2; i < args.length; i++) {
                Path base = Paths.get(args[i]);
                try {
                    byte[] patched = patcher.apply(logical, base);
                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
                    StringBuilder hex = new StringBuilder();
                    for (byte b : digest.digest(patched)) hex.append(String.format("%02x", b));
                    String md5 = hex.toString();
                    Path out = Paths.get("build", "probe-" + i + ".class");
                    Files.createDirectories(out.getParent());
                    Files.write(out, patched);
                    System.out.println("base " + i + ": " + base.getFileName() + " -> " + patched.length
                            + " bytes, md5 " + md5 + (md5.equals(patcher.expectedMd5(logical)) ? "  [md5 MATCH]" : ""));
                    System.out.println("  written to " + out.toAbsolutePath());
                } catch (Exception error) {
                    System.out.println("base " + i + ": " + base.getFileName() + " -> " + error);
                }
            }
        }
    }
}
