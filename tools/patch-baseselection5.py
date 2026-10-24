"""Switches the base-jar readability test from all-or-nothing to a 60% ratio."""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\OptifinePack.java")
text = path.read_text(encoding="utf-8")

start = text.index("    /** True when every sampled payload patch on ")
end = text.index("\n    }\n", start) + len("\n    }\n")

new = '''    /**
     * True when most sampled payload patches on {@code candidate} yield class files ASM can read.
     *
     * <p>A minority of failures is normal: Forge binpatches a handful of classes and the payload cannot rebuild
     * those, so those samples come out unreadable even on a line where everything else works (measured on 1.21.8,
     * where the Forge client jar is the base that preserves the access transformers). On 26.2 the failure rate
     * flips - nearly every sample is unreadable - which is how the binpatched jar is told apart from the vanilla jar
     * the payload was built against.
     */
    private static boolean patchesReadable(OptifinePatcher patcher, List<String> probe, Path candidate) {
        int tried = 0;
        int readable = 0;
        for (String logical : probe) {
            if (tried >= 10) break;
            tried++;
            try {
                byte[] patched = patcher.apply(logical, candidate);
                if (!isClassFile(patched)) continue;
                new org.objectweb.asm.ClassReader(patched);
                readable++;
            } catch (Exception unreadable) {
                // counted as a failure for this candidate
            }
        }
        return tried > 0 && readable * 10 >= tried * 6;
    }
'''
text = text[:start] + new + text[end:]
path.write_text(text, encoding="utf-8")
print("patched")
