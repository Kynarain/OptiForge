"""Adds a per-class fallback: when patching from the primary base yields unreadable bytes, retry from the vanilla jar.

On 26.2 Forge binpatches many classes, so OptiFine's payload cannot rebuild them from Forge's client jar ("Bad tag
(99)"); for 1.17-1.21 the Forge client jar is still the right primary base because it carries the access
transformers. Falling back per class keeps both properties.
"""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\OptifinePack.java")
text = path.read_text(encoding="utf-8")

# 1. static hint field, next to forgeVersion
anchor = "    public static String forgeVersion = \"\";"
if anchor in text:
    text = text.replace(
        anchor,
        anchor + "\n\n    /** First vanilla client jar found for this launch; used to re-patch classes the primary\n"
        "     * base cannot rebuild (Forge binpatches them, measured on 26.2). */\n"
        "    public static Path vanillaJarHint;",
        1,
    )

# 2. populate the hint where candidates are finalised
anchor2 = "        List<Path> candidates = dedupe(ordered, optifineJar);"
text = text.replace(
    anchor2,
    "        List<Path> vanillaCandidates = dedupe(vanilla, optifineJar);\n"
    "        vanillaJarHint = vanillaCandidates.isEmpty() ? null : vanillaCandidates.get(0);\n"
    + anchor2,
    1,
)

# 3. per-class fallback inside the patch loop
old_loop = """                for (String logical : payload.keySet()) {
                    try {
                        byte[] bytes = patcher.applyWith(provider, patterns, configuration, logical);
                        if (obfRemap != null) {
                            bytes = obfRemap.remap(bytes);
                        }
                        raw.put(classNameOf(bytes, logical), bytes);
                    } catch (Exception e) {
                        failures.add(logical + " : " + e.getMessage());
                    }
                }"""
new_loop = """                // Fallback provider: the vanilla client jar. Forge binpatches a subset of classes, and the payload
                // cannot rebuild those from Forge's client jar - the result is a structurally broken class file
                // (measured on 26.2 K2_pre5: javap "Bad tag (99)"). Such a class is re-patched from the vanilla jar,
                // which is what the payload was built against. Classes that patch fine keep the primary base, so the
                // running line's access transformers are preserved.
                Object fallbackProvider = null;
                java.util.zip.ZipFile fallbackZip = null;
                if (vanillaJarHint != null && !sameFile(vanillaJarHint, baseJar)) {
                    try {
                        fallbackZip = new java.util.zip.ZipFile(vanillaJarHint.toFile());
                        fallbackProvider = patcher.newResourceProvider(fallbackZip);
                    } catch (Exception ignored) {
                        fallbackZip = null;
                        fallbackProvider = null;
                    }
                }
                int fallbacks = 0;
                try {
                    for (String logical : payload.keySet()) {
                        try {
                            byte[] bytes = patcher.applyWith(provider, patterns, configuration, logical);
                            if (!readableClass(bytes) && fallbackProvider != null) {
                                byte[] retried = patcher.applyWith(fallbackProvider, patterns, configuration,
                                        logical);
                                if (readableClass(retried)) {
                                    bytes = retried;
                                    fallbacks++;
                                }
                            }
                            if (obfRemap != null) {
                                bytes = obfRemap.remap(bytes);
                            }
                            raw.put(classNameOf(bytes, logical), bytes);
                        } catch (Exception e) {
                            failures.add(logical + " : " + e.getMessage());
                        }
                    }
                } finally {
                    if (fallbackZip != null) {
                        try {
                            fallbackZip.close();
                        } catch (java.io.IOException ignored) {
                            // nothing useful to do
                        }
                    }
                }
                if (fallbacks > 0) {
                    notes.append("re-patched from the vanilla jar: ").append(fallbacks)
                            .append(" class(es) Forge had binpatched\\n");
                }"""
if old_loop not in text:
    raise SystemExit("patch loop anchor not found")
text = text.replace(old_loop, new_loop, 1)

# 4. helper
helper_anchor = "    /** True when these bytes start with the class-file magic; used to keep malformed payload entries out of ASM. */"
helper = """    /** True when ASM can parse these bytes as a class file; false for the corrupt output Forge binpatches cause. */
    private static boolean readableClass(byte[] bytes) {
        if (!isClassFile(bytes)) return false;
        try {
            new org.objectweb.asm.ClassReader(bytes);
            return true;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

"""
if helper_anchor in text:
    text = text.replace(helper_anchor, helper + helper_anchor, 1)
else:
    raise SystemExit("helper anchor not found")

path.write_text(text, encoding="utf-8")
print("patched")
