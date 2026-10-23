# OptiForge — OptiFine on Forge

Run **OptiFine on Forge**. OptiForge takes *your own* OptiFine jar, applies OptiFine's own xdelta payload
with its own patcher, and delivers the patched classes plus a small resource mod, so the game boots with
OptiFine's features (shaders, connected textures, zoom, performance options) and Forge's mods side by side.

**This project does not include OptiFine.** OptiFine is © sp614x and must be downloaded from
<https://optifine.net/downloads> by you.

---

## Supported versions (tested by starting the game and entering a world)

| Artifact | Minecraft | Forge | Java |
|---|---|---|---|
| `OptiForge-<ver>.jar` | 1.17 – 1.20.1, 1.20.6, 1.21.x, 26.1.2, 26.2 | matching Forge line | 17 / 21 / 25 |
| `OptiForge-<ver>-oldml.jar` | 1.14.4, 1.15.2, 1.16.5 | 28.2.26 / 31.2.57 / 36.2.39 | **8** |
| `OptiForge-<ver>-oldml.jar` | 1.13.2 (experimental) | 25.0.219 | **8** |
| `OptiForge-<ver>-legacy.jar` | 1.8.9, 1.9.4, 1.10.2, 1.11.2, 1.12.2 | 11.15.1.2318 / 12.17.0.2317 / 12.18.3.2511 / 13.20.1.2505 / 14.23.5.2860 | **8** |
| `OptiForge-<ver>-legacy17.jar` | 1.7.10 (alpha) | 10.13.0.1157 | **8** |

Download the artifact that matches your Minecraft version. They are **not** interchangeable: the loading
mechanism differs between version eras.

---

## Install

1. Install Forge for your Minecraft version (Forge installer, as usual).
2. Download OptiFine for the **same** Minecraft version from <https://optifine.net/downloads>.
3. Create a folder named `optiforge` inside your game directory
   (`.minecraft/optiforge/`) — **not** inside `mods/`.
4. Put the OptiFine jar there, keeping its original file name
   (e.g. `optiforge/OptiFine_1.16.5_HD_U_G8.jar`).
5. Put the OptiForge jar that matches your version into `mods/`.
6. Start the game. The first launch builds a cache under `optiforge/pack/` and a generated
   `mods/OptiForge-<ver>-classes.jar` (that is the entry you see in the mods list).

Why OptiFine must not go into `mods/`: Forge treats any jar that declares a transformation service as such
and excludes it from mod discovery, so having both sides patch the game would break. OptiForge owns that
pipeline instead.

---

## Java versions

OptiForge cannot change the Java version the game runs on: Forge itself requires

- **1.7.10 – 1.16.5**: Java 8 (these Forge versions do not support newer JVMs)
- **1.17 – 1.20.4**: Java 17
- **1.20.5 / 1.20.6 / 1.21.x**: Java 21
- **26.x**: Java 25

If you use the wrong artifact or the wrong Java, the old-line artifacts print a clear warning:

```
[OptiForge] WARNING: this oldml artifact targets Java 8 and the runtime is Java 17; ...
```

---

## Shaders

Verified with real shader packs on 1.21.8 (Forge 58.1.26, OptiFine `HD_U_J6_pre13`):

| Shader pack | Compiled GL programs | Errors | Crashes |
|---|---|---|---|
| Complementary Reimagined r5.9.3 | 27 | 0 | 0 |
| BSL v10.1.8 | 26 | 0 | 0 |
| MakeUp Ultra Fast 9.6 | 25 | 0 | 0 |

Note: OptiFine build `1.21.7 HD_U_J6_pre7` skips shader packs on its own (an OptiFine-side bug, not
OptiForge). Use 1.21.8 if you want shaders on that line.

---

## Known limitations

- **1.16.5 / 1.13.2 (experimental)**: the game starts and OptiFine works, but entering a world can crash
  inside OptiFine's patched classes (`ClientWorld.getPartEntities` on 1.16.5, `RenderChunkCache` on 1.13.2).
  Root cause: OptiFine's payload replaces classes that Forge has already binpatch-ed, and Forge's code
  changes inside those classes are not fully merged yet. Tracked for 1.1.0.
- **OptiFine 1.17.1 `HD_U_G9`** (marked N/A on optifine.net) is not supported: its payload uses
  pre-release MCP ids and no public mapping exists for them. Use `HD_U_H1` on 1.17.1.
- **1.20.2**: OptiFine itself crashes against Forge 50 (`ForgeGui.f_291320_`), independent of OptiForge.
- **1.7.10**: the artifact is verified offline, but Forge 1.7.10's own launchwrapper pass throws
  `ConcurrentModificationException` in the author's test environment, so treat 1.7.10 as alpha.

---

## Links

- Source code, issues, full change log: <https://github.com/Kynarain/OptiForge>
- License: Mozilla Public License 2.0
- OptiFine: <https://optifine.net> (© sp614x) — required, not included
