package kynarain.cn.optiforge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The "which OptiFine builds are Forge-compatible" table, as shipped in the jar.
 *
 * <p>Source of truth is OptiFine's own downloads page (the {@code Forge} column) as mirrored by
 * BMCLAPI's {@code /optifine/versionlist}; {@code data/optifine-forge-matrix.csv} is the checked-in
 * copy and {@code tools/build-matrix.ps1} regenerates it. A row's {@code forge} cell is one of:
 * <ul>
 *   <li>{@code Forge 47.2.18} — OptiFine states compatibility with that Forge build;</li>
 *   <li>{@code Forge #2847} — the pre-1.13 numbering (1.12.2 and older);</li>
 *   <li>{@code Forge N/A} — OptiFine states it is <b>not</b> compatible with Forge;</li>
 *   <li>empty — the mirror has no value (build never listed); <b>not</b> evidence of anything.</li>
 * </ul>
 */
public final class ForgeMatrix {

    public static final String RESOURCE = "/optiforge/optifine-forge-matrix.csv";

    /** One row of the table. */
    public static final class Row {
        public final String mcVersion;
        public final String fileName;
        public final String type;
        public final String patch;
        public final String forgeRaw;
        public final boolean forgeOk;
        public final String forgeVersion;

        Row(String mcVersion, String fileName, String type, String patch,
            String forgeRaw, boolean forgeOk, String forgeVersion) {
            this.mcVersion = mcVersion;
            this.fileName = fileName;
            this.type = type;
            this.patch = patch;
            this.forgeRaw = forgeRaw;
            this.forgeOk = forgeOk;
            this.forgeVersion = forgeVersion;
        }

        public boolean known() {
            return !forgeRaw.isEmpty();
        }

        public String describe() {
            if (!known()) return "no mirror value (build never listed on optifine.net)";
            return forgeRaw + (forgeOk ? " (Forge-compatible)" : " (NOT Forge-compatible)");
        }
    }

    private final Map<String, Row> byFileName = new LinkedHashMap<>();
    /** Secondary index: the file name reduced to {@code optifine_<version>_<build>.jar}. */
    private final Map<String, Row> byCanonicalName = new LinkedHashMap<>();

    public static ForgeMatrix empty() {
        return new ForgeMatrix();
    }

    /**
     * Reduces a file name to the part starting at {@code OptiFine_}, so that
     * {@code optifine-OptiFine_1.20.1_HD_U_I6.jar} (what a loader's mods/ folder often holds)
     * resolves to the same row as {@code OptiFine_1.20.1_HD_U_I6.jar} (what optifine.net ships).
     * Returns the input, lower-cased, when the marker is absent.
     */
    public static String canonicalName(String fileName) {
        if (fileName == null) return "";
        String lower = fileName.toLowerCase(Locale.ROOT);
        int at = lower.lastIndexOf("optifine_");
        String tail = at >= 0 ? lower.substring(at) : lower;
        if (tail.endsWith(".jar")) tail = tail.substring(0, tail.length() - 4);
        return tail;
    }

    /** Loads the bundled copy; falls back to an empty table (never throws) so a bad resource cannot brick a launch. */
    public static ForgeMatrix loadBundled() {
        ForgeMatrix matrix = new ForgeMatrix();
        try (InputStream in = ForgeMatrix.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return matrix;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            matrix.parse(reader);
        } catch (IOException ignored) {
            // keep the empty table: the diagnostic says "not in table" and nothing else changes
        }
        return matrix;
    }

    /** Loads a CSV from disk (an instance may carry a newer table than the jar). */
    public static ForgeMatrix loadFile(Path csv) throws IOException {
        ForgeMatrix matrix = new ForgeMatrix();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            matrix.parse(reader);
        }
        return matrix;
    }

    public Row lookup(String fileName) {
        if (fileName == null) return null;
        Row row = byFileName.get(fileName.toLowerCase(Locale.ROOT));
        if (row != null) return row;
        return byCanonicalName.get(canonicalName(fileName));
    }

    public int size() {
        return byFileName.size();
    }

    public List<Row> rows() {
        return new ArrayList<>(byFileName.values());
    }

    private void parse(BufferedReader reader) throws IOException {
        String line;
        boolean header = true;
        while ((line = reader.readLine()) != null) {
            if (J8.isBlank(line) || line.startsWith("#")) continue;
            if (header) {
                header = false;
                if (line.toLowerCase(Locale.ROOT).startsWith("mcversion")) continue;
            }
            List<String> cells = splitCsv(line);
            if (cells.size() < 7) continue;
            String mc = cells.get(0).trim();
            String file = cells.get(1).trim();
            String type = cells.get(2).trim();
            String patch = cells.get(3).trim();
            String forge = cells.get(4).trim();
            String ok = cells.get(5).trim();
            String version = cells.get(6).trim();
            byFileName.put(file.toLowerCase(Locale.ROOT),
                    new Row(mc, file, type, patch, forge, "true".equalsIgnoreCase(ok), version));
            byCanonicalName.putIfAbsent(canonicalName(file),
                    new Row(mc, file, type, patch, forge, "true".equalsIgnoreCase(ok), version));
        }
    }

    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        out.add(cell.toString());
        return out;
    }

    /**
     * Compares two Forge version strings numerically ({@code 61.0.8} vs {@code 61.2.4}). Returns
     * 0 when they cannot be compared, so a caller never treats an unparsable value as "newer".
     */
    public static int compareVersions(String a, String b) {
        int[] pa = parse(a);
        int[] pb = parse(b);
        if (pa == null || pb == null) return 0;
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int va = i < pa.length ? pa[i] : 0;
            int vb = i < pb.length ? pb[i] : 0;
            if (va != vb) return va < vb ? -1 : 1;
        }
        return 0;
    }

    private static int[] parse(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isEmpty()) return null;
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9].*$", "");
            if (digits.isEmpty()) return null;
            try {
                out[i] = Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }
}
