# OptiForge — OptiFine on Forge (1.7.10 – 26.2)

Run **OptiFine** on **Forge**: put the OptiForge jar for your game version into `mods/`, put **your own
OptiFine jar** into `optiforge/`, and start the game. OptiFine itself is not included.

A loader that brings **OptiFine** to **Forge**. Put the OptiForge jar for your game version into `mods/`, put
**your own OptiFine jar** into `optiforge/`, and start the game.

> ⚠️ **OptiFine is not bundled or redistributed.** Download the build for your Minecraft version from the
> official site (<https://optifine.net/downloads>) and drop the jar in as-is — you do **not** need to run
> OptiFine's installer. OptiForge never ships OptiFine code; the artifacts contain zero `net/optifine/**`
> entries and zero OptiFine assets.

### Supported versions

Four artifacts, one per era. They are **not interchangeable**: each era loads mods through a different
mechanism, so pick the jar that matches your Minecraft version.

| Minecraft | OptiForge file | Example OptiFine build | Java |
|---|---|---|---|
| **1.17 – 1.20.1, 1.20.6, 1.21.x, 26.1.2, 26.2** | `OptiForge-1.0.1.jar` | matching build, any | 17 / 21 / 25 |
| **1.14.4, 1.15.2, 1.16.5** | `OptiForge-1.0.1-oldml.jar` | `HD_U_G5` / `HD_U_G6` / `HD_U_G8` | **8** |
| **1.13.2** (experimental) | `OptiForge-1.0.1-oldml.jar` | `1.13.2 HD_U_G5` | **8** |
| **1.8.9 – 1.12.2** | `OptiForge-1.0.1-legacy.jar` | `HD_U_M5` / `I5` / `I5` / `G5` / `G5` | **8** |
| **1.7.10** (alpha) | `OptiForge-1.0.1-legacy17.jar` | `1.7.10 HD_U_E7` | **8** |

The Java column is Forge's own requirement, not a preference: Forge 1.13–1.16 and 1.8–1.12 do not run on newer
JVMs. On 1.17–1.20.4 use Java 17, on 1.20.5/1.20.6/1.21.x Java 21, and on 26.x Java 25.

### Why it is needed

Forge does not treat OptiFine as a mod. Any jar in `mods/` that declares a ModLauncher transformation service
is picked up as a **service** and excluded from mod discovery — and OptiFine genuinely is one, because that is
how it patches the game. Dropping the OptiFine jar into `mods/` therefore either does nothing useful or ends in
two sides patching the same classes.

The second half of the problem is that OptiFine's payload is built against **one specific Minecraft build per
release**, while Forge also rewrites those same classes (binpatches, access wideners, its own transformation
services). The two disagree in ways that are hard to diagnose: missing access, methods whose bodies moved,
constructors whose initialisation was replaced, and so on.

### What it does

At startup OptiForge:

1. finds the OptiFine jar in `<game directory>/optiforge/` (never in `mods/`);
2. applies **OptiFine's own xdelta payload** with OptiFine's own patcher to the **vanilla** client jar for your
   version;
3. remaps only when the runtime really uses a different namespace (1.17+ with official names), with a dedicated
   obfuscation bridge for the old 1.13.2-style flat payload;
4. replays **only the running version line's** access transformers onto the patched classes;
5. applies small compatibility repairs where OptiFine's patch and Forge's rewrite collide;
6. stages OptiFine's own classes (and, on request, its CTM texture payloads) as a generated
   `mods/OptiForge-<version>-classes.jar`, which is the "OptiForge" entry you see in the mods list;
7. caches everything under `<game directory>/optiforge/pack/<key>/` so later launches reuse it.

### Installation

1. Install **Forge** for your Minecraft version, as usual.
2. Download **OptiFine** for the *same* Minecraft version from <https://optifine.net/downloads>.
3. Create a folder named `optiforge` inside your game directory (`.minecraft/optiforge/`).
4. Put the OptiFine jar there, keeping its original file name
   (e.g. `.minecraft/optiforge/OptiFine_1.16.5_HD_U_G8.jar`).
5. Put the matching **OptiForge** jar into `.minecraft/mods/`.
6. Start the game.

**First launch takes longer.** OptiForge prints a console line when it has to prepare something for the first
time. On 1.13–1.16 OptiFine additionally compiles its default connected-textures set on the first resource
reload, which can take a few minutes; after that it is cached. If you do not use connected textures, add
`-Doptiforge.assets=none` to your JVM arguments to skip the texture staging entirely.

### Requirements

- Forge for the matching version line (no other mod needed).
- Java 8 / 17 / 21 / 25 as listed above.
- OptiFine jar obtained by you from the official site — not included here.

### Verified state

Every line below was started and brought into a world (singleplayer or a Forge dedicated server) with
OptiFine active and no crash reports:

- **1.8.9, 1.9.4, 1.10.2, 1.11.2, 1.12.2** (legacy artifact)
- **1.14.4, 1.15.2** (old-loader artifact)
- **1.17.1, 1.18, 1.18.1, 1.18.2, 1.19, 1.19.1, 1.19.2, 1.19.3, 1.19.4, 1.20.1, 1.20.6, 1.21, 1.21.1,
  1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1.2, 26.2** (main artifact)

Shader packs were tested on **1.21.8** (Forge 58.1.26, OptiFine `HD_U_J6_pre13`) by counting compiled GL
programs in the log:

| Shader pack | Compiled programs | Errors | Crashes |
|---|---|---|---|
| Complementary Reimagined r5.9.3 | 27 | 0 | 0 |
| BSL v10.1.8 | 26 | 0 | 0 |
| MakeUp Ultra Fast 9.6 | 25 | 0 | 0 |

### Known issues

- **1.16.5 and 1.13.2 are experimental.** The game starts and OptiFine works, but entering a world can crash
  inside OptiFine's patched classes — `ClientWorld.getPartEntities` on 1.16.5, `RenderChunkCache` on 1.13.2.
  Cause: OptiFine's payload replaces classes Forge has already binpatch-ed, and Forge's code changes *inside*
  those classes are not fully merged yet. Fix planned for 1.1.0. Download the 1.16.5 file from the "Beta"
  section if you want to try it.
- **OptiFine `1.17.1 HD_U_G9`** (marked N/A on optifine.net) does not work: its payload uses pre-release MCP
  ids and no public mapping exists for them. Use `HD_U_H1` on 1.17.1.
- **1.20.2**: OptiFine itself crashes against Forge 50 (`IllegalAccessError ForgeGui.f_291320_`), independent
  of OptiForge.
- **1.7.10**: the artifact passes offline verification (payload md5, class layout) but Forge 1.7.10's own
  launchwrapper pass throws `ConcurrentModificationException` in the author's test environment, so an in-game
  pass is not confirmed. Treat it as alpha.
- **OptiFine `1.21.7 HD_U_J6_pre7`** skips shader packs on its own (an OptiFine bug, not OptiForge). Use 1.21.8
  if you want shaders on that line.

### Troubleshooting

**The game starts but OptiFine seems absent.**
Check `<game directory>/optiforge/diagnose.txt` (on legacy lines: `diagnose-legacy.txt`). It records the
detected versions, namespace, access-transformer grants, patch counts and the staged jar. The log line
`[OptiForge] NO ACTIVE PACK` means the OptiFine jar was not found — it must be in `optiforge/`, not `mods/`.

**"OptiFine's own transformation service is also loaded".**
Your OptiFine jar is inside `mods/`. Move it to `optiforge/`.

**A warning naming another artifact or Java version.**
OptiForge prints `[OptiForge] WARNING: ...` when the jar or JVM does not belong to the line you launched.
Use the artifact named in the message, with the Java version listed for that line.

**Textures look wrong / log full of `Using missing texture: ... optifine/ctm/...`.**
The CTM payload was skipped. Remove `-Doptiforge.assets=none` (staging is on by default) and delete
`optiforge/pack/` so the next launch rebuilds it.

**First launch looks frozen on 1.13–1.16.**
It is OptiFine compiling its default CTM set. The console line says so; wait it out, or add
`-Doptiforge.assets=none`.

### Reporting a problem

Please attach:

- `<game directory>/logs/latest.log`
- `<game directory>/optiforge/diagnose.txt` (or `diagnose-legacy.txt`)
- the crash report from `crash-reports/` if there is one
- Minecraft version, Forge version, the OptiFine file name, the OptiForge file name and your Java version

### License and credits

- OptiForge: **Mozilla Public License 2.0**, source at <https://github.com/Kynarain/OptiForge>.
- OptiFine: © **sp614x**, <https://optifine.net> — required, **not included**, all rights belong to its author.
- Not affiliated with or endorsed by OptiFine, Mojang or Forge.
