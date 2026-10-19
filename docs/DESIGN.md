# DESIGN — 为什么 OptiForge 是这个形状

本文只记录**已经核对过**的事实和由它推出的决定，以及尚未验证的部分。凡是没实测支撑的句子都标了
"未验证"。版本与构建的数据在 [VERSIONS.md](VERSIONS.md)，当前进度在 [STATE.md](STATE.md)。

## 1. 目标

让**用户自己下载的** OptiFine jar 在 **Forge** 上跑起来，覆盖三条线：1.20.x、1.21.x、26.x。

判断"需不需要修"的第一依据是 OptiFine 官方下载页的 `Forge` 列：`Forge N/A` 的构建（以及被钉在更老
Forge 上的构建）就是本工程的活。第二依据是第 4 节的实测：**载荷的成员命名空间 与 运行时命名空间是否一致**。

## 2. Forge 侧的机制（已核对源码 + 真机日志）

### 2.1 OptiFine 在 Forge 上不是 mod，是"变换服务"

Forge 的 `ModDirTransformerDiscoverer`（核对分支 `1.21.11` 与 `26.2` 的
`fmlloader/src/main/java/net/minecraftforge/fml/loading/ModDirTransformerDiscoverer.java`）：

- 扫描 `mods/` 一层目录，对每个 `*.jar` 用 `SecureJar.from(path)` 读模块描述符；
- 只要 jar 声明了下列任一 service，就把它**加进变换服务候选**并**排除普通 mod 发现**
  （`allExcluded()`）：
  - `cpw.mods.modlauncher.api.ITransformationService`
  - `net.minecraftforge.forgespi.locating.IModLocator`
  - `net.minecraftforge.forgespi.locating.IDependencyLocator`

OptiFine 的每一份构建都带 `META-INF/services/cpw.mods.modlauncher.api.ITransformationService`
（内容 `optifine.OptiFineTransformationService`），所以它走了这条路。

**三条推论，都改变了实现：**

1. **`mods.toml` 完全不参与**。OptiFine jar 里的 `loaderVersion="[14,)"`、`FMLModType: LIBRARY`
   在 Forge 上没有任何校验作用 —— 所以 `Forge N/A` 不是元数据问题，改元数据不会有任何效果。
2. **本工程同样不写 `mods.toml`、不写 `@Mod`**。要让 Forge 发现 OptiForge，唯一需要的凭据就是同一个
   service 文件。把一个声明了该 service 的 jar 又当 mod 用是不可能的（它会被 `allExcluded()` 排除）。
3. **OptiFine 之后只会走 `OptiFineTransformationService` → `OptiFineTransformer`**，
   而该 transformer 只读 `srg/` 与 `patch/srg/`（常量池里是 `PREFIX_SRG="srg/"`、
   `PREFIX_PATCH_SRG="patch/srg/"`，来自既有调研；`notch/`、`patch/notch/` 属于 installer/LaunchWrapper 路线）。

真机佐证（本机已有 Forge 1.20.1 实例，OptiFine `I6`）：

```
[optifine.OptiFineTransformationService/]: OptiFineTransformationService.onLoad
[optifine.OptiFineTransformationService/]: OptiFine ZIP file: ...\mods\optifine-OptiFine_1.20.1_HD_U_I6.jar
[optifine.OptiFineTransformer/]: Target.PRE_CLASS is available
[optifine.OptiFineTransformationService/]: additionalClassesLocator: [optifine., net.optifine.]
[optifine.OptiFineTransformer/]: Targets: 397
```

那一次启动 `Setting user=1`、`Sound engine started`、`joined=1`，没有 MD5/transform 失败 —— 这就是
"Forge 兼容构建 + 对应 Forge"的参照系。

### 2.2 Forge 版本可以比 OptiFine 声明的更新

上例的 OptiFine `I6` 声明的是 `Forge 47.2.18`，实例跑的是 `47.4.23`，**能进世界**。所以
"Forge 列"是**声明过的那个构建**，不是一个区间；矩阵里 `MISMATCH`（运行中的 Forge 更新）本身不等于坏，
它只是说"这段组合没有官方声明"。诊断报告因此把它与 `INCOMPATIBLE`（官方明说不兼容）分开写。

## 3. 实测：原件 OptiFine 的补丁布局（本工程 `Diagnose` 量出来的）

对**原件**（非经任何工具改写的）jar 逐个读 zip：

| 构建 | MC | `patch/srg` | `patch/notch` | `patch/assets` | `srg/` 自有类（其中 forge） | `notch/` 自有类（其中 forge） |
|---|---|---|---|---|---|---|
| `I6` | 1.20.1 | 412 | 411 | 1621 | 635 (0) | 703 (67) |
| `I7_pre1` | 1.20.2 | 423 | 422 | 1621 | 638 (0) | 706 (67) |
| `I7` | 1.20.4 | 427 | 426 | 1621 | 643 (0) | 711 (67) |
| `J1_pre9` | 1.21 | 440 | 439 | 1613 | 658 (0) | 726 (67) |
| `J1` | 1.21.1 | 425 | 424 | 1613 | 660 (0) | 736 (75) |
| `J9` | 1.21.11 | 568 | 567 | 1613 | 756 (0) | 827 (70) |
| `K1_pre2` | 26.1.2 | 566 | 566 | 1613 | 759 (0) | 829 (70) |
| `K2_pre1` | 26.2 | 562 | 562 | 1613 | 759 (0) | 829 (70) |

再看 `srg/` 里真正**引用** `net/minecraftforge` 的类（按常量池统计，`[CLASS]` 表示硬 `Class` 常量）：

| 构建 | 引用的类数 / 扫描类数 | 硬 `Class` 常量 | 目标 |
|---|---|---|---|
| `I6` / `I7_pre1` / `I7` / `J1_pre9` | 2 / 635–658 | 4 | `client/event/RenderLevelStageEvent`、`…$Stage`、`client/model/ForgeFaceData`、`eventbus/api/Event` |
| `J1` | 3 / 660 | 6 | 上面 4 个 + `client/event/ViewportEvent`、`…$ComputeCameraAngles` |
| `J9` / `K1_pre2` / `K2_pre1` | 3 / 756–759 | 4 | `client/model/ForgeFaceData`、`client/event/ViewportEvent`、`…$ComputeCameraAngles`、`resource/DelegatingPackResources` |

**三个结论（第一条推翻了我最初的猜测）：**

1. **`patch/srg` 里没有任何 `net/minecraftforge` 目标**（全部构建都是 0）。所以 "Forge N/A" 不是
   "xdelta 补丁打不到 Forge 类"，"剔除 Forge 类补丁"这条方案没有对象。
2. `net/minecraftforge` 的自有类只出现在 **`notch/`**（67–75 个），而 ModLauncher 路径只读 `srg/`
   —— 这些类属于 installer/LaunchWrapper 和 vanilla 模式用的垫片，在本条路径上是死重。
3. 真正会把 OptiFine 和某个具体 Forge 绑在一起的，是**极少的硬 `Class` 常量**：2–3 个类、4–6 个类型。

## 4. 根因（M0 实测，2026-10-19）

### 4.1 真机：1.21 的 N/A 构建死在 `CrashReport`，1.21.1 的兼容构建起得来

同一套启动器、同一个 rig，只换版本：

| 组合 | 结果 |
|---|---|
| **Forge 51.0.33 + OptiFine `1.21` `J1_pre9`**（N/A） | `OptiFineTransformationService.onLoad`、`Targets: 440` 都正常，随后死在 `NoSuchMethodError: net.minecraft.CrashReport.forThrowable(Throwable, String)`（`Main.main:184`） |
| **Forge 52.1.20 + OptiFine `1.21.1` `J1`**（声明 52.0.16） | **STARTED**：`Setting user`、`Sound engine started`、169 条 `[OptiFine]`、无崩溃报告 |
| 本工程的诊断服务（两次启动都在） | 都被 Forge 当作变换服务加载：`[OptiForge] onLoad, alongside [optiforge, mixin, OptiFine, fml]`，并写出 `optiforge/diagnose.txt` |

`OptiForge` 与 `OptiFine` 在 `mods/` 里共存、各自被 ModLauncher 发现，这一点已在真机确认。

### 4.2 离线复现：用 OptiFine 自己的 patcher 量"载荷的成员命名空间"

`OptifinePatcher`（本工程）用 `URLClassLoader` 加载 OptiFine jar 里的 `optifine.Patcher`，对
`patch/srg/*.xdelta` 以**原版客户端 jar**为基包逐类打补丁、校验 `.md5`，再统计产物里的成员名：

| 构建 | MC | 抽样 | 应用 / MD5 匹配 | 产物成员名 |
|---|---|---|---|---|
| `I6` | 1.20.1 | 25 | 25 / 25 | **SRG**（328 个 `f_`/`m_` 记号） |
| `I7_pre1` | 1.20.2 | 25 | 25 / 25 | **SRG** |
| `I7` | 1.20.4 | 25 | 25 / 25 | **SRG** |
| `J1_pre9` | 1.21 | 25 | 25 / 25 | **SRG** |
| `J1` | 1.21.1 | 25 | 25 / 25 | **官方名**（0 个 SRG 记号） |
| `J7_pre2` | 1.21.9 | 25 | 25 / 25 | 官方名 |
| `J7_pre11` | 1.21.10 | 25 | 25 / 25 | 官方名 |
| `J9` | 1.21.11 | 25 | 25 / 25 | 官方名 |
| `K1_pre2` | 26.1.2 | 25 | 25 / 25 | 官方名 |
| `K2_pre1` | 26.2 | 25 | 25 / 25 | 官方名 |

**结论：`1.21` 的 N/A 就是命名空间错配。** OptiFine 的补丁产物用 SRG 成员名（`m_127521_`），而
Forge 1.20.6 起的运行时用官方名（`forThrowable`）。Forge 1.21 客户端 jar 里的 `CrashReport` 确实是
`forThrowable(Throwable, String)`；被 OptiFine 换成 SRG 版本后，未打补丁的 `Main` 就找不到方法了 ——
与实测的 `NoSuchMethodError` 完全对上。

反过来 `1.21.1` 的构建在 1.21 → 1.21.1 之间把载荷改成了官方名，所以一上来就能跑。这也解释了为什么
1.20.1/1.20.2/1.20.4 的 SRG 载荷在 Forge 上没问题：那几版 Forge 运行时本身就是 SRG。

### 4.3 由此确定的 M1

对"载荷命名空间 ≠ 运行时命名空间"的线（目前确认 `1.21`；`1.21.6`/`1.21.7` 等未测），修法是
**加一层成员重映射**，而不是重写 OptiFine 的补丁管线：

- OptiFine 自己的 transformer 继续负责打补丁、提供 `net/optifine/**` 与资源（真机已证明它能跑）；
- OptiForge 注册一个**排在它之后**的 transformer，把经过它的类里的 SRG 成员名换成官方名；
- SRG 记号（`f_127499_` / `m_127521_`）是**全局唯一**的，所以重命名不需要 owner 上下文 ——
  一个 `SRG 名 → 官方名` 的全局表就够；
- 映射来源：`joined.tsrg`（`obf ↔ srg`，Forge 的 `mcp_config-<mc>.zip` 里，格式 `tsrg2 obf srg id`）
  与 Mojang 的 `client.txt`（`official ↔ obf`）按混淆名 join。

## 5. 兼容表：语义与边界

`data/optifine-forge-matrix.csv`（645 行：379 兼容 / 90 N/A / 176 无数据）由
`tools/build-matrix.ps1` 从 BMCLAPI 的 `/optifine/versionlist` 生成，它抓的就是 optifine.net 下载页那列。
两个独立的语义佐证：

- 官方 changelog 逐条写着 `- not compatible with Forge` / `- compatible with Forge 47.2.18`；
- HMCL 用同一个字段做 OptiFine+Forge 的兼容闸门（HMCL-dev/HMCL#2063），并明确"字段为空就跳过检查"
  —— 与表里 176 行 `unknown` 的处理一致。

1.20 以后共 52 个构建标 N/A，整条线都 N/A 的版本是：`1.21`、`1.21.6`、`1.21.7`、`1.21.9`、`26.1.2`、`26.2`
（外加只有单构建且 N/A 的 `1.20.2`）。半兼容线的分界点见 [VERSIONS.md](VERSIONS.md)。

## 6. 架构决定

### 6.1 一份 jar 覆盖三条线，不引入 Minecraft/Forge 编译期依赖

本工程只 `compileOnly` ModLauncher + ASM。理由：

- 要实现的东西（`ITransformationService`、`ITransformer`）是 ModLauncher 的 API，不是 Minecraft 或 Forge 的；
- 1.20.x 运行时是 SRG 名、1.20.6+ 是官方名 —— 这是**被补丁的类**的命名问题，不是本工程 API 的问题；
- 于是不需要 ForgeGradle、不需要 mappings、不需要每条线一个工程/分支，`release 17` 一份 class 文件
  在 Java 17 / 21 / 25 上都能加载。

代价：任何需要 Minecraft 或 Forge 类型的操作只能靠 ASM 与字符串/反射，这在可预期改动范围内。

### 6.2 不在启动时改写用户的 OptiFine jar（暂定）

OptiFabric 的做法是"生成一个打过补丁的 OptiFine jar 再用"。在 Forge 上这条路要更小心：jar 在我们能插手
之前就已经被 `SecureJar.from(path)` 打开过，Windows 上文件名可能被 JVM 持有。所以先用 transformer
叠加（第 4.3 节），**不**做原地改写。

### 6.3 判定逻辑读表，不硬编码

OptiFine 的 Forge 支持是**逐构建**声明的。判定 = jar 身份（`buildof.txt` + 规范文件名）查表，
再加上第 4.2 节的命名空间实测。

## 7. M1 的候选修法（按 M0 的证据排序）

1. **成员命名空间重映射**（第 4.3 节）—— 已由证据确认为 `1.21` 的直接原因，也是唯一有日志支撑的方案。
2. **硬 `Class` 常量**（4–6 个类型）：若重映射后仍出现 `NoClassDefFoundError`/`NoSuchMethodError` 落在
   这些类型上，再处理。
3. **与 Forge 自身补丁叠加**：OptiFine 打过的 vanilla 类，Forge 也会改；顺序冲突只能靠真机日志定位。
4. **元数据/发现时机**：第 2.1 节的源码表明 Forge 不检查 jar 内容，除非日志反证。

## 8. 未验证 / 已知风险

- 加权重映射后从未在真机复测过（这是下一轮的入口）。
- `1.21.6` / `1.21.7` / `1.21.9` 的 N/A 是否同因未测；其中 `1.21.9`/`1.21.10` 的载荷已是官方名，
  可能有别的原因。
- 表里 176 行 `unknown`（从未上架的 preview）无法从公开数据补，只能实测。
- 26.x 的 Forge 运行时未接触（载荷是官方名，理论上不需要重映射，但未验证）。
