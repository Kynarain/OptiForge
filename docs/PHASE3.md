# PHASE3 — Minecraft 1.12.2 及更早：launchwrapper + FML

本文是阶段 3 的实测记录与设计。**所有数字都是本机离线跑出来的**，证据文件在 `build/phase3/`
（探针源码 `build/phase3/probe/*.java`，以及本文 §8 的 M1 实现）。凡未实测的句子都标"未验证"。
当前现代线（1.13+）的实现见 [DESIGN.md](DESIGN.md) / [STATE.md](STATE.md)，本文不重复。

## 0. 结论先行

| 问题 | 实测答案 |
|---|---|
| ≤1.12.2 的 Forge 怎么加载东西 | `mainClass = net.minecraft.launchwrapper.Launch`，`--tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker`；**没有** ModLauncher，`ITransformationService` 永远不会被加载 |
| OptiFine 1.12.2 的载荷形状 | 扁平 `patch/<obf>.class.xdelta`（248 个类）+ `patch/assets/**`（1630）；**没有** `patch/srg/`、`patch/notch/`、`srg/`、`notch/`（这些是 1.13+ 的布局） |
| 载荷的基包 | **原版 1.12.2 混淆客户端 jar**。逐类 applyPatch 后 `patch/<name>.class.md5` **248/248 命中** |
| 载荷产物的命名空间 | **obf 类名 + 原版 obf 成员名 + Forge 补的 MCP 名成员**（`awu` 里既有 `a`..`e`，也有 `createState`/`IUnlistedProperty`/`Optional`） |
| Forge 1.12.2 运行时的命名空间 | **与载荷相同**：obf 类名、原版 obf 成员名、Forge 后加成员用 MCP 名。所以 **不需要 SrgRemap / remap 表** |
| 真正的风险点 | 载荷**整类替换**，Forge 在该类上加的成员被丢掉。248 个补丁类里 **16 个类缺 Forge 的成员**（最坏 `GuiVideoSettings` 7 个。只比名字+描述符；`access` 标志差异由 Forge 的 AT 在运行时补上，不算缺失） |
| 交付形状 | **第二个 jar，全部类 `--release 8`**，`FMLCorePlugin` 入口（`IFMLLoadingPlugin`），不能复用 17 的 jar，也不能做 MRJAR |

## 1. 实测：OptiFine 1.12.2 G5 的载荷

下载：`https://bmclapi2.bangbang93.com/optifine/1.12.2/HD_U/G5`
→ `build/optifine-pristine/OptiFine_1.12.2_HD_U_G5.jar`（2 669 107 B，2734 entries，zip 完整）。

顶层条目（`System.IO.Compression` 量出来的）：

| 条目 | 数量 / 大小 | 说明 |
|---|---|---|
| `patch/` | 2126 | 其中 `*.class.xdelta` 496 个（248 类 × xdelta+md5），`patch/assets/**` 1630 |
| `net/` | 442 | 432 个 `net/optifine/**` + 10 个 `net/minecraftforge/**` stub |
| `assets/` | 72 条 | OptiFine 自己的资源 |
| `optifine/` | 49 | launcher 侧类：`OptiFineForgeTweaker`、`OptiFineTweaker`、`OptiFineClassTransformer`、`Patcher`、`Installer`、`AccessFixer`、`xdelta/**`… |
| 根 | `Config.class`、`awu$Builder.class`、`launchwrapper-of-2.2.jar`(15 900 B)、`patch.cfg`、`buildof.txt`、`files.txt`、`changelog.txt` | |

`META-INF/MANIFEST.MF`（全文 122 B）：

```
Manifest-Version: 1.0
Main-Class: optifine.InstallerFrame
TweakClass: optifine.OptiFineForgeTweaker
TweakOrder: -1000
```

**没有** `FMLCorePlugin`、`ModType`、`mods.toml`、`mcmod.info`。`buildof.txt = 20210124-142939`，
`patch.cfg` 第一条是 `[a-z$0-9]+.class=*`（决定 patch base 的资源名 = 类自身名字）。

### 1.1 运行时装配（`javap -p -c`，证据 `build/phase3/javap-*.txt`）

- `optifine.OptiFineForgeTweaker.injectIntoClassLoader(LaunchClassLoader)`：
  `LaunchUtils.isForgeServer()` 为真时直接返回（服务端不打补丁）；否则
  `classLoader.registerTransformer("optifine.OptiFineClassTransformer")`。
- `optifine.OptiFineClassTransformer.transform(name, transformedName, basicClass)`：
  **完全忽略入参 `basicClass`**。它用 `name + ".class"` 去 `getOptiFineResource`：
  1. 先查 OptiFine zip 里同名条目（所以根目录那两个类优先）；
  2. 否则 `patch/<name>.xdelta` + `patch/<name>.md5`，走 `Patcher.applyPatch`；
  3. 校验失败只打印并返回 `null`，于是落回 `basicClass`。
- `optifine.Patcher.applyPatch(name, delta, patterns, patchMap, provider)`（字节码确认）：
  `getPatchBase(name, patterns, patchMap)` → `provider.getResourceStream(base)` →
  `new optifine.xdelta.GDiffPatcher(baseBytes, delta, out)`。`provider` 在 transformer 里传的是
  `this`（`getClass().getResourceAsStream("/" + base)`），也就是**类路径上的原版 jar 原始类字节**。
  **结论：载荷的 base 永远是原版混淆类，与 Forge 已经打到入参上的 binpatch 无关。**

### 1.2 离线复算（248/248）

```
java -cp OptiFine_1.12.2_HD_U_G5.jar optifine.Patcher 1.12.2.jar OptiFine.jar out.jar
# exit 0；out.jar 2457427 B，1671 条（248 个补丁类 + 原 jar 自带类/资源）
java -cp build\phase3\probe Md5VerifyProbe <OptiFine.jar> <1.12.2.jar>
# flat patch entries: 248 / md5 OK: 248 / mismatch: 0 / failure: 0
```

产物命名空间（`javap` 对比原版与产物）：

| 类 | 原版 | 载荷产物 |
|---|---|---|
| `awu`（BlockStateContainer） | 类名 `awu`，成员 `a b c d e` | 类名 **`awu`**，成员 `a b c d e` **+** `createState(...IUnlistedProperty...)`、`awu(aow, axj[], ImmutableMap...)`（Forge 的成员，MCP 名） |

`net/optifine/**` 432 个类里 `func_`/`field_` 记号计数为 **0** → 自有类已经是运行时命名空间，
可以直接交付，不需要 `SrgRemap`（对照：1.21 的 SRG 载荷有 2818 个记号）。

顺带确认：`optifine/AccessFixer.fixMemberAccess(ClassNode, ClassNode)` 是 OptiFine 自己的"把另一份类
的访问标志并回来"的工具（建包期用），与我们现代线的 `mergeInPlaceAdditions` 是同一类东西。

## 2. 实测：Forge 1.12.2-14.23.5.2860 的运行时

安装（installer 的 profile/json 存在 `build/phase3/forge-install-1.12.2/`，客户端装到
`build/phase3/forge-install-1.12.2/mc/`，日志 `install.log`）：

- 安装器从 bmclapi 镜像取（`maven.minecraftforge.net` 上 curl 会挂）：4 681 950 B，353 entries，
  `Main-Class: net.minecraftforge.installer.SimpleInstaller`，`spec: 0`、`processors: []`；
- 用 **JRE 8**（`C:\Program Files\Java\jre1.8.0_503`）跑 `--installClient <scratch>`（先放
  `versions/1.12.2/1.12.2.{json,jar}` 与 `launcher_profiles.json`）→ exit 0，
  产出 `versions/1.12.2-forge-14.23.5.2860/*.json` + `libraries/net/minecraftforge/forge/**`；
- `version.json`：`mainClass = net.minecraft.launchwrapper.Launch`，
  `minecraftArguments = ... --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker`，
  `inheritsFrom = 1.12.2`。

Forge universal jar（`libraries/net/minecraftforge/forge/1.12.2-14.23.5.2860/…jar`，4 466 148 B）：

- `binpatches.pack.lzma`（1 738 083 B）= **LZMA(Pack200)**；`unpack200 -r` 后是
  `binpatch/client/<MCP 名>.binpatch` 2308 条；
- `deobfuscation_data-1.12.2.lzma`（329 227 B，LZMA 解出 3 642 383 B 文本）：
  `CL: <notch> <MCP>`、`FD: <notch>/<obf> <MCP>/<MCP 名或 field_xxx>`、`MD:` —— 即
  **notch → MCP/SRG** 的映射表；
- 每个 `.binpatch` 是 `DataInput`：`name, sourceClassName, targetClassName, existsAtTarget,
  inputChecksum(仅 exists 时), patchLength, patchBytes`；`source` = notch（如 `ns`），
  `target` = MCP（`net.minecraft.advancements.AdvancementManager`）。

**离线把 binpatch 打到原版类上**（`build/phase3/probe/BinPatchProbe.java`，用 FML 自己的
`net.minecraftforge.fml.repackage.com.nothome.delta.GDiffPatcher`）：

```
patched awu -> net.minecraft.block.state.BlockStateContainer bytes=8001 (vanilla 6783)
javap awu-forge.class  →  class awu   （内部名仍是 notch！成员 = a b c d e + createState …）
```

所以 **Forge 1.12.2 运行时 = obf 类名 + 原版 obf 成员名 + Forge 后加的 MCP 名成员**；
`targetClassName` 只用于 `ClassPatchManager` 的"patch 打错类"告警。`ClassPatchManager.applyPatch`
对 `adler32(input)` 与 `inputChecksum` 做校验，不符时 **fatal + `System.exit(1)`**
（`-Dfml.ignorePatchDiscrepancies=true` 可降级为"跳过该补丁"）。

### 2.1 transformer 注册顺序（这是整条线的硬约束）

`javap` 读了 `Launch` / `LaunchClassLoader` / `CoreModManager` / `FMLTweaker` / `FMLDeobfTweaker` /
`FMLInjectionAndSortingTweaker` / `FMLLaunchHandler`，拼出来的顺序：

1. launchwrapper 1.12 的 `Launch` 有个 worklist：`TweakClasses`/`Tweaks`。`FMLTweaker` 先被实例化并
   `injectIntoClassLoader`；
2. `FMLTweaker.injectIntoClassLoader` → `FMLLaunchHandler.configureForClientLaunch` →
   `CoreModManager.handleLaunch`：这里 **第一个** `registerTransformer("…PatchingTransformer")`，
   然后 load root plugins（`net.minecraftforge.fml.relauncher.FMLCorePlugin` 与
   `net.minecraftforge.classloading.FMLForgePlugin`），再扫 `mods/` 的
   `TweakClass`/`TweakOrder`/`FMLCorePlugin`（OptiFine 的 `-1000` 就在这里被收进来），
   最后 `appendCoreMods()` → `injectTransformers` 把 `FMLDeobfTweaker` 排到 `tweakSorting = 1000`；
3. `FMLInjectionAndSortingTweaker.acceptOptions` 调 `CoreModManager.sortTweakList()`：
   按 `MIN_VALUE`(排序 tweaker) / `-1000`(OptiFine) / `sortIndex`(coremod 插件，默认 **1000**) 升序；
4. `FMLDeobfTweaker.injectIntoClassLoader` 注册 `DeobfuscationTransformer`、各
   `AccessTransformer`/`ModAccessTransformer`、`ItemStack/ItemBlock/PotionEffect` transformer。

于是游戏类的实际链路是：

```
PatchingTransformer → OptiFineClassTransformer(若在 mods/) → root plugins/自定义 coremod → FMLDeobfTweaker(Deobf + AT + Item*)
```

两条推论（设计基点）：

1. 我们的 transformer 只要**不是**在 FMLTweaker 之前注入，就必然排在 `PatchingTransformer` 之后
   → 不会触发 §2 的 adler32 `System.exit(1)`；反过来，**任何把已补丁类塞进 `mods/` jar 的做法都会踩**
   （`LaunchClassLoader` 会从 mods jar 找到 `awu.class` 并走整条链，`PatchingTransformer` 收到非原版
   字节 → fatal）。
2. 把我们的 transformer 放在 `FMLDeobfTweaker` **之前**，Forge 的 AT/ModAccessTransformer 就会施加到
   我们的产物上（和 OptiFine 自己同位置）——这是"不需要重放 AT"的前提；顺序要用一次真机日志确认。

## 3. 载荷 vs Forge 运行时的成员漂移（实测）

`build/phase3/probe/ClassDriftProbe.java`（离线）：对 248 个补丁类，用 FML 的 GDiff 把 binpatch 打到
原版类上，再与 OptiFine 载荷产物做集合 diff（Forge 2860，client 侧补丁；只比"名字 + 描述符"）：

| 项 | 数量 |
|---|---|
| 载荷根类（在原版里有对照） | 248 |
| 成员集合与 Forge 运行时一致 | 93 |
| **Forge 有、载荷缺成员** | **16** |
| 载荷有、Forge 缺成员（OptiFine 自己加的） | 50 |
| Forge 没有 binpatch（无需处理） | 103 |

缺得最多的是 `bls`(GuiVideoSettings) 7、`buy$a` 5、`cdp` 5、`bvo$c` 4、`bvo` 2、`bvx` 2、`axp` 1。

（更早一版把 `access` 标志也算进成员集合，得到的是 36 —— 那部分是 AT 级别的差异：Forge 的
`AccessTransformer` 在 `FMLDeobfTweaker` 里注册、在我们之后运行，会照常作用到我们交付的类上
（§2.1），所以不需要并集。真正的"成员不存在"才是失败面。）

矩阵数据对得上：`OptiFine_1.12.2_HD_U_G5.jar` 官方 Forge 列是 **Forge #2847**，我们装的是 **#2860**
（`data/optifine-forge-matrix.csv`）。这 16 个类就是"钉在旧 Forge 的载荷跑新 Forge"的可量化失败面
——也正是 OptiFine 在 1.12.2 上"通常能用、偶发 `NoSuchMethodError`"的来源。

## 4. 设计

### 4.1 产物形状：第二个 jar，全部 `--release 8`

`OptiForge-<版本>-legacy.jar`，放 `mods/`，只给 ≤1.12.2 用；现代线继续用 `OptiForge-<版本>.jar`。
MANIFEST：

```
Manifest-Version: 1.0
Main-Class: kynarain.cn.optiforge.legacy.LegacyProbe
Implementation-Title: OptiForge (legacy)
Implementation-Version: <版本>
Implementation-Vendor: Kynarain
FMLCorePlugin: kynarain.cn.optiforge.legacy.OptiForgeLegacyCorePlugin
FMLCorePluginContainsFMLMod: false
```

入口 = `IFMLLoadingPlugin`（不是 TweakClass）：

- `@MCVersion("[1.8,1.12.2]")`（只做信息提示）、显式 `@SortingIndex(1000)`、`@Name("OptiForge (legacy)")`；
- `getASMTransformerClass()` → `…legacy.LegacyTransformer`（无条件返回；transformer 在没有 pack 时是
  no-op，这样 FML 先调 `injectData` 还是先调它都不影响行为）；
- `getModContainerClass()` / `getSetupClass()` / `getAccessTransformerClass()` → null；
- `injectData(Map)` → 拿 `mcLocation`，在**任何游戏类加载之前**准备 pack。

**为什么必须是独立 jar（不能 MRJAR、不能复用 17 的字节码）：**

1. FML 1.12.2 用 **ASM 5.2**（`asm-debug-all-5.2`）解析 `mods/` 里每个 jar 的**每个类**
   （`DeobfuscationTransformer`、`ModAccessTransformer`、`SideTransformer`、`@Mod`/coremod 扫描）；
   class file 61（Java 17）直接抛 `IllegalArgumentException: Unsupported class file major version`。
2. launchwrapper 没有模块路径/`SecureJar`；`LaunchClassLoader` 对所有类一视同仁，MRJAR 的
   `META-INF/versions/` 语义在 `URLClassLoader` 下没有意义。
3. 两条线的 MANIFEST 语义不同（`service` vs `TweakClass`/`FMLCorePlugin`），分开更不容易互相污染。

### 4.2 补丁怎么打、怎么送进类加载器

```
injectData(mcLocation)
 ├─ 找 OptiFine jar：<game>/optiforge/（-Doptiforge.optifine 可覆盖）
 ├─ 找基包：<game>/versions/<mc>/<mc>.jar（-Doptiforge.vanilla 可覆盖；1.12.2 没有 Forge client jar）
 ├─ LegacyPack：逐类 applyPatch（flat 布局）+ md5 校验 → <game>/optiforge/pack/legacy-v1-<key>/
 │   └─ 同时写 mods/OptiForge-<版本>-optifine.jar：net/optifine/** + assets/** + pack.mcmeta
 └─ LegacyTransformer：name → 补丁字节；未命中返回入参
      └─ 命中时做"并集"：把入参（PatchingTransformer 已处理过的 Forge 版本）里
         多出的 字段/方法/接口 并进我们的产物（治 §3 的 16 个类）
```

要点：

- **补丁类绝不能进 `mods/` jar 的 classpath**（否则 `PatchingTransformer` 的 adler32 会 `System.exit(1)`，
  见 §2.1）。游戏类只经 transformer 交付；`mods/` 里那个 jar 只放 OptiFine 自有类 + 资源。
- OptiFine 自有类放在 `mods/` jar 里是最省事的：`LaunchClassLoader` 会从那里加载，
  走过 `PatchingTransformer`(无 binpatch → 原样) → `DeobfuscationTransformer`(notch 名字不在
  MCP/SRG 键里 → 原样) → AT → mod 加载，这正是现在"把 OptiFine jar 丢进 mods/"能工作的原因。
- **资源定位**：OptiFine 的类用 `optifine.OptiFineResourceLocator.getResourceStream` 拿
  `assets/**`，原生由 `OptiFineClassTransformer` 在构造时装好 locator。我们不加载它的 transformer，
  所以用 `java.lang.reflect.Proxy` 实现 `optifine.IOptiFineResourceLocator`（运行时接口，只有一个方法）
  反射塞给 `OptiFineResourceLocator`，后备 = staged jar 的 `assets/`；`pack.mcmeta` 仍然要写
  （1.12.2 的 `ResourcePackLoader` 会读 mod jar 的资源包元数据，文本/语言文件走它）。
- 服务端：1.12.2 的 `OptiFineForgeTweaker` 自己会在 server 上跳过补丁；我们的 coremod 用
  `LaunchUtils` 同款判定（`--accessToken` 缺失且 `--version == UnknownFMLProfile`）识别服务端并跳过，
  否则客户端渲染类在服务端会炸 `NoClassDefFoundError: net/minecraft/client/**`。

### 4.3 与现有代码的复用关系

| 现有类 | 1.12.2 用法 |
|---|---|
| `OptifinePatcher` | **可复用，但要改两处**：(a) 载荷布局参数化——`patch/srg/<logical>.class.xdelta` + base `srg/<logical>.class`（1.13+）vs `patch/<obf>.class.xdelta` + base `<obf>.class`（≤1.12.2）；(b) Java 8 清洁：`ClassLoader.getPlatformClassLoader()`→`new URLClassLoader(urls, null)`、`var`、`readNBytes/readAllBytes`、switch 箭头。它是整条线最该共享的。本轮为了不动 `src/main`，先按同样语义复制到 `LegacyPayload`（同样是反射驱动 `optifine.Patcher.applyPatch`）。 |
| `OptifineJar` | 加扁平布局计数/判定（现在只认 `patch/srg|notch|assets`），否则 1.12.2 的诊断会显示 0 补丁；`files.txt`/`patch.cfg` 等标记也要收。 |
| `SrgRemap` | **不需要**（§1.2：载荷与运行时同命名空间，自有类 0 个 SRG 记号）。 |
| `CollisionFixer` | **不需要**（它治的是"SRG 名与 OptiFine 加的官方名撞名"，只发生在官方名运行时）。 |
| `ForgeAccessTransformers` | **不需要重放**：AT 在 `FMLDeobfTweaker`（sortIndex 1000）注册，我们的 coremod（默认 1000）排在它之前，AT 会打到产物上（§2.1）。真机日志确认后即可定案。 |
| `OptifineClassTransformer.mergeInPlaceAdditions` | 概念复用；本轮在 `LegacyTransformer.merge` 里用 ASM 5.2 实现了同构的"补字段/方法/接口"，但**跳过 `<init>`/`<clinit>`**（现代线踩过：二次 `<clinit>` 会重复初始化）。 |
| `PackBuild` / pack 缓存键 | 复用思路（`sha256(OptiFine)+sha256(base)` + `PACK_VERSION`），加 `legacy-` 前缀，避免与 1.13+ 缓存互串。 |

### 4.4 风险（按严重度）

1. **transformer 顺序**：唯一会让游戏 `System.exit(1)` 的硬失败。§2.1 的结论来自字节码，但仍需一次真机
   日志验证（打印 `Launch.classLoader.getTransformers()`）；`-Doptiforge.legacy.merge=false` 可单独
   关掉并集。
2. **成员漂移**（§3 的 16 个类）：并集能补"成员"，补不了"方法体/AT/事件签名"变化。缓解：按 Forge 版本
   判定（G5 只对 #2847 官方声明），不匹配时把漂移表写进 `diagnose-legacy.txt`，必要时只支持官方 Forge。
3. **与其它 coremod / ASM mod 的叠加**：1.12.2 没有 Mixin，但 coremod 很多；我们"整类替换"会丢它们的
   改动（同 26.x 的已知问题）。并集只能部分缓解，需要 `-Doptiforge.skipClasses=` 一类的开关（未实现）。
4. **更老的线（1.8.9/1.9.2/1.10/1.11.2）**：载荷布局、`patch.cfg`、launchwrapper 版本、
   `TweakOrder`/`Manifest` 属性都可能不同；矩阵里 9 个 N/A 构建（1.10 B6/B7、1.11.2 B5、1.9.2 B1/B3/D7/D8/E3、
   1.12.2 C5）**未验证**。
5. **Java 8 运行时**：本机 JRE 8（`jre1.8.0_503`）可跑安装器和探针；`--release 8` 由 JDK 21 编译。
   本轮没有启动游戏，所以"类在 JRE 8 + Forge 1.12.2 下真实加载"未验证（见 §8）。

## 5. 文件清单（本阶段落的，`src/legacy/java/kynarain/cn/optiforge/legacy/`）

```
OptiForgeLegacyCorePlugin.java  IFMLLoadingPlugin 入口（@MCVersion/@SortingIndex/@Name），injectData 建包
LegacyTransformer.java          IClassTransformer：返回补丁字节；用 ASM 5.2 并入 Forge 后加的字段/方法/接口
LegacyPack.java                 找 jar/基包、建 pack 缓存、写 mods/OptiForge-<ver>-optifine.jar、写 diagnose
LegacyPayload.java              扁平布局判定 + applyPatch + md5（optifine.Patcher 反射驱动，Java 8 版）
LegacyPaths.java                gameDir/optiforge/versions 解析、服务端判定、Forge/lzma/ASM/launchwrapper 定位
LegacyResourceLocator.java      Proxy 实现 optifine.IOptiFineResourceLocator，反射装给 OptiFineResourceLocator
LegacyProbe.java                离线 CLI：不启动游戏地建包 + 漂移 + coremod 加载/并集校验（M1 验收）
tools/build-legacy.ps1          javac --release 8（只编 src/legacy/java）+ 写 MANIFEST + jar
tools/check-legacy.ps1          清单/class-file 52/无 Java 9+ API/探针运行/staged jar 内容/coremod 加载
```

共享改造（后续轮次，先不动现代线）：`OptifinePatcher`（布局参数 + Java 8 清洁）、`OptifineJar`（扁平布局
检测）、从 `OptifineClassTransformer` 抽出 `ClassMerger`。

## 6. 第一个里程碑（不需要 GUI，也不需要启动游戏）——已完成

```
pwsh -File tools/build-legacy.ps1
pwsh -File tools/check-legacy.ps1
```

`tools/check-legacy.ps1` 会：验 MANIFEST（`FMLCorePlugin`/`Main-Class`/Title/Version/Vendor）、jar 里所有
类都是 class-file **52**、没有 `src/main` 的类混入、没有打包 ASM/Forge/launchwrapper、源码里没有 Java 9+
API，然后**用 JRE 8** 跑探针。探针必须打印：

```
layout=flat / patches 248 / md5 248 OK / own 432 / SRG 0 / drift 16 / RESULT: OK
```

第二个里程碑（需要 Lead 跑一次游戏）：干净 `mods/`（只放 legacy jar）+ OptiFine 放
`<game>/optiforge/`，Forge 1.12.2-14.23.5.2860 + JRE 8。

## 7. 证据索引（`build/phase3/`）

| 文件 | 内容 |
|---|---|
| `build/optifine-pristine/OptiFine_1.12.2_HD_U_G5.jar` | 原件，2 669 107 B |
| `build/optifine-pristine/1.12.2.jar` | 原版客户端，10 180 113 B（sha1 `0f275bc1…`） |
| `patched-of-1.12.2.jar` | OptiFine 自己的 `Patcher` 离线打出来的 overlay（2 457 427 B） |
| `binpatches.jar` / `binpatches.bin` | `unpack200 -r` 后的 FML 补丁包（2308 条）与中间 Pack200 文件 |
| `deobf-data.bin` | `deobfuscation_data-1.12.2.lzma` 解压后的文本（`CL:/FD:/MD:`） |
| `javap-*.txt` | `Launch`/`LaunchClassLoader`/`CoreModManager`/`FMLTweaker`/`FMLDeobfTweaker`/`ClassPatchManager`/`Patcher`/transformer 的字节码 |
| `probe/BinPatchProbe.java` | 把 FML binpatch 离线打到原版类（证明运行时命名空间） |
| `probe/ClassDriftProbe.java` | 248 个补丁类的"载荷 vs Forge 运行时"成员集合 diff（§3） |
| `probe/Md5VerifyProbe.java` | 248 个扁平补丁逐个 applyPatch + md5 校验 |
| `forge-install-1.12.2/` | installer、`install_profile.json`/`version.json`、装好的 `mc/` 客户端 |
| `packtest/` | 探针产出的 pack 缓存 + `mods/OptiForge-<ver>-optifine.jar` |

## 8. 本轮落地状态（M1）与未验证项

产物：`build/libs-legacy/OptiForge-<版本>-legacy.jar`（本轮 0.14.0，**40 334 B**），15 个类，全部
class-file 52，MANIFEST 带 `FMLCorePlugin`。`tools/build-legacy.ps1` 只编 `src/legacy/java`；
`src/main/**` 未改动；`gradle.properties`、`build/libs` 未改动。

并集实测细节（`build/phase3/probe/DriftMembersProbe.java`）：16 个漂移类里，被并入的成员绝大多数是
实例字段/方法或 `access$N` 合成方法；**唯一的静态字段**是 `GuiVideoSettings.i`
（`private static final GameSettings$Options[]`），因为不并 `<clinit>` 它只会保留默认值 null —— 这是本轮
已知的窄风险。运行期开关：`-Doptiforge.legacy.merge=false`（整体关并集）、
`-Doptiforge.legacy.skipClasses=bls`（这一类交回 Forge 自己的版本）、
`-Doptiforge.optifine=` / `-Doptiforge.vanilla=`（jar 路径覆盖）。

探针实测（JRE 8，真实 jar）：

```
optifine jar  : OptiFine_1.12.2_HD_U_G5.jar (2669107 B, sha1 ca3aea3a09ce)
base jar      : forge-install-1.12.2/mc/versions/1.12.2/1.12.2.jar (10180113 B, sha1 0f275bc1547d)
forge jar     : forge-1.12.2-14.23.5.2860.jar (4466148 B, sha1 029250575d3a)
payload       : patches=248 md5_ok=248 mismatch=0 failure=0 packed_bytes=1201154
drift         : 16 classes (93 identical, 50 payload-only, 103 without binpatch)
staged jar    : mods/OptiForge-0.14.0-optifine.jar (1212578 B, 432 net/optifine + assets + pack.mcmeta)
coremod       : OK（plugin 用 Forge 1.12.2 + launchwrapper + ASM 5.2 加载，@MCVersion/@SortingIndex 可读，
                并集在 bls 上补 7 个成员，ASM 5.2 完整解析结果）
layout=flat / patches 248 / md5 248 OK / own 432 / SRG 0 / drift 16 / RESULT: OK
```

**没有验证、需要一次真机启动才能确认的**：

1. FML 1.12.2 确实按 §2.1 的顺序注册 transformer（日志应有 `[OptiForge/legacy] optifine=… base=… mc=1.12.2`
   与 `pack ready`；顺序用 `Launch.classLoader.getTransformers()` 核对）。
2. `Launch.classLoader.addURL(stagedJar)` 在 `injectData` 阶段对 `net/optifine/**` 生效（类能从该 jar 加载）。
3. `LegacyResourceLocator` 的 Proxy 能被 `optifine.OptiFineResourceLocator.setResourceLocator` 接受，
   且 OptiFine 的 Lang/资源路径能读到 staged jar 里的 `assets/**`。
4. 并集后的类在真实类层次下能通过校验（探针只用 ASM 5.2 做了解析/帧完整性检查，没有 JVM 定义这些类）。
5. 服务端判定（`--accessToken` 缺失 + `UnknownFMLProfile`）在 Lead 的启动器下是否成立。
6. `injectData` 里建包的耗时（首次约 1–2 s；缓存目录已写但本轮不读缓存，下次可优化）。
7. 第二次启动时 staged jar 已经躺在 `mods/` 里，FML 的发现流程会把它当普通候选扫一遍（没有 `@Mod`/
   `mcmod.info`，预期只记一行"no mods"日志）。如果它报错，把 staged jar 改写到 `<game>/optiforge/` 下、
   仍用 `Launch.classLoader.addURL` 暴露即可（只改 `LegacyPack` 里的目标路径一行）。
