"""Finishes the base-selection rewrite: keeps the original return path and the rejection notes."""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\OptifinePack.java")
text = path.read_text(encoding="utf-8")

start_anchor = '            if (best != null && bestScore >= 2) {\n'
start = text.index(start_anchor)
# the block currently ends with the notes.append(...) line, then "        } catch (IOException e) {"
catch_anchor = '        } catch (IOException e) {\n'
catch_at = text.index(catch_anchor, start)

notes_block = text[start:catch_at]

new_block = notes_block + """                return best;
            }
            if (best != null) {
                notes.append("  base candidate rejected: ").append(best.getFileName())
                        .append(" (score ").append(bestScore).append(")\\n");
            }
"""
text = text[:start] + new_block + text[catch_at:]

# drop now-unused helpers that referenced the old flow
for dead in ("    /** Returns the first probe class that patches to its recorded MD5 on {@code candidate}, for the log line. */\n",):
    text = text.replace(dead, "    /** Returns the first probe class that patches to its recorded MD5 on {@code candidate}. */\n")

path.write_text(text, encoding="utf-8")
print("finished")
