# OptiForge Reforged 1.0.2 — 1.8.9 – 1.12.2 (Java 8)

- Artifact: `OptiForge-1.0.1-legacy.jar` (Java 8, Forge coremod).
- Requires your own OptiFine jar in `optiforge/`; nothing from OptiFine is bundled.
- Verified by joining a Forge dedicated server on 1.8.9, 1.9.4, 1.10.2, 1.11.2 and 1.12.2
  (3314 – 3316 `[OptiFine]` log lines, 0 crash reports on the older lines; 1669 on 1.12.2).
- OptiFine's own classes that live in the jar root (`Config`, `awu$Builder` on 1.12.2) are handled; a very
  small number of Forge-added members are merged into the patched classes.

## 1.0.2（本次）

- 每个 Minecraft 版本一个独立文件：文件名 `OptiForge-Reforged-<版本>+mc<MC 版本>.jar`，与 OptiFabric Reforged 一致。
- 每个文件内嵌 `optiforge/target-mc.txt`（记录目标 MC 版本），因此 31 个文件的 SHA-256 各不相同；
  启动时若发现该标记与运行中的 MC 版本不一致，日志会打印
  `[OptiForge] WARNING: this file was built for Minecraft X but the game is Y; download the file whose name ends with +mcY.jar`。
- 代码与 1.0.1 相同（只是加了这个标记与检查）。