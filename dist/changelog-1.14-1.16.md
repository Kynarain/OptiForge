# OptiForge 1.0.1 — 1.14.4 – 1.16.5 (Java 8)

- Artifact: `OptiForge-1.0.1-oldml.jar` (Java 8; Forge 28.2.26 / 31.2.57 / 36.2.39).
- Requires your own OptiFine jar in `optiforge/`; nothing from OptiFine is bundled.
- Verified with a Forge dedicated server: 1.14.4 and 1.15.2 start, join and run with 0 crash reports
  (1684 – 3320 `[OptiFine]` log lines). 1.16.5 starts and OptiFine works, but entering a world can crash
  inside OptiFine's `ClientWorld.getPartEntities` — that is why this file is also offered as Beta for 1.16.5.
- OptiFine's CTM texture payloads are staged on first launch (a few minutes on 1.13–1.16, cached afterwards);
  `-Doptiforge.assets=none` skips staging if you do not need connected textures.
- Access transformers are resolved from the running line only; wrong-Java self check prints a warning.
