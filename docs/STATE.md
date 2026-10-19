# STATE — 已经做到哪一步

最后更新：2026-10-20。**0.2.0：管线可用**——在两条线上实测启动并进世界。

## 结论先行（实测）

| 组合 | 启动 | 进世界 | 证据 |
|---|---|---|---|
| **1.21 + Forge 51.0.33 + OptiFine `1.21 J1_pre9`（官方标 N/A）** | 是 | **是**（`Dev joined the game`，冷缓存 + 全新实例 + 0.2.0 jar 复验过） | `[OptiFine] Forge version: 51.0.33`、`[OptiFine] Disable Forge light pipeline`、161 条 `[OptiFine]`、0 崩溃报告、stderr 0 字节 |
| **1.20.1 + Forge 47.4.23 + OptiFine `1.20.1 I6`（官方声明兼容）** | 是 | 未复现（世界未加载，原因待查） | `Setting user`、`Sound engine started`、112 条 `[OptiFine]`、0 崩溃报告、stderr 0 字节；OptiFine 自己在改 Forge 配置 |

`1.21` 那条就是本工程存在的理由：OptiFine 官方把它标成 **not compatible with Forge**，现在通过 OptiForge 能起、能进世界。

## 管线（每个环节都对应一次实测失败）

1. 用户在 `<game>/optiforge/` 放 OptiFine jar（**不能**放 `mods/`）。
2. `OptifinePatcher` 用 OptiFine 自带的 patcher，以**原版客户端 jar**为基包打全部 440 个补丁类。
3. `SrgRemap`：运行时是官方名时把 SRG 成员名改写成官方名（`1.21` 需要；`1.20.x` 是 no-op）。
4. `CollisionFixer`：删掉 OptiFine 额外加的、与官方名撞名的包装方法，把原方法改名占位。
5. `ForgeAccessTransformers`：重放 Forge 的 AT（成员是 SRG id、方法写成 `name(desc)`、`-f` 去 final），
   否则 Forge 自己的 `ForgeRenderTypes` 会撞私有字段。
6. `CompatFixes`：`ShaderInstance.getOrCreate` 里的 `ResourceLocation.withDefaultNamespace` 改成 `parse`，
   否则 Forge 的 `forge:rendertype_*` 着色器名会被判非法（这就是 1.21 卡住的最后一处）。
7. 自有类 + assets 打成 `mods/optiforge-optifine-classes.jar`（真正的 javafml mod，ASM 生成带 `@Mod` 的标记类）。
8. 打包结果按 `sha256(OptiFine)+sha256(base)` 缓存到 `<game>/optiforge/pack/`。

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
| **接管管线（optiforge/ 里的 jar → 补丁 → 重映射 → Forge AT → 交付）** | 完成，真机跑通 | `[OptiFine] Forge version: 51.0.33`、`Sound engine started`、137 条 `[OptiFine]`、着色器子系统与资源重载都到了 |
| 碰撞处理（SRG 名与 OptiFine 加的官方名包装同名） | 完成 | `ModelPart` 保住 `m_171324_` + `getChild` 两个方法，不再 `Duplicate method name` |
| Forge 访问转换器的重放 | 完成 | 335 条授权，10 个类被放宽；修掉 `RenderStateShard.setupState`、`RenderType.create` 的 `IllegalAccessError` |
| 自有类以 javafml mod 形式交付（生成 `@Mod` + 构造器） | 完成 | 否则 `NoClassDefFoundError: net/optifine/util/MathUtils`（LIBRARY 形态进 PLUGIN 层，看的是未变换的游戏类） |
| 运行时命名空间探测（从 classpath 里的 `CrashReport.class` 判断） | 完成 | 不再依赖版本号猜 SRG/官方名 |

## 没有做过（不要当成已实现）

- `1.20.6`、`1.21.3/4/6/7/8/9/10/11`、`26.1.2`、`26.2` 都**没有**实测过；其中 `26.x` 的载荷是官方名、
  可能是 no-op 管线，但未验证。
- `1.20.1` 的进世界没有复现（启动成功、世界未加载）；`1.21` 的进世界已复现。
- 没有 CI；Gradle 构建仍未验证（javac 构建是验证过的）。

## 下一步

1. `1.20.1` 世界未加载的原因（`--quickPlaySingleplayer=RigSession` 已确认送达；疑似世界副本/超时）。
2. 按同一管线刷 `1.21.4`、`1.21.8`、`1.21.11`（官方声明兼容，作为回归），再刷 `1.21.6`/`1.21.7`/`1.21.9`
   与 `26.1.2`（N/A 线）。
3. 出 `v0.2.0` 的 GitHub Release（jar + 说明：哪条线实测、OptiFine jar 放哪、已知限制）。

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
