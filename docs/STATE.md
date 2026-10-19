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

- `1.20.6`、`1.21.3/4/6/7/8/9/10/11`、`26.1.2`、`26.2` 只有 `1.21.6` 与 `26.1.2` 试过（都失败，见下），其余没试。
- `1.20.1` 的进世界没有复现（启动成功、世界未加载）；`1.21` 的进世界已复现。
- 没有 CI；Gradle 构建仍未验证（javac 构建是验证过的）。

## 本轮新测的两条线：同一类模块可见性失败

| 组合 | 结果 | 关键错误 |
|---|---|---|
| 1.21.6 + Forge 56.0.9 + OptiFine `J6_pre3` | 失败 | `NoClassDefFoundError: net.minecraftforge.eventbus.api.Event`，抛点是 `net.optifine.reflect.Reflector.<clinit>`（我们的 mod 模块） |
| 26.1.2 + Forge 64.1.5 + OptiFine `K1_pre2` | 失败 | `NoClassDefFoundError: net.minecraftforge.client.extensions.IForgeVertexFormat`，抛点是游戏类 `DefaultVertexFormat.<clinit>` |

两条线都是"Forge 的类在交付上下文里不可见"：`1.21.6` 是 OptiFine 自有类所在的 mod 模块读不到 Forge 模块，
`26.1.2` 是 `minecraft` 模块读不到 forge 模块（Forge 56/64 不再像 51 那样把这些读边提前补好）。

另外 26.x 还有两个附带问题：Forge 64 需要 Java 25（本机只有 27，报 `Module.implAddExportsOrOpens` 缺失）；
OptiFine 26.x 的 `Options.loadOfOptions` 在 `optionsof.txt` 缺失时 AIOOBE（从 rig 里复制一份可绕过），
并且它要找 `optifine.OptiFineResourceLocator`——那在 jar 里 `optifine/**` 那一半，我们没有 stage。

### 模块可见性：已试过的两条路（本轮）

1. **反射 `Module.implAddReads`** —— 失败：`InaccessibleObjectException ... java.base does not "opens java.lang"
   to module OptiForge`，即启动器只对 Forge 自己的模块开了这个口子，没对无名模块开。
2. **在游戏模块内注入调用** —— 已实现（`ModuleReadsInjector`：给 `net.minecraft.CrashReport` 生成两个私有静态方法，
   在它的 `<clinit>` 里沿父层链对所有模块 `addReads`）。**注入确实跑了**（标记行
   `[OptiForge] injected module-read installer running` 出现在 1.21.6 的日志里），但 `Event` 仍然
   `ClassNotFoundException`。也就是说动态加读边没能影响 Forge 的 `SecureModuleClassLoader` 的解析，或
   `eventbus` 模块没有把 `net.minecraftforge.eventbus.api` 导出给 `minecraft`。

   下一步的判别实验（很小）：在注入代码里 `println(self.canRead(eventbusModule))` 前后各打一次，并检查
   eventbus 的 `module-info` 是否 `exports net.minecraftforge.eventbus.api`。

3. 该注入默认开启但对已验证线无副作用：1.21 回归跑过——`Setting user`、`Sound engine started`、
   `Dev joined the game`、0 崩溃、161 条 `[OptiFine]`。开关：`-Doptiforge.moduleReads=false`。
### 本轮新增的实测事实（1.21.6 / Forge 56）

- **模块分层**（`-Doptiforge.layerDump=true` 打印）：
  `SERVICE` 91 个模块（含 `net.minecraftforge.eventbus`、`net.minecraftforge.forgespi`、`net.minecraftforge.fmlloader`、
  `org.apache.logging.log4j`…），`PLUGIN` 4 个（`fmlcore`/`javafmlmod`/`lowcodemod`/`mclanguageprovider`），
  `GAME` 4 个（**`minecraft`**、`net.minecraftforge.forge` 等）。我们的 staged mod 在 `PLUGIN`，
  失败点是**它读不到 `SERVICE` 层的 `eventbus`**（`Reflector.<clinit>` 直接引用 `Event`）。
- `ModuleLayer` **没有 `parent()`**，只有 `parents()`（返回 `List`）。之前注入代码里用了 `parent()`，
  `NoSuchMethodError` 被 try/catch 吞掉，所以"注入跑了但没效果"。已改。
- **FML 不接受带 `module-info` 的 mod jar**：`AbstractModProvider` 报
  `Failed to parse ... module-info`，于是整个 staged jar 被丢弃 → 立刻出现
  `NoClassDefFoundError: net/optifine/util/MathUtils`。所以"给 mod jar 加显式模块描述符"这条路是死的
  （已确认不是写法问题）。
- `additionalClassesLocator` 在 Forge 51/56 的 ForgeBootstrap 启动下**从未被调用**（两代日志都没有
  `additionalClassesLocator() requested`）。

### 下一步（模块可见性）

1. 既然不能改 mod jar 的模块性，就**改引用**：给 OptiFine 自有类做一遍"非强制引用"处理——把
   `net/minecraftforge/**`、`net/neoforged/**` 的类字面量（`LDC Type`）换成
   `Class.forName` + 捕获异常的形式（OptiFine 的 Reflector 本来就把"类不存在"当正常情况），
   这样 `Reflector.<clinit>` 不会因为读不到 `Event` 而失败。
2. 或者让 staged jar 走 SERVICE 层（classpath 上的变换服务 jar），避开 mod 层的读边限制——但要注意
   之前的 split package/export 报错。
3. 26.x 还有 Java 25、`optionsof.txt`、`optifine/**`（`OptiFineResourceLocator`）三件事。
## 下一步

1. **修模块可见性**（两条线的共同根因）：给 staged mod 生成 `module-info.class` 或用
   `Module.addReads` 让它读 Forge 的模块；26.x 那侧要弄清 `minecraft` 模块为什么没读到 `forge`。
2. 备选：让 OptiFine 自有类改走 `additionalClassesLocator`（落到**未命名模块**，能读所有模块），
   staged mod 只留 assets。1.20.1 上验证过该接口会被调用（前缀带尾点即可），1.21.6 上待复测。
3. stage OptiFine jar 的 `optifine/**`（launcher 侧）一半，补上 `OptiFineResourceLocator`。
4. 用 Java 25 跑 26.x（本机需装 JDK 25）。
5. `1.20.1` 世界未加载的原因（`--quickPlaySingleplayer=RigSession` 已确认送达；本轮已发现"世界目录被拍平到
   saves/ 根"这一坑，修正后 1.21 复现成功，1.20.1 需重试）。

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
