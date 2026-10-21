# PHASE 2 — Minecraft 1.13 … 1.16.5 (Java 8 / old ModLauncher)

Status of this document: **probes and design only**. Nothing here has been run in a game yet (game runs are
owned by the Lead). Every number below was measured on this machine on the paths named in the text; the
probe scripts are in `build/phase2/` and are re-runnable.

Scope: the Java-8 line, `1.13.2` … `1.16.5`. Phase 1 (`1.17.1` … `1.19.4`, plus `1.20.x`) is unchanged by
this document; `1.20.6+`/`1.21`/`26.x` are out of scope here.

## 0. TL;DR

1. **The Java 17 artifact cannot load on 1.13–1.16.5 at all.** The first blocker is the class-file version,
   the second is the ModLauncher API (`beginScanning` and `ITransformer.Target` changed shape).
2. **The naming model of 1.14.4 / 1.15.2 / 1.16.5 is the same as 1.17–1.19.4: official class names + SRG
   member names** (`func_…_` / `field_…_`, not `m_…_` / `f_…_`). OptiFine's payload is built for exactly that
   namespace, so **no SRG→official remap and no obf↔srg class-name bridge is needed** on those three lines —
   contrary to the hypothesis in the task brief. The remap must stay *disabled*; the namespace *detector*
   does need a third state. **1.13.2 is the exception**: its OptiFine build carries only obfuscated-named
   payloads (no `srg/` at all), so it does need an obf→srg bridge (§2.3) and is therefore a separate
   workstream, not part of the first Java-8 artifact.
3. These OptiFine builds (1.14.4+) ship **only** `patch/srg/` + `srg/` (no `patch/notch/`, no `notch/` at all),
   and the `patch/srg` xdelta payloads apply **only to the vanilla (obfuscated) client jar**, not to Forge's
   client jar — 311/311, 299/299 and 246/246 MD5 matches for 1.16.5/1.15.2/1.14.4, and the output is already
   in the srg namespace.
4. A single **legacy jar built with `--release 8` against ModLauncher 2.1.1** (the oldest of the line) loads
   and service-resolves on JRE 1.8 under ModLauncher 2.1.1, 4.1.0, 5.1.2 and 8.1.3. That is the recommended
   way to produce the Java 8 artifact for 1.14.4–1.16.5. The current Java-17/ML-10 sources do not compile to
   8: 73 Java-9+ API uses and 129 Java-10+ language-feature tokens (217 `javac --release 8` syntax errors
   across 13 files).

## 1. What was installed (rig evidence)

The rig is `I:\mods\optifineoforge-test`, used the way `tools/legacy-rig-test.ps1` uses it: Forge
`--installClient` into a scratch directory that has `launcher_profiles.json` + `versions/<mc>/<mc>.json`,
then the scratch `versions/` and `libraries/` are merged into the rig. **Nothing was launched.**

| line | installer | scratch dir | rig profile id |
|---|---|---|---|
| 1.16.5 | `downloads\1.16.5-36.2.39\forge-1.16.5-36.2.39-installer.jar` (7 481 495 B) | `downloads\1.16.5-36.2.39\mc` | `1.16.5-forge-36.2.39` |
| 1.14.4 | `downloads\1.14.4-28.2.26\forge-1.14.4-28.2.26-installer.jar` (6 579 712 B) | `downloads\1.14.4-28.2.26\mc` | `1.14.4-forge-28.2.26` |
| 1.15.2 | `downloads\1.15.2-31.2.57\forge-1.15.2-31.2.57-installer.jar` | `downloads\1.15.2-31.2.57\mc` | `1.15.2-forge-31.2.57` |
| 1.13.2 | `downloads\1.13.2-25.0.219\forge-1.13.2-25.0.219-installer.jar` | `downloads\1.13.2-25.0.219\mc` | `1.13.2-forge-25.0.219` |

Installer had to run on **JRE 1.8** (`C:\Program Files\Java\jre1.8.0_503\bin\java.exe`); it succeeded (exit 0,
"Successfully installed client into launcher"). Vanilla `versions/<mc>/<mc>.json` + `.jar` were downloaded
from Mojang's piston-meta manifest into the scratch dir first, because `--installClient` does not fetch the
vanilla side (same pitfall as phase 1). The rig now has, for each line, `versions/<mc>/<mc>.jar` (the
**vanilla** jar the patcher needs) and `versions/<mc>-forge-<ver>/<mc>-forge-<ver>.json`.

OptiFine jars (curl from bmclapi2, ZIP-validated with `System.IO.Compression.ZipFile`, no stub fallback
needed):

| file | bytes | buildof.txt |
|---|---|---|
| `build\optifine-pristine\OptiFine_1.16.5_HD_U_G8.jar` | 6 046 996 | `20210515-161946` |
| `build\optifine-pristine\OptiFine_1.15.2_HD_U_G6.jar` | 5 748 075 | `20210124-205244` |
| `build\optifine-pristine\OptiFine_1.14.4_HD_U_G5.jar` | 5 307 919 | `20210124-160733` |
| `build\optifine-pristine\OptiFine_1.13.2_HD_U_G5.jar` | 2 766 461 | `20210124-161303` |

The 1.15.2 jar first came down **truncated at 3 670 016 B** ("no end-of-central-directory record") — the
BMCLAPI truncation pitfall phase 1 documented; retrying with ZIP validation got 5 748 075 B. Always validate
before measuring (the 1.16.5/1.14.4 files above are valid). Neither is a `<100 KB` stub, so the optifine.net
`adloadx` → `downloadx` fallback was not exercised (that path is already implemented in
`tools/legacy-rig-test.ps1` lines 56–69 for the next time it is).

## 2. Runtime and payload naming per line (measured)

Every value comes from `javap` on the installed jars, or from applying OptiFine's own patcher
(`kynarain.cn.optiforge.PatchApply`) against the rig's vanilla jar.

| line | ModLauncher | launch main / target | Minecraft runtime jar | class names | member names | `patch/srg` xdelta | `patch/notch` | `srg/` classes (all `net/optifine`) | `notch/` | payload vs vanilla jar |
|---|---|---|---|---|---|---|---|---|---|---|
| **1.16.5**-36.2.39 | **8.1.3** | `cpw.mods.modlauncher.Launcher` / `fmlclient` | `net/minecraft/client/1.16.5-20210115.111550/client-1.16.5-20210115.111550-srg.jar` (11 352 644 B) | **official** (`net.minecraft.client.Minecraft`) | **`func_` / `field_`** | **311** (+311 `.md5`) | **0** | **560** | **0** | **311/311 MD5 match** |
| **1.14.4**-28.2.26 | **4.1.0** | same | `net/minecraft/client/1.14.4-20190829.143755-srg.jar` (9 973 917 B) | **official** (`net.minecraft.client.Minecraft`) | **`func_` / `field_`** | **246** (+246 `.md5`) | **0** | **466** | **0** | **246/246 MD5 match** |
| **1.15.2**-31.2.57 | **5.1.2** | same | `client-1.15.2-20200515.085601-srg.jar` | **official** (`net.minecraft.client.Minecraft`) | **`func_` / `field_`** | **299** (+299 `.md5`) | **0** | **517** | **0** | **299/299 MD5 match** |
| **1.13.2**-25.0.219 | **2.1.1** | same | `client-1.13.2-20190213.203750-srg.jar` (8 198 877 B) | **official** (`net.minecraft.client.Minecraft`) | **`func_` / `field_`** | **0** (and no `patch/notch/`) | **0** | **0** | **0** | **no srg payload**: only flat obf `patch/<obf>.class.xdelta`, 233 classes (§2.3) |

Notes on the table:

- The **runtime Minecraft jar is a full srg-named jar**: Forge installs it as `net.minecraft:client:<mc>-…:srg`.
  Forge's own `forge-<mc>-<forge>-client.jar` (1.16.5: 4 216 473 B / 1 276 classes; 1.14.4: 3 460 674 B /
  1 041 classes; 1.13.2: 3 065 519 B / 919 classes) is only the **patched subset**, also in official/srg names.
  `javap` on `client-…-srg.jar` `net.minecraft.client.Minecraft` shows `field_147123_G`, `field_142025_a`, …
  and `func_` methods: this is the same "official class names + SRG members" model phase 1 already handles for
  1.17–1.19.4, with the older `func_/field_` SRG spelling instead of `m_/f_`.
- **There is no `patch/notch` and no `notch/` directory in the 1.14.4/1.15.2/1.16.5 builds.** (1.20.1 `I6`
  has 411 + 703; these older builds are srg-only.) So "patch/srg vs patch/notch counts" is 311/0, 299/0 and
  246/0; there is no notch payload to choose between. 1.13.2 is the opposite extreme — see §2.3.
- `patch/srg` = 622/598/492 *entries* in the zip, but half of them are `.class.md5`; the payload classes are
  **311 / 299 / 246**.
- `srg/` contains **only** `net/optifine/**` classes (560 / 517 / 466); it contains no `net/minecraft/**`
  classes. The patched game classes are delivered by `patch/srg` xdelta only.
- The `srg/` own classes and the `patch/srg` payloads reference **official class names** (`net/minecraft/…`)
  and **SRG `func_`/`field_` members**, *not* obf class names. Token scan over the zip entries:

| build | `srg/` own classes: `func_` | `field_` | `m_`/`f_` | `patch/srg` payloads: `func_` | `field_` | `net/minecraftforge` |
|---|---|---|---|---|---|---|
| 1.16.5 G8 | 1546 tokens / 259 classes | 712 / 212 | 0 | 6027 / 226 | 2950 / 231 | 66 tokens / 16 classes |
| 1.15.2 G6 | 1364 / 241 | 581 / 189 | 0 | 5784 / 220 | 2848 / 228 | 53 / 14 |
| 1.14.4 G5 | 1130 / 230 | 505 / 179 | 0 | 4661 / 187 | 2366 / 186 | 64 / 16 |

### 2.2 Where the payload applies (the base-jar question)

`PatchApply --optifine <of> --base <jar> --all`:

| base jar | 1.16.5 G8 | 1.15.2 G6 | 1.14.4 G5 |
|---|---|---|---|
| rig vanilla `versions\<mc>\<mc>.jar` (obf) | **311 match / 0 mismatch / 0 failed** | **299 / 0 / 0** | **246 / 0 / 0** |
| Forge `forge-<mc>-<ver>-client.jar` (srg names) | 0 / 0 / 311 failed — `Base resource not found: dfm.class` | same failure mode | same |
| full `client-…-srg.jar` | fails at the same `dfm.class` lookup | same | same |

So the patcher's `patch2.cfg` resolves `srg/<official name>.class` to the **obf entry name** in the base jar
(`srg/com/mojang/blaze3d/matrix/MatrixStack.class=dfm.class`), and the delta turns that obf class into the
srg-named patched class. `PatchApply --class com/mojang/blaze3d/matrix/MatrixStack --out …` then `javap`
shows `public class com.mojang.blaze3d.matrix.MatrixStack` with `func_227861_a_(double, double, double)` —
i.e. **srg output from an obf base**. The patched `net/minecraft/client/renderer/WorldRenderer` carries
11 `net/minecraftforge/` references, so the target of the delta is the **Forge-flavoured** class, not a
pure-vanilla class; the payload is meant for a Forge runtime (matrix entry in
`data/optifine-forge-matrix.csv`: 1.16.5 `G8` = `Forge 36.1.0`, 1.14.4 `G5` = `Forge 28.2.23`).

`OptifinePack.findBaseJar` already does the right thing: it *prefers* Forge's client jar but validates each
candidate by applying the `CrashReport` delta and comparing MD5, so on 1.14/1.16 it falls through to the
vanilla `versions/<mc>/<mc>.jar` (which the rig now has). No change needed there except a note.

### 2.3 1.13.2 is a different payload generation (the one place an obf→srg bridge is needed)

`OptiFine_1.13.2_HD_U_G5.jar` has **no `srg/` and no `patch/srg/` at all**. Its layout is the LaunchWrapper
("vanilla") one:

- top-level `net/optifine/**` own classes, **455** of them (not under `srg/`);
- `patch/` = **233 obf-named** xdelta classes (+ `.md5`), flat entry names such as
  `patch/afb$1.class.xdelta`, `patch/auv.class.xdelta` — i.e. the *notch* namespace, not `patch/notch/`;
- top-level `net/minecraftforge/**` shim classes (13: `client/model/pipeline/IVertexConsumer`, …) plus
  `optifine.ReflectorForge` / `optifine.ForgeSplashCompatible` — 1.12-style Forge reflection shims;
- token scan: own classes contain **0** `func_`/`field_` tokens and only **1** `net/minecraft/` reference —
  they address the game by **obfuscated class and member names**. The flat payloads likewise contain 0
  `net/minecraft/` class references.

`PatchApply --all` therefore reports `payload: 0 patch/srg entries` for this build. Consequence: the Java-8
artifact planned below covers **1.14.4 / 1.15.2 / 1.16.5**; 1.13.2 would need a *second* pipeline stage —
apply the flat obf xdelta to the vanilla obf jar, then remap the resulting classes **and** the 455 own classes
from obf names/members to srg (class *and* member mapping; MCPConfig for 1.13.2 is the data source). That is
exactly the "obf↔srg class-name bridge" the brief anticipated, but it applies **only to 1.13.2**, and it also
explains why every 1.13.2 build is `Forge N/A` in the matrix: the payload has no srg form to begin with.
Treat 1.13.2 as a separate workstream (or defer it).

## 3. Java 8 compatibility of the current sources (measured)

`build/phase2/scan-java8.ps1` compiles all of `src/main/java` with `javac --release 8` (JDK 11, English
messages, `-Xmaxerrs 100000`). Result: **compilation fails — 217 syntax errors in 13 files**, so the current
tree cannot produce class files below 17. 11 files are syntactically Java-8-clean but still use Java-9+ APIs.

`build/phase2/scan-java8-api.ps1` counts the API/language surface (hits = regex occurrences; TSV:
`build/phase2/java8-api-counts.tsv`, `java8-api-hits.tsv`):

**Per API (73 API occurrences total):**

| API | count |
|---|---|
| `String.isBlank()` (11) | 17 |
| `InputStream.readAllBytes()` (9) | 12 |
| `Path.of(...)` (11) | 9 |
| `Set.of(...)` (9) | 7 |
| `Files.writeString(...)` (11) | 5 |
| `List.of(...)` (9) | 4 |
| `List/Map/Set.copyOf(...)` (10) | 4 |
| `Stream.toList()` (16) | 3 |
| `Map.entry(k,v)` (9) | 2 |
| `Files.readString(...)` (11) | 1 |
| `String.repeat(int)` (11) | 1 |
| `String.strip()/stripLeading()/stripTrailing()` (11) | 1 |
| `Module.getModule()/java.lang.Module` (9) | 6 |

**Per file (API uses / Java-10+ language features):**

| file | API | language |
|---|---|---|
| `OptifinePack.java` | 29 | 32 |
| `OptifineClassTransformer.java` | 1 | 18 |
| `SrgRemap.java` | 1 | 12 |
| `Diagnose.java` | 4 | 8 |
| `ForgeAccessTransformers.java` | 1 | 10 |
| `PatchApply.java` | 0 | 10 |
| `LaunchPaths.java` | 7 | 2 |
| `OptifinePatcher.java` | 1 | 7 |
| `PackBuild.java` | 0 | 8 |
| `ForgeRefs.java` | 1 | 7 |
| `OptiForgeService.java` (modern-only) | 7 | 0 |
| `PatchPlan.java` | 2 | 4 |
| `CompatFixes.java` | 0 | 5 |
| `ForgeInterfaces.java` | 3 | 2 |
| `ModuleReads.java` (modern-only) | 4 | 0 |
| `Notice.java` | 3 | 1 |
| `OwnRefs.java` | 1 | 2 |
| `RuntimeInfo.java` | 2 | 0 |
| `OptifineJar.java` | 2 | 0 |
| `ModuleReadsInjector.java` (modern-only) | 2 | 0 |
| `SuperInterfaceFixer.java` | 0 | 1 |
| `ForgeMatrix.java` | 1 | 0 |

**Language features (129 tokens):** switch expressions/`case … ->` 96, `var` 25, pattern-matching
`instanceof` 8. (`record`/text blocks/sealed: 0.) These are what the 217 `javac --release 8` errors are —
javac stops before attributing, so the API list above is grep-derived and the syntax-error number is the
corroborating count.

### 3.1 ModLauncher API differences (the real second blocker)

`javap` on the four ML versions in the rig (`libraries/cpw/mods/modlauncher/…`):

| | ML 2.1.1 (1.13.2) | ML 4.1.0 (1.14.4) | ML 5.1.2 (1.15.2) | ML 8.1.3 (1.16.5) | ML 10.0.9 (phase 1) |
|---|---|---|---|---|---|
| class-file of ML jar | 52 | 52 | 52 | 52 | 61 (Java 17) |
| `ITransformationService.beginScanning` | **abstract `void`** | **abstract `void`** | **abstract `void`** | **abstract `void`** | `default List<Resource>` |
| `ITransformationService.completeScan` | – | – | – | – | `default List<Resource>` (+ nested `Record Resource`) |
| `additionalClassesLocator` / `additionalResourcesLocator` | **absent** | default | default | default | default |
| `ITransformer.Target.targetClass` | yes | yes | yes | yes | yes |
| `ITransformer.Target.targetPreClass` | **no** | **no** | **no** | **yes** | **yes** |
| `IEnvironment.Keys.GAMEDIR` | yes | yes | yes | yes | yes |
| `IEnvironment.findModuleLayerManager()` / `IModuleLayerManager` | no | no | no | no | **yes** |
| `ITransformer` / `ITransformerVotingContext` / `TransformerVoteResult` | same | same | same | same | same |
| `ITransformerDiscoveryService.candidates(Path)` (mods-dir service discovery) | same | same | same | same | same |

Both `1.14.4` and `1.16.5` Forge jars carry `net/minecraftforge/fml/loading/ModDirTransformerDiscoverer`
and `META-INF/services/cpw.mods.modlauncher.serviceapi.ITransformerDiscoveryService`; its constant pool
looks for `META-INF/services/…ITransformationService`, `…IModLocator` — the same mechanism phase 1 relies on.
1.13.2 has it too.

**Proof that one entry class cannot serve both APIs** (`build/phase2/probe-legacy/`):

- `LegacyProbe` (declares `void beginScanning`) compiles with `--release 8` against **ML 2.1.1, 4.1.0,
  5.1.2 and 8.1.3** (class version 52) and `ServiceLoader` finds it on **JRE 1.8.0_503** with each of those
  ML jars, including class files compiled against 2.1.1 loaded under 4.1.0, 5.1.2 and 8.1.3:
  `RESULT services=1 java=1.8.0_503`.
- Compiling the same `LegacyProbe` against ML 10 fails:
  `error: beginScanning(IEnvironment) in LegacyProbe cannot implement beginScanning(IEnvironment) in ITransformationService; return type void is not compatible with List<Resource>`.
- The ML-10 shape (`ModernProbe`, no `beginScanning`) compiled against ML 4.1.0 fails:
  `error: ModernProbe is not abstract and does not override abstract method beginScanning(IEnvironment)`.

## 4. Design

### 4.1 Artifact: a second jar, not a multi-release jar

Produce **`OptiForge-<version>-legacy.jar`** with `--release 8`, compiled against **ModLauncher 2.1.1**
(the oldest of the line; the probe compiled against 2.1.1 loads and service-resolves on JRE 1.8 under ML
2.1.1, 4.1.0, 5.1.2 and 8.1.3). It targets **1.14.4 / 1.15.2 / 1.16.5**; 1.13.2 is not covered (its OptiFine
payload is obf-namespace, §2.3). The existing `OptiForge-<version>.jar` stays the Java-17/ML-10 artifact. A
multi-release jar (`META-INF/versions/17/…`) would put both entry classes in one file, but ModLauncher loads
the mods-directory service jar through its own `SecureJar` / module layer; whether that honours
`Multi-Release` (and whether the base entry class is visible to the service loader under ML 10) is
unverified, and a wrong answer fails in a much harder-to-see way than two files. Two files are also
independently testable per line, which is what the rig needs.

The two artifacts differ only in the ML-bound entry point (and the fixes that only make sense on one line);
the patch/remap/pack pipeline is shared source.

### 4.2 Source layout and why

> **Naming collision to avoid:** the workspace already has `src/legacy/java/kynarain/cn/optiforge/legacy/`
> and `tools/build-legacy.ps1` for the **1.8–1.12.2 launchwrapper** artifact (a different phase, FML coremod
> route). That tree must not be reused here: phase 2 is ModLauncher and needs a different entry point, a
> different compile classpath and a different package. The names below are therefore `src/oldml/` +
> `kynarain.cn.optiforge.oldml`; the artifact name could also be `-ml-legacy` to keep the two apart.

- `src/main/java/**` — pipeline, **made Java-8-clean** (remove `var`, switch arrows, pattern `instanceof`,
  `List/Set/Map.of`, `copyOf`, `Map.entry`, `toList`, `Path.of`, `Files.readString/writeString`,
  `readAllBytes`/`readNBytes`, `String.isBlank/strip/repeat`, `ClassLoader.getPlatformClassLoader`).
  Compiled into **both** jars; shipping it in the legacy jar is what keeps one implementation of the
  pipeline.
- `src/java17/java/**` (or the existing files moved): `OptiForgeService` (ML 10 entry), `ModuleReads`,
  `ModuleReadsInjector`, modern `Target` factory using `targetPreClass`. Compiled only into the Java-17 jar.
- `src/oldml/java/**`: `OldModLauncherService` (package `kynarain.cn.optiforge.oldml`; implements ML 2.1.1
  API: `name`, `initialize`, `void beginScanning`, `onLoad`, `transformers`; **no** `additional*Locator`
  overrides, no `IModuleLayerManager`, no module reads) and a legacy `Target` factory using `targetClass`.
  Compiled only into the legacy jar.
- `OptifineClassTransformer` keeps the transform logic and takes its `Set<Target>` from the factory, so the
  only ML-API-specific code is two small classes.
- The staged `mods/OptiForge-<version>-classes.jar` delivery is reused unchanged; on legacy lines own classes
  must come from that javafml mod (the `additionalClassesLocator` fast path does not exist on ML 2.1.1 and the
  1.20.1 path already proved the mod-jar delivery works).
- `modToml()` must stop writing `minecraft versionRange="[1.17,)"`: legacy lines need `[1.13,)` (and the
  `loaderVersion="[14,)"` floor OptiFine's own legacy `META-INF/mods.toml` uses is fine on FML 25/28/31/36).

### 4.3 Namespace handling: no obf↔srg bridge (except 1.13.2)

The facts in §2 close this question for 1.14.4–1.16.5:

- runtime = official class names + `func_`/`field_` members;
- payload = official class names + `func_`/`field_` members (srg output from an obf base);
- therefore the bridge the brief anticipated is **not** needed on 1.14.4–1.16.5 (1.13.2 is the exception,
  §2.3). The existing remap stays disabled; `SrgRemap`'s table keys are `m_/f_` only, so even if it were
  switched on here it would be a no-op — but the *detector* must be fixed so it does not claim "OFFICIAL":
  - `OptifinePack.detectRuntimeNamespace` and `OptifinePatcher.memberTokens`, `PackBuild`, `CollisionFixer`
    all use `Pattern.compile("[fm]_\\d+_")`. Add a legacy SRG state (`(func|field)_\\d+_`) and return
    `SRG_LEGACY` for it; `remapEnabled` must be false for `SRG_LEGACY` (and for `SRG`), true only for
    `OFFICIAL`.
  - The namespace note in `diagnose.txt` should say which spelling it found: `m_/f_` (1.17+), `func_/field_`
    (1.13–1.16), or plain (1.20.6+).
- Base jar: keep `findBaseJar` (vanilla jar, validated by the `CrashReport` MD5 probe). The Forge client jar
  fails the probe on these lines by design (obf entry names); that is harmless but should be logged once so a
  future reader does not chase it.
- Forge AT replay: the 1.14.4/1.16.5 `META-INF/accesstransformer.cfg` uses **official class names + `func_`/
  `field_` ids** (e.g. `public net.minecraft.advancements.CriteriaTriggers func_192118_a(…);`). If AT replay
  is needed on the legacy line, `ForgeAccessTransformers`'s member handling has to accept that spelling.
  (Not yet tested whether OptiFine's legacy payload actually needs the ATs.)
- 1.13.2 is special: its OptiFine build has no srg payload at all (§2.3), so it needs an obf→srg bridge and
  is not covered by the first legacy artifact. On top of that, ML 2.1.1 has no
  `additionalClassesLocator`/`additionalResourcesLocator`, and every OptiFine 1.13.2 build is `Forge N/A` in
  `data/optifine-forge-matrix.csv`.

### 4.4 Build command lines (exact)

The two artifacts are built by the two scripts (the legacy one has the full dependency/source list in
its header):

```powershell
pwsh -File tools/build.ps1       # Java-17 artifact: build/libs/OptiForge-<v>.jar (unchanged behaviour)
pwsh -File tools/check.ps1       # Java-17 artifact: Forge would find it (offline)
pwsh -File tools/build-oldml.ps1 # Java-8 artifact: build/libs-oldml/OptiForge-<v>-oldml.jar
pwsh -File tools/check-oldml.ps1 # Java-8 artifact: class-file 52, service file, JRE 1.8 probe, Java-17 build
```

`build-oldml.ps1` compiles `src/main/java` **minus** the four Java-17-only classes (`OptiForgeService`,
`ModernTargets`, `ModuleReads`, `ModuleReadsInjector`) plus `src/oldml/java`, with `--release 8`, against:

```
ML4  = …\downloads\1.14.4-28.2.26\mc\libraries\cpw\mods\modlauncher\4.1.0\modlauncher-4.1.0.jar
JOPT = …\downloads\1.14.4-28.2.26\mc\libraries\net\sf\jopt-simple\jopt-simple-5.0.4.jar
ASM  = …\downloads\1.14.4-28.2.26\mc\libraries\org\ow2\asm\{asm,asm-tree,asm-commons}\6.2\*.jar
```

(the 1.14.4 runtime's own ASM 6.2: the oldest of the line, so no ASM-7+-only API can slip in; the 1.16.5
line ships 9.1 and runs the same classes). `--release 8` needs a JDK 9+ (`javac 11.0.32` works; the
`JRE 1.8.0_503` here cannot compile), and the result is major version 52 with
`META-INF/services/cpw.mods.modlauncher.api.ITransformationService` containing
`kynarain.cn.optiforge.oldml.OldModLauncherService`.

One shared source needs care: **ASM 6.2's `InsnList` is not `Iterable`**, so the shared code iterates
instruction lists with `method.instructions.toArray()` (works on every ASM generation) instead of a
for-each over `InsnList`; `Opcodes.V17` is written as the literal `61` because ASM 6.2 has no such constant.

### 4.5 Shared vs duplicated

| group | classes | action |
|---|---|---|
| shared (both jars) | `Bootstrap` (orchestration), `J8` (Java-8 shims), `TargetFactory`, `OptifineJar`, `OptifinePatcher`, `OptifinePack`, `OptifineClassTransformer` (core), `SrgRemap`, `CollisionFixer`, `OwnRefs`, `ForgeRefs`, `ForgeInterfaces`, `ForgeAccessTransformers`, `PatchPlan`, `CompatFixes`, `SuperInterfaceFixer`, `ForgeMatrix`, `RuntimeInfo`, `LaunchPaths`, `Diagnose`, `Notice`, `PackBuild`, `PatchApply` | Java-8 clean; version-gated fixes no-op on legacy (they key off class/member names) |
| duplicate (tiny, ML-bound) | `OptiForgeService` (Java-17 entry, delegates to `Bootstrap`) / `OldModLauncherService` (Java-8 entry, package `kynarain.cn.optiforge.oldml`) | two entry points, one pipeline |
| duplicate (tiny, Target factory) | `ModernTargets` (`Target.targetPreClass`) / `OldMlTargets` (`Target.targetClass`) | injected into the shared transformer |
| modern-only | `OptiForgeService`, `ModernTargets`, `ModuleReads`, `ModuleReadsInjector` | left in the Java-17 jar only |
| legacy-only | `OldModLauncherService`, `OldMlTargets`, `OldMlProbe` (offline check main) | not in the Java-17 jar |

### 4.6 Risks / what the next round must test

1. **Forge-patch merge is unproven (top risk).** The delta target is Forge-flavoured (11 `net/minecraftforge`
   refs in the patched `WorldRenderer`), but I could not prove the patched output also carries *Forge's edits
   to the same vanilla methods* (it has more members than Forge's class — 211 vs 160 javap lines — but the
   comparison needs a name-level diff). If it does not, the Patcher output must be merged with Forge's class
   the way the `1.21.6 BlockModelPart` case already forced in phase 1. Offline probe: apply a payload for a
   class Forge definitely patches (`WorldRenderer`, `GameSettings`) and compare its member **names** with
   Forge's client-jar class, not javap lines.
2. **No game run yet.** The pipeline's runtime behaviour (transform ordering, `targetClass` vs `targetPreClass`,
   `additionalClassesLocator` absence on legacy) is designed and offline-verified, but not measured in a
   launch. Test order: 1.16.5 → 1.14.4 → 1.15.2 (1.13.2 is out of scope, §2.3).
3. **Java 8 vs the 1.16.5 profile's JVM args.** The installer wrote `--add-exports=java.base/sun.security.util=ALL-UNNAMED`,
   `--add-exports=jdk.naming.dns/…`, `--add-opens=java.base/java.util.jar=ALL-UNNAMED` into
   `1.16.5-forge-36.2.39.json`. A JVM 8 rejects `--add-exports`/`--add-opens`; the rig launcher must drop
   them when the selected Java is 8 (1.14.4's profile has the same kind of args for Java 9 users). This is a
   rig concern, not an OptiForge one, but it will look like "Forge 1.16.5 does not start on Java 8".
4. **ASM version.** The legacy runtimes ship ASM 6.2 (1.14.4) and 9.1 (1.16.5); OptiForge does not bundle
   ASM. The legacy jar is compiled against 6.2 (done), and the shared code avoids ASM-7+-only API: the
   notable one is that ASM 6's `InsnList` is not `Iterable`, so instruction lists are iterated through
   `toArray()`.
5. **`ClassLoader.getPlatformClassLoader()`** (9+) isolates OptiFine's patcher. The shared code calls it
   reflectively through `J8.platformOrBootstrap()`, which returns `null` (bootstrap, which on Java 8 sees the
   whole `rt.jar`) when the method does not exist.
6. **1.13.2** is a separate workstream: its OptiFine payload is obf-namespace only (§2.3), so it needs the
   obf→srg bridge (class+member remap of the 233 patched classes and 455 own classes) plus the oldest ML.
   Do not fold it into the first legacy artifact; it is also the only `Forge N/A` line in this scope.
7. **`OptiFine_1.17.1_HD_U_G9`-style mapping-generation mismatch** (phase 1 finding) could equally exist for a
   legacy build whose payload references pre-final SRG ids; the `func_/field_` token scan is the first check.

## 5. Reproducible probes (all in `build/phase2/`)

| script / log | what it did |
|---|---|
| `install-lines.ps1` + `install-*.log` | download vanilla json/jar, Forge `--installClient` on JRE 8, merge into the rig (pattern of `tools/legacy-rig-test.ps1`) |
| `inspect-optifine.ps1` + `unpack-OptiFine_*` | zip layout counts, namespace token scan of `srg/` and `patch/srg/`, `buildof.txt` |
| `patch-1.14.4-all.log`, `patch-1.15.2-all.log`, `patch-1.13.2-all.log`, `probe16/patch-16.log`, `probe16/*.log` | `PatchApply --all` / `--class` MD5 results and patched-class namespace |
| `scan-java8.ps1` + `javac-release8.log` | `javac --release 8` over the whole tree (217 syntax errors, 13 files) |
| `scan-java8-api.ps1` + `java8-api-counts.tsv` / `java8-api-hits.tsv` | Java-9+ API and language-feature inventory |
| `probe-legacy/` | ML 2.1.1/4.1.0/8.1.3/10 compile + `ServiceLoader` probes on JRE 1.8 (the evidence in §3.1) |
| `ModDirTransformerDiscoverer-*.class` | extracted from the Forge jars to check the mods-directory discovery mechanism |

No Minecraft client or server was launched for this document.

## 6. Implementation (this pass)

Status: **implemented, built and offline-verified**; no game launch was performed (owned by the Lead).
Two artifacts now come from the same shared sources:

- `build/libs/OptiForge-<v>.jar` - Java 17, ModLauncher 10, entry `OptiForgeService` (unchanged behaviour);
- `build/libs-oldml/OptiForge-<v>-oldml.jar` - Java 8 (class file 52), ModLauncher 4.1.0-compatible,
  entry `kynarain.cn.optiforge.oldml.OldModLauncherService`, targeting **1.14.4 / 1.15.2 / 1.16.5**.

### 6.1 Build/check evidence (measured on this machine)

| command | result |
|---|---|
| `pwsh -File tools/build.ps1` | `built build\libs\OptiForge-0.14.0.jar` (699 322 bytes, 28 sources) |
| `pwsh -File tools/check.ps1` | `RESULT : OK - 1 service(s) declared and loadable` |
| `pwsh -File tools/build-oldml.ps1` | `built build\libs-oldml\OptiForge-0.14.0-oldml.jar` (118 681 bytes; 28 sources compiled, 39 class entries) |
| `pwsh -File tools/check-oldml.ps1` | `RESULT : OK - 39 classes major 52, service loads on Java 8 against ML 4.1.0/5.1.2/8.1.3, Java-17 build green` |
| `build/phase2/LegacyTomlProbe.java` (JRE 1.8) | `RESULT : OK - legacy ranges [1.13,)/[14,), @Mod class major 52` (staged `mods.toml` + generated `@Mod` class) |
| launch simulation, `src/oldml/.../LaunchProbe.java` on JRE 1.8 (§6.5, §6.6) | 1.16.5: `base jar: 1.16.5.jar (confirmed by patch/srg/… MD5)`, `namespace decided by: …forge-1.16.5-36.2.39-client.jar (launch jars)`, `access transformers: 329 grant(s) from 1 config file(s) of the running line`, `re-applied to 18 class(es)`, `remap: disabled (runtime=MCP, mapping=none)`, `RESULT : OK - pack prepared, 311 patched classes, 560 own classes`; 1.14.4: 180 grants/17 classes, 246/466; 1.15.2: 182 grants/16 classes, 299/517 |

`check-oldml.ps1` verifies: every entry is class-file major 52, no `module-info`/`META-INF/versions`, the
service file names the oldml entry, `ServiceLoader` instantiates it on **JRE 1.8.0_503** with each
ModLauncher of the range and the old abstract `beginScanning`/`transformers` are callable, and the Java-17
build still succeeds.

Offline full-pipeline probe on **JRE 1.8** (`PackBuild`, no game; ASM 6.2 from the 1.14.4 runtime):

| line | OptiFine | base jar | patched | failures | own classes | staged mod jar |
|---|---|---|---|---|---|---|
| 1.16.5-36.2.39 | `OptiFine_1.16.5_HD_U_G8.jar` | `versions\1.16.5\1.16.5.jar` | **311** | 0 | 560 | `OptiForge-0.14.0-classes.jar` (560 classes, 111 assets) |
| 1.14.4-28.2.26 | `OptiFine_1.14.4_HD_U_G5.jar` | `versions\1.14.4\1.14.4.jar` | **246** | 0 | 466 | 466 classes, 110 assets |

Both report `remap off [0 renamed, 0 unmapped]`, `runtime namespace: MCP/SRG func_/field_ names`, and
`RESULT : OK (MCP/SRG namespace, no remap expected)` - i.e. the payload already matches the runtime.

### 6.2 Files changed for review

New shared classes in `src/main/java/kynarain/cn/optiforge/`: `J8.java` (Java-8 shims), `Bootstrap.java`
(pack orchestration, moved out of `OptiForgeService` so both entries share it), `TargetFactory.java`,
`ModernTargets.java`. `OptiForgeService.java` was reduced to the Java-17 entry (delegates to `Bootstrap`).
Mechanical Java-8 refactors: `CompatFixes`, `Diagnose`, `ForgeAccessTransformers`, `ForgeInterfaces`,
`ForgeMatrix`, `ForgeRefs`, `LaunchPaths`, `Notice`, `OptifineClassTransformer`, `OptifineJar`,
`OptifinePack`, `OptifinePatcher`, `OwnRefs`, `PackBuild`, `PatchApply`, `PatchPlan`, `RuntimeInfo`,
`SrgRemap`, `SuperInterfaceFixer`.
New oldml sources in `src/oldml/java/kynarain/cn/optiforge/oldml/`: `OldModLauncherService.java`,
`OldMlTargets.java`, `OldMlProbe.java`. New scripts: `tools/build-oldml.ps1`, `tools/check-oldml.ps1`.

Three-way namespace: `OptifinePack.detectRuntimeNamespace` now returns `OFFICIAL` / `SRG` (`m_/f_`) /
`MCP` (`func_/field_`) / `UNKNOWN`; `OptifinePatcher.memberTokens` and `PackBuild` count all three
spellings. `remapEnabled` stays true only for `OFFICIAL`, so the legacy lines never remap. On legacy,
`OptifinePack.findBaseJar` tries the **vanilla** `versions\<mc>\<mc>.jar` first (measured: the Forge client
jar cannot be patched because `patch2.cfg` resolves obf entry names). The staged `mods.toml` gets
`minecraft [1.13,)` + `loaderVersion [14,)` from `Bootstrap.setLegacy(true)` (the ranges OptiFine's own
legacy toml declares), and the generated `@Mod` class is written as class-file 52 on legacy.

### 6.3 What to test in a real launch (per line)

Common for all three lines:

- JDK **8** (`C:\Program Files\Java\jre1.8.0_503\bin\java.exe`) - strip the profile's `--add-exports` /
  `--add-opens` JVM args, JVM 8 rejects them;
- `mods/` = `OptiForge-0.14.0-oldml.jar` only (no `OptiForge-0.14.0.jar`, and **no OptiFine jar** in mods/);
- `optiforge/` = the matching OptiFine jar, named exactly as downloaded;
- pass `-Doptiforge.libraries=I:\mods\optifineoforge-test\libraries` (AT replay and the interface index scan
  it; the pack itself works without it but Forge's access transformers would be missed);
- a previous `optiforge/pack/` may be deleted to force a rebuild; with a warm cache the `[OptiForge]` lines
  report `pack cache hit`.

| line | version id | OptiFine jar in `optiforge/` | expected `[OptiForge]` lines (order-insensitive) |
|---|---|---|---|
| 1.16.5 | `1.16.5-forge-36.2.39` | `OptiFine_1.16.5_HD_U_G8.jar` | `OptiForge 0.14.0`; `artifact: oldml (Java 8, ModLauncher 2-8)`; `base jar: 1.16.5.jar (confirmed by patch/srg/… MD5)`; `runtime namespace: MCP/SRG func_/field_ names (forge-1.16.5-36.2.39-client.jar via net/minecraft/crash/CrashReport.class, 29 mcp / 0 srg / 0 plain)`; `namespace decided by: …forge-1.16.5-36.2.39-client.jar (launch jars)`; `remap: disabled (runtime=MCP, mapping=none)`; `access transformers: 329 grant(s) from 1 config file(s) of the running line (1.16.5-36.2.39)`; `access transformers re-applied to 18 class(es)`; `pack v7-…: 311 patched classes (failures 0), 560 own classes, remap off`; `library mod: OptiForge-0.14.0-classes.jar (560 classes, 111 assets)`; `onLoad (oldml/Java 8), alongside [optiforge, mixin, OptiFine, fml]`; `oldml artifact active: 311 target classes, 560 own classes` |
| 1.14.4 | `1.14.4-forge-28.2.26` | `OptiFine_1.14.4_HD_U_G5.jar` | same shape: `base jar: 1.14.4.jar`, namespace `21 mcp / 0 srg / 7 plain` decided by `forge-1.14.4-28.2.26-client.jar`, `access transformers: 180 grant(s) from 1 config file(s) of the running line`, `re-applied to 17 class(es)`, `246 patched classes`, `466 own classes`, `110 assets`, `runtime=MCP` |
| 1.15.2 | `1.15.2-forge-31.2.57` | `OptiFine_1.15.2_HD_U_G6.jar` | same shape: `base jar: 1.15.2.jar`, namespace `22 mcp / 0 srg / 7 plain` decided by `forge-1.15.2-31.2.57-client.jar`, `access transformers: 182 grant(s) from 1 config file(s) of the running line`, `re-applied to 16 class(es)`, `299 patched classes`, `517 own classes`, `runtime=MCP` |

Success evidence: `Setting user`, `Sound engine started`, a normal `[OptiFine] Forge version: 36.2.39`
(or 28.2.26 / 31.2.57), no `crash-reports/`, stderr empty. `<game>/optiforge/diagnose.txt` carries the same
numbers for the report. Kill switches if a class misbehaves: `-Doptiforge.skipClasses=a.b.C` (leave that
class to Forge) and `-Doptiforge.deferVote=true` (change transformer vote order).

Layout sanity to check first on failure: `mods/OptiForge-0.14.0-classes.jar` must exist after launch (it is
staged at runtime; do not pre-place it) and must contain `optiforge/generated/GeneratedMod.class`; `mods.toml`
must be accepted by FML (minecraft range `[1.13,)`, loaderVersion `[14,)`).

### 6.4 Known gaps after this pass

- The Forge-patch merge question (§4.6.1) is still unproven; if a launch fails with `NoSuchMethodError` for a
  Forge-added member, that is the cause and `-Doptiforge.skipClasses=` can narrow the class.
- The legacy artifact does not implement `additionalClassesLocator`; OptiFine's own classes are delivered by
  the staged javafml mod jar (the 1.20.1 path). If a `NoClassDefFoundError: net/optifine/...` appears before
  the mod layer is up, that is the first thing to revisit (ML 4/5/8 do have the API).
- 1.13.2 is not supported by this artifact (§2.3) and its OptiFine build has no srg payload.
- `mods.toml` for legacy keeps `credits`/`logoFile`/`displayURL`/`issueTrackerURL`: 1.14.4's `ModInfo` only
  parses `logoFile` of those; the rest are read from the config element by the GUI. If FML 28 rejects the
  file, drop those keys in `OptifinePack.modToml()` for legacy.
### 6.5 Bug fixes after the first real 1.16.5 launch

The Lead's first real launch surfaced two defects; both are fixed and reproduced offline on JRE 1.8 with
`src/oldml/java/.../LaunchProbe.java` (a launch simulation, no game).

**Defect 1 - `-Doptiforge.vanilla` appeared to be ignored.**
Root cause was not the property: `findBaseJar` validated every candidate by applying
`patch/srg/net/minecraft/CrashReport.class.xdelta`, and **the 1.14-1.16 payloads contain no CrashReport
entry at all** (the `PackBuild` probe already printed `net.minecraft.CrashReport : not in pack`). Every
candidate - including the right vanilla jar - was rejected, then the fallback message said "could not find
the vanilla client jar".
Fix: `findBaseJar` now (a) searches in the documented order - `-Doptiforge.vanilla`, then
`<gameDir>/versions/<mc>/<mc>.jar`, then the same under 4 ancestors, every library root's parent (a shared rig
keeps `versions/` next to `libraries/`), `~/.minecraft`, then every `java.class.path` entry named
`<mc>.jar`, then the newest non-Forge profile - and (b) validates a candidate with **up to 8 payload entries
that actually exist in the OptiFine jar** (small ones first, `net/minecraft/CrashReport` first only where the
payload has it), accepting on the first MD5 match and logging which entry confirmed it.

**Defect 2 - namespace detection read a jar from another line.**
Two causes: `LaunchPaths.jars(...)` iterated every Forge version under `-Doptiforge.libraries`; and the
detector only looked for `net/minecraft/CrashReport.class`, while **1.13-1.16 name it
`net/minecraft/crash/CrashReport`** (lower-case package), so even the right jar was skipped.
Fix: new `LaunchPaths.runtimeJars(gameDir, mcVersion, forgeVersion)` keeps only the class/module-path
entries that belong to the running line plus the library jars of exactly
`net/minecraftforge/forge/<mc>-<forge>/` and `net/minecraft/client/<mc>-*/`; it is what
`OptifinePack.detectRuntimeNamespace(info, gameDir, baseJar, notes)` now uses, it checks both CrashReport
package names, falls back to the resolved base jar, and logs `namespace decided by: <path> (launch jars |
base jar fallback)`.

Reproduced (JRE 1.8, decoy `forge-1.17.1-37.0.103-client.jar` on the classpath for the 1.16.5 case):

```
[1.16.5, -Doptiforge.vanilla + -Doptiforge.libraries, decoy 1.17.1 jar on cp]
base jar: 1.16.5.jar (confirmed by patch/srg/net/minecraft/state/Property$1.class MD5)
runtime namespace: MCP/SRG func_/field_ names (forge-1.16.5-36.2.39-client.jar via
    net/minecraft/crash/CrashReport.class, 29 mcp / 0 srg / 0 plain) - matches the payload, no remap
namespace decided by: I:\mods\optifineoforge-test\libraries\net\minecraftforge\forge\1.16.5-36.2.39\forge-1.16.5-36.2.39-client.jar (launch jars)
remap: disabled (runtime=MCP, mapping=none)
pack v6-…: 311 patched classes (failures 0), 560 own classes, remap off [0 renamed, 0 unmapped], base 1.16.5.jar
RESULT   : OK - pack prepared, 311 patched classes, 560 own classes

[1.16.5, no -Doptiforge.vanilla, only -Doptiforge.libraries]
base jar: 1.16.5.jar (confirmed by patch/srg/net/minecraft/state/Property$1.class MD5)   <- found via
    <libraries>/../versions/1.16.5/1.16.5.jar
RESULT   : OK - pack prepared, 311 patched classes, 560 own classes

[1.14.4] base jar: 1.14.4.jar; namespace 21 mcp / 0 srg / 7 plain by forge-1.14.4-28.2.26-client.jar;
         246 patched, 466 own; RESULT OK
[1.15.2] base jar: 1.15.2.jar; namespace 22 mcp / 0 srg / 7 plain by forge-1.15.2-31.2.57-client.jar;
         299 patched, 517 own; RESULT OK
```

When neither a run-line srg jar nor a classifiable base jar is available (e.g. only the obf vanilla jar on
the classpath), the notes now say so explicitly, with the jar count and "base jar … is the vanilla obf form
and cannot be classified - remap stays disabled", which is the safe outcome for an srg payload.

The reproduction gamedirs and probe sources are `build/phase2/oldml-launch-gamedir*/`,
`build/phase2/ListRuntimeJars.java`, `build/phase2/DetectProbe.java` and
`src/oldml/java/kynarain/cn/optiforge/oldml/LaunchProbe.java`; no game was launched.
### 6.6 Bug fix after the 1.16.5 model-bake crash: AT replay read every line's configs

Crash: `IllegalAccessError: tried to access field ModelBakery.field_177606_o from
net.minecraftforge.client.model.ModelLoaderRegistry` at the first bake.
Root cause: the replay collected AT configs with `LaunchPaths.jars(gameDir)`, which - by design - returns
every Forge version under a shared rig. On this machine that was **14 856 grants from 29 config files**
(1.12.2/1.13.2/1.14.4/1.15.2/1.16.5/1.17.1/... all mixed). 1.14.4 and 1.15.2 each declare
`protected ... ModelBakery field_177606_o`, while 1.16.5's own config does not mention it (the field is
public in vanilla/Forge 1.16.5). Applying the other lines' grant narrowed `public` to `protected` on the
class we replace, and Forge's `bakeHelper` then got `IllegalAccessError`.

Fix (shared path, so phase 1 benefits too): `ForgeAccessTransformers.load(...)` takes the running
`mcVersion`/`forgeVersion` and reads configs from **this launch only**:
`LaunchPaths.runtimeJars(gameDir, mc, forge)` (class/module path filtered to the running line + the
`libraries/.../forge/<mc>-<forge>/` jars) plus `<game>/mods/*.jar` (mod ATs use the same
`META-INF/accesstransformer.cfg` format). If none is found it retries the full launch list but keeps only
paths containing `/forge/<tag>/` or `/versions/<tag>/`. The per-class replay itself is the existing
phase-1 logic and was not duplicated: fields, methods, class-level grants, inner classes (`Outer$Inner`)
and the `-f` flag clearing all go through the same `transform()`/`widen()` code; on 1.14-1.16 the member
ids in the cfg are already `func_`/`field_`, and with no remap table the matcher uses them unchanged.
`PACK_VERSION` was bumped `v6` -> `v7` so an existing `optiforge/pack/v6-*` (built with the wrongly
narrowed bytes) cannot be reused.

Numbers (offline JRE 1.8 launch simulation, `LaunchProbe`):

| line | AT grants / config files (before -> after) | classes re-applied | staged `ModelBakery.field_177606_o` | Forge runtime reference |
|---|---|---|---|---|
| 1.16.5 | 14 856 / 29 -> **329 / 1** | 18 | `public static final` | `public static final` (matches) |
| 1.14.4 | same before -> **180 / 1** | 17 | `protected static final` (that line's own grant) | pre-AT client jar shows public; its own config declares protected |
| 1.15.2 | same before -> **182 / 1** | 16 | `protected static final` (that line's own grant) | same |

All 18 classes that both the payload replaces and 1.16.5's AT config targets are touched (202 distinct AT
classes in the config; 18 overlap with the payload), so "every AT grant that targets a replaced class" is
covered. Spot checks on the staged 1.16.5 classes: `GameSettings field_74324_K` = `public` with `final`
cleared (`public-f`), `RenderState$TextureState field_228603_R_` = `protected` (`protected-f`, inner
class), `ModelBakery field_217853_J` = `private` non-final (`private-f`). `javap` on the staged class:

```
public static final net.minecraft.client.renderer.model.BlockModel field_177606_o;
```
### 6.7 1.13.2 support: the obf -> runtime bridge

1.13.2 is a different OptiFine payload generation, so it needed a bridge rather than a new pipeline. What the
build actually carries (measured, `OptiFine_1.13.2_HD_U_G5.jar`): **no** `srg/` or `patch/srg/` at all;
**233** flat `patch/<obf>.class.xdelta` entries; **455** own classes at the jar root under
`net/optifine/**`; own classes and payloads are entirely in the **obfuscated** namespace (e.g.
`net.optifine.BlockPosM extends el`, calls `o()`); 13 root `net/minecraftforge/**` 1.12-style shims and the
`optifine/**` installer classes are present but not used at runtime.

Runtime on Forge 25.0.219 is "official class names + joined member names" (`mcp_config`'s
`config/joined.tsrg`: 4027 classes, 14 542 fields, 26 093 methods; e.g. `el.o() -> Vec3i.func_177958_n`).

What was added (all shared code, everything still `--release 8`, nothing in `src/legacy`):

1. **`ObfNamespaceRemap`** (new shared class): parses `joined.tsrg` and remaps classes, fields, methods and
   descriptors obf -> runtime. It also loads the **vanilla jar hierarchy** so a member referenced through a
   subclass owner (`BlockPos.o()`, declared on `Vec3i`) resolves exactly like the JVM does; that inheritance
   step was required - without it 436 own-class references stayed obfuscated.
2. **`OptifinePatcher.Mode`**: `SRG` (1.14+, `patch/srg/...`, target `srg/<name>.class`) vs `NOTCH` (flat
   `patch/<obf>.class.xdelta`, target `<obf>.class`). `payload()`, `apply()`, `applyWith()`, `expectedMd5()`
   and the MD5 base-jar probe are all mode-aware. Measured: **233/233** flat payloads apply to
   `versions/1.13.2/1.13.2.jar` with matching MD5 (and none apply to a srg-named jar).
3. **`OptifinePack.prepare`** detects the mode, loads `optiforge/mappings/1.13.2-obf-to-srg.tsrg` from the
   artifact, remaps each patched class ("raw" keys become the remapped class names), and remaps the own
   classes from the root `net/optifine/**` prefix instead of `srg/**`. Everything after (collision/AT replay/
   interface strip/compat/staging/cache/mods.toml) is the same 1.14-1.16 code path. `PACK_VERSION` v8
   invalidates any earlier pack cache.
4. **ModLauncher 2.1.1 compatibility**: `OptifineClassTransformer` now takes the class name from the incoming
   `ClassNode` instead of `ITransformerVotingContext.getClassName()` (that method does not exist on ML 2.1.1 -
   its context is an empty marker interface), votes YES when the context cannot name the class, and returns
   early for non-targets. `castVote` reads the name reflectively, so the same class files still behave
   precisely on ML 4/5/8/10.
5. **`tools/build-oldml.ps1`** extracts `config/joined.tsrg` from the rig's 1.13.2 `mcp_config` artifact into
   the jar as `optiforge/mappings/1.13.2-obf-to-srg.tsrg` (1 429 394 bytes plain, ~270 KB inside the jar);
   `-McpConfig13 <zip>` overrides the lookup. `tools/check-oldml.ps1` now probes ML **2.1.1** as well and
   asserts the mapping entry is present.

Offline launch simulation on JRE 1.8 with ML 2.1.1 (`LaunchProbe`, no game):

```
base jar: 1.13.2.jar (confirmed by patch/k$1.class MD5)
runtime namespace: MCP/SRG func_/field_ names (forge-1.13.2-25.0.219-client.jar via
    net/minecraft/crash/CrashReport.class, 21 mcp / 0 srg / 6 plain) - matches the payload, no remap
namespace bridge: obf->runtime map: 4027 classes, 14542 fields, 26093 methods;
    remapped 26987 member ref(s), 24 obf-looking ref(s) not in the table
access transformers: 237 grant(s) from 1 config file(s) of the running line (1.13.2-25.0.219)
access transformers re-applied to 16 class(es)
patched 233 classes, 0 failure(s)
own classes: 455 (root net/optifine, obf->runtime remapped)
library mod: OptiForge-0.14.0-classes.jar (455 classes, 72 assets)
pack v8-…: 233 patched classes (failures 0), 455 own classes, remap off, base 1.13.2.jar
transformer : kynarain.cn.optiforge.OptifineClassTransformer
RESULT   : OK - pack prepared, 233 patched classes, 455 own classes
```

The 24 "obf-looking refs not in the table" are OptiFine **additions** (e.g. `ModelRenderer.id`,
`BufferBuilder.draw`): both are declared by the patched classes we serve, so the reference is correct and
must not be renamed. Staged namespace was checked with `javap`: patched `PotionUtils`/`EntityLiving` carry the
same `func_`/`field_` names as `client-1.13.2-…-srg.jar`, and own `net.optifine.BlockPosM` reads
`extends net.minecraft.util.math.BlockPos` with 10 `func_`/1 `field_` references. The staged jar keeps
`net/optifine/**` paths, `optiforge/generated/GeneratedMod.class` is class-file 52, and `mods.toml` has
`loaderVersion="[14,)"` / minecraft `[1.13,)` (pack.mcmeta format 4).

Expected `[OptiForge]` lines on the Lead's 1.13.2 run are the block above (plus `artifact: oldml (Java 8,
ModLauncher 2-8)` and `onLoad (oldml/Java 8), alongside …`); success = title screen and no crash report.
`net/minecraftforge/**` shims from the OptiFine jar are deliberately not staged (Forge 25 provides its own
classes; OptiFine's own classes have zero references to those package names).
### 6.8 Join-test fixes: wildcard access transformers and OptiFine asset payloads

**AT wildcard lines.** 1.14.4/1.15.2 Forge spell most of their grants explicitly but add
`protected net.minecraft.client.gui.IngameGui *` (and the same shape for a few other classes). The replay
parsed `*` as a literal member name, so only the explicit `func_*` method lines landed on `IngameGui` and
`field_73839_d` kept vanilla `private`; the class was already in the re-applied list (that is why the count
did not change), but the field the HUD needs was not widened. `ForgeIngameGui` then died with
`IllegalAccessError ... IngameGui.field_73839_d` on the first HUD frame.

`ForgeAccessTransformers` now treats a `*` member token as a wildcard grant over every field and method of
the class. Wildcards are applied **widening-only**: Forge's own AT plugin never sees OptiFine's added
members, and narrowing those back to `protected` would break `net.optifine.**` callers, so a member that is
already `public` stays public. Verified on the staged classes (javap):
`protected final net.minecraft.client.Minecraft field_73839_d;` on both 1.14.4 and 1.15.2.

Touched classes (new note lists them; counts unchanged because `IngameGui` already matched explicit lines):

- 1.14.4 (17): `IngameGui`, `DebugOverlayGui`, `ParticleManager$IParticleMetaFactory`, `ParticleManager`,
  `BufferBuilder`, `GameRenderer`, `ItemRenderer`, `EntityRendererManager`, `entity.ItemRenderer`,
  `LivingRenderer`, `ItemOverrideList`, `ModelBakery`, `TextureAtlasSprite`, `TileEntityRendererDispatcher`,
  `MobEntity`, `ResourcePack`, `ThreadTaskExecutor`.
- 1.15.2 (16): same list minus `BufferBuilder` and `TextureAtlasSprite`, plus `GameSettings` (wildcard lines
  are what changed the field flags, the class list itself is unchanged).
- 1.16.5 (18) and 1.13.2 (16) were re-checked after the change: counts unchanged, no regressions.

**OptiFine asset payloads.** The jars carry no top-level `mcpatcher/` or plain top-level texture roots
(measured: 0 entries for 1.13.2/1.14.4/1.15.2/1.16.5; the only top-level resource root is `assets/**`,
111 plain entries on 1.16.5). The files OptiFine loads as `minecraft:optifine/ctm/default/...png` are
`patch/assets/**` payload entries: xdelta deltas against the **vanilla** assets. Staging only `assets/**`
therefore produced "Using missing texture" for every CTM default texture.

`OptifinePack` now materialises every `patch/assets/**.xdelta` once per pack cache (provider = the
`client-<mc>-…-extra.jar` of the running line, found through `LaunchPaths.assetsJar`), stores the result in
`<game>/optiforge/pack/<key>/assets/`, and stages those files into the javafml mod jar next to the plain
`assets/**` entries. Measured: 1.13.2 = 815 files, 1.14.4/1.15.2/1.16.5 = 1621 files, 0 failures,
`assets/minecraft/optifine/ctm/default/10_glass_purple/44.png` present with a valid PNG signature; the staged
jar grows to ~1.6-2.1 MB and the second launch reuses the cache (1 s). 1.12.2 (phase 3, different artifact)
uses the same payload mechanism with `mcpatcher/` targets; the shared staging code handles those targets too
if that artifact routes through this `OptifinePack`.
### 6.9 1.13.2 join test: interface methods of own classes

First resource reload on 1.13.2 died with
`AbstractMethodError: net/optifine/util/TextureUtils$1.func_195410_a(IResourceManager)V is abstract`.

Root cause: the obf -> runtime table is keyed by `(declaring class, obf name, descriptor)`, and the remapper
resolves through the obf hierarchy of the vanilla jar. OptiFine's own classes are **not** in that jar, so a
lookup starting at `net.optifine.util.TextureUtils$1` found neither the class nor its interfaces: its
`a(IResourceManager)V` stayed `a(...)`, while the interface side became `func_195410_a(...)`, so the anonymous
class no longer implemented `IResourceManagerReloadListener.func_195410_a`.

Fix: before remapping a class, its own hierarchy (`superName`, `interfaces`) is registered into the remap's
hierarchy map. The resolver can then walk own class -> vanilla interface (or vanilla superclass) and pick the
interface's runtime id, exactly how the JVM resolves the implementation. This also fixes overrides of vanilla
superclass methods declared in own classes.

Assertion at pack build (`own class interface assertions: N mismatch(es)`): for every declared own-class method
that overrides an interface method - including interfaces inherited through the vanilla superclass chain - the
expected runtime id is computed directly from `joined.tsrg`'s interface rows, independent of the resolver, and
checked against the remapped class. Any mismatch is printed with class, original method and expected ids.
Measured after the fix: **0 mismatches** across all 455 own classes.

Verified offline on the staged jar itself (`net/optifine/util/TextureUtils$1.class` extracted from
`mods/OptiForge-0.14.0-classes.jar`):

```
final class net.optifine.util.TextureUtils$1 implements net.minecraft.resources.IResourceManagerReloadListener {
  net.optifine.util.TextureUtils$1();
  public void func_195410_a(net.minecraft.resources.IResourceManager);
}
```

and the method body calls `net.optifine.util.TextureUtils.resourcesReloaded(IResourceManager)V`. Pack numbers
are unchanged: 233 patched classes, 455 own classes, 815 payload assets, AT re-applied to 16 classes.