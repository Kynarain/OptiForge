# OptiForge Reforged 1.0.2 — 1.14.4 – 1.16.5 (Java 8)

- Artifact: `OptiForge-1.0.1-oldml.jar` (Java 8; Forge 28.2.26 / 31.2.57 / 36.2.39).
- Requires your own OptiFine jar in `optiforge/`; nothing from OptiFine is bundled.
- Verified with a Forge dedicated server: 1.14.4 and 1.15.2 start, join and run with 0 crash reports
  (1684 – 3320 `[OptiFine]` log lines). 1.16.5 starts and OptiFine works, but entering a world can crash
  inside OptiFine's `ClientWorld.getPartEntities` — that is why this file is also offered as Beta for 1.16.5.
- OptiFine's CTM texture payloads are staged on first launch (a few minutes on 1.13–1.16, cached afterwards);
  `-Doptiforge.assets=none` skips staging if you do not need connected textures.
- Access transformers are resolved from the running line only; wrong-Java self check prints a warning.

## 1.0.2（本次）

- 每个 Minecraft 版本一个独立文件：文件名 `OptiForge-Reforged-<版本>+mc<MC 版本>.jar`，与 OptiFabric Reforged 一致。
- 每个文件内嵌 `optiforge/target-mc.txt`（记录目标 MC 版本），因此 31 个文件的 SHA-256 各不相同；
  启动时若发现该标记与运行中的 MC 版本不一致，日志会打印
  `[OptiForge] WARNING: this file was built for Minecraft X but the game is Y; download the file whose name ends with +mcY.jar`。
- 代码与 1.0.1 相同（只是加了这个标记与检查）。