# OptiForge Reforged 1.0.2 — 1.17 – 26.2

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

## 1.0.2（本次）

- 每个 Minecraft 版本一个独立文件：文件名 `OptiForge-Reforged-<版本>+mc<MC 版本>.jar`，与 OptiFabric Reforged 一致。
- 每个文件内嵌 `optiforge/target-mc.txt`（记录目标 MC 版本），因此 31 个文件的 SHA-256 各不相同；
  启动时若发现该标记与运行中的 MC 版本不一致，日志会打印
  `[OptiForge] WARNING: this file was built for Minecraft X but the game is Y; download the file whose name ends with +mcY.jar`。
- 代码与 1.0.1 相同（只是加了这个标记与检查）。