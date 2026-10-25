"""Replaces the md5-confirm base loop with a straight "first existing candidate by era" choice."""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\OptifinePack.java")
text = path.read_text(encoding="utf-8")

start = text.index("            for (Path candidate : candidates) {")
# the loop ends right before the catch of the prepare(...) try-with-resources
catch_anchor = "        } catch (IOException e) {"
end = text.index(catch_anchor, start)

new_block = """            // The candidate list is ordered by era above, so the first existing jar is the right base. Probing the
            // payload's MD5s here was counterproductive: a Forge client jar never reproduces them (its classes carry
            // the line's access transformers), so the probe rejected the very jar 1.17-1.21 need and the pack was
            // silently built from the vanilla jar instead - which surfaced later as IllegalAccessError inside Forge
            // code (measured on 1.21.8: RenderPipelines.GUI_TEXTURED_SNIPPET stayed private). The probe is kept for
            // the log note only.
            for (Path candidate : candidates) {
                if (!Files.isRegularFile(candidate)) continue;
                notes.append("base jar: ").append(candidate.getFileName())
                        .append(" (payload probe ").append(firstMatch(patcher, probe, candidate)).append(")\\n");
                return candidate;
            }
"""
text = text[:start] + new_block + text[end:]
path.write_text(text, encoding="utf-8")
print("patched")
