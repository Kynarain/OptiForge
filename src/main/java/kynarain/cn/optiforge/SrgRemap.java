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
        return new Result(out.toByteArray(), renamed, unmapped);
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
