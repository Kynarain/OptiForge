# OptiForge 1.0.1 — 1.7.10 (alpha)

- Artifact: `OptiForge-1.0.1-legacy17.jar` (Java 8; Forge 1.7.10 uses `cpw.mods.fml`, so it needs its own
  binding).
- Requires your own OptiFine 1.7.10 jar in `optiforge/`; nothing from OptiFine is bundled.
- Alpha: the payload probe passes offline (87 patches, md5 all matching, 225 own classes, no SRG remap), but
  the author's test environment hits a `ConcurrentModificationException` inside Forge 1.7.10's own
  launchwrapper pass even without OptiForge, so an in-game pass is not confirmed yet.
