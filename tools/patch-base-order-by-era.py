"""Orders the base candidates by version era: 26.x patches the vanilla jar, 1.17-1.21 patch the Forge client jar."""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\OptifinePack.java")
text = path.read_text(encoding="utf-8")

old = """        List<Path> ordered = new ArrayList<>();
        if (Bootstrap.legacy()) {
            ordered.addAll(vanilla);
            ordered.addAll(forgeClients);
        } else {
            ordered.addAll(forgeClients);
            ordered.addAll(vanilla);
        }"""
new = """        // Which jar the payload patches depends on the era, and the difference is measurable rather than
        // cosmetic: 1.17-1.21 payloads are written against the SRG-named classes that only the Forge client jar
        // carries, and that jar also already holds the line's access transformers, so it must be the base
        // (measured on 1.21.8: patching the vanilla jar instead left RenderPipelines.GUI_TEXTURED_SNIPPET private
        // and ForgeRenderTypes died with IllegalAccessError). 26.x ships unobfuscated and its payload is applied to
        // the vanilla classes; patching Forge's binpatched client jar there yields structurally broken class files
        // (javap: "Bad tag (99)"). So 26.x prefers vanilla and everything else prefers the Forge client jar.
        List<Path> ordered = new ArrayList<>();
        boolean unobfuscatedLine = mc != null && mc.startsWith("26.");
        if (Bootstrap.legacy() || unobfuscatedLine) {
            ordered.addAll(vanilla);
            ordered.addAll(forgeClients);
        } else {
            ordered.addAll(forgeClients);
            ordered.addAll(vanilla);
        }"""
if old not in text:
    raise SystemExit("ordering anchor not found")
text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("patched")
