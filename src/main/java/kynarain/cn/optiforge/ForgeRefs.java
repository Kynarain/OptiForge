package kynarain.cn.optiforge;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which {@code net/minecraftforge} names an OptiFine build actually references.
 *
 * <p>This is the measurement the first design guess got wrong: a build carries no
 * {@code patch/srg/net/minecraftforge/**} targets, so "Forge compatibility" is not expressed in the
 * patch payload. What a build does carry is compiled code in {@code srg/} that names Forge types -
 * as {@code Class} constants (hard links: a missing class is a {@code NoClassDefFoundError} on first
 * execution) or only as {@code Utf8} strings (usually {@code Class.forName} probes, which can fail
 * harmlessly). Separating those two is exactly what decides how a build breaks on a newer Forge.
 *
 * <p>Implemented by reading the class-file constant pool directly instead of loading classes: no
 * Forge or Minecraft types are needed, nothing is initialised, and it works offline on a jar whose
 * classes could not even be resolved here.
 */
public final class ForgeRefs {

    public static final String PREFIX = "net/minecraftforge/";
    public static final String OPENTARGET = "net/minecraftforge";

    /** One reference from an OptiFine class to a Forge name. */
    public static final class Ref {
        public final String ownClass;
        public final String target;
        /** True when the name appears as a {@code Class} constant: a hard link, not a probe. */
        public final boolean classConstant;

        Ref(String ownClass, String target, boolean classConstant) {
            this.ownClass = ownClass;
            this.target = target;
            this.classConstant = classConstant;
        }

        public String describe() {
            return ownClass + " -> " + target + (classConstant ? "  [CLASS]" : "  [utf8]");
        }
    }

    public final List<Ref> refs;
    /** Distinct Forge names, hard links first. */
    public final Set<String> hardTargets;
    public final Set<String> probeTargets;
    public final int classesWithRefs;
    public final int classesScanned;
    public final String problem;

    private ForgeRefs(List<Ref> refs, Set<String> hardTargets, Set<String> probeTargets,
                      int classesWithRefs, int classesScanned, String problem) {
        this.refs = Collections.unmodifiableList(refs);
        this.hardTargets = Collections.unmodifiableSet(hardTargets);
        this.probeTargets = Collections.unmodifiableSet(probeTargets);
        this.classesWithRefs = classesWithRefs;
        this.classesScanned = classesScanned;
        this.problem = problem;
    }

    public static ForgeRefs scan(OptifineJar jar) {
        List<Ref> refs = new ArrayList<>();
        Set<String> hard = new LinkedHashSet<>();
        Set<String> probes = new LinkedHashSet<>();
        int scanned = 0;
        int withRefs = 0;
        String problem = null;

        try (ZipFile zip = new ZipFile(jar.path.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                // Only the classes OptiFine's ModLauncher transformer would provide. The notch/ tree
                // is the installer/LaunchWrapper form and is not read on this path.
                if (!name.startsWith("srg/") || !name.endsWith(".class")) continue;
                scanned++;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(e)) {
                    bytes = in.readAllBytes();
                } catch (IOException ex) {
                    continue;
                }
                Set<String> seenTargets = new LinkedHashSet<>();
                boolean sawHard = false;
                for (Ref r : scanClass(name, bytes)) {
                    if (seenTargets.add(r.target)) {
                        if (r.classConstant) {
                            hard.add(r.target);
                            sawHard = true;
                        } else {
                            probes.add(r.target);
                        }
                    }
                    refs.add(r);
                }
                if (!seenTargets.isEmpty()) withRefs++;
                // If any target was a hard link, keep it out of the "probe only" set.
                if (sawHard) probes.removeAll(hard);
            }
        } catch (IOException e) {
            problem = e.toString();
        }

        return new ForgeRefs(refs, hard, probes, withRefs, scanned, problem);
    }

    private static List<Ref> scanClass(String ownClass, byte[] bytes) {
        List<Ref> out = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0xCAFEBABE) return out;
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();
            String[] utf8 = new String[cpCount];
            int[] classRef = new int[cpCount]; // Class entries -> utf8 index
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> {
                        int len = in.readUnsignedShort();
                        byte[] raw = in.readNBytes(len);
                        utf8[i] = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                    }
                    case 7 -> classRef[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> in.skipBytes(2);
                    case 15 -> in.skipBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 5, 6 -> {
                        in.skipBytes(8);
                        i++; // long/double occupy two slots
                    }
                    default -> {
                        return out; // unknown tag: give up on this class rather than mis-parse
                    }
                }
            }
            Map<String, Boolean> targetIsClass = new TreeMap<>();
            for (int i = 1; i < cpCount; i++) {
                String s = utf8[i];
                if (s == null) continue;
                if (s.startsWith(PREFIX) || s.equals(OPENTARGET)) {
                    targetIsClass.merge(s, false, (a, b) -> a);
                }
            }
            for (int i = 1; i < cpCount; i++) {
                int idx = classRef[i];
                if (idx <= 0 || idx >= cpCount) continue;
                String s = utf8[idx];
                if (s == null) continue;
                if (s.startsWith(PREFIX) || s.equals(OPENTARGET)) {
                    targetIsClass.put(s, true);
                }
            }
            for (Map.Entry<String, Boolean> e : targetIsClass.entrySet()) {
                out.add(new Ref(ownClass, e.getKey(), e.getValue()));
            }
        } catch (IOException ignored) {
            // a malformed class only loses its own references
        }
        return out;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("forge refs  : ").append(classesWithRefs).append(" of ").append(classesScanned)
                .append(" srg/ classes name net/minecraftforge");
        if (problem != null) sb.append(" (").append(problem).append(')');
        sb.append('\n');
        sb.append("              hard Class constants: ").append(hardTargets.size())
                .append(", string probes: ").append(probeTargets.size()).append('\n');
        Map<String, Integer> byTarget = new LinkedHashMap<>();
        for (Ref r : refs) byTarget.merge(r.target, 1, Integer::sum);
        int shown = 0;
        for (Map.Entry<String, Integer> e : byTarget.entrySet()) {
            if (shown++ >= 12) {
                sb.append("              ... ").append(byTarget.size() - 12).append(" more target(s)\n");
                break;
            }
            sb.append("              ").append(e.getKey()).append(" x").append(e.getValue())
                    .append(hardTargets.contains(e.getKey()) ? "  [CLASS]" : "").append('\n');
        }
        return sb.toString();
    }
}
