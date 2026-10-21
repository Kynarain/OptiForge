package kynarain.cn.optiforge;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The versions and paths of the run OptiForge is attached to.
 *
 * <p>Forge passes its own coordinates on the ModLauncher command line, and those appear verbatim in
 * the {@code sun.java.command} system property. From a real Forge 1.20.1 launch:
 *
 * <pre>
 * ModLauncher running: args [--launchTarget, forgeclient, --fml.forgeVersion, 47.4.23,
 *                            --fml.mcVersion, 1.20.1, --fml.fmlVersion, 47.2.2, ...]
 * </pre>
 *
 * <p>Parsing that property needs no Forge classes, so the same code works on every line and can
 * also be driven offline by {@link Diagnose#main}.
 */
public final class RuntimeInfo {

    public final String mcVersion;
    public final String forgeVersion;
    public final String fmlVersion;
    public final Path gameDir;
    /** How each value was obtained, so a report never claims more than it knows. */
    public final String source;
    /** Set when the service could not read the launch arguments at all. */
    public final String problem;

    private RuntimeInfo(String mcVersion, String forgeVersion, String fmlVersion, Path gameDir,
                        String source, String problem) {
        this.mcVersion = mcVersion;
        this.forgeVersion = forgeVersion;
        this.fmlVersion = fmlVersion;
        this.gameDir = gameDir;
        this.source = source;
        this.problem = problem;
    }

    public static RuntimeInfo offline(String mcVersion, String forgeVersion, Path gameDir) {
        return new RuntimeInfo(nullToEmpty(mcVersion), nullToEmpty(forgeVersion), "",
                gameDir, "offline (explicit arguments)", null);
    }

    /** Reads {@code sun.java.command}; {@code fallbackGameDir} covers the case where nothing parses. */
    public static RuntimeInfo fromLaunchArguments(Path fallbackGameDir) {
        Map<String, String> args = parseLaunchArguments(System.getProperty("sun.java.command"));
        String mc = args.getOrDefault("fml.mcVersion", "");
        String forge = args.getOrDefault("fml.forgeVersion", "");
        String fml = args.getOrDefault("fml.fmlVersion", "");

        // Forge 1.20.6+ no longer passes --fml.* on the command line (ForgeBootstrap reads the versions
        // out of the fmlloader module), but the launcher's --version argument still carries the profile
        // id, e.g. "1.21-forge-51.0.33" or "neoforge-21.4.149". Parsed as a fallback, never over an
        // explicit --fml value.
        String versionArg = args.getOrDefault("version", "");
        if (mc.isEmpty() || forge.isEmpty()) {
            Matcher m = Pattern.compile("^((?:\\d+\\.)*\\d+?)-forge-(\\d+(?:\\.\\d+)*)$").matcher(versionArg);
            if (m.matches()) {
                if (mc.isEmpty()) mc = m.group(1);
                if (forge.isEmpty()) forge = m.group(2);
            } else {
                Matcher neo = Pattern.compile("^neoforge-(\\d+(?:\\.\\d+)*)$").matcher(versionArg);
                if (neo.matches()) {
                    String neoforge = neo.group(1);
                    if (forge.isEmpty()) {
                        // NeoForge's first component is the Minecraft minor, e.g. 21.4.149 -> 1.21.4
                        String[] parts = neoforge.split("\\.");
                        if (parts.length >= 2) {
                            mc = (mc.isEmpty() ? "1." + parts[0] + "." + parts[1] : mc);
                            forge = neoforge;
                        }
                    }
                }
            }
        }
        // Explicit overrides win, for rigs whose profile id does not follow any convention.
        mc = orDefault(System.getProperty("optiforge.mc"), mc);
        forge = orDefault(System.getProperty("optiforge.forge"), forge);

        Path gameDir = fallbackGameDir;
        String argGameDir = args.get("gameDir");
        if (argGameDir != null && !J8.isBlank(argGameDir)) {
            try {
                gameDir = Paths.get(argGameDir);
            } catch (RuntimeException ignored) {
                // keep the fallback
            }
        }
        String problem = null;
        if (mc.isEmpty() || forge.isEmpty()) {
            problem = "could not read the Minecraft/Forge versions from sun.java.command"
                    + (versionArg.isEmpty() ? "" : " (--version was \"" + versionArg + "\")");
        }
        return new RuntimeInfo(mc, forge, fml, gameDir,
                "sun.java.command (" + args.size() + " args)", problem);
    }

    private static String orDefault(String preferred, String fallback) {
        return preferred == null || J8.isBlank(preferred) ? fallback : preferred.trim();
    }

    public boolean known() {
        return !mcVersion.isEmpty() && !forgeVersion.isEmpty();
    }

    /** Tokenises a command line into {@code --key value} / {@code --key=value} pairs (keys without the dashes). */
    static Map<String, String> parseLaunchArguments(String command) {
        Map<String, String> out = new LinkedHashMap<>();
        if (command == null) return out;
        String[] tokens = command.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (!token.startsWith("--")) continue;
            String body = token.substring(2);
            int eq = body.indexOf('=');
            if (eq >= 0) {
                out.put(body.substring(0, eq), body.substring(eq + 1));
            } else if (i + 1 < tokens.length) {
                out.put(body, tokens[i + 1]);
                i++;
            }
        }
        return out;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Minecraft       : ").append(mcVersion.isEmpty() ? "<unknown>" : mcVersion).append('\n');
        sb.append("Forge           : ").append(forgeVersion.isEmpty() ? "<unknown>" : forgeVersion).append('\n');
        sb.append("FML             : ").append(fmlVersion.isEmpty() ? "<unknown>" : fmlVersion).append('\n');
        sb.append("Game directory  : ").append(gameDir == null ? "<unknown>" : gameDir).append('\n');
        sb.append("Resolved from   : ").append(source).append('\n');
        if (problem != null) sb.append("Problem         : ").append(problem).append('\n');
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /** Lower-case convenience used by report writers. */
    public String shortId() {
        String mc = mcVersion.isEmpty() ? "unknown" : mcVersion;
        String forge = forgeVersion.isEmpty() ? "unknown" : forgeVersion;
        return ("mc" + mc + "-forge" + forge).toLowerCase(Locale.ROOT);
    }
}
