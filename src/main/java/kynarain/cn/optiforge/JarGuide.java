package kynarain.cn.optiforge;

/**
 * Startup self-check for the two mistakes that dominate support requests: the wrong jar for a version
 * line, and the wrong Java for that line.
 *
 * <p>The jar choice is decided by the loading mechanism, not by Java: 1.13.2-1.16.5 use the old
 * ModLauncher (2-8) whose {@code ITransformationService} has a different shape, so they need
 * {@code -oldml}; 1.8.9-1.12.2 use launchwrapper and need {@code -legacy}; 1.7.10 needs
 * {@code -legacy17} because its FML lives under {@code cpw.mods.fml}. Measured in this project: the
 * Java-17 artifact on a Java 8 runtime dies with {@code UnsupportedClassVersionError: class file version
 * 61.0}, and on 1.16.5 Forge's bundled ASM cannot read newer class files either, so the guidance has to
 * name both numbers.
 *
 * <p>This class is deliberately Java-8 clean so the same source can be compiled into every artifact.
 */
public final class JarGuide {

    private JarGuide() {
    }

    /** Runtime Java major version, e.g. 8, 17, 21, 25. Returns 0 when it cannot be read. */
    public static int javaMajor() {
        String spec = System.getProperty("java.specification.version", "");
        try {
            if (spec.startsWith("1.")) {
                return Integer.parseInt(spec.substring(2));
            }
            int dot = spec.indexOf('.');
            return Integer.parseInt(dot < 0 ? spec : spec.substring(0, dot));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** MC "1.16.5" -> 16 (minor), "26.1.2" -> 26 (major). 0 when unreadable. */
    static int mcEra(String mcVersion) {
        if (mcVersion == null) return 0;
        String v = mcVersion.trim();
        if (v.isEmpty()) return 0;
        try {
            int dot = v.indexOf('.');
            String head = dot < 0 ? v : v.substring(0, dot);
            int major = Integer.parseInt(head);
            if (major == 1 && dot > 0) {
                int next = v.indexOf('.', dot + 1);
                return Integer.parseInt(next < 0 ? v.substring(dot + 1) : v.substring(dot + 1, next));
            }
            return major;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The artifact that belongs to a Minecraft line, for the message text. */
    public static String expectedArtifact(String mcVersion) {
        int era = mcEra(mcVersion);
        String mc = mcVersion == null ? "?" : mcVersion;
        if (era == 0) return "OptiForge-<ver>.jar (1.17+)";
        if (era >= 17) return "OptiForge-<ver>.jar (1.17+)";
        if (era == 7) return "OptiForge-<ver>-legacy17.jar (<=1.7.10)";
        if (era < 13) return "OptiForge-<ver>-legacy.jar (1.8.9-1.12.2)";
        return "OptiForge-<ver>-oldml.jar (1.13.2-1.16.5)";
    }

    /** Java major version Forge requires on that line, for the message text. */
    public static String expectedJava(String mcVersion) {
        int era = mcEra(mcVersion);
        if (era >= 26) return "25";
        if (era >= 21) return "21";
        if (era >= 17) return "17";
        return "8";
    }

    /**
     * Records a warning when the running artifact or the runtime Java does not belong to this line.
     * Never throws: a mismatch must cost a log line, not the launch.
     *
     * @param notes      diagnose buffer, may be null
     * @param artifact   "jdk17", "oldml", "legacy", "legacy17"
     * @param mcVersion  detected Minecraft version, may be null
     * @param javaTarget Java major the artifact was compiled for (8 or 17)
     * @return true when something looked wrong
     */
    public static boolean check(StringBuilder notes, String artifact, String mcVersion, int javaTarget) {
        int java = javaMajor();
        String expected = expectedArtifact(mcVersion);
        String expectedJava = expectedJava(mcVersion);
        boolean odd = false;
        StringBuilder message = new StringBuilder();
        if (java != 0 && javaTarget == 17 && java < 17) {
            odd = true;
            message.append("this ") .append(artifact).append(" artifact is Java 17 bytecode but the runtime is Java ")
                    .append(java).append("; use the artifact for this line: ").append(expected);
        } else if (java != 0 && javaTarget == 8 && java > 8) {
            // Compiled to class-file 52 it still loads, but the game/Forge side is not supported there.
            odd = true;
            message.append("this ").append(artifact).append(" artifact targets Java 8 and the runtime is Java ")
                    .append(java).append("; Forge for 1.13-1.16 is only supported on Java 8, so switch the")
                    .append(" instance's Java to 8 if anything looks wrong");
        }
        if (mcVersion != null && !mcVersion.isEmpty()) {
            String want = expected.startsWith("OptiForge-<ver>.jar") ? "jdk17"
                    : expected.contains("oldml") ? "oldml"
                    : expected.contains("legacy17") ? "legacy17" : "legacy";
            if (!want.equals(artifact)) {
                odd = true;
                if (message.length() > 0) message.append("; ");
                message.append("MC ").append(mcVersion).append(" (").append(expectedJava).append(") expects ")
                        .append(expected).append(" but this jar is the ").append(artifact).append(" artifact");
            }
        }
        if (!odd) return false;
        String line = "[OptiForge] WARNING: " + message + "\n";
        System.out.println(line.trim());
        if (notes != null) notes.append(line);
        return true;
    }
}
