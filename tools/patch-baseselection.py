"""Rewrites the base-jar candidate loop in OptifinePack.java to score candidates instead of taking the first hit."""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\OptifinePack.java")
text = path.read_text(encoding="utf-8")

old_loop_start = "            for (Path candidate : candidates) {"
old_loop_end = "            }\n"
start = text.index(old_loop_start)
# find the closing of this for-loop: the line after "                }\n            }\n"
end = text.index("                }\n            }\n", start) + len("                }\n            }\n")

new_loop = """            // Score every candidate and keep the best one. Taking the first hit was wrong on 26.2: the Forge
            // binpatched client jar matches MD5 for the classes the payload only copies (their output is the
            // untouched vanilla class), while the jar actually patched - the vanilla client jar - matches for the
            // classes OptiFine does rewrite. Highest score therefore means "the payload was built for this jar".
            Path best = null;
            String bestConfirmed = null;
            int bestScore = 0;
            for (Path candidate : candidates) {
                int score = scoreBase(patcher, probe, candidate);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                    bestConfirmed = firstMatch(patcher, probe, candidate);
                }
            }
            if (best != null && bestScore >= 2) {
                notes.append("base jar: ").append(best.getFileName())
                        .append(" (score ").append(bestScore).append("/").append(Math.min(probe.size(), 12))
                        .append(", e.g. ").append(patcher.mode() == OptifinePatcher.Mode.NOTCH ? "patch/" : "patch/srg/")
                        .append(bestConfirmed).append(".class MD5)\\n");
"""

text = text[:start] + new_loop + text[end:]

# The old loop body appended notes and assigned baseJar; make sure the outer code still sets it.
helper = """
    /**
     * Counts how many probe classes patch to their recorded MD5 <em>and</em> to a readable class file when
     * {@code candidate} is used as the base jar.
     *
     * <p>On 26.2 the bodyless copy patches make a Forge binpatched client jar match a few MD5s, while the real base -
     * the vanilla client jar - matches the classes OptiFine rewrites; the score tells them apart. Measured: vanilla
     * scores 5-6 of 12, the binpatched jar 2-3.
     */
    private static int scoreBase(OptifinePatcher patcher, List<String> probe, Path candidate) {
        int score = 0;
        int tried = 0;
        for (String logical : probe) {
            if (tried >= 12) break;
            tried++;
            try {
                byte[] patched = patcher.apply(logical, candidate);
                if (!isClassFile(patched)) continue;
                if (!OptifinePatcher.sha(patched, "MD5").equals(patcher.expectedMd5(logical))) continue;
                new org.objectweb.asm.ClassReader(patched);
                score++;
            } catch (Exception ignored) {
                // contributes nothing to the score
            }
        }
        return score;
    }

    /** Returns the first probe class that patches to its recorded MD5 on {@code candidate}, for the log line. */
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

marker = "    private static boolean sameFile(Path a, Path b) {"
text = text.replace(marker, helper + marker, 1)

path.write_text(text, encoding="utf-8")
print("rewritten")
