# OptiForge 1.0.1 — 1.8.9 – 1.12.2 (Java 8)

- Artifact: `OptiForge-1.0.1-legacy.jar` (Java 8, Forge coremod).
- Requires your own OptiFine jar in `optiforge/`; nothing from OptiFine is bundled.
- Verified by joining a Forge dedicated server on 1.8.9, 1.9.4, 1.10.2, 1.11.2 and 1.12.2
  (3314 – 3316 `[OptiFine]` log lines, 0 crash reports on the older lines; 1669 on 1.12.2).
- OptiFine's own classes that live in the jar root (`Config`, `awu$Builder` on 1.12.2) are handled; a very
  small number of Forge-added members are merged into the patched classes.
