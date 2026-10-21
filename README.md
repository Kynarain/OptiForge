<img src="docs/icon.png" width="96" align="right" alt="OptiForge">

# OptiForge

> OptiFine on Forge, with Forge Mod Loader loaded alongside.

版本号遵循 SemVer（当前 `0.10.0`，见 `gradle.properties` 的 `mod_version_base`）。协议：[MPL 2.0](LICENSE)。

在 **Forge** 上加载 **OptiFine** 的模组 —— 和 OptiFabric 在 Fabric 上做的事一样，只是目标换成 Forge。
重点是那些 OptiFine 官方标成 **`Forge N/A`（not compatible with Forge）** 的构建：`1.21`、`1.21.6`、
`1.21.7`、`1.21.9`、`26.1.2`、`26.2` 整条线都是 N/A。

## 实测结果（0.9.x 系列）

七条 1.21.x 版本线、Forge 51→61，全部启动并进世界（`Dev joined the game`、0 崩溃报告、stderr 0 字节）：

| 组合 | Forge | OptiFine | 官方 Forge 列 | 启动 | 进世界 |
|---|---|---|---|---|---|
| 1.21 | 51.0.33 | `1.21 HD_U_J1_pre9` | **N/A** | 是 | 是 |
| 1.21.6 | 56.0.9 | `1.21.6 HD_U_J6_pre3` | **N/A** | 是 | 是 |
| 1.21.7 | 57.0.3 | `1.21.7 HD_U_J6_pre7` | **N/A** | 是 | 是 |
| 1.21.8 | 58.1.26 | `1.21.8 HD_U_J6_pre13` | 兼容 | 是 | 是 |
| 1.21.9 | 59.0.5 | `1.21.9 HD_U_J7_pre2` | **N/A** | 是 | 是 |
| 1.21.10 | 60.1.18 | `1.21.10 HD_U_J7_pre9` | 兼容 | 是 | 是 |
| 1.21.11 | 61.2.4 | `1.21.11 HD_U_J9_pre4` | 兼容 | 是 | 是 |
| 1.20.6 | 50.2.14 | `1.20.6 HD_U_J1_pre18` | 兼容 | 是 | 是 |
| 1.21.1 | 52.1.20 | `1.21.1 HD_U_J1_pre9` | **N/A** | 是 | 是 |
| 1.20.1 | 47.4.23 | `1.20.1 HD_U_I6` | 兼容 | 是 | 未复现 |
| 26.2 | 65.1.5 | `26.2 HD_U_K2_pre4` | **N/A** | 是 | 是（JDK 25） |
| 26.1.2 | 64.1.5 | `26.1.2 HD_U_K1_pre2` | **N/A** | 是 | 是（JDK 25） |
| 1.19.4 | 45.1.0 | `1.19.4 HD_U_I4` | 兼容 | 是 | 是（连服务器） |
| 1.19.4 | 45.0.23 | `1.19.4 HD_U_I3` | 兼容 | 是 | 是（连服务器） |
| 1.19.3 | 44.0.49 | `1.19.3 HD_U_I2_pre4` | **N/A** | 是 | 是（连服务器） |
| 1.19.2 | 43.2.14 | `1.19.2 HD_U_I2` | 兼容 | 是 | 是（连服务器） |
| 1.18.2 | 40.2.9 | `1.18.2 HD_U_H9` | 兼容 | 是 | 是（连服务器） |
| 1.17.1 | 37.0.103 | `1.17.1 HD_U_H1` | 兼容 | 是 | 是（连服务器） |
| 1.18 | 38.0.14 | `1.18 HD_U_H3` | 兼容 | 是 | 是（连服务器） |
| 1.18.1 | 39.1.0 | `1.18.1 HD_U_H6` | 兼容 | 是 | 是（连服务器） |
| 1.19 | 41.1.0 | `1.19 HD_U_H9` | 兼容 | 是 | 是（连服务器） |
| 1.19.1 | 42.0.9 | `1.19.1 HD_U_H9` | 兼容 | 是 | 是（连服务器） |
| 1.19.3 | 44.1.23 | `1.19.3 HD_U_I3` | 兼容 | 是 | 是（连服务器） |

**1.17–1.19.4 也是支持的**（Java 17 线）：这些 Forge 运行时用的是"SGR 成员名 + 官方类名"，
OptiFine 的 `patch/srg` 负载正好对得上，所以不做重映射；测试通过专用服务器 + 客户端 `--server/--port`
自动连服完成（这几代的客户端还认这两个参数，1.20 起没有了）。

**1.17 那一行没法测**：Forge 从未给 MC 1.17 出过构建（`maven-metadata.xml` 里只有 `1.17.1-37.x`），
所以这一行不是"失败"，是没有可配的 Forge。另一处边界是 OptiFine `1.17.1 HD_U_G9`（官方标 `Forge N/A`）：
它即使走 OptiForge 也用不起来——补丁类里引用的还是 MCP 定稿前的名字（例如 `MemoryReserve.m_320811_()`，
定稿的 1.17.1 用的是 `m_182327_()`），这是**映射世代**对不上，不是打补丁的问题。

两条 N/A 线都是：`Setting user`、`Sound engine started`、250 条上下 `[OptiFine]` 日志、0 崩溃报告、
stderr 0 字节，且 OptiFine 自己打印 `Forge version: 5x`。其余版本线尚未实测，见 [docs/STATE.md](docs/STATE.md)。

已知边界：`1.20.2`（唯一构建 `HD_U_I7_pre1`，官方 N/A）在 Forge 48 上启动阶段报
`IllegalAccessError: ... ForgeGui`——三组对照（纯 Forge / 只有 OptiForge / OptiForge+OptiFine）显示
**只有 OptiFine 在场时**出现，属于该 OptiFine 构建与 Forge 48 的相互作用，不是加载器的替换错误。
`1.20.1` 启动通过，但本环境无法自动进世界（Forge 47 不响应 quickPlay/--server 等参数）。

## 作者与链接

- 作者：**Kynarain**（mods 列表里的 Authors，对应 mods.toml 的 authors 字段）
- 仓库：<https://github.com/Kynarain/OptiForge>　问题反馈：<https://github.com/Kynarain/OptiForge/issues>
- 协议：[MPL 2.0](LICENSE)
- Credits：OptiFine 由 **sp614x** 开发；Fabric 侧同类方案是 **OptiFabric**（Chocohead），本项目的界面提示等做法参考了它

## 用法

1. 把**你自己下载的** OptiFine jar 放到实例目录的 `optiforge/`（不是 `mods/` —— 放 `mods/` 会让
   OptiFine 自己的变换服务也跑起来，两边同时打补丁）；
2. 把 `OptiForge-<版本>.jar` 放进 `mods/`；
3. 启动。首次启动会把 OptiFine 的补丁打进 `<game>/optiforge/pack/<key>/`，并生成
   `mods/optiforge-optifine-classes.jar`（OptiFine 自有类 + 资源），之后复用缓存。

可选覆盖：`-Doptiforge.optifine=<jar>`、`-Doptiforge.vanilla=<原版客户端 jar>`、
`-Doptiforge.libraries=<libraries 目录>`。

'诊断报告写在 `<game>/optiforge/diagnose.txt`：版本、运行时命名空间、补丁/重映射/AT/兼容修复的计数。

**没放 OptiFine 时**：OptiForge 会创建 `<game>/optiforge/`，并**在游戏里**提示（做法参考 OptiFabric 的
`MixinTitleScreen`）：标题界面弹出 `OptiForge 需要 OptiFine` 的确认框，正文给出 `optiforge` 文件夹的绝对路径、
<https://optifine.net/downloads> 和"不要放 mods/"；点 "Yes" 直接打开该文件夹。游戏本身按原版 Forge 正常启动，
不会崩。放好 OptiFine 后下次启动就不再提示。

**文件名与 mods 列表**（参考 OptiFabric 的命名）：
- `OptiForge-<版本>.jar` → 放进 `mods/`，这是 OptiForge 本体（变换服务，不出现在 mod 列表）；
- `OptiForge-<版本>-classes.jar` → 自动生成在 `mods/`，是 mod 列表里那个 **OptiForge**（modId `optiforge`，
  协议 MPL 2.0）；缺 OptiFine 时它只带提示、不带 OptiFine 类；
- OptiFine 自身的 jar 保持官方文件名，放在 `optiforge/`。

旧版本线额外可选：`-Doptiforge.assets=staged` 会把 OptiFine 的 `patch/assets` 纹理载荷解出来一起 stage
（视觉最完整，但 1.13–1.16 上首次会花时间编译默认 CTM）；默认 `none`，只带普通 `assets/**`。'

## 旧版本线：1.13–1.16.5 与 1.12.2 及更早

这两段旧版本线不能用主 jar：它们跑在 **Java 8** 上，主 jar 是 Java 17 字节码。同一套代码另出两个产物，
命名规则不变（`optiforge/` 放 OptiFine、modId `optiforge`、MPL 2.0）：

| 版本线 | 产物（放 `mods/`） | 入口 | 说明 |
|---|---|---|---|
| 1.17 – 26.x | `OptiForge-<版本>.jar` | ModLauncher `ITransformationService` | 主产物（Java 17/21/25 运行时） |
| 1.14.4 – 1.16.5 | `OptiForge-<版本>-oldml.jar` | 同一变换服务，编译成 Java 8 字节码 | 只依赖 ModLauncher 2–8 的旧 API 形态 |
| 1.13.2 | 同上（`-oldml`） | 同上 + 混淆名→运行时名桥 | 该构建的补丁是扁平混淆布局，需要额外映射 |
| ≤ 1.12.2 | `OptiForge-<版本>-legacy.jar` | Forge coremod（`FMLCorePlugin`） | launchwrapper 世界，用 `IClassTransformer` 交付补丁类 |

旧版本线的实测（每条都是真机启动 + 进服务器世界，JRE 1.8.0_503）：

| MC | Forge | OptiFine | 结果 |
|---|---|---|---|
| 1.16.5 | 36.2.39 | `1.16.5 HD_U_G8` | 标题界面、`[OptiFine]` 日志正常 |
| 1.15.2 | 31.2.57 | `1.15.2 HD_U_G6` | 启动 + 进世界、0 崩溃 |
| 1.14.4 | 28.2.26 | `1.14.4 HD_U_G5` | 启动 + 进世界、0 崩溃 |
| 1.13.2 | 25.0.219 | `1.13.2 HD_U_G5` | 标题界面、0 崩溃（不 stage 资源载荷时 FML 会因缺纹理弹错误页，stage 则卡在 OptiFine 编译默认 CTM） |
| 1.12.2 | 14.23.5.2860 | `1.12.2 HD_U_G5` | 启动 + 进世界、1669 条 `[OptiFine]`、0 崩溃 |

注意：1.13–1.16 的运行时是**官方类名 + `func_`/`field_` 成员名**，1.12.2 是**混淆类名 + 混淆成员名**，
两条管线因此分开；主产物的 Java 17 代码与它们共享同一套补丁/合并逻辑。

## 它在做什么（详见 [docs/DESIGN.md](docs/DESIGN.md)）

Forge 不把 OptiFine 当 mod：`mods/` 里任何声明了 `cpw.mods.modlauncher.api.ITransformationService`
的 jar 都被 `ModDirTransformerDiscoverer` 收作"变换服务"并从普通 mod 发现里排除。所以本工程也**没有
`mods.toml`、没有 `@Mod`**，而 OptiFine jar 必须留在 `mods/` 之外，由 OptiForge 接管整条管线：

打补丁（OptiFine 自带 patcher）→ SRG→官方名重映射（仅当运行时用官方名）→ 撞名包装处理 →
重放 Forge 的 access transformer → 兼容修复（如 `ResourceLocation.parse`）→ 以游戏类 + javafml mod
两种形态交付。

只依赖 ModLauncher API + ASM：一份 jar 同时给 1.20.x(Java 17) / 1.21.x(21) / 26.x(25) 用，不需要
ForgeGradle，也不需要每条线一个工程。

## 构建

```powershell
pwsh -File tools/build.ps1          # javac + jar（版本号取 gradle.properties）
pwsh -File tools/check.ps1          # 验证 Forge 会发现这个 jar（离线）
.\gradlew build                     # 同一份产物，Gradle 版（首次要下载发行包，未验证）
```

离线工具：

```powershell
# 打印某个 OptiFine 构建的补丁 / Forge 引用 / 成员命名空间
java -cp build\libs\OptiForge-0.2.0.jar kynarain.cn.optiforge.Diagnose --jar <OptiFine.jar> --forge <ver> --mc <mc>
# 用 OptiFine 自己的 patcher 离线打一个类，并看重映射结果
java -cp build\libs\OptiForge-0.2.0.jar kynarain.cn.optiforge.PatchApply --optifine <jar> --base <原版 jar> --class <类> --map data\mappings\1.21-srg-to-official.tsv --out <输出>
# 离线构建整包
java -cp "build\libs\OptiForge-0.2.0.jar;build\deps\asm-9.9.1.jar" kynarain.cn.optiforge.PackBuild --optifine <jar> --base <原版 jar> --mapping data\mappings\1.21-srg-to-official.tsv --gamedir build\packtest
```

## 数据

- `data/optifine-forge-matrix.csv` —— OptiFine 官方 `Forge` 列的完整快照（645 行：379 兼容 / 90 N/A /
  176 无数据），由 `tools/build-matrix.ps1` 从 BMCLAPI 生成；
- `data/mappings/1.21-srg-to-official.tsv` —— 75 196 行 SRG→官方名，由 `tools/build-srg-map.ps1` 生成。
