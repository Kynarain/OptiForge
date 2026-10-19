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
| 表里的空值与 `Forge N/A` 的语义 | 完成（两处独立佐证） | DESIGN §5 |
| **真机：OptiForge 被真实 Forge 发现并运行**（1.21 + Forge 51.0.33，以及 1.21.1 + Forge 52.1.20） | 完成 | 两次启动日志都有 `[OptiForge] onLoad, alongside [optiforge, mixin, OptiFine, fml]` 与 `optiforge/diagnose.txt` |
| **真机：1.21.1 + Forge 52.1.20 + OptiFine `J1`（声明兼容）STARTED** | 完成 | `Setting user` / `Sound engine started` / 169 条 `[OptiFine]` / 0 崩溃报告 |
| **真机：1.21 + Forge 51.0.33 + OptiFine `J1_pre9`（N/A）的失败点** | 完成 | `NoSuchMethodError: net.minecraft.CrashReport.forThrowable(Throwable, String)` at `Main.main:184` |
| `OptifinePatcher`（离线跑 OptiFine 自己的 patcher） | 完成，10 个构建 × 25 类全部 MD5 匹配 | DESIGN §4.2 |
| 载荷成员命名空间实测：`1.20.x`/`1.21` = SRG；`1.21.1`+ = 官方名 | 完成 | DESIGN §4.2 的表 |
| `data/mappings/1.21-srg-to-official.tsv`（75 196 行） | 完成 | `tools/build-srg-map.ps1` join `joined.tsrg` + `obf-official-1.21.tsrg`；抽查 `m_127521_ → forThrowable`、`f_127499_ → LOGGER` |
| `SrgRemap`（常量池级重命名） | 完成，离线验证 | 对 1.21 `CrashReport`：补丁 MD5 匹配 → 重命名 34 个记号、0 个未映射 → `javap` 里出现 `forThrowable(Throwable, String)` 与 `LOGGER` |

## 没有做过（不要当成已实现）

- **没有实现任何修复**：`transformers()` 仍然返回空列表。（修法已定：SRG→官方名的成员重映射，DESIGN §4.3。）
- `1.21.6` / `1.21.7` / `1.21.9` / 26.x 都没有做过真机启动。
- 无 Release、无 CHANGELOG、无 CI。

## 下一步（M1）

1. ~~生成 `1.21` 的 `SRG 名 → 官方名` 全局表~~ —— 完成，75 196 行。
2. ~~实现重映射~~ —— `SrgRemap` 已能在离线把补丁产物改成官方名（已验证 `CrashReport`）。
3. **把它接到服务里**：OptiFine 自己的 transformer 会先跑（真机已证明），但它的产物在官方名运行时上
   需要重映射。有两条待验路径：
   a. 让 OptiFine 的 transformer 继续跑，OptiForge 注册一个**排在它之后**的 `ITransformer` 重映射它的目标类
      —— 需要确认 ModLauncher 的服务排序（日志里 `[optiforge, mixin, OptiFine, fml]` 的顺序是否就是执行序），
      以及 `net/optifine/**` 自有类（由 OptiFine 的 `additionalClassesLocator` 提供，我们碰不到）怎么办；
   b. 自己接管：把 OptiFine jar 放到 `mods/` 之外，由 OptiForge 用 `OptifinePatcher` 打补丁 + `SrgRemap`
      重映射，再通过自己的 `ITransformationService`（`transformers()` + `additionalClassesLocator()`）提供 ——
      与 OptiFabric 同构，工作量更大但不依赖 OptiFine 的 transformer 行为。
4. 用同一套 rig 复跑 `1.21 + Forge 51.0.33 + J1_pre9`，验收信号与 1.21.1 一致：
   `Setting user`、`Sound engine started`、`joined`、0 崩溃报告。
5. 再按同一方法测 `1.21.6` / `1.21.7`（若载荷同为 SRG，则同一处修法覆盖）。

## 下一步（M0，按顺序）

1. 造一个 **1.21 + Forge 51.0.33** 的实例，把原件 `preview_OptiFine_1.21_HD_U_J1_pre9.jar` 与本 jar 一起放进
   `mods/`，启动一次。产物：`optiforge/diagnose.txt` + `logs/latest.log`。
2. 同一个实例里再跑**已声明兼容**的组合（1.21.1 + Forge 52.1.20 + `J1`）当对照，确认本 jar 不干扰成功路径。
3. 用这两份日志给 M1 候选排序（DESIGN §6），再决定是 ASM 改常量、补 stub，还是别的。

## 踩过的坑（写下来省一次重来）

- **Forge 安装器只装 Forge 自己的东西**：`--installClient` 会写下 `versions/<id>/<id>.json` 与
  `libraries/net/minecraftforge/**`，但**不下载原版 json 与大部分原版库**。原版 `1.21.json` 要自己放进去
  （从已有 rig 的 `versions\1.21\` 复制），否则启动器解析 `inheritsFrom` 直接失败。
- **`-Rig` 要指向带完整 libraries 的目录**：我第一次把 `-Rig` 指向安装器的临时 `.minecraft`，于是
  `Module org.apache.logging.log4j not found, required by net.minecraftforge.eventbus` —— 原版 log4j 库在
  共用 rig 的 `libraries` 里，不在安装器目录里。
- **BMCLAPI 的下载会静默截断**：`1.21/HD_U_J1/pre9` 与 `1.21.1/HD_U/J1` 各遇到一次 7 MB 的 jar 只下到
  7.0 MB / 3.1 MB / 1.7 MB，`ZipFile` 打开时报"找不到中央目录结尾记录"。实测前必须校验 zip 完整性。
- **rig 里现成的 OptiFine jar 多数是处理过的**（`optifine-OptiFine_1.20.1_HD_U_I6.jar` 只有 5.66 MB，
  原件 7.15 MB；`notch/`、`patch/assets` 已被剥掉）。量布局必须用原件，判断依据之一是 `shape` 字段。
- **PowerShell 5.1 的编码陷阱**：`Get-Content -Raw` 读无 BOM 的 UTF-8 中文文件会按 GBK 解码，再
  `Set-Content -Encoding UTF8` 写回就把整份文档写坏（DESIGN.md 被这样毁过一次，已用 UTF-8 重写）。
  改写文档一律用编辑工具，不要用 PowerShell 重写整个文件。
- Windows PowerShell 5.1 上 `@($raw | ConvertFrom-Json)` 会把整个数组包成单个元素（矩阵生成脚本踩过），
  必须先赋值再 `@()`。
