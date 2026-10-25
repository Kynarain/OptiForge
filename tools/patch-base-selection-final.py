"""Base jar selection, final design:

1. add the launcher profile jar (versions/<versionId>/<versionId>.jar) as a candidate - the historical working base
   on 1.17-1.21, recorded by an old diagnose as "base 1.21.8-forge-58.1.26.jar";
2. order candidates by era: 26.x -> vanilla jar first (its payload is built against unobfuscated classes); every
   other line -> profile jar first, then the Forge client jar, then the plain vanilla jar;
3. drop the MD5 gate: a Forge/profile jar never reproduces the payload's recorded hashes, so the gate rejected the
   jar the line actually needs.
"""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\OptifinePack.java")
text = path.read_text(encoding="utf-8")
changed = []

# --- 1. profile jar discovery, inserted just before the Forge client jar block -------------------------
marker = "        // Forge's patched client jar: the jar the game classes actually come from on the modern lines."
profile_block = '''        // The launcher profile jar: versions/<versionId>/<versionId>.jar, where the version id comes from the
        // --version launch argument. This is the jar the launcher actually starts the game with, and it is the
        // historically working base on 1.17-1.21 (an old diagnose recorded "base 1.21.8-forge-58.1.26.jar").
        List<Path> profiles = new ArrayList<>();
        String profileId = launchVersionId();
        if (!J8.isBlank(profileId) && !J8.isBlank(mc)) {
            if (gameDir != null) {
                profiles.add(gameDir.resolve("versions").resolve(profileId).resolve(profileId + ".jar"));
            }
            for (Path root : versionRoots) {
                profiles.add(root.resolve("versions").resolve(profileId).resolve(profileId + ".jar"));
            }
        }

'''
if "List<Path> profiles" not in text and marker in text:
    text = text.replace(marker, profile_block + marker, 1)
    changed.append("profiles")

# --- 2. era ordering ----------------------------------------------------------------------------------
old_order = """        List<Path> ordered = new ArrayList<>();
        if (Bootstrap.legacy()) {
            ordered.addAll(vanilla);
            ordered.addAll(forgeClients);
        } else {
            ordered.addAll(forgeClients);
            ordered.addAll(vanilla);
        }"""
new_order = """        // Which jar the payload patches depends on the era, and the difference is measurable rather than
        // cosmetic. 1.17-1.21 payloads are written against the classes of the jar the launcher starts the game
        // with (the profile jar, SRG-named and already carrying the line's access transformers); 26.x ships
        // unobfuscated and its payload is applied to the plain vanilla classes. Patching a Forge-binpatched class
        // jar on 26.2 yields structurally broken class files (javap: "Bad tag (99) at index 11"), while patching
        // the vanilla jar on 1.21.8 leaves RenderPipelines.GUI_TEXTURED_SNIPPET private and ForgeRenderTypes dies
        // with IllegalAccessError - so each era needs its own base.
        List<Path> ordered = new ArrayList<>();
        boolean unobfuscatedLine = mc != null && mc.startsWith("26.");
        if (Bootstrap.legacy() || unobfuscatedLine) {
            ordered.addAll(vanilla);
            ordered.addAll(profiles);
            ordered.addAll(forgeClients);
        } else {
            ordered.addAll(profiles);
            ordered.addAll(forgeClients);
            ordered.addAll(vanilla);
        }"""
if old_order in text:
    text = text.replace(old_order, new_order, 1)
    changed.append("order")

# --- 3. drop the MD5 gate -----------------------------------------------------------------------------
start = text.index("            for (Path candidate : candidates) {")
catch_anchor = "        } catch (IOException e) {"
end = text.index(catch_anchor, start)
new_loop = '''            // The candidate list is ordered by era above, so the first existing jar is the right base. Probing the
            // payload's recorded MD5s here was counterproductive - a Forge or profile jar never reproduces them
            // because its classes carry the line's access transformers - so the probe rejected the very jar the
            // line needs and the pack was silently built from the vanilla jar instead.
            for (Path candidate : candidates) {
                if (!Files.isRegularFile(candidate)) continue;
                notes.append("base jar: ").append(candidate.getFileName()).append('\\n');
                return candidate;
            }
'''
text = text[:start] + new_loop + text[end:]
changed.append("gate")

# --- 4. --version helper ------------------------------------------------------------------------------
helper_anchor = "    private static boolean sameFile(Path a, Path b) {"
helper = '''    /** Reads the {@code --version} value from {@code sun.java.command}; "" when the launcher did not pass one. */
    private static String launchVersionId() {
        String command = System.getProperty("sun.java.command", "");
        String[] parts = command.split(" ");
        for (int i = 0; i + 1 < parts.length; i++) {
            if ("--version".equals(parts[i])) return parts[i + 1];
        }
        return "";
    }

'''
if "private static String launchVersionId" not in text and helper_anchor in text:
    text = text.replace(helper_anchor, helper + helper_anchor, 1)
    changed.append("helper")

path.write_text(text, encoding="utf-8")
print("changed:", ", ".join(changed))
