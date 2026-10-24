"""Replaces the scoring heuristic with a hard validity check that still prefers the Forge client jar."""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\OptifinePack.java")
text = path.read_text(encoding="utf-8")

old_anchor = "            // Score every candidate and keep the best one."
start = text.index(old_anchor)
end_marker = "                return best;\n            }\n"
end = text.index(end_marker, start) + len(end_marker)

new_block = """            // Take the first candidate that actually survives patching, in preference order (Forge client jar
            // first, so classes the running line's access transformers already widened stay widened). A candidate
            // is rejected as soon as a sampled patch yields bytes that are not a readable class file - on 26.2 the
            // binpatched client jar fails exactly there ("Bad tag (99)"), which is what made OptiFine unusable on
            // the 26.x previews until the vanilla jar was picked instead.
            Path best = null;
            String bestConfirmed = null;
            for (Path candidate : candidates) {
                String confirmed = confirmBase(patcher, probe, candidate);
                if (confirmed != null) {
                    best = candidate;
                    bestConfirmed = confirmed;
                    break;
                }
                if (J8.isBlank(override) || !sameFile(candidate, Paths.get(override))) {
                    notes.append("  base candidate rejected: ").append(candidate.getFileName()).append('\\n');
                }
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

# Rewrite confirmBase: hard validity on every sample, plus at least one MD5 match.
cb_start = text.index("    private static String confirmBase(")
cb_end = text.index("\n    }\n", cb_start) + len("\n    }\n")
new_confirm = """    private static String confirmBase(OptifinePatcher patcher, List<String> probe, Path candidate) {
        String firstMatch = null;
        int matches = 0;
        int tried = 0;
        for (String logical : probe) {
            if (tried >= 8) break;
            tried++;
            byte[] patched;
            try {
                patched = patcher.apply(logical, candidate);
            } catch (Exception unreadable) {
                // the patcher itself refused this input: not the base jar
                return null;
            }
            if (!isClassFile(patched)) {
                return null;
            }
            try {
                new org.objectweb.asm.ClassReader(patched);
            } catch (RuntimeException unreadable) {
                return null;
            }
            try {
                if (!OptifinePatcher.sha(patched, "MD5").equals(patcher.expectedMd5(logical))) continue;
            } catch (Exception ignored) {
                continue;
            }
            matches++;
            if (firstMatch == null) firstMatch = logical;
        }
        return matches >= 1 ? firstMatch : null;
    }
"""
text = text[:cb_start] + new_confirm + text[cb_end:]

path.write_text(text, encoding="utf-8")
print("rewritten")
