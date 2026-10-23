# OptiForge 1.0.1 — 1.17 – 26.2

First CurseForge release.

- Loads OptiFine on Forge from Minecraft 1.17 through 26.2 with one artifact.
- Requires your own OptiFine jar in `optiforge/` (not in `mods/`); nothing from OptiFine is bundled.
- Verified by entering a world on 1.17.1, 1.18, 1.18.1, 1.18.2, 1.19, 1.19.1, 1.19.2, 1.19.3, 1.19.4,
  1.20.1, 1.20.6, 1.21, 1.21.1, 1.21.6 – 1.21.11, 26.1.2 and 26.2.
- Shader test on 1.21.8 with Complementary Reimagined, BSL and MakeUp Ultra Fast: 27 / 26 / 25 GL programs
  compiled, 0 errors, 0 crashes.
- Wrong-artifact / wrong-Java self check prints a clear `[OptiForge] WARNING` in the log.
- Known: OptiFine 1.17.1 HD_U_G9 is not supported (pre-release mappings, use H1); 1.20.2 fails inside
  OptiFine against Forge 50.
