# OptiForge

在 **Forge** 上加载 **OptiFine** 的模组 —— 和 OptiFabric 在 Fabric 上、OptifiNeoforge 在 NeoForge 上做的事一样，
只是目标换成 Forge。

> **状态：骨架，尚未可用。** 现在这个 jar 只做一件事：在真实 Forge 启动时把 OptiFine jar 的底细和
> OptiFine 官方的 Forge 兼容声明写成一份报告（`optiforge/diagnose.txt`）。`transformers()` 是空的，
> 也就是说它还没有修任何东西。哪些地方要修，由这份报告在真机上定 —— 见 [docs/STATE.md](docs/STATE.md)。

## 为什么需要它

OptiFine 的下载页每一行都有一列 `Forge`：

- `Forge 47.2.18` —— 该构建声明兼容这个 Forge 构建；
- `Forge N/A` —— OptiFine 明说**不兼容 Forge**（changelog 里写作 `not compatible with Forge`）。

`Forge N/A` 的构建在 Forge 上直接放 `mods/` 是起不来的，而这些构建分布在不少版本线上：`1.20.2`、`1.21`、
`1.21.6`、`1.21.7`、`1.21.9`、`26.1.2`、`26.2` 整条线都是 N/A。表里还有一批"早期 preview 不兼容、后面某一版才开始兼容"
的半兼容线（`1.21.3` 从 `J2_pre8`、`1.21.8` 从 `J6_pre13`、`1.21.10` 从 `J7_pre6`、`1.21.11` 从 `J8_pre3` …）。

OptiForge 要做的就是把这些构建接回 Forge。

## 现在能做什么：诊断

把 OptiForge 和**你自己下载的** OptiFine jar 一起放进 Forge 实例的 `mods/`，启动一次，然后看
`optiforge/diagnose.txt`。它会写出：

- 这次启动真实的 Minecraft / Forge / FML 版本（从 `sun.java.command` 的 `--fml.*` 参数读，不依赖任何 Forge 类）；
- `mods/` 里每个 OptiFine jar 的大小、SHA-256、`buildof.txt` 构建时间、jar 形态（安装器形态还是被别的工具改写过）；
- 补丁负载清单：`patch/srg` / `patch/notch` / `patch/assets` 各多少条，其中**有没有** `net/minecraftforge` 目标；
- OptiFine 自己带的类：`srg/`、`notch/` 各多少，其中 `net/minecraftforge` 有多少个；
- 对照 `data/optifine-forge-matrix.csv`（OptiFine 官方 Forge 列的完整快照）得出该构建对这次 Forge 版本的判定；
- 下一步该改哪里的候选清单。

也可以完全离线跑同一个报告（不启动游戏）：

```powershell
pwsh -File tools/build.ps1
& "C:\Program Files\Java\jdk-21\bin\java.exe" -cp build\libs\OptiForge-0.1.0.jar `
  kynarain.cn.optiforge.Diagnose --jar mods\OptiFine_1.21_HD_U_J1_pre9.jar --forge 51.0.33 --mc 1.21
```

## 工程结构

| 路径 | 内容 |
|---|---|
| `src/main/java/kynarain/cn/optiforge/` | ModLauncher 变换服务 + 诊断（见下表） |
| `src/main/resources/META-INF/services/cpw.mods.modlauncher.api.ITransformationService` | 让 Forge 在 `mods/` 里发现本 jar 的唯一凭据 |
| `src/main/resources/optiforge/optifine-forge-matrix.csv` | 打进 jar 的兼容表（由 `data/` 同步） |
| `data/optifine-forge-matrix.csv` | 兼容表的源文件，645 行：379 兼容 / 90 N/A / 176 无数据 |
| `tools/build-matrix.ps1` | 从 BMCLAPI 重新生成兼容表 |
| `tools/fetch-deps.ps1` · `tools/build.ps1` | 取 ModLauncher/ASM、用 javac 构建 |
| `docs/` | 机制调研、版本矩阵、当前状态 |

代码只有 6 个类，职责单一：

| 类 | 职责 |
|---|---|
| `OptiForgeService` | `ITransformationService` 实现；`initialize` 里做诊断，`transformers()` 目前为空 |
| `RuntimeInfo` | 从 `sun.java.command` 读 `--fml.mcVersion` / `--fml.forgeVersion` / `--gameDir` |
| `OptifineJar` | 读 zip 得到补丁与自有类清单（不碰任何 Forge/Minecraft 类型） |
| `ForgeMatrix` | 官方 Forge 兼容表 + 版本比较（处理 `Forge #2847` 这种旧编号） |
| `PatchPlan` | 由 jar 清单 + 表行推导"要不要修、可能修哪里" |
| `Diagnose` | 渲染报告；带 `main()` 可离线运行 |

## 构建

```powershell
pwsh -File tools/build.ps1          # javac + jar，已验证
pwsh -File tools/check.ps1          # 验证 Forge 会发现这个 jar（离线，不启动游戏）
.\gradlew build                     # 同一份产物，Gradle 版（首次要下载发行包）
```

目标 Java 17（class file 61），一份 jar 同时给 1.20.x(Java 17) / 1.21.x(21) / 26.x(25) 用。

## 设计要点（详情见 [docs/DESIGN.md](docs/DESIGN.md)）

- **Forge 不把 OptiFine 当 mod**：`mods/` 里任何声明了 `cpw.mods.modlauncher.api.ITransformationService`
  的 jar 都被 `ModDirTransformerDiscoverer` 收作"变换服务"并从普通 mod 发现里排除。所以本工程**没有
  `mods.toml`、没有 `@Mod`** —— 它和 OptiFine 走同一个入口。
- **不编译依赖 Minecraft/Forge**，只依赖 ModLauncher API 与 ASM，因此不需要 ForgeGradle、不需要每条线一个工程。
- **兼容表是数据，不是代码**：OptiFine 的 Forge 支持是逐构建声明的，所以判定逻辑读表。
