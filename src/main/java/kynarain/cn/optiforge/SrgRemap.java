package kynarain.cn.optiforge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Renames SRG member names to their official names inside a class file.
 *
 * <p>OptiFine payloads up to the 1.21 line are compiled against SRG member names ({@code m_127521_}),
 * while Forge 1.20.6+ runs on official names ({@code forThrowable}). Those SRG ids are globally
 * unique, so the rename needs no owner context: one {@code srg -> official} table is enough, which is
 * what {@code tools/build-srg-map.ps1} produces.
 *
 * <p>Implemented as a constant-pool rewrite rather than an ASM tree pass, on purpose:
 * <ul>
 *   <li>it catches every reference, including ones a tree pass might not enumerate (method handles,
 *       invokedynamic bootstrap names, constants), because it rewrites the {@code CONSTANT_Utf8}
 *       entries themselves;</li>
 *   <li>nothing else in the class file changes - every index, descriptor and offset stays as is, so
 *       the risk of a mis-parse corrupting a class is limited to the pool entry lengths.</li>
 * </ul>
 */
public final class SrgRemap {

    private static final Pattern SRG = Pattern.compile("[fm]_\\d+_");

    private final Map<String, String> srgToOfficial;

    public SrgRemap(Map<String, String> srgToOfficial) {
        this.srgToOfficial = srgToOfficial;
    }

    /** Loads a two-column TSV: {@code srgName<TAB>officialName}. */
    public static SrgRemap load(Path tsv) throws IOException {
        Map<String, String> map = new HashMap<>(1 << 17);
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            map.put(line.substring(0, tab), line.substring(tab + 1));
        }
        return new SrgRemap(map);
    }

    /** The official name for an SRG id, or null when the table does not carry it. */
    public String officialName(String srg) {
        return srg == null ? null : srgToOfficial.get(srg);
    }

    public int size() {
        return srgToOfficial.size();
    }

    /** Result of one remap: the bytes plus how much was touched, so callers can report honestly. */
    public static final class Result {
        public final byte[] bytes;
        public final int renamed;
        public final int unmapped;

        Result(byte[] bytes, int renamed, int unmapped) {
            this.bytes = bytes;
            this.renamed = renamed;
            this.unmapped = unmapped;
        }
    }

    public Result remap(byte[] classBytes) {
        return remap(classBytes, java.util.Set.of());
    }

    /**
     * Renames SRG tokens except the ones in {@code blocked}.
     *
     * <p>Blocking exists because a payload can contain <b>both</b> the original member under its SRG
     * name and an OptiFine-added wrapper under the official name - {@code ModelPart} carries
     * {@code m_171324_(String)} next to an added {@code getChild(String)}. Renaming the SRG one would
     * turn a legal class into a {@code ClassFormatError: Duplicate method name "getChild"}. Leaving
     * the SRG token alone keeps both members and keeps every call site valid: OptiFine's wrapper calls
     * the SRG member it still has, and game code calls the official wrapper. Callers elsewhere are
     * blocked too (the caller cannot know which class declared what), and renaming them to the wrapper
     * name would be equivalent anyway, since the wrapper delegates to the original.
     */
    public Result remap(byte[] classBytes, java.util.Set<String> blocked) {
        // Constant pool layout: u4 magic, u2 minor, u2 major, u2 count, then entries.
        if (classBytes.length < 10) return new Result(classBytes, 0, 0);
        if (readInt(classBytes, 0) != 0xCAFEBABE) return new Result(classBytes, 0, 0);

        int pos = 8;
        int cpCount = readU2(classBytes, pos);
        pos += 2;
        int poolStart = pos;

        // Remember each Utf8 entry's byte range and its replacement, if any.
        List<int[]> ranges = new java.util.ArrayList<>();
        List<byte[]> replacements = new java.util.ArrayList<>();
        List<Integer> utf8At = new java.util.ArrayList<>(); // parallel to ranges: cp index
        int renamed = 0;
        int unmapped = 0;

        for (int i = 1; i < cpCount; i++) {
            int tag = classBytes[pos] & 0xFF;
            int entryStart = pos;
            pos += 1;
            switch (tag) {
                case 1 -> {
                    int len = readU2(classBytes, pos);
                    pos += 2;
                    String value = new String(classBytes, pos, len, StandardCharsets.UTF_8);
                    pos += len;
                    if (SRG.matcher(value).matches()) {
                        String official = srgToOfficial.get(value);
                        if (official != null) {
                            ranges.add(new int[]{entryStart, pos});
                            replacements.add(utf8Entry(official));
                            utf8At.add(i);
                            renamed++;
                        } else {
                            unmapped++;
                        }
                    }
                }
                case 7, 8, 16, 19, 20 -> pos += 2;
                case 15 -> pos += 3;
                case 3, 4, 9, 10, 11, 12, 17, 18 -> pos += 4;
                case 5, 6 -> {
                    pos += 8;
                    i++;
                }
                default -> {
                    // Unknown tag: leave the class untouched rather than emit a corrupt one.
                    return new Result(classBytes, 0, 0);
                }
            }
        }
        if (renamed == 0) return new Result(classBytes, 0, unmapped);
        return new Result(rebuild(classBytes, ranges, replacements, blocked, utf8At), renamed, unmapped);
    }

    private byte[] rebuild(byte[] classBytes, List<int[]> ranges, List<byte[]> replacements,
                           java.util.Set<String> blocked, List<Integer> utf8At) {
        if (blocked.isEmpty()) return splice(classBytes, ranges, replacements);
        ByteArrayOutputStream out = new ByteArrayOutputStream(classBytes.length + 64);
        int copied = 0;
        for (int i = 0; i < ranges.size(); i++) {
            int start = ranges.get(i)[0];
            int end = ranges.get(i)[1];
            // The SRG token of this entry, to test the block list.
            out.write(classBytes, copied, start - copied);
            String srg = new String(classBytes, start + 3, end - start - 3, StandardCharsets.UTF_8);
            byte[] entry = blocked.contains(srg)
                    ? java.util.Arrays.copyOfRange(classBytes, start, end)
                    : replacements.get(i);
            out.write(entry, 0, entry.length);
            copied = end;
        }
        out.write(classBytes, copied, classBytes.length - copied);
        return out.toByteArray();
    }

    private static byte[] splice(byte[] classBytes, List<int[]> ranges, List<byte[]> replacements) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(classBytes.length + 64);
        int copied = 0;
        for (int i = 0; i < ranges.size(); i++) {
            int start = ranges.get(i)[0];
            int end = ranges.get(i)[1];
            out.write(classBytes, copied, start - copied);
            byte[] entry = replacements.get(i);
            out.write(entry, 0, entry.length);
            copied = end;
        }
        out.write(classBytes, copied, classBytes.length - copied);
        return out.toByteArray();
    }

    /**
     * Which SRG tokens in this class would collide with a member OptiFine already declares under the
     * official name - i.e. the tokens that must NOT be renamed.
     */
    public java.util.Set<String> blockedTokens(byte[] classBytes) {
        java.util.Set<String> blocked = new java.util.HashSet<>();
        try {
            Cursor cursor = new Cursor(classBytes);
            if (cursor.takeInt() != 0xCAFEBABE) return blocked;
            cursor.skip(4); // minor + major
            int cpCount = cursor.takeU2();
            Map<Integer, String> utf8 = new HashMap<>();
            for (int i = 1; i < cpCount; i++) {
                int tag = cursor.takeU1();
                switch (tag) {
                    case 1 -> utf8.put(i, cursor.takeUtf8());
                    case 7, 8, 16, 19, 20 -> cursor.skip(2);
                    case 15 -> cursor.skip(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> cursor.skip(4);
                    case 5, 6 -> {
                        cursor.skip(8);
                        i++;
                    }
                    default -> {
                        return blocked;
                    }
                }
            }
            cursor.skip(6); // access, this, super
            int interfaces = cursor.takeU2();
            cursor.skip(2 * interfaces);
            for (int section = 0; section < 2; section++) { // fields, methods
                int count = cursor.takeU2();
                java.util.Set<String> declared = new java.util.HashSet<>();
                java.util.Map<String, Integer> names = new HashMap<>(); // name -> cp index
                java.util.List<int[]> members = new java.util.ArrayList<>();
                for (int i = 0; i < count; i++) {
                    cursor.skip(2); // access
                    int nameIndex = cursor.takeU2();
                    int descIndex = cursor.takeU2();
                    members.add(new int[]{nameIndex, descIndex});
                    int attributes = cursor.takeU2();
                    for (int a = 0; a < attributes; a++) {
                        cursor.skip(2); // attribute name
                        int length = cursor.takeInt();
                        cursor.skip(length);
                    }
                }
                java.util.Set<String> officialNames = new java.util.HashSet<>();
                java.util.Set<String> srgNames = new java.util.HashSet<>();
                for (int[] member : members) {
                    String name = utf8.get(member[0]);
                    String desc = utf8.get(member[1]);
                    if (name == null) continue;
                    String key = name + desc;
                    declared.add(key);
                    if (SRG.matcher(name).matches()) srgNames.add(name);
                    else officialNames.add(key);
                }
                for (String srg : srgNames) {
                    String official = srgToOfficial.get(srg);
                    if (official == null) continue;
                    for (int[] member : members) {
                        String name = utf8.get(member[0]);
                        if (!srg.equals(name)) continue;
                        String desc = utf8.get(member[1]);
                        if (officialNames.contains(official + desc)) blocked.add(srg);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // A class this parser cannot follow simply blocks nothing.
        }
        return blocked;
    }

    /** Minimal byte cursor for the class-file structures used above. */
    private static final class Cursor {
        final byte[] data;
        int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        int takeU1() {
            return data[pos++] & 0xFF;
        }

        int takeU2() {
            int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        int takeInt() {
            int v = readInt(data, pos);
            pos += 4;
            return v;
        }

        String takeUtf8() {
            int len = takeU2();
            String value = new String(data, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return value;
        }

        void skip(int n) {
            pos += n;
        }
    }

    private static byte[] utf8Entry(String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        byte[] entry = new byte[3 + raw.length];
        entry[0] = 1;
        entry[1] = (byte) (raw.length >> 8);
        entry[2] = (byte) raw.length;
        System.arraycopy(raw, 0, entry, 3, raw.length);
        return entry;
    }

    private static int readU2(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }
}
