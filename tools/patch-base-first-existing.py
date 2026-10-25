"""Pick the first existing base candidate by era instead of probing MD5s (which rejects legitimate Forge jars)."""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\OptifinePack.java")
text = path.read_text(encoding="utf-8")

start = text.index("            for (Path candidate : candidates) {")
end_marker = "                return candidate;\n            }\n"
end = text.index(end_marker, start) + len(end_marker)

new_block = """            // The candidate list is already ordered by era (see above), so the first existing jar is the right base.
            // Probing MD5s here was worse than useless: a Forge client jar never reproduces the payload's recorded
            // hashes (its classes carry the line's access transformers), so the probe rejected the very jar that
            // 1.17-1.21 need, and the pack was silently built from the vanilla jar instead (which then failed later
            // with IllegalAccessError inside Forge). The probe is kept only for the log note.
            for (Path candidate : candidates) {
                if (!Files.isRegularFile(candidate)) continue;
                String probe = firstMatch(patcher, probeList, candidate);
                notes.append("base jar: ").append(candidate.getFileName())
                        .append(" (probe ").append(probe).append(")\\n");
                return candidate;
            }
"""
text = text[:start] + new_block + text[end:]
path.write_text(text, encoding="utf-8")
print("patched")
