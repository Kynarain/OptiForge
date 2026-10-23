# OptiForge

OptiForge loads OptiFine on Forge. It applies OptiFine's own patcher to **a user-supplied OptiFine jar** at
startup and delivers the patched game classes plus a small resource mod to Forge. This works for OptiFine
builds that OptiFine lists as Forge-compatible, and also for builds that OptiFine lists as **"Forge N/A"**.

**OptiFine is not included and is not redistributed.** The published jars contain no OptiFine code and no
OptiFine assets; every user has to download OptiFine from <https://optifine.net/downloads> themselves.

### "Forge N/A" builds

OptiFine's download page lists a Forge compatibility value per build. In the snapshot this project tracks
(`data/optifine-forge-matrix.csv`, 645 rows), **90 rows are marked "Forge N/A"** and 379 rows list a Forge
version. The N/A rows are concentrated on recent version lines: every 1.21.x preview and every 26.x build in
that snapshot is marked N/A.

A Forge-N/A build ships patches for the vanilla game jar only and has no Forge-side entry point, so Forge has
no supported way to load it. Separately, Forge does not treat an OptiFine jar in `mods/` as a mod at all: the
jar declares a ModLauncher transformation service, so Forge picks it up as a service and excludes it from mod
discovery.

OptiForge handles both points by running OptiFine's payload itself, outside OptiFine's installer, and serving
the resulting classes to Forge through its own transformation service.

### Builds verified through OptiForge

Verification method for each row: start the client with the listed Forge version, enter a world (singleplayer
or a Forge dedicated server), confirm OptiFine's version banner and its `[OptiFine]` log lines, and confirm
`crash-reports/` stays empty.

| Minecraft | OptiFine build | OptiFine's Forge column | Forge | Result |
|---|---|---|---|---|
| 1.21 | `preview_OptiFine_1.21_HD_U_J1_pre9.jar` | Forge N/A | 51.0.33 | world loads, no crash report |
| 1.21.1 | `preview_OptiFine_1.21.1_HD_U_J1_pre9.jar` | Forge N/A | 52.1.20 | world loads, no crash report |
| 1.21.6 | `preview_OptiFine_1.21.6_HD_U_J6_pre3.jar` | Forge N/A | 56.0.9 | world loads, no crash report |
| 1.21.7 | `preview_OptiFine_1.21.7_HD_U_J6_pre7.jar` | Forge N/A | 57.0.3 | starts; this OptiFine build does not load shader packs |
| 1.21.9 | `preview_OptiFine_1.21.9_HD_U_J7_pre2.jar` | Forge N/A | 59.0.5 | world loads, no crash report |
| 26.1.2 | `preview_OptiFine_26.1.2_HD_U_K1_pre2.jar` | Forge N/A | 64.1.5 | world loads (Java 25) |
| 26.2 | `preview_OptiFine_26.2_HD_U_K2_pre4.jar` | Forge N/A | 65.1.5 | world loads (Java 25) |
| 1.13.2 | `OptiFine_1.13.2_HD_U_G5.jar` | Forge N/A | 25.0.219 | starts; entering a world can crash (see Known issues) |
| 1.17.1 | `OptiFine_1.17.1_HD_U_G9.jar` | Forge N/A | 37.0.103 | does not work; this build requires mappings that are not public (use `HD_U_H1`) |

Builds that OptiFine already lists as Forge-compatible run through OptiForge as well; verified examples:
1.16.5 `HD_U_G8`, 1.17.1 `HD_U_H1`, 1.19.3 `HD_U_I2_pre4`, 1.21.8 `HD_U_J6_pre13`.

### Artifacts

Four artifacts. Each targets one era of Forge's mod loading mechanism, and they are not interchangeable.

| Minecraft | File | Java |
|---|---|---|
| 1.17 – 1.20.1, 1.20.6, 1.21.x, 26.1.2, 26.2 | `OptiForge-1.0.1.jar` | 17 / 21 / 25 |
| 1.14.4, 1.15.2, 1.16.5 | `OptiForge-1.0.1-oldml.jar` | 8 |
| 1.13.2 | `OptiForge-1.0.1-oldml.jar` | 8 |
| 1.8.9 – 1.12.2 | `OptiForge-1.0.1-legacy.jar` | 8 |
| 1.7.10 | `OptiForge-1.0.1-legacy17.jar` | 8 |

The Java column lists the version those Forge releases run on: Java 17 for 1.17–1.20.4, Java 21 for
1.20.5/1.20.6/1.21.x, Java 25 for 26.x, and Java 8 for 1.13–1.16 and 1.8–1.12. OptiForge cannot change the JVM
the game runs on; it only reports a warning when the jar or the Java version does not match the running line.

### What it does at startup

1. Looks for the OptiFine jar in `<game directory>/optiforge/` (not in `mods/`).
2. Applies OptiFine's own xdelta payload with OptiFine's own patcher to the vanilla client jar of the running
   version.
3. Remaps names only when the runtime namespace differs from the payload's (1.17+ with official names). For
   the 1.13.2 flat obfuscated payload a dedicated mapping table is used.
4. Re-applies the running version line's access transformers to the patched classes.
5. Applies a small set of compatibility repairs where OptiFine's patch and Forge's own class changes overlap.
6. Writes OptiFine's classes (and, if enabled, its CTM texture payloads) into a generated
   `mods/OptiForge-<version>-classes.jar`, which appears as "OptiForge" in the mods list.
7. Caches the result under `<game directory>/optiforge/pack/<key>/`; later launches reuse the cache.

### Installation

1. Install Forge for the Minecraft version.
2. Download OptiFine for the same Minecraft version from <https://optifine.net/downloads>. For a Forge-N/A
   line, use the newest OptiFine build published for that version.
3. Create `.minecraft/optiforge/`.
4. Put the OptiFine jar there without renaming it (for example
   `.minecraft/optiforge/preview_OptiFine_26.2_HD_U_K2_pre4.jar`). OptiFine's installer does not need to be run.
5. Put the OptiForge jar for the same Minecraft version into `.minecraft/mods/`.
6. Start the game.

The first launch performs the patching and takes longer than later ones; the console logs when it starts. On
1.13–1.16 OptiFine additionally compiles its default connected-textures set during the first resource reload,
which takes several minutes. `-Doptiforge.assets=none` skips the texture staging step.

### Requirements

- Forge for the matching version line.
- Java version as listed in the artifacts table.
- An OptiFine jar obtained from the official site.

### Verified state

The following lines were started and brought into a world (singleplayer or a Forge dedicated server) with
OptiFine active and no crash reports. OptiFine log line counts are from those runs.

- 1.8.9, 1.9.4, 1.10.2, 1.11.2 (legacy artifact): 3314–3316 `[OptiFine]` lines each.
- 1.12.2 (legacy artifact): 1669 `[OptiFine]` lines.
- 1.14.4, 1.15.2 (old-loader artifact): 1684 and 3320 `[OptiFine]` lines.
- 1.16.5 (old-loader artifact): starts and OptiFine loads; entering a world can crash (see Known issues).
- 1.17.1, 1.18, 1.18.1, 1.18.2, 1.19, 1.19.1, 1.19.2, 1.19.3, 1.19.4, 1.20.1, 1.20.6, 1.21, 1.21.1, 1.21.6,
  1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1.2, 26.2 (main artifact).
- 1.13.2 (old-loader artifact): starts and OptiFine loads; entering a world can crash (see Known issues).

Shader test, run on 1.21.8 with Forge 58.1.26 and OptiFine `HD_U_J6_pre13`. Counted from the game log, which
prints one `Program loaded:` line per successfully compiled and linked GL program:

| Shader pack | Compiled programs | Compile errors | Crash reports |
|---|---|---|---|
| Complementary Reimagined r5.9.3 | 27 | 0 | 0 |
| BSL v10.1.8 | 26 | 0 | 0 |
| MakeUp Ultra Fast 9.6 | 25 | 0 | 0 |

Versions not listed above have not been tested and are not claimed to work.

### Known issues

- **1.16.5 and 1.13.2**: the game starts and OptiFine loads, but entering a world can crash inside OptiFine's
  patched classes — `ClientWorld.getPartEntities` on 1.16.5 and `RenderChunkCache` on 1.13.2. OptiFine's payload
  replaces classes that Forge has already binpatch-ed, and Forge's changes inside those classes are not fully
  merged yet. Planned fix: 1.1.0.
- **OptiFine `1.17.1 HD_U_G9`**: does not work. Its payload references pre-release MCP ids, and no public
  mapping for them exists. Use `HD_U_H1` on 1.17.1.
- **1.20.2**: OptiFine itself fails against Forge 50 with `IllegalAccessError ForgeGui.f_291320_`, independently
  of OptiForge.
- **1.7.10**: the artifact passes offline verification (payload md5, class layout), but in the author's test
  environment Forge 1.7.10's own launchwrapper pass throws `ConcurrentModificationException`, with or without
  OptiForge present. An in-game run has not been confirmed.
- **OptiFine `1.21.7 HD_U_J6_pre7`**: does not load shader packs. The game logs `[Shaders] No shaderpack
  loaded.`; the build's `Shaders.loadShaderPack` contains an unconditional early return, and this is present
  without OptiForge as well.

### Troubleshooting

**OptiFine appears to be absent.** Open `<game directory>/optiforge/diagnose.txt` (legacy lines:
`diagnose-legacy.txt`). It records the detected versions, runtime namespace, access-transformer grants, patch
counts and the generated jar. The log line `[OptiForge] NO ACTIVE PACK` means no OptiFine jar was found in
`optiforge/`.

**Log says "OptiFine's own transformation service is also loaded".** The OptiFine jar is in `mods/`; it belongs
in `optiforge/`.

**A warning names another artifact or another Java version.** The jar or JVM does not match the running line.
Use the file and Java version named in the warning.

**Log repeats `Using missing texture: ... optifine/ctm/...`.** The CTM payload was skipped. Remove
`-Doptiforge.assets=none` and delete `optiforge/pack/` so the next launch rebuilds it.

**First launch on 1.13–1.16 appears to hang.** It is OptiFine compiling its default CTM set; the console says
so. Wait for it to finish, or use `-Doptiforge.assets=none`.

### Reporting a problem

Include:

- `<game directory>/logs/latest.log`
- `<game directory>/optiforge/diagnose.txt` (or `diagnose-legacy.txt`)
- the newest file from `crash-reports/`, if present
- Minecraft version, Forge version, OptiFine file name, OptiForge file name and Java version

### License and credits

- OptiForge: Mozilla Public License 2.0; source at <https://github.com/Kynarain/OptiForge>.
- OptiFine: © sp614x, <https://optifine.net>. Required, not included, not redistributed here.
- OptiForge is not affiliated with or endorsed by OptiFine, Mojang or Forge.
