# OptiForge Reforged 1.0.2 — 1.7.10 (alpha)

- Artifact: `OptiForge-1.0.1-legacy17.jar` (Java 8; Forge 1.7.10 uses `cpw.mods.fml`, so it needs its own
  binding).
- Requires your own OptiFine 1.7.10 jar in `optiforge/`; nothing from OptiFine is bundled.
- Alpha: the payload probe passes offline (87 patches, md5 all matching, 225 own classes, no SRG remap), but
  the author's test environment hits a `ConcurrentModificationException` inside Forge 1.7.10's own
  launchwrapper pass even without OptiForge, so an in-game pass is not confirmed yet.

## 1.0.2（本次）

- 每个 Minecraft 版本一个独立文件：文件名 `OptiForge-Reforged-<版本>+mc<MC 版本>.jar`，与 OptiFabric Reforged 一致。
- 每个文件内嵌 `optiforge/target-mc.txt`（记录目标 MC 版本），因此 31 个文件的 SHA-256 各不相同；
  启动时若发现该标记与运行中的 MC 版本不一致，日志会打印
  `[OptiForge] WARNING: this file was built for Minecraft X but the game is Y; download the file whose name ends with +mcY.jar`。
- 代码与 1.0.1 相同（只是加了这个标记与检查）。