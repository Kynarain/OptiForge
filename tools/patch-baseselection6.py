"""Restores the historical base choice (first candidate, forge client jar preferred) and documents the override."""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\OptifinePack.java")
text = path.read_text(encoding="utf-8")

start = text.index("            // The Forge client jar is preferred because it already carries")
end_marker = "                return best;\n            }\n"
end = text.index(end_marker, start) + len(end_marker)

new_block = """            // Candidate order decides: the Forge client jar comes first so the running line's access transformers
            // survive into the served classes (measured on 1.21.8: a vanilla-based replacement left
            // RenderPipelines.GUI_TEXTURED_SNIPPET private and ForgeRenderTypes died with IllegalAccessError).
            // OptiFine 26.2 K2_pre5 is the exception - the payload cannot rebuild the binpatched classes there and
            // the launch dies in prepare() with "Bad tag (99)" - so 26.x needs -Doptiforge.vanilla=<path to
            // versions/<mc>/<mc>.jar>. Automating that choice is tracked as the next step; see docs/STATE.md.
            for (Path candidate : candidates) {
                String confirmed = firstMatch(patcher, probe, candidate);
                notes.append("base jar: ").append(candidate.getFileName())
                        .append(" (probe ").append(confirmed)
                        .append(", mode ").append(patcher.mode()).append(")\\n");
                return candidate;
            }
"""
text = text[:start] + new_block + text[end:]

# drop the readability helper, no longer used
if "    private static boolean patchesReadable(" in text:
    h_start = text.index("    /**\n     * True when most sampled payload patches on ")
    h_end = text.index("\n    }\n", h_start) + len("\n    }\n")
    text = text[:h_start] + text[h_end:]

path.write_text(text, encoding="utf-8")
print("restored")
