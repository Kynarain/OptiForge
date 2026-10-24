"""Final base selection: first candidate whose patched samples are all readable classes, preferring Forge client."""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\OptifinePack.java")
text = path.read_text(encoding="utf-8")

start = text.index("            // Take the first candidate that actually survives patching")
end_marker = "                return best;\n            }\n"
end = text.index(end_marker, start) + len(end_marker)

new_block = """            // The Forge client jar is preferred because it already carries the running line's access
            // transformers (measured on 1.21.8: a vanilla-based base left RenderPipelines.GUI_TEXTURED_SNIPPET
            // private and ForgeRenderTypes died with IllegalAccessError). It is, however, only usable while the
            // payload still produces readable classes from it; on 26.2 it does not ("Bad tag (99)"), so that
            // candidate is skipped and the vanilla client jar - the jar the payload was actually built against -
            // is used instead.
            Path best = null;
            String bestConfirmed = null;
            for (Path candidate : candidates) {
                if (patchesReadable(patcher, probe, candidate)) {
                    best = candidate;
                    bestConfirmed = firstMatch(patcher, probe, candidate);
                    break;
                }
                notes.append("  base candidate rejected: ").append(candidate.getFileName())
                        .append(" (payload output is not a readable class)\\n");
            }
            if (best != null) {
                notes.append("base jar: ").append(best.getFileName())
                        .append(" (confirmed by ")
                        .append(patcher.mode() == OptifinePatcher.Mode.NOTCH ? "patch/" : "patch/srg/")
                        .append(bestConfirmed).append(".class MD5)\\n");
                return best;
            }
"""
text = text[:start] + new_block + text[end:]

cb_start = text.index("    private static String confirmBase(")
cb_end = text.index("\n    }\n", cb_start) + len("\n    }\n")
new_cb = """    /** True when every sampled payload patch on {@code candidate} yields a class file ASM can read. */
    private static boolean patchesReadable(OptifinePatcher patcher, List<String> probe, Path candidate) {
        int tried = 0;
        for (String logical : probe) {
            if (tried >= 8) break;
            tried++;
            byte[] patched;
            try {
                patched = patcher.apply(logical, candidate);
            } catch (Exception refused) {
                return false;
            }
            if (!isClassFile(patched)) return false;
            try {
                new org.objectweb.asm.ClassReader(patched);
            } catch (RuntimeException unreadable) {
                return false;
            }
        }
        return true;
    }

    /** Returns the first sampled class whose patch output carries the payload's recorded MD5, or "?". */
    private static String firstMatch(OptifinePatcher patcher, List<String> probe, Path candidate) {
        int tried = 0;
        for (String logical : probe) {
            if (tried >= 12) break;
            tried++;
            try {
                byte[] patched = patcher.apply(logical, candidate);
                if (OptifinePatcher.sha(patched, "MD5").equals(patcher.expectedMd5(logical))) return logical;
            } catch (Exception ignored) {
                // try the next entry
            }
        }
        return "?";
    }
"""
text = text[:cb_start] + new_cb + text[cb_end:]

path.write_text(text, encoding="utf-8")
print("rewritten")
