# STATE — 已经做到哪一步

最后更新：2026-10-19。骨架阶段，**没有可用产物**：`transformers()` 是空的，本工程还没有修过任何东西。

## 已经做过、并且验证过的

| 项 | 状态 | 证据 |
|---|---|---|
| 仓库骨架（Java 17、无 ForgeGradle、一份源码三条线） | 完成 | `src/main/java` 7 个类 |
| `tools/fetch-deps.ps1` 取 ModLauncher 10.0.9 / ASM 9.9.1 / securejarhandler | 完成 | 下载并解出 class |
| `tools/build.ps1`（javac + jar） | 完成并跑通 | `build/libs/OptiForge-0.1.0.jar`，32 076 字节 |
| `tools/check.ps1`：jar 的模块描述符声明了 `ITransformationService`（正是 Forge 判断的那一步）+ `ServiceLoader` 可加载 | 完成并跑通 | `RESULT : OK - 1 service(s) declared and loadable` |
| `.\gradlew build` | **未验证** | 首次要下载 130 MB 发行包，本次没跑 |
| 兼容表生成（645 行：379 兼容 / 90 N/A / 176 无数据） | 完成 | `data/optifine-forge-matrix.csv` |
| 离线诊断（`Diagnose --jar ... --forge ... --mc ...`） | 完成，8 个原件跑过 | DESIGN §3 的表 |
| Forge 发现机制的源码核对（`ModDirTransformerDiscoverer`，分支 1.21.11 与 26.2） | 完成 | DESIGN §2.1 |
| Forge 1.20.1 + OptiFine `I6` 原生跑通的参照日志 | 已有（本机 rig 的历史运行） | `OptiFineTransformer: Targets: 397`、`joined=1`、无 MD5 失败 |
| 表里的空值与 `Forge N/A` 的语义 | 完成（两处独立佐证） | DESIGN §4 |

## 没有做过（不要当成已实现）

- **本工程从未在真实 Forge 里启动过**。`OptiForgeService.initialize()` 只在离线 `main()` 的同一个渲染器上验证过逻辑。
- **没有实现任何修复**：`transformers()` 返回空列表。
- **不知道 N/A 构建在 Forge 上具体坏在哪一步**。这是 M0 的产出，也是 M1 排序的唯一依据。
- 26.x 的 Forge 运行时（Java 25、未混淆命名空间）未接触。
- 无 Release、无 CHANGELOG、无 CI。

## 下一步（M0，按顺序）

1. 造一个 **1.21 + Forge 51.0.33** 的实例，把原件 `preview_OptiFine_1.21_HD_U_J1_pre9.jar` 与本 jar 一起放进
   `mods/`，启动一次。产物：`optiforge/diagnose.txt` + `logs/latest.log`。
2. 同一个实例里再跑**已声明兼容**的组合（1.21.1 + Forge 52.1.20 + `J1`）当对照，确认本 jar 不干扰成功路径。
3. 用这两份日志给 M1 候选排序（DESIGN §6），再决定是 ASM 改常量、补 stub，还是别的。

## 踩过的坑（写下来省一次重来）

- **BMCLAPI 的下载会静默截断**：`1.21/HD_U_J1/pre9` 与 `1.21.1/HD_U/J1` 各遇到一次 7 MB 的 jar 只下到
  7.0 MB / 3.1 MB / 1.7 MB，`ZipFile` 打开时报"找不到中央目录结尾记录"。所以在实测前必须校验 zip 完整性
  （`tools/` 里的下载循环就是干这个的），否则会误判成"不是 OptiFine 构建"。
- **rig 里现成的 OptiFine jar 多数是处理过的**（`optifine-OptiFine_1.20.1_HD_U_I6.jar` 只有 5.66 MB，
  原件 7.15 MB；`notch/`、`patch/assets` 已被剥掉）。量布局必须用原件，判断依据之一是 `shape` 字段。
- Windows PowerShell 5.1 上 `@($raw | ConvertFrom-Json)` 会把整个数组包成单个元素（矩阵生成脚本踩过），
  必须先赋值再 `@()`。
