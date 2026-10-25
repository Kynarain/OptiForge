"""Adds the launcher profile jar (versions/<versionId>/<versionId>.jar) as the primary base candidate.

Historical working runs used it: diagnose showed "base 1.21.8-forge-58.1.26.jar", i.e. the profile jar, not the
Forge -client.jar and not the plain vanilla jar.
"""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\OptifinePack.java")
text = path.read_text(encoding="utf-8")

anchor = """        List<Path> forged = new ArrayList<>();"""
if anchor in text:
    raise SystemExit("unexpected anchor; inspect the file first")

# insert profile-jar discovery right before the forge client jar block
marker = "        // Forge's patched client jar: the jar the game classes actually come from on the modern lines."
profile_block = """        // The launcher profile jar: versions/<versionId>/<versionId>.jar. This is the jar the game actually gets
        // started with, and the historically working base on 1.17-1.21 (diagnose recorded "base
        // 1.21.8-forge-58.1.26.jar"). It is not the Forge -client.jar and not the plain vanilla jar.
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

"""
if marker not in text:
    raise SystemExit("forge client marker not found")
text = text.replace(marker, profile_block + marker, 1)

# ordering: non-26 lines try the profile jar first
old_order = "            ordered.addAll(forgeClients);\n            ordered.addAll(vanilla);"
new_order = "            ordered.addAll(profiles);\n            ordered.addAll(forgeClients);\n            ordered.addAll(vanilla);"
text = text.replace(old_order, new_order, 1)

# helper
helper_anchor = "    private static boolean sameFile(Path a, Path b) {"
helper = """    /** Reads the {@code --version} value from {@code sun.java.command}; "" when the launcher did not pass one. */
    private static String launchVersionId() {
        String command = System.getProperty("sun.java.command", "");
        String[] parts = command.split(" ");
        for (int i = 0; i + 1 < parts.length; i++) {
            if ("--version".equals(parts[i])) return parts[i + 1];
        }
        return "";
    }

"""
if "launchVersionId()" not in text:
    text = text.replace(helper_anchor, helper + helper_anchor, 1)

path.write_text(text, encoding="utf-8")
print("patched")
