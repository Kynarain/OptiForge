"""Fixes mapping-table selection: prefix match then single-table fallback, and never on the unobfuscated lines.

Measured failure this fixes (1.21.8): diagnose said "mapping table: none available for 1.21.8", so the access
transformer grant `public net.minecraft.client.renderer.RenderPipelines f_379621_` could not be translated to the
runtime name GUI_TEXTURED_SNIPPET and ForgeRenderTypes died with IllegalAccessError. The bundle ships a 1.21 table;
index.txt lists two entries (1.17.1-srg-renumber and 1.21), so the old "only one bundled table" fallback never
triggered.
"""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\OptifinePack.java")
text = path.read_text(encoding="utf-8")

start = text.index("    public static Path pickMapping(String mcVersion, StringBuilder notes) {")
end = text.index("\n    }\n", start) + len("\n    }\n")

new = '''    public static Path pickMapping(String mcVersion, StringBuilder notes) {
        // Only real srg-to-official tables may be chosen; 1.17.1-srg-renumber is a different kind of file.
        java.util.List<String> available = new ArrayList<>();
        try (java.io.InputStream in = OptifinePack.class.getResourceAsStream("/optiforge/mappings/index.txt")) {
            if (in != null) {
                for (String line : new String(J8.readAll(in), StandardCharsets.UTF_8).split("\\\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.matches("^\\\\d+\\\\.\\\\d+(\\\\.\\\\d+)?$")) available.add(trimmed);
                }
            }
        } catch (IOException ignored) {
            // fall through with an empty list
        }
        // 26.x ships unobfuscated: its payload and its access transformers already use the runtime names, so
        // applying an older table there would rename the wrong things.
        if (mcVersion != null && mcVersion.startsWith("26.")) {
            notes.append("mapping table: not applicable (").append(mcVersion).append(" is unobfuscated)\\n");
            return null;
        }
        String chosen = null;
        if (!J8.isBlank(mcVersion) && available.contains(mcVersion)) {
            chosen = mcVersion;
        }
        if (chosen == null && !J8.isBlank(mcVersion)) {
            // A 1.21 table is what the runtime needs on 1.21.8: the access transformers still carry SRG member names
            // while the game itself uses the official ones. Longest prefix wins.
            for (String candidate : available) {
                if (mcVersion.startsWith(candidate)
                        && (chosen == null || candidate.length() > chosen.length())) {
                    chosen = candidate;
                }
            }
            if (chosen != null) {
                notes.append("mapping table: no exact table for ").append(mcVersion)
                        .append(", using the ").append(chosen).append(" table\\n");
            }
        }
        if (chosen == null && available.size() == 1) {
            chosen = available.get(0);
            notes.append("mapping table: no table for ").append(mcVersion.isEmpty() ? "<unknown>" : mcVersion)
                    .append(", using the only bundled one (").append(chosen).append(")\\n");
        }
        if (chosen == null) {
            notes.append("mapping table: none available for ")
                    .append(mcVersion.isEmpty() ? "<unknown>" : mcVersion).append('\\n');
            return null;
        }
        try (java.io.InputStream in = OptifinePack.class.getResourceAsStream("/optiforge/mappings/" + chosen + "-srg-to-official.tsv")) {
            if (in == null) return null;
            Path target = Files.createTempFile("optiforge-map-", ".tsv");
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            notes.append("mapping table: could not extract: ").append(e).append('\\n');
            return null;
        }
    }
'''
text = text[:start] + new + text[end:]
path.write_text(text, encoding="utf-8")
print("patched")
