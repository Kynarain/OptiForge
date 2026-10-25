"""Adds the per-class vanilla fallback (self-contained: also adds the helpers the current file lacks)."""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\OptifinePack.java")
text = path.read_text(encoding="utf-8")

changed = []

# 1. hint field next to forgeVersion
anchor = '    public static String forgeVersion = "";'
if "vanillaJarHint" not in text and anchor in text:
    text = text.replace(
        anchor,
        anchor
        + "\n\n    /** First vanilla client jar found for this launch; used to re-patch classes Forge binpatched. */\n"
        + "    public static Path vanillaJarHint;",
        1,
    )
    changed.append("field")

# 2. populate the hint
anchor2 = "        List<Path> candidates = dedupe(ordered, optifineJar);"
if "vanillaJarHint =" not in text and anchor2 in text:
    text = text.replace(
        anchor2,
        "        List<Path> vanillaCandidates = dedupe(vanilla, optifineJar);\n"
        "        vanillaJarHint = vanillaCandidates.isEmpty() ? null : vanillaCandidates.get(0);\n"
        + anchor2,
        1,
    )
    changed.append("hint")

# 3. helpers before sameFile
helper_anchor = "    private static boolean sameFile(Path a, Path b) {"
helpers = (
    "    /** True when these bytes start with the class-file magic. */\n"
    "    private static boolean isClassFile(byte[] bytes) {\n"
    "        return bytes != null && bytes.length >= 8\n"
    "                && (bytes[0] & 0xFF) == 0xCA && (bytes[1] & 0xFF) == 0xFE\n"
    "                && (bytes[2] & 0xFF) == 0xBA && (bytes[3] & 0xFF) == 0xBE;\n"
    "    }\n\n"
    "    /** True when ASM can parse these bytes; false for the corrupt output a binpatched base produces. */\n"
    "    private static boolean readableClass(byte[] bytes) {\n"
    "        if (!isClassFile(bytes)) return false;\n"
    "        try {\n"
    "            new org.objectweb.asm.ClassReader(bytes);\n"
    "            return true;\n"
    "        } catch (RuntimeException unreadable) {\n"
    "            return false;\n"
    "        }\n"
    "    }\n\n"
)
if "readableClass" not in text and helper_anchor in text:
    text = text.replace(helper_anchor, helpers + helper_anchor, 1)
    changed.append("helpers")

# 4. fallback inside the patch loop
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
new_loop = """                // The vanilla client jar, used to re-patch classes the primary base cannot rebuild: Forge binpatches
                // a subset of classes and the payload then produces structurally broken bytes from Forge's client
                // jar (measured on 26.2 K2_pre5, javap reports "Bad tag (99)"). Classes that patch fine keep the
                // primary base, so the running line's access transformers survive into the served classes.
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
if "re-patched from the vanilla jar" not in text and old_loop in text:
    text = text.replace(old_loop, new_loop, 1)
    changed.append("fallback")

path.write_text(text, encoding="utf-8")
print("changed:", ", ".join(changed) if changed else "nothing")
