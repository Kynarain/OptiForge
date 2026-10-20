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

#### 突破：1.21.6（N/A）现在能启动（2026-10-20）

两处修复让 `1.21.6 + Forge 56.0.9 + OptiFine J6_pre3` 从"启动即死"变成
**`Setting user` + `Sound engine started` + 250 条 `[OptiFine]` + 0 崩溃报告 + stderr 0 字节**
（OptiFine 自己报 `Forge version: 56.0.9`）。

1. **`OwnRefs`**：把 OptiFine 自有类里对 `net/minecraftforge/**`、`net/neoforged/**` 的**类字面量**
   （`ldc Type`）换成 `Object.class`。1.21.6 的自有类里只有 3 个文件提到 Forge，致命的是
   `net.optifine.reflect.Reflector` 里对 `net.minecraftforge.eventbus.api.Event` 的类字面量——
   模块读边拿不到，就干脆不要硬链接（OptiFine 的 Reflector 本来就"类不存在"当正常情况）。
2. **`ForgeAccessTransformers` 漏了类级 AT**：Forge 的 cfg 里有 `public a.b.C` 这种只有两个 token 的行，
   之前用 `parts.length < 3` 直接跳过，于是 `ModelDiscovery$ModelWrapper` 没被 public 化，
   报 `IllegalAccessError: failed to access class ... from class ModelContext`。
   现在类级授权也会应用（1.21 上 AT 授权数 335 → 392，重放到的类 21 → 39，回归通过）。

1.21 回归：`Dev joined the game`、0 崩溃、161 条 `[OptiFine]`，确认没有副作用。

### 1.21.6 进世界的下一个阻塞

进世界时 `Dev joined the game` 已出现，随后崩溃：

    NoSuchMethodError: 'boolean BlockModelPart.useAmbientOcclusion(BlockState, RenderType)'
      at ModelBlockRenderer.renderModel
      at ModelBlockRenderer.tesselateBlock

我们替换的 `BlockModelPart`（来自 OptiFine 载荷，`extends IForgeBlockModelPart`）只有
`useAmbientOcclusion()`，没有 Forge 那个带 `(BlockState, RenderType)` 的重载——说明该重载是 Forge
**运行时**才加上去的，而我们用 OptiFine 的补丁整类替换后丢掉了它。下一步：要么让 Forge 的补丁再叠加一次
（确认它是不是 Mixin/transformer），要么在 `OwnRefs` 之外加一遍"保留 Forge 后加成员"的处理
（例如从 Forge 的 client jar 里取同名类做成员并集）。
### 0.5.0：七条 1.21.x 线全部实测通过（2026-10-20）

| MC | Forge | OptiFine | 官方 Forge 列 | 启动 | 进世界 |
|---|---|---|---|---|---|
| 1.21 | 51.0.33 | `J1_pre9` | N/A | 是 | 是 |
| 1.21.6 | 56.0.9 | `J6_pre3` | N/A | 是 | 是 |
| 1.21.7 | 57.0.3 | `J6_pre7` | N/A | 是 | 是 |
| 1.21.8 | 58.1.26 | `J6_pre13` | 兼容 | 是 | 是 |
| 1.21.9 | 59.0.5 | `J7_pre2` | N/A | 是 | 是 |
| 1.21.10 | 60.1.18 | `J7_pre9` | 兼容 | 是 | 是 |
| 1.21.11 | 61.2.4 | `J9_pre4` | 兼容 | 是 | 是 |

每条都是 `Setting user` + `Sound engine started` + `Dev joined the game` + 0 崩溃报告 + stderr 0 字节，
OptiFine 自己打印对应的 `Forge version:`。1.21.7/8/9/10/11 都**不需要新增兼容补丁**，说明管线本身是稳的。

rig 经验：NeoForge 21.7 那个 13.6 MB 的世界会在 `Preparing spawn area: 51%` 卡住，换同实例的
`CaptureWorld`（3.8 MB）秒进——卡的是世界不是加载器。
### 26.x：环境就绪，卡在一处接口注入（2026-10-20）

- 已下载 **JDK 25**（`build/jdk25`，Temurin 25.0.4.1）——26.x 必需，JDK 27 上 Forge 64 会因
  `Module.implAddExportsOrOpens` 缺失报错。
- **对照组**：纯 Forge 26.1.2（64.1.5）+ JDK 25 → `Setting user` + `Sound engine started`、0 崩溃，
  说明 rig 本身没问题。
- **带 OptiForge**：仍然
  `NoClassDefFoundError: net/minecraftforge/client/extensions/IForgeVertexFormat`，抛点是游戏类
  `com.mojang.blaze3d.vertex.DefaultVertexFormat.<clinit>`。
  - 我们产出的 `DefaultVertexFormat`（OptiFine 载荷）里**没有任何** Forge 引用（扫过常量池），
    基包 jar（`versions/26.1.2-forge-64.1.5/26.1.2-forge-64.1.5.jar`）里的同名类也**没有**实现该接口
    → 说明这个接口是 Forge 在运行时（Mixin/transformer）后加的。
  - 已实现"并集"（`mergeInPlaceAdditions`：把 transform 入参里多出来的接口/方法/字段并进我们的结果），
    但**入参里也没有**该接口 → 说明它是在我们之后才被加上的，或者由另一种机制（AT+运行时注入）完成。
    并集本身无害：1.21.11 回归仍 `Dev joined the game`、0 崩溃。
- 下一步：在 Forge 64 里定位给 `DefaultVertexFormat` 加 `IForgeVertexFormat` 的那段代码
  （大概率是 forge 的 mixin 配置），确认它在我们之后运行却没生效的原因；或直接由 OptiForge
  按"类名 → 应实现的接口"补上（先只补这一个）。另需照旧补 `optifine/**` 的
  `OptiFineResourceLocator`（26.x 的 Reflector 报 "Class not present"）。
### 本轮发现的两个"验收集成"问题（重要）

1. **实例 `mods/` 里的旧 OptiForge jar 会被一起加载**：每次测试都把新 jar 拷进去，但旧的
   `OptiForge-0.2.0.jar` 一直在，Forge 会把两个都加载，实际跑的是**旧代码**（表现为 pack 目录名一直是
   `v2-…`，而源码已经是 v3）。**从今以后每次启动前清掉除被测 jar 之外的所有 `OptiForge-*.jar`。**
2. **pack 缓存键只看输入 jar 的哈希**，不含"处理逻辑版本"，所以旧缓存会被无限复用——`OwnRefs`、
   类级 AT、`CompatFixes` 这些包构建期的修复在旧缓存上**根本没跑过**。已把
   `PACK_VERSION` 提到 `v3`（改处理逻辑时必须再提）。

这两点意味着：**上一轮 1.21.7 / 1.21.8 / 1.21.9 / 1.21.10 / 1.21.11 的"进世界"需要在干净环境下重验**。
已经用干净环境（单 jar + 新建 v3 pack）重验通过的是：**1.21（51.0.33）**、**1.21.6（56.0.9）**、
**1.21.7（57.0.3）**、**1.21.8（58.1.26）**、**1.21.9（59.0.5）**、**1.21.10（60.1.18）**、**1.21.11（61.2.4）**——
七条线全部是 `Dev joined the game` + 0 崩溃 + stderr 0 字节，pack 目录都是 `v3-…`。
工具：`tools/rig-verify.ps1` 负责"清掉旧 jar、清 pack、跑一次、从日志里抓证据"，避免再被试运行环境误导。

### `mergeInPlaceAdditions` 的坑

为了不丢别的 transformer 已加到类上的接口/成员，transform 现在把入参里多出来的接口/方法/字段并进结果。
第一版把**原版的 `<clinit>`** 也并了进来，而 OptiFine 有时把原版静态字段改成实例字段
（`BreezeWindLayer.TEXTURE_LOCATION`），于是 `IncompatibleClassChangeError: Expected static field`。
现在**跳过 `<clinit>`/`<init>`**，1.21 与 1.21.6 干净环境下均通过。
### 26.1.2 的真实首因（本轮定位）

用 `-Xlog:class+load=info` 跑完后，日志里**第一个**异常不是 `IForgeVertexFormat`，而是：

    java.lang.IllegalArgumentException: wrong number of arguments: 4 expected: 5
      at net.optifine.reflect.Reflector.callLong
      at com.mojang.blaze3d.platform.Window.<init>(Window.java:113)

即 OptiFine 通过反射调 `ImmediateWindowHandler.setupMinecraftWindow`（4 个参数），而 Forge 26 里这个方法
变成了 **5 个参数**。这条只是 OptiFine 记的 WARN；随后 Forge 打 `Preparing crash report`，崩溃报告里的
`NoClassDefFoundError: IForgeVertexFormat` 是**后续**错误，不是首因。

下一步（二选一）：

1. 在 `CompatFixes` 里给 `net.minecraft.client.Minecraft`/`Window` 的 Reflector 调用补上第 5 个参数
   （需要先确认真实签名：`javap` Forge 26 的 `ImmediateWindowHandler`）；
2. 或者让 OptiFine 跳过 `ImmediateWindowHandler` 路径（Forge 26 自己的窗口处理接管），代价是 early-window
   行为可能与 OptiFine 预期不同。

另外 26.x 还缺 `optifine/**`（`OptiFineResourceLocator`）的 staging：日志里 OptiFine 自己报
`(Reflector) Class not present: optifine.OptiFineResourceLocator`。
### 26.x：窗口反射已修，剩下 `IForgeVertexFormat`（本轮）

已实现并生效（`compat fixes: 1`，日志有 `[OptiForge] compat fix in com.mojang.blaze3d.platform.Window:
bypassed ImmediateWindowHandler.setupMinecraftWindow (arity changed on 26.x) x1`）：
在 26.x 上把 OptiFine `Window` 里那个 4 参数的反射调用改成直接返回 `0`，`Window` 会把 0 当作
"没有 early window"，回落到原版窗口创建。之前那条 `wrong number of arguments: 4 expected: 5` 消失。

（顺带修了两处：MC 版本必须在**建包之前**就已知，现在作为参数传进 `prepare()`，不再依赖静态字段；
版本门控的 compat 规则只有 `mcVersion` 以 `26.` 开头时才启用，所以 1.21.x 七条线不受影响。）

**剩下唯一阻塞**：`DefaultVertexFormat.<clinit>(第 9 行)` 触发定义某个类时，
`NoClassDefFoundError: net/minecraftforge/client/extensions/IForgeVertexFormat`（`defineClass1` 处）。
已确认该名字**不在**我们产出的任何 class、`own` 类、基包 jar、26.1.2 profile 的 40 个库里；
需要下一步用 `-Xlog:class+load=info` 找到**正要被定义的那个类**（日志里 `class,load` 的那一行紧挨着报错），
再看它为什么带上这个接口——大概率是 Forge 的 `minecraft` 模块读边问题（`forge` 模块不可读）。
### 26.1.2 现在能到标题界面（本轮）

两处修复叠加后，`26.1.2 + Forge 64.1.5 + OptiFine K1_pre2`（JDK 25）从"窗口创建前就崩"变成：

    Setting user            : 1
    Sound engine started    : 1
    [OptiFine] lines        : 195
    crash reports           : 0

1. **窗口反射**：`Window` 里对 `ImmediateWindowHandler.setupMinecraftWindow` 的 4 参数反射调用改为返回 0，
   回落到原版窗口创建（见上一节）。
2. **摘掉运行时不存在的外部接口**（`ForgeInterfaces`）：OptiFine 26.x 的补丁会让 `VertexFormat`、
   `PoseStack`、`GpuTexture`、`Font`、`GuiGraphics` 等 `implements net.minecraftforge.client.extensions.IForge*`，
   而 Forge 64 的 jar 里**这些接口已经不存在**（1.21.x 的 universal 都还有）。做法：用"本次启动版本的
   jar 集合"建一个类名索引，补丁类里凡是 `net/minecraftforge/**`、`net/neoforged/**` 的接口在索引里找不到就摘掉。
   26.1.2 上摘掉 1 个类（`VertexFormat`），1.21.7 上索引 51952 个类、摘掉 0 个（接口都在，行为不变，回归通过）。

   **注意**：索引必须限定在"本次启动的 Forge 版本"的 jar；一开始用了整个 rig 的 libraries，1.21.x 的 universal
   让索引误报"存在"，结果在 1.21.7 上把真接口摘掉、直接把已验证的线弄坏（已修复并回归）。

**进世界仍未达成**：世界开始加载（`world_gen_settings.dat` 读不到只是 NeoForge 的文件差异），但 200 秒内
没出现 `joined the game`，像是卡在加载/生成阶段。
### 26.1.2 已经能进世界，但进世界后立刻撞 Forge 的 loot 断言（本轮）

用一个**由 Forge 26.1.2 专用服务器生成的世界**（`--installServer` + 首次启动生成）后：

    [OptiFine] Forge version: 64.1.5
    [OptiFine] Disable Forge light pipeline
    Dev joined the game            <-- 进世界成功
    [OptiFine] lines : 203

进世界后 ~0.2 秒，**集成服务器**崩：

    IllegalStateException: Can not retrieve LootModifierManager until resources have loaded once.
      at ForgeInternalHandler.getLootModifierManager
      at ForgeHooks.modifyLoot
      at LootTable.getRandomItems
      at BlockBehaviour.getDrops

**对照**：同一个世界、纯 Forge 26.1.2（无 OptiFine）→ `joined: True`、**0 崩溃**。所以这是 OptiFine
影响资源重载时序导致 Forge 的 `LootModifierManager` 还没初始化就去掉落物。下一步查 OptiFine 26.x 对
资源重载/`ReloadableResourceManager` 的补丁是否吞掉了 Forge 的 reload listener 事件。

rig 经验（有用）：26.x 用 NeoForge 的世界会卡在 `world_gen_settings.dat` 读不到；**用 Forge 自己的
server 生成一个世界**再复制到客户端的 `saves/` 就正常（这条也解释了之前 26.1.2 一直"进不了世界"）。
### 0.6.0：八条线干净通过，1.20.6 一次通过（2026-10-20）

新增 **1.20.6 + Forge 50.2.14 + OptiFine `1.20.6 J1_pre18`**：`joined the game`、141 条 `[OptiFine]`、
0 崩溃、`v3` pack——**不需要任何新修复**。

当前干净通过的组合（都是 `Dev joined the game` + 0 崩溃 + stderr 0 字节）：

| MC | Forge | OptiFine | 官方 Forge 列 |
|---|---|---|---|
| 1.20.6 | 50.2.14 | `J1_pre18` | 兼容 |
| 1.21 | 51.0.33 | `J1_pre9` | N/A |
| 1.21.6 | 56.0.9 | `J6_pre3` | N/A |
| 1.21.7 | 57.0.3 | `J6_pre7` | N/A |
| 1.21.8 | 58.1.26 | `J6_pre13` | 兼容 |
| 1.21.9 | 59.0.5 | `J7_pre2` | N/A |
| 1.21.10 | 60.1.18 | `J7_pre9` | 兼容 |
| 1.21.11 | 61.2.4 | `J9_pre4` | 兼容 |

边界状态：`26.1.2`（N/A）能启动 + 进世界，但进世界后撞 Forge 的 `LootModifierManager` 断言
（纯 Forge 对照不崩）；`1.20.1` 只验证了启动。
### 26.1.2 的 loot 断言：查到的结论（下一轮从这里继续）

- `ForgeEventFactory.onResourceReload(...)` 负责 post `AddReloadListenerEvent`，
  而调用它的 `ReloadableServerResources`（在 Forge 的 client jar 里，二进制补丁过）**不在我们的补丁集**
  ——所以钩子没丢，事件应该照常发。
- 因此这是**时序**问题：quickPlay 进世界时，集成服务器的资源重载还没跑完（OptiFine 的
  ResourcePack/资源加载把它推后了），服务器线程先算掉落物 → `LootModifierManager` 还是 null → 断言。
- 纯 Forge 对照同世界不崩，说明差异确实来自 OptiFine 的资源处理路径。

下一轮候选：
1. 让 OptiFine 的资源重载与 `AddReloadListenerEvent` 的先后关系回到原版（看 OptiFine 26.x 对
   `ReloadableResourceManager.createReload`/`CloseableResourceManager` 的补丁）；
2. 或在 `ForgeInternalHandler.getLootModifierManager` 上做兜底（Forge 的类，我们可以 transform：
   `INSTANCE` 为空时返回一个空的 manager——需要先确认 `LootModifierManager` 能不能无参构造）；
3. 或延迟"进世界"（不可控，跳过）。
### 1.21.1：进世界后 `Direction.getUpdateOrder()` NPE（本轮发现，未修）

`1.21.1 + Forge 52.1.20 + OptiFine J1_pre9`（官方 N/A）能启动、能进世界，但进世界后 ~0.2s：

    NullPointerException: Cannot invoke "java.util.List.iterator()" because the return value of
    "net.minecraft.core.Direction.getUpdateOrder()" is null
      at Level.updateNeighbourForOutputSignal

- 我们产出的 `Direction`（OptiFine 补丁版）里**没有** `getUpdateOrder`；运行时能调到它说明是 Forge 的 Mixin
  补的，但它依赖的静态数据没被初始化——即"整类替换 + 只并成员、不并 `<clinit>`"的后果。
- 试过 `-Doptiforge.skipClasses=net.minecraft.core.Direction`（本轮新增的诊断开关）：能跳过替换，但 OptiFine
  自己的 `RenderEnv` 又需要 OptiFine 给的 AT（`Direction.VALUES` 可访问）→ `IllegalAccessError`。所以不能简单跳过。
- 正确的下一步：合并时**把入参 `<clinit>` 里只初始化"我们缺失字段"的那部分搬过来**（不能整段并，避免二次初始化）。

顺带确认：1.21.1 的基包本来就选的是 `1.21.1-forge-52.1.20.jar`（Forge 的 client jar），不是原版 jar——
`findBaseJar` 现在也显式优先 Forge client jar（对其它线无影响）。
### 26.2 也测了（本轮）：能启动 + 进世界，随后崩；两处尝试已回退

- `26.2 + Forge 65.1.5 + OptiFine K2_pre4`（JDK 25）：`Setting user`、`Sound engine started`、
  192~200 条 `[OptiFine]`、`Dev joined the game`（世界用 Forge 26.2 专用服务器生成）。
- 进世界后客户端崩 `IllegalAccessError: LevelExtractor tried to access private method
  LevelRenderer.weatherEffectRenderer`——Forge 26.2 的 AT 里有收窄行
  `private-f net.minecraft.client.renderer.LevelRenderer weatherEffectRenderer`，我们原样应用后把方法变成 private。
  - 尝试"AT 只放宽不收窄"：26.2 的这个错消失了，但**已验证的 1.21.7 立刻回归**（1 崩溃、进不了世界），
    所以已回退。正确做法是按行/按版本判断哪条收窄是给同包调用者用的，而不是全局策略。
  - 修好 AT 之后 26.2 的下一个错变成 `NoSuchMethodError: Gui.popLayer()`（`Screen.onClose` → `Gui`），
    与我们替换 `Gui` 后丢掉 Forge 运行时补的方法同类。
- 1.21.1 的 `<clinit>` 字段初始化合并也试过：抽取语句时用"行号标签"当边界不成立，
  在 `ResourceLocation.<clinit>` 产生 `VerifyError: Operand stack underflow`，已回退。正确做法需要
  **栈模拟**来确定语句起点。

两处实验都已回退，`tools/build.ps1` / 1.21.7 回归通过（`joined the game`、0 崩溃）。
### 26.x：AT 收窄按版本处理（本轮），`popLayer` 类问题仍在

- AT 重放现在**只在 26.x 上"只放宽不收窄"**（`ForgeAccessTransformers` 的 `widenOnly`，由 `mcVersion`
  决定）。效果：26.2 的 `IllegalAccessError: LevelExtractor -> private LevelRenderer.weatherEffectRenderer`
  消失，崩溃数 2 → 1；1.21.x 行为完全不变（1.21.7 复查 `joined the game`、0 崩溃）。
- 26.2 剩下的错是 `NoSuchMethodError: Gui.popLayer()`（`Screen.onClose` 调用）。查过：**基包 jar 里的
  `Gui` 也没有 `popLayer`**，所以它是 Forge 运行时加给 `Gui` 的方法，而我们整类替换后它没生效——
  和 1.21.1 的 `Direction.getUpdateOrder()` 同一类问题（Mixin 在我们之后注入，注到我们替换的类上不成）。
- 试过让 transformer **投 DEFER**（期望最后运行、能看到 Forge 的注入）：ModLauncher 直接
  `VoteDeadlockException`（`ClassTransformer.performVote`）。该开关 `-Doptiforge.deferVote=true` 保留但默认关闭，
  结论记在这里：这条路不通。

下一步（真解法）：把"整类替换"改成**差异应用**——构建期算出 OptiFine 补丁相对基包的差异，运行时把差异
应用到 Forge 处理过的类上，而不是用我们的整份字节替换；这样 Mixin 的注入天然保留。
### 26.x：父类被补丁改回 Object 的问题修好了（本轮）

- 现象：26.2 进世界后 `NoSuchMethodError: Gui.popLayer()`。查证：Forge 的 client jar 里
  `Gui extends net.minecraftforge.client.gui.overlay.ForgeLayerInstance`（`popLayer` 继承自它），
  而 **OptiFine 的补丁输出把父类写回了 `Object`**（它的 xdelta 以原版为基准）。
- 修法（`OptifineClassTransformer`）：合并时若我方父类是 `Object` 而入参（Forge 处理过）的父类不是，
  **采纳入参的父类**，并把构造器里的 `invokespecial java/lang/Object.<init>()V` 改写成
  `ForgeLayerInstance.<init>(Minecraft)`（`Gui` 构造器第一个参数就是 `Minecraft`）。
  不重写会得到 `VerifyError: Bad <init> method call ... Type java/lang/Object is not assignable to Gui`。
- 结果：**26.2 的 `popLayer` 错误消失**，进世界后剩下的唯一崩溃就是与 26.1.2 相同的
  `LootModifierManager` 时序断言——**两条 26.x 线现在收敛到同一个问题**。
- 回归：1.21.7 这条规则不触发（无 `adopting superclass` 日志），仍 `joined the game`、0 崩溃。
### 0.7.0：26.2（N/A）也干净通过（2026-10-20）

`26.2 + Forge 65.1.5 + OptiFine K2_pre4`（JDK 25）：`Setting user`、`Sound engine started`、
**`Dev joined the game`**、200 条 `[OptiFine]`、**0 崩溃报告**。

这一步靠两个修复：

1. **父类被补丁改回 Object**（上一轮）：`Gui extends ForgeLayerInstance` 被 OptiFine 的补丁写回 `Object`，
   合并时采纳入参父类并重写构造器 `super` 调用。
2. **loot 管理器时序兜底**（本轮，`RuntimePatches`）：26.x 上集成服务器会在资源重载完成前 tick，
   `ForgeHooks.modifyLoot` 调 `ForgeInternalHandler.getLootModifierManager()` 抛断言。
   给这个方法加 `catch (Throwable)` → 返回未修改的掉落物；管理器在时行为不变，不在时掉落物只是不经过
   全局 loot modifier，而不是把世界打死。仅 26.x 启用。

26.1.2 在 loot 兜底后继续往前：现在崩在 `EntityRenderer.submitNameDisplay` 的
`NullPointerException: renderNameplateEvent is null`（Forge 的 `HasResult`/`Result` 事件对象为空），
下一轮就从这里继续。
### 0.8.0：26.1.2 也干净通过（十条第 6 条 N/A）

`26.1.2 + Forge 64.1.5 + OptiFine K1_pre2`（JDK 25）：`joined the game`、218 条 `[OptiFine]`、**0 崩溃**。

它的最后一处是 `EntityRenderer.submitNameDisplay` 的
`NullPointerException: renderNameplateEvent is null`：

- OptiFine 26.1.2 的反射调用 `Reflector.ForgeEventFactoryClient_fireRenderNameTagEvent` **传 6 个参数**，
  而 Forge 64 里的 `ForgeEventFactoryClient.fireRenderNameTagEvent` 只有 **5 个** → 反射器认为"方法存在"
  但调用返回 null → NPE。
- 修法：建包时用 ASM **实测该方法的参数个数**（`ForgeInterfaces.maxParameterCount`），
  26.x 上 `arity < 6` 就把那段 `exists()` 判定改成 false，走原版名牌路径（判定前 `show`/`content` 已经算好）。
  诊断里会打印 `nameplate event arity: 5 (reflector bypassed)`。
- 顺带把 `OwnRefs`（中和外部类字面量）改成**只中和"本次启动的 jar 里不存在"的类**：之前一刀切导致 26.1.2
  的 `ForgeEventFactoryClient` 字面量也被换成 `Object`。Event 这类真正读不到的仍然被中和（eventbus 是独立
  artifact，不在索引里），所以 1.21 的修复不受影响——1.21 回归 `joined the game`、161 条、0 崩溃。

26.2 同版复验：315 条 `[OptiFine]`、0 崩溃。
### 0.9.0：1.21.1 也干净通过（十一路第 7 条 N/A）

`1.21.1 + Forge 52.1.20 + OptiFine J1_pre9`（JDK 21）：`joined the game`、172 条 `[OptiFine]`、**0 崩溃**。

最后两处：

1. **被采纳字段的惰性默认值**：`Direction.getUpdateOrder()` 是从入参（Forge 处理过）并进来的方法，
   它读的静态字段 `UPDATE_ORDER` 由原版 `<clinit>` 初始化，而我们不并 `<clinit>` → 返回 null。
   现在合并时为"被采纳方法读取的被采纳字段"插入 `if (FIELD == null) FIELD = <空容器>;` 守卫
   （数组 → 空数组，`Map` → `HashMap`，`List`/`Collection` → `Collections.emptyList()`）。
2. **清掉 `final`**：那个字段是 `static final`，从别的（非 `<clinit>`）方法写它会
   `IllegalAccessError: Update to static final field ... attempted from a different method`，
   所以加守卫时同时 `& ~ACC_FINAL`。

另外 `tools/rig-verify.ps1` 的"进世界"检测补上了新版本的情况：MC 26 的进服消息走可翻译组件，
日志里是本地化文本（`Dev加入了游戏`），脚本改为同时认 `logged in with entity id`。

回归：1.21.7（`joined the game`、250 条、0 崩溃）、26.2（`logged in with entity id`、0 崩溃、315 条）。

1.20.1 的进世界仍未成功：**`--quickPlaySingleplayer` 在 Forge 47 下不触发**（日志里只有启动完成，
没有任何世界加载动作），属于测试环境限制；该线启动本身已验证。
### 1.20.1 进世界：三种自动方式都试过（环境限制）

`1.20.1 + Forge 47.4.23 + OptiFine I6` 的**启动**一直是通过的（112 条 `[OptiFine]`、0 崩溃、OptiFine 在改
Forge 配置）。进世界在本测试环境里试过三种方式，**都没有触发世界加载**：

1. `--quickPlaySingleplayer=CaptureWorld`：参数确实送到了游戏（日志 args 可见），但 1.20.1 没有任何反应；
2. `--server 127.0.0.1 --port 25566`（配一个 Forge 1.20.1 专用服务器，世界已生成）：客户端不发起连接；
3. `--quickPlayMultiplayer 127.0.0.1:25566`：同样不发起连接。

服务器端 `Done (11.277s)` 正常，说明服务器没问题；是 Forge 47 客户端对这几个参数的支持问题（1.20 之后
这些入口改成了 quickPlay 系列，Forge 47 的 `Main` 补丁似乎没有把它们接上）。**这不是加载器的问题**，
但本环境无法自动验证，记录在此以免误判成"通过了"。
### 最终全量回归（0.9.0 单 jar + 清空 pack，逐条重跑）

| MC | Forge | OptiFine | 官方 Forge 列 | 进世界 | 崩溃 | OptiFine 日志 |
|---|---|---|---|---|---|---|
| 1.20.6 | 50.2.14 | `J1_pre18` | 兼容 | 是 | 0 | 132 |
| 1.21 | 51.0.33 | `J1_pre9` | **N/A** | 是 | 0 | 161 |
| 1.21.1 | 52.1.20 | `J1_pre9` | **N/A** | 是 | 0 | 172 |
| 1.21.6 | 56.0.9 | `J6_pre3` | **N/A** | 是 | 0 | 249 |
| 1.21.7 | 57.0.3 | `J6_pre7` | **N/A** | 是 | 0 | 250 |
| 1.21.8 | 58.1.26 | `J6_pre13` | 兼容 | 是 | 0 | 237 |
| 1.21.9 | 59.0.5 | `J7_pre2` | **N/A** | 是 | 0 | 236 |
| 1.21.10 | 60.1.18 | `J7_pre9` | 兼容 | 是 | 0 | 226 |
| 1.21.11 | 61.2.4 | `J9_pre4` | 兼容 | 是 | 0 | 146 |
| 26.1.2 | 64.1.5 | `K1_pre2` | **N/A** | 是 | 0 | 210 |
| 26.2 | 65.1.5 | `K2_pre4` | **N/A** | 是 | 0 | 199 |

（26.x 需要 JDK 25；进世界用 `logged in with entity id` 判定，因为 26 的进服消息经过了本地化。）

唯一未复现进世界的是 `1.20.1 + Forge 47.4.23`：启动通过（112 条 `[OptiFine]`、0 崩溃），但 Forge 47
客户端不响应 `--quickPlaySingleplayer` / `--server` / `--quickPlayMultiplayer` 三种自动进服方式，
属于本测试环境限制（专用服务器 `Done (11.277s)` 正常）。
### 1.20.2（矩阵里最后一条现代 N/A 线）：本轮现状

- 该线只有 1 个构建：`preview_OptiFine_1.20.2_HD_U_I7_pre1.jar`，官方标 `Forge N/A`；Forge 侧装了 48.0.1，
  世界用 Forge 1.20.2 专用服务器生成。
- rig 最初缺少 Forge 生成的 vanilla 映射 jar（`client-1.20.2-…-srg.jar` 等）：**必须先把原版 1.20.2 的
  jar/json 放进安装器 scratch 的 `versions/1.20.2/`，再跑 `--installClient`**，否则 `--installClient` 不会
  产出它们（这就是 STATE 里那条"安装器不下载原版"的坑的延伸）。补齐后启动能走更远（22 条 `[OptiFine]`）。
- 当前停在：
  `IllegalAccessError: Update to non-static final field net.minecraftforge.client.gui.overlay.ForgeGui.f_291320_
   attempted from a different class (ForgeGui) than the field's declaring class`
  （`ForgeGui.<init>` 来自 `Minecraft.<init>`）。这个类**不在我们的补丁集**里，怀疑是修复 rig 库时引入了
  重复的 ForgeGui 定义（同名类被两个 loader 定义），下一轮先核对 rig 的库是否有重复 jar。
### 1.20.2 的 ForgeGui 错误：定位到"只有 OptiFine 在场才出现"

三组对照（都在同一个 rig、同一个 Forge 1.20.2-48.0.1）：

| 组合 | 结果 |
|---|---|
| 纯 Forge 1.20.2（无 OptiForge、无 OptiFine） | 正常：`Setting user` + `Sound engine started`、0 崩溃 |
| 有 OptiForge、**没有** OptiFine | 正常：`Setting user`、0 崩溃 |
| 有 OptiForge + OptiFine `1.20.2 HD_U_I7_pre1` | `IllegalAccessError: Update to non-static final field ForgeGui.f_291320_ attempted from a different class (ForgeGui)` |
| 同上，且 `-Doptiforge.skipClasses=net.minecraft.client.Minecraft` | 同一个错误 → 与"是否替换 Minecraft"无关 |

已排除：`ForgeGui` **不在**我们的补丁集、我们的 staged mod jar 里**没有** `net/minecraftforge/**` 类、
OptiFine jar 里也没有 `ForgeGui` 的补丁条目（只有 `notch/net/minecraftforge/client/extensions/IForgeGuiGraphics.class`
这种它自带的 stub）。所以这是 **OptiFine 1.20.2 这个构建与 Forge 48 的相互作用**，不是加载器对它做了错误替换。
该线只有这一个构建，若以后有新的 1.20.2 构建可以再试。
### 0.9.1：修两个只在"版本隔离启动器"上暴露的问题

用户用 PCL 的 `versions/1.20.1-Forge_47.4.20/` 实例测试时出现
`VerifyError: Bad invokespecial instruction: interface method reference is in an indirect superinterface`
（`Mob.lambda$jumpInFluid$4` → `IForgeLivingEntity.jumpInFluid`），而同一组合在测试环境正常。

两个原因，都在"库目录定位"上：

1. **`libraries` 在上两级**：PCL 的实例目录是 `.minecraft/versions/<id>`，库在 `.minecraft/libraries`。
   `LaunchPaths` 原来只查 `gameDir/libraries` 和上一级，于是类名索引几乎为空。
2. **空索引导致误删**：索引不全时 `ForgeInterfaces.strip` 把**真实存在**的 Forge 接口当成"运行时没有"
   从补丁类上摘掉（那次摘了 10 个，`Mob` 的 `IForgeLivingEntity` 就在里面）→ `invokespecial` 的
   直接父接口要求不满足 → VerifyError。

修法：`LaunchPaths` / `findBaseJar` 都**向上最多找 4 层**的 `libraries`；另外新增 `SuperInterfaceFixer`
（建包期发现 `invokespecial` 到接口方法却没有声明该接口时补上，作为兜底）；`PACK_VERSION` 提到 `v4`，
让所有旧缓存重建。

实测（用户同实例、Java 22、PCL profile）：`Setting user`、`Sound engine started`、112 条 `[OptiFine]`、
0 崩溃；索引 38351 个类、误删 0 个。回归：1.21.7 仍 `joined the game`、0 崩溃。
### 0.9.2：视频设置界面崩溃（OptiFine 改父类后误并了旧继承链的方法）

用户点"视频设置"时崩：

    VerifyError: Bad invokespecial instruction: current class isn't assignable to reference class.
      at VideoSettingsScreen.m_6050_(DDD)Z @82

原因：OptiFine 的 `VideoSettingsScreen` 被**改父类**成 `net.optifine.gui.GuiScreenOF`，
而运行时合并把原版（父类 `OptionsSubScreen`）的 `mouseClicked`（`m_6050_`）也并了进来——
里面的 `super.mouseClicked(...)` 指向已经不在继承链上的类 → 校验失败。

修法：合并时若**父类不一致**（`hierarchyChanged`），跳过所有含 `super.` 调用（`invokespecial` 到非自身类）
的方法。离线验证：对同一份入参，合并后不再包含 `m_6050_`。
### 0.9.3：OptiFine 文本显示成键名（mod jar 没有 pack.mcmeta）

现象：视频设置里 OptiFine 的条目是键名（`of.options.AO_LEVEL`、`of.options.DYNAMIC_LIGHTS`、
`of.options.shadersTitle`…），原版条目正常。截图确认后看日志，有两处线索：

1. 启动警告界面：`optiforge-optifine-classes.jar failed to load a valid ResourcePackInfo`；
2. 日志：`Missing metadata in pack mod:optiforge_optifine_classes`。

原因：我们 stage 的 jar 只有 `assets/**` 而没有 `pack.mcmeta`，Forge 不把它注册成资源包，于是
OptiFine 的 `Lang.resourcesReloaded()` 读不到 `assets/minecraft/optifine/lang/<locale>.lang`
（OptiFine 的 UI 文本全在这些 `.lang` 里，`zh_cn.lang` 的 672 个键是齐的，所以不是翻译缺条目）。

修法：stage 时写入 `pack.mcmeta`（`pack_format` 按 MC 版本给：1.20.1 → 15 等），`PACK_VERSION` → `v5`。
验证：两条警告都不再出现，`pack v5-…` 正常构建，118 条 `[OptiFine]`、0 崩溃。
### 0.10.0：mod 身份、协议、缺失 OptiFine 时的提示、版本号修正

- **modId 改为 `optiforge`**（原来是 `optiforge_optifine_classes`），`displayName="OptiForge"`，
  mods 列表里就是一个叫 OptiForge 的 mod；描述与 GitHub 仓库一致
  （"OptiFine on Forge, with Forge Mod Loader loaded alongside."）。
- **协议改为 MPL 2.0**（`mods.toml` 的 `license` 字段 + 仓库根目录 `LICENSE`）。
- **没有 OptiFine 时**：创建 `<game>/optiforge/`，写入 `PUT-OPTIFINE-HERE.txt`（中英双语，含下载地址与
  "不要放 mods/" 的说明），同时清掉上一轮遗留的 `mods/optiforge-optifine-classes.jar`；找到 OptiFine 后
  自动删除该提示文件。实测：无 OptiFine → 目录+提示文件出现、游戏按原版 Forge 启动、0 崩溃；
  放回 OptiFine → 提示文件消失、132 条 `[OptiFine]`、0 崩溃。
- **版本号修正**：`Diagnose.VERSION` 原来是硬编码的 `0.1.0`（诊断报告和生成的 mods.toml 都跟着错）。
  现在由构建写入 `optiforge/version.txt`（`build.ps1` 与 Gradle `writeVersion` 两处），运行时读取；
  实测报告与 mods.toml 均为 `0.10.0`。
### 0.11.0：缺 OptiFine 改成游戏内提示（照 OptiFabric 的做法），并给生成 jar 起可读名字

OptiFabric 的做法（`MixinTitleScreen`）：缺 OptiFine 时在标题界面打开一个 ConfirmScreen（红色标题），
带"打开 mods 文件夹 / 帮助"按钮。OptiForge 没有 Mixin，改成等价的实现：

- 服务仍然创建 `<game>/optiforge/`，并**总是**在 `mods/` 里生成 `OptiForge-<版本>-classes.jar`
  （缺 OptiFine 时里面只有提示类，没有 OptiFine 类），mod 列表里始终是一个 **OptiForge**；
- 生成的 mod 构造函数启动一个守护线程，等标题界面出现后弹出 ConfirmScreen：
  `OptiForge 需要 OptiFine`，正文含 `optiforge/` 绝对路径、下载地址、"不要放 mods/"，点 Yes 打开该文件夹；
  拿不到 ConfirmScreen 时退化为系统 toast，再不行只写日志，绝不崩；
- `Notice` 类随生成 jar 一起打包，并用 ASM `ClassRemapper` 重定位到 `optiforge/generated/Notice`
  （只改名字会留下指向旧类的常量池引用，实测 `IllegalAccessError: ... private field`）；
- 移除 `PUT-OPTIFINE-HERE.txt`（用户要求不要文件提示）；旧版本的 `optiforge-optifine-classes.jar`
  启动时自动删除，避免两个模组抢同一个 modId；
- `findOptifineJar` 照 OptiFabric 的挑选方式：优先文件名含 `OptiFine`，其次验证 jar 里确实有
  `net/optifine/` 类，其它 jar 会被忽略并在诊断里记录。

实测（1.20.1 + Forge 47.4.20）：缺 OptiFine → 标题界面弹出提示框、0 崩溃、mods 里只有
`OptiForge-0.11.0-classes.jar`；放回 OptiFine → 提示不再出现、132 条 `[OptiFine]`、0 崩溃。
## 下一步（模块可见性）

1. 既然不能改 mod jar 的模块性，就**改引用**：给 OptiFine 自有类做一遍"非强制引用"处理——把
   `net/minecraftforge/**`、`net/neoforged/**` 的类字面量（`LDC Type`）换成
   `Class.forName` + 捕获异常的形式（OptiFine 的 Reflector 本来就把"类不存在"当正常情况），
   这样 `Reflector.<clinit>` 不会因为读不到 `Event` 而失败。
2. 或者让 staged jar 走 SERVICE 层（classpath 上的变换服务 jar），避开 mod 层的读边限制——但要注意
   之前的 split package/export 报错。
3. 26.x 还有 Java 25、`optionsof.txt`、`optifine/**`（`OptiFineResourceLocator`）三件事。
### 突破：1.21.6（N/A）现在能启动（2026-10-20）

两处修复让 `1.21.6 + Forge 56.0.9 + OptiFine J6_pre3` 从"启动即死"变成
**`Setting user` + `Sound engine started` + 250 条 `[OptiFine]` + 0 崩溃报告 + stderr 0 字节**
（OptiFine 自己报 `Forge version: 56.0.9`）。

1. **`OwnRefs`**：把 OptiFine 自有类里对 `net/minecraftforge/**`、`net/neoforged/**` 的**类字面量**
   （`ldc Type`）换成 `Object.class`。1.21.6 的自有类里只有 3 个文件提到 Forge，致命的是
   `net.optifine.reflect.Reflector` 里对 `net.minecraftforge.eventbus.api.Event` 的类字面量——
   模块读边拿不到，就干脆不要硬链接（OptiFine 的 Reflector 本来就"类不存在"当正常情况）。
2. **`ForgeAccessTransformers` 漏了类级 AT**：Forge 的 cfg 里有 `public a.b.C` 这种只有两个 token 的行，
   之前用 `parts.length < 3` 直接跳过，于是 `ModelDiscovery$ModelWrapper` 没被 public 化，
   报 `IllegalAccessError: failed to access class ... from class ModelContext`。
   现在类级授权也会应用（1.21 上 AT 授权数 335 → 392，重放到的类 21 → 39，回归通过）。

1.21 回归：`Dev joined the game`、0 崩溃、161 条 `[OptiFine]`，确认没有副作用。

### 1.21.6 进世界的下一个阻塞

进世界时 `Dev joined the game` 已出现，随后崩溃：

    NoSuchMethodError: 'boolean BlockModelPart.useAmbientOcclusion(BlockState, RenderType)'
      at ModelBlockRenderer.renderModel
      at ModelBlockRenderer.tesselateBlock

我们替换的 `BlockModelPart`（来自 OptiFine 载荷，`extends IForgeBlockModelPart`）只有
`useAmbientOcclusion()`，没有 Forge 那个带 `(BlockState, RenderType)` 的重载——说明该重载是 Forge
**运行时**才加上去的，而我们用 OptiFine 的补丁整类替换后丢掉了它。下一步：要么让 Forge 的补丁再叠加一次
（确认它是不是 Mixin/transformer），要么在 `OwnRefs` 之外加一遍"保留 Forge 后加成员"的处理
（例如从 Forge 的 client jar 里取同名类做成员并集）。
### 0.5.0：七条 1.21.x 线全部实测通过（2026-10-20）

| MC | Forge | OptiFine | 官方 Forge 列 | 启动 | 进世界 |
|---|---|---|---|---|---|
| 1.21 | 51.0.33 | `J1_pre9` | N/A | 是 | 是 |
| 1.21.6 | 56.0.9 | `J6_pre3` | N/A | 是 | 是 |
| 1.21.7 | 57.0.3 | `J6_pre7` | N/A | 是 | 是 |
| 1.21.8 | 58.1.26 | `J6_pre13` | 兼容 | 是 | 是 |
| 1.21.9 | 59.0.5 | `J7_pre2` | N/A | 是 | 是 |
| 1.21.10 | 60.1.18 | `J7_pre9` | 兼容 | 是 | 是 |
| 1.21.11 | 61.2.4 | `J9_pre4` | 兼容 | 是 | 是 |

每条都是 `Setting user` + `Sound engine started` + `Dev joined the game` + 0 崩溃报告 + stderr 0 字节，
OptiFine 自己打印对应的 `Forge version:`。1.21.7/8/9/10/11 都**不需要新增兼容补丁**，说明管线本身是稳的。

rig 经验：NeoForge 21.7 那个 13.6 MB 的世界会在 `Preparing spawn area: 51%` 卡住，换同实例的
`CaptureWorld`（3.8 MB）秒进——卡的是世界不是加载器。
### 26.x：环境就绪，卡在一处接口注入（2026-10-20）

- 已下载 **JDK 25**（`build/jdk25`，Temurin 25.0.4.1）——26.x 必需，JDK 27 上 Forge 64 会因
  `Module.implAddExportsOrOpens` 缺失报错。
- **对照组**：纯 Forge 26.1.2（64.1.5）+ JDK 25 → `Setting user` + `Sound engine started`、0 崩溃，
  说明 rig 本身没问题。
- **带 OptiForge**：仍然
  `NoClassDefFoundError: net/minecraftforge/client/extensions/IForgeVertexFormat`，抛点是游戏类
  `com.mojang.blaze3d.vertex.DefaultVertexFormat.<clinit>`。
  - 我们产出的 `DefaultVertexFormat`（OptiFine 载荷）里**没有任何** Forge 引用（扫过常量池），
    基包 jar（`versions/26.1.2-forge-64.1.5/26.1.2-forge-64.1.5.jar`）里的同名类也**没有**实现该接口
    → 说明这个接口是 Forge 在运行时（Mixin/transformer）后加的。
  - 已实现"并集"（`mergeInPlaceAdditions`：把 transform 入参里多出来的接口/方法/字段并进我们的结果），
    但**入参里也没有**该接口 → 说明它是在我们之后才被加上的，或者由另一种机制（AT+运行时注入）完成。
    并集本身无害：1.21.11 回归仍 `Dev joined the game`、0 崩溃。
- 下一步：在 Forge 64 里定位给 `DefaultVertexFormat` 加 `IForgeVertexFormat` 的那段代码
  （大概率是 forge 的 mixin 配置），确认它在我们之后运行却没生效的原因；或直接由 OptiForge
  按"类名 → 应实现的接口"补上（先只补这一个）。另需照旧补 `optifine/**` 的
  `OptiFineResourceLocator`（26.x 的 Reflector 报 "Class not present"）。
### 本轮发现的两个"验收集成"问题（重要）

1. **实例 `mods/` 里的旧 OptiForge jar 会被一起加载**：每次测试都把新 jar 拷进去，但旧的
   `OptiForge-0.2.0.jar` 一直在，Forge 会把两个都加载，实际跑的是**旧代码**（表现为 pack 目录名一直是
   `v2-…`，而源码已经是 v3）。**从今以后每次启动前清掉除被测 jar 之外的所有 `OptiForge-*.jar`。**
2. **pack 缓存键只看输入 jar 的哈希**，不含"处理逻辑版本"，所以旧缓存会被无限复用——`OwnRefs`、
   类级 AT、`CompatFixes` 这些包构建期的修复在旧缓存上**根本没跑过**。已把
   `PACK_VERSION` 提到 `v3`（改处理逻辑时必须再提）。

这两点意味着：**上一轮 1.21.7 / 1.21.8 / 1.21.9 / 1.21.10 / 1.21.11 的"进世界"需要在干净环境下重验**。
已经用干净环境（单 jar + 新建 v3 pack）重验通过的是：**1.21（51.0.33）**、**1.21.6（56.0.9）**、
**1.21.7（57.0.3）**、**1.21.8（58.1.26）**、**1.21.9（59.0.5）**、**1.21.10（60.1.18）**、**1.21.11（61.2.4）**——
七条线全部是 `Dev joined the game` + 0 崩溃 + stderr 0 字节，pack 目录都是 `v3-…`。
工具：`tools/rig-verify.ps1` 负责"清掉旧 jar、清 pack、跑一次、从日志里抓证据"，避免再被试运行环境误导。

### `mergeInPlaceAdditions` 的坑

为了不丢别的 transformer 已加到类上的接口/成员，transform 现在把入参里多出来的接口/方法/字段并进结果。
第一版把**原版的 `<clinit>`** 也并了进来，而 OptiFine 有时把原版静态字段改成实例字段
（`BreezeWindLayer.TEXTURE_LOCATION`），于是 `IncompatibleClassChangeError: Expected static field`。
现在**跳过 `<clinit>`/`<init>`**，1.21 与 1.21.6 干净环境下均通过。
### 26.1.2 的真实首因（本轮定位）

用 `-Xlog:class+load=info` 跑完后，日志里**第一个**异常不是 `IForgeVertexFormat`，而是：

    java.lang.IllegalArgumentException: wrong number of arguments: 4 expected: 5
      at net.optifine.reflect.Reflector.callLong
      at com.mojang.blaze3d.platform.Window.<init>(Window.java:113)

即 OptiFine 通过反射调 `ImmediateWindowHandler.setupMinecraftWindow`（4 个参数），而 Forge 26 里这个方法
变成了 **5 个参数**。这条只是 OptiFine 记的 WARN；随后 Forge 打 `Preparing crash report`，崩溃报告里的
`NoClassDefFoundError: IForgeVertexFormat` 是**后续**错误，不是首因。

下一步（二选一）：

1. 在 `CompatFixes` 里给 `net.minecraft.client.Minecraft`/`Window` 的 Reflector 调用补上第 5 个参数
   （需要先确认真实签名：`javap` Forge 26 的 `ImmediateWindowHandler`）；
2. 或者让 OptiFine 跳过 `ImmediateWindowHandler` 路径（Forge 26 自己的窗口处理接管），代价是 early-window
   行为可能与 OptiFine 预期不同。

另外 26.x 还缺 `optifine/**`（`OptiFineResourceLocator`）的 staging：日志里 OptiFine 自己报
`(Reflector) Class not present: optifine.OptiFineResourceLocator`。
### 26.x：窗口反射已修，剩下 `IForgeVertexFormat`（本轮）

已实现并生效（`compat fixes: 1`，日志有 `[OptiForge] compat fix in com.mojang.blaze3d.platform.Window:
bypassed ImmediateWindowHandler.setupMinecraftWindow (arity changed on 26.x) x1`）：
在 26.x 上把 OptiFine `Window` 里那个 4 参数的反射调用改成直接返回 `0`，`Window` 会把 0 当作
"没有 early window"，回落到原版窗口创建。之前那条 `wrong number of arguments: 4 expected: 5` 消失。

（顺带修了两处：MC 版本必须在**建包之前**就已知，现在作为参数传进 `prepare()`，不再依赖静态字段；
版本门控的 compat 规则只有 `mcVersion` 以 `26.` 开头时才启用，所以 1.21.x 七条线不受影响。）

**剩下唯一阻塞**：`DefaultVertexFormat.<clinit>(第 9 行)` 触发定义某个类时，
`NoClassDefFoundError: net/minecraftforge/client/extensions/IForgeVertexFormat`（`defineClass1` 处）。
已确认该名字**不在**我们产出的任何 class、`own` 类、基包 jar、26.1.2 profile 的 40 个库里；
需要下一步用 `-Xlog:class+load=info` 找到**正要被定义的那个类**（日志里 `class,load` 的那一行紧挨着报错），
再看它为什么带上这个接口——大概率是 Forge 的 `minecraft` 模块读边问题（`forge` 模块不可读）。
### 26.1.2 现在能到标题界面（本轮）

两处修复叠加后，`26.1.2 + Forge 64.1.5 + OptiFine K1_pre2`（JDK 25）从"窗口创建前就崩"变成：

    Setting user            : 1
    Sound engine started    : 1
    [OptiFine] lines        : 195
    crash reports           : 0

1. **窗口反射**：`Window` 里对 `ImmediateWindowHandler.setupMinecraftWindow` 的 4 参数反射调用改为返回 0，
   回落到原版窗口创建（见上一节）。
2. **摘掉运行时不存在的外部接口**（`ForgeInterfaces`）：OptiFine 26.x 的补丁会让 `VertexFormat`、
   `PoseStack`、`GpuTexture`、`Font`、`GuiGraphics` 等 `implements net.minecraftforge.client.extensions.IForge*`，
   而 Forge 64 的 jar 里**这些接口已经不存在**（1.21.x 的 universal 都还有）。做法：用"本次启动版本的
   jar 集合"建一个类名索引，补丁类里凡是 `net/minecraftforge/**`、`net/neoforged/**` 的接口在索引里找不到就摘掉。
   26.1.2 上摘掉 1 个类（`VertexFormat`），1.21.7 上索引 51952 个类、摘掉 0 个（接口都在，行为不变，回归通过）。

   **注意**：索引必须限定在"本次启动的 Forge 版本"的 jar；一开始用了整个 rig 的 libraries，1.21.x 的 universal
   让索引误报"存在"，结果在 1.21.7 上把真接口摘掉、直接把已验证的线弄坏（已修复并回归）。

**进世界仍未达成**：世界开始加载（`world_gen_settings.dat` 读不到只是 NeoForge 的文件差异），但 200 秒内
没出现 `joined the game`，像是卡在加载/生成阶段。
### 26.1.2 已经能进世界，但进世界后立刻撞 Forge 的 loot 断言（本轮）

用一个**由 Forge 26.1.2 专用服务器生成的世界**（`--installServer` + 首次启动生成）后：

    [OptiFine] Forge version: 64.1.5
    [OptiFine] Disable Forge light pipeline
    Dev joined the game            <-- 进世界成功
    [OptiFine] lines : 203

进世界后 ~0.2 秒，**集成服务器**崩：

    IllegalStateException: Can not retrieve LootModifierManager until resources have loaded once.
      at ForgeInternalHandler.getLootModifierManager
      at ForgeHooks.modifyLoot
      at LootTable.getRandomItems
      at BlockBehaviour.getDrops

**对照**：同一个世界、纯 Forge 26.1.2（无 OptiFine）→ `joined: True`、**0 崩溃**。所以这是 OptiFine
影响资源重载时序导致 Forge 的 `LootModifierManager` 还没初始化就去掉落物。下一步查 OptiFine 26.x 对
资源重载/`ReloadableResourceManager` 的补丁是否吞掉了 Forge 的 reload listener 事件。

rig 经验（有用）：26.x 用 NeoForge 的世界会卡在 `world_gen_settings.dat` 读不到；**用 Forge 自己的
server 生成一个世界**再复制到客户端的 `saves/` 就正常（这条也解释了之前 26.1.2 一直"进不了世界"）。
### 0.6.0：八条线干净通过，1.20.6 一次通过（2026-10-20）

新增 **1.20.6 + Forge 50.2.14 + OptiFine `1.20.6 J1_pre18`**：`joined the game`、141 条 `[OptiFine]`、
0 崩溃、`v3` pack——**不需要任何新修复**。

当前干净通过的组合（都是 `Dev joined the game` + 0 崩溃 + stderr 0 字节）：

| MC | Forge | OptiFine | 官方 Forge 列 |
|---|---|---|---|
| 1.20.6 | 50.2.14 | `J1_pre18` | 兼容 |
| 1.21 | 51.0.33 | `J1_pre9` | N/A |
| 1.21.6 | 56.0.9 | `J6_pre3` | N/A |
| 1.21.7 | 57.0.3 | `J6_pre7` | N/A |
| 1.21.8 | 58.1.26 | `J6_pre13` | 兼容 |
| 1.21.9 | 59.0.5 | `J7_pre2` | N/A |
| 1.21.10 | 60.1.18 | `J7_pre9` | 兼容 |
| 1.21.11 | 61.2.4 | `J9_pre4` | 兼容 |

边界状态：`26.1.2`（N/A）能启动 + 进世界，但进世界后撞 Forge 的 `LootModifierManager` 断言
（纯 Forge 对照不崩）；`1.20.1` 只验证了启动。
### 26.1.2 的 loot 断言：查到的结论（下一轮从这里继续）

- `ForgeEventFactory.onResourceReload(...)` 负责 post `AddReloadListenerEvent`，
  而调用它的 `ReloadableServerResources`（在 Forge 的 client jar 里，二进制补丁过）**不在我们的补丁集**
  ——所以钩子没丢，事件应该照常发。
- 因此这是**时序**问题：quickPlay 进世界时，集成服务器的资源重载还没跑完（OptiFine 的
  ResourcePack/资源加载把它推后了），服务器线程先算掉落物 → `LootModifierManager` 还是 null → 断言。
- 纯 Forge 对照同世界不崩，说明差异确实来自 OptiFine 的资源处理路径。

下一轮候选：
1. 让 OptiFine 的资源重载与 `AddReloadListenerEvent` 的先后关系回到原版（看 OptiFine 26.x 对
   `ReloadableResourceManager.createReload`/`CloseableResourceManager` 的补丁）；
2. 或在 `ForgeInternalHandler.getLootModifierManager` 上做兜底（Forge 的类，我们可以 transform：
   `INSTANCE` 为空时返回一个空的 manager——需要先确认 `LootModifierManager` 能不能无参构造）；
3. 或延迟"进世界"（不可控，跳过）。
### 1.21.1：进世界后 `Direction.getUpdateOrder()` NPE（本轮发现，未修）

`1.21.1 + Forge 52.1.20 + OptiFine J1_pre9`（官方 N/A）能启动、能进世界，但进世界后 ~0.2s：

    NullPointerException: Cannot invoke "java.util.List.iterator()" because the return value of
    "net.minecraft.core.Direction.getUpdateOrder()" is null
      at Level.updateNeighbourForOutputSignal

- 我们产出的 `Direction`（OptiFine 补丁版）里**没有** `getUpdateOrder`；运行时能调到它说明是 Forge 的 Mixin
  补的，但它依赖的静态数据没被初始化——即"整类替换 + 只并成员、不并 `<clinit>`"的后果。
- 试过 `-Doptiforge.skipClasses=net.minecraft.core.Direction`（本轮新增的诊断开关）：能跳过替换，但 OptiFine
  自己的 `RenderEnv` 又需要 OptiFine 给的 AT（`Direction.VALUES` 可访问）→ `IllegalAccessError`。所以不能简单跳过。
- 正确的下一步：合并时**把入参 `<clinit>` 里只初始化"我们缺失字段"的那部分搬过来**（不能整段并，避免二次初始化）。

顺带确认：1.21.1 的基包本来就选的是 `1.21.1-forge-52.1.20.jar`（Forge 的 client jar），不是原版 jar——
`findBaseJar` 现在也显式优先 Forge client jar（对其它线无影响）。
### 26.2 也测了（本轮）：能启动 + 进世界，随后崩；两处尝试已回退

- `26.2 + Forge 65.1.5 + OptiFine K2_pre4`（JDK 25）：`Setting user`、`Sound engine started`、
  192~200 条 `[OptiFine]`、`Dev joined the game`（世界用 Forge 26.2 专用服务器生成）。
- 进世界后客户端崩 `IllegalAccessError: LevelExtractor tried to access private method
  LevelRenderer.weatherEffectRenderer`——Forge 26.2 的 AT 里有收窄行
  `private-f net.minecraft.client.renderer.LevelRenderer weatherEffectRenderer`，我们原样应用后把方法变成 private。
  - 尝试"AT 只放宽不收窄"：26.2 的这个错消失了，但**已验证的 1.21.7 立刻回归**（1 崩溃、进不了世界），
    所以已回退。正确做法是按行/按版本判断哪条收窄是给同包调用者用的，而不是全局策略。
  - 修好 AT 之后 26.2 的下一个错变成 `NoSuchMethodError: Gui.popLayer()`（`Screen.onClose` → `Gui`），
    与我们替换 `Gui` 后丢掉 Forge 运行时补的方法同类。
- 1.21.1 的 `<clinit>` 字段初始化合并也试过：抽取语句时用"行号标签"当边界不成立，
  在 `ResourceLocation.<clinit>` 产生 `VerifyError: Operand stack underflow`，已回退。正确做法需要
  **栈模拟**来确定语句起点。

两处实验都已回退，`tools/build.ps1` / 1.21.7 回归通过（`joined the game`、0 崩溃）。
### 26.x：AT 收窄按版本处理（本轮），`popLayer` 类问题仍在

- AT 重放现在**只在 26.x 上"只放宽不收窄"**（`ForgeAccessTransformers` 的 `widenOnly`，由 `mcVersion`
  决定）。效果：26.2 的 `IllegalAccessError: LevelExtractor -> private LevelRenderer.weatherEffectRenderer`
  消失，崩溃数 2 → 1；1.21.x 行为完全不变（1.21.7 复查 `joined the game`、0 崩溃）。
- 26.2 剩下的错是 `NoSuchMethodError: Gui.popLayer()`（`Screen.onClose` 调用）。查过：**基包 jar 里的
  `Gui` 也没有 `popLayer`**，所以它是 Forge 运行时加给 `Gui` 的方法，而我们整类替换后它没生效——
  和 1.21.1 的 `Direction.getUpdateOrder()` 同一类问题（Mixin 在我们之后注入，注到我们替换的类上不成）。
- 试过让 transformer **投 DEFER**（期望最后运行、能看到 Forge 的注入）：ModLauncher 直接
  `VoteDeadlockException`（`ClassTransformer.performVote`）。该开关 `-Doptiforge.deferVote=true` 保留但默认关闭，
  结论记在这里：这条路不通。

下一步（真解法）：把"整类替换"改成**差异应用**——构建期算出 OptiFine 补丁相对基包的差异，运行时把差异
应用到 Forge 处理过的类上，而不是用我们的整份字节替换；这样 Mixin 的注入天然保留。
### 26.x：父类被补丁改回 Object 的问题修好了（本轮）

- 现象：26.2 进世界后 `NoSuchMethodError: Gui.popLayer()`。查证：Forge 的 client jar 里
  `Gui extends net.minecraftforge.client.gui.overlay.ForgeLayerInstance`（`popLayer` 继承自它），
  而 **OptiFine 的补丁输出把父类写回了 `Object`**（它的 xdelta 以原版为基准）。
- 修法（`OptifineClassTransformer`）：合并时若我方父类是 `Object` 而入参（Forge 处理过）的父类不是，
  **采纳入参的父类**，并把构造器里的 `invokespecial java/lang/Object.<init>()V` 改写成
  `ForgeLayerInstance.<init>(Minecraft)`（`Gui` 构造器第一个参数就是 `Minecraft`）。
  不重写会得到 `VerifyError: Bad <init> method call ... Type java/lang/Object is not assignable to Gui`。
- 结果：**26.2 的 `popLayer` 错误消失**，进世界后剩下的唯一崩溃就是与 26.1.2 相同的
  `LootModifierManager` 时序断言——**两条 26.x 线现在收敛到同一个问题**。
- 回归：1.21.7 这条规则不触发（无 `adopting superclass` 日志），仍 `joined the game`、0 崩溃。
### 0.7.0：26.2（N/A）也干净通过（2026-10-20）

`26.2 + Forge 65.1.5 + OptiFine K2_pre4`（JDK 25）：`Setting user`、`Sound engine started`、
**`Dev joined the game`**、200 条 `[OptiFine]`、**0 崩溃报告**。

这一步靠两个修复：

1. **父类被补丁改回 Object**（上一轮）：`Gui extends ForgeLayerInstance` 被 OptiFine 的补丁写回 `Object`，
   合并时采纳入参父类并重写构造器 `super` 调用。
2. **loot 管理器时序兜底**（本轮，`RuntimePatches`）：26.x 上集成服务器会在资源重载完成前 tick，
   `ForgeHooks.modifyLoot` 调 `ForgeInternalHandler.getLootModifierManager()` 抛断言。
   给这个方法加 `catch (Throwable)` → 返回未修改的掉落物；管理器在时行为不变，不在时掉落物只是不经过
   全局 loot modifier，而不是把世界打死。仅 26.x 启用。

26.1.2 在 loot 兜底后继续往前：现在崩在 `EntityRenderer.submitNameDisplay` 的
`NullPointerException: renderNameplateEvent is null`（Forge 的 `HasResult`/`Result` 事件对象为空），
下一轮就从这里继续。
### 0.8.0：26.1.2 也干净通过（十条第 6 条 N/A）

`26.1.2 + Forge 64.1.5 + OptiFine K1_pre2`（JDK 25）：`joined the game`、218 条 `[OptiFine]`、**0 崩溃**。

它的最后一处是 `EntityRenderer.submitNameDisplay` 的
`NullPointerException: renderNameplateEvent is null`：

- OptiFine 26.1.2 的反射调用 `Reflector.ForgeEventFactoryClient_fireRenderNameTagEvent` **传 6 个参数**，
  而 Forge 64 里的 `ForgeEventFactoryClient.fireRenderNameTagEvent` 只有 **5 个** → 反射器认为"方法存在"
  但调用返回 null → NPE。
- 修法：建包时用 ASM **实测该方法的参数个数**（`ForgeInterfaces.maxParameterCount`），
  26.x 上 `arity < 6` 就把那段 `exists()` 判定改成 false，走原版名牌路径（判定前 `show`/`content` 已经算好）。
  诊断里会打印 `nameplate event arity: 5 (reflector bypassed)`。
- 顺带把 `OwnRefs`（中和外部类字面量）改成**只中和"本次启动的 jar 里不存在"的类**：之前一刀切导致 26.1.2
  的 `ForgeEventFactoryClient` 字面量也被换成 `Object`。Event 这类真正读不到的仍然被中和（eventbus 是独立
  artifact，不在索引里），所以 1.21 的修复不受影响——1.21 回归 `joined the game`、161 条、0 崩溃。

26.2 同版复验：315 条 `[OptiFine]`、0 崩溃。
### 0.9.0：1.21.1 也干净通过（十一路第 7 条 N/A）

`1.21.1 + Forge 52.1.20 + OptiFine J1_pre9`（JDK 21）：`joined the game`、172 条 `[OptiFine]`、**0 崩溃**。

最后两处：

1. **被采纳字段的惰性默认值**：`Direction.getUpdateOrder()` 是从入参（Forge 处理过）并进来的方法，
   它读的静态字段 `UPDATE_ORDER` 由原版 `<clinit>` 初始化，而我们不并 `<clinit>` → 返回 null。
   现在合并时为"被采纳方法读取的被采纳字段"插入 `if (FIELD == null) FIELD = <空容器>;` 守卫
   （数组 → 空数组，`Map` → `HashMap`，`List`/`Collection` → `Collections.emptyList()`）。
2. **清掉 `final`**：那个字段是 `static final`，从别的（非 `<clinit>`）方法写它会
   `IllegalAccessError: Update to static final field ... attempted from a different method`，
   所以加守卫时同时 `& ~ACC_FINAL`。

另外 `tools/rig-verify.ps1` 的"进世界"检测补上了新版本的情况：MC 26 的进服消息走可翻译组件，
日志里是本地化文本（`Dev加入了游戏`），脚本改为同时认 `logged in with entity id`。

回归：1.21.7（`joined the game`、250 条、0 崩溃）、26.2（`logged in with entity id`、0 崩溃、315 条）。

1.20.1 的进世界仍未成功：**`--quickPlaySingleplayer` 在 Forge 47 下不触发**（日志里只有启动完成，
没有任何世界加载动作），属于测试环境限制；该线启动本身已验证。
### 1.20.1 进世界：三种自动方式都试过（环境限制）

`1.20.1 + Forge 47.4.23 + OptiFine I6` 的**启动**一直是通过的（112 条 `[OptiFine]`、0 崩溃、OptiFine 在改
Forge 配置）。进世界在本测试环境里试过三种方式，**都没有触发世界加载**：

1. `--quickPlaySingleplayer=CaptureWorld`：参数确实送到了游戏（日志 args 可见），但 1.20.1 没有任何反应；
2. `--server 127.0.0.1 --port 25566`（配一个 Forge 1.20.1 专用服务器，世界已生成）：客户端不发起连接；
3. `--quickPlayMultiplayer 127.0.0.1:25566`：同样不发起连接。

服务器端 `Done (11.277s)` 正常，说明服务器没问题；是 Forge 47 客户端对这几个参数的支持问题（1.20 之后
这些入口改成了 quickPlay 系列，Forge 47 的 `Main` 补丁似乎没有把它们接上）。**这不是加载器的问题**，
但本环境无法自动验证，记录在此以免误判成"通过了"。
### 最终全量回归（0.9.0 单 jar + 清空 pack，逐条重跑）

| MC | Forge | OptiFine | 官方 Forge 列 | 进世界 | 崩溃 | OptiFine 日志 |
|---|---|---|---|---|---|---|
| 1.20.6 | 50.2.14 | `J1_pre18` | 兼容 | 是 | 0 | 132 |
| 1.21 | 51.0.33 | `J1_pre9` | **N/A** | 是 | 0 | 161 |
| 1.21.1 | 52.1.20 | `J1_pre9` | **N/A** | 是 | 0 | 172 |
| 1.21.6 | 56.0.9 | `J6_pre3` | **N/A** | 是 | 0 | 249 |
| 1.21.7 | 57.0.3 | `J6_pre7` | **N/A** | 是 | 0 | 250 |
| 1.21.8 | 58.1.26 | `J6_pre13` | 兼容 | 是 | 0 | 237 |
| 1.21.9 | 59.0.5 | `J7_pre2` | **N/A** | 是 | 0 | 236 |
| 1.21.10 | 60.1.18 | `J7_pre9` | 兼容 | 是 | 0 | 226 |
| 1.21.11 | 61.2.4 | `J9_pre4` | 兼容 | 是 | 0 | 146 |
| 26.1.2 | 64.1.5 | `K1_pre2` | **N/A** | 是 | 0 | 210 |
| 26.2 | 65.1.5 | `K2_pre4` | **N/A** | 是 | 0 | 199 |

（26.x 需要 JDK 25；进世界用 `logged in with entity id` 判定，因为 26 的进服消息经过了本地化。）

唯一未复现进世界的是 `1.20.1 + Forge 47.4.23`：启动通过（112 条 `[OptiFine]`、0 崩溃），但 Forge 47
客户端不响应 `--quickPlaySingleplayer` / `--server` / `--quickPlayMultiplayer` 三种自动进服方式，
属于本测试环境限制（专用服务器 `Done (11.277s)` 正常）。
### 1.20.2（矩阵里最后一条现代 N/A 线）：本轮现状

- 该线只有 1 个构建：`preview_OptiFine_1.20.2_HD_U_I7_pre1.jar`，官方标 `Forge N/A`；Forge 侧装了 48.0.1，
  世界用 Forge 1.20.2 专用服务器生成。
- rig 最初缺少 Forge 生成的 vanilla 映射 jar（`client-1.20.2-…-srg.jar` 等）：**必须先把原版 1.20.2 的
  jar/json 放进安装器 scratch 的 `versions/1.20.2/`，再跑 `--installClient`**，否则 `--installClient` 不会
  产出它们（这就是 STATE 里那条"安装器不下载原版"的坑的延伸）。补齐后启动能走更远（22 条 `[OptiFine]`）。
- 当前停在：
  `IllegalAccessError: Update to non-static final field net.minecraftforge.client.gui.overlay.ForgeGui.f_291320_
   attempted from a different class (ForgeGui) than the field's declaring class`
  （`ForgeGui.<init>` 来自 `Minecraft.<init>`）。这个类**不在我们的补丁集**里，怀疑是修复 rig 库时引入了
  重复的 ForgeGui 定义（同名类被两个 loader 定义），下一轮先核对 rig 的库是否有重复 jar。
### 1.20.2 的 ForgeGui 错误：定位到"只有 OptiFine 在场才出现"

三组对照（都在同一个 rig、同一个 Forge 1.20.2-48.0.1）：

| 组合 | 结果 |
|---|---|
| 纯 Forge 1.20.2（无 OptiForge、无 OptiFine） | 正常：`Setting user` + `Sound engine started`、0 崩溃 |
| 有 OptiForge、**没有** OptiFine | 正常：`Setting user`、0 崩溃 |
| 有 OptiForge + OptiFine `1.20.2 HD_U_I7_pre1` | `IllegalAccessError: Update to non-static final field ForgeGui.f_291320_ attempted from a different class (ForgeGui)` |
| 同上，且 `-Doptiforge.skipClasses=net.minecraft.client.Minecraft` | 同一个错误 → 与"是否替换 Minecraft"无关 |

已排除：`ForgeGui` **不在**我们的补丁集、我们的 staged mod jar 里**没有** `net/minecraftforge/**` 类、
OptiFine jar 里也没有 `ForgeGui` 的补丁条目（只有 `notch/net/minecraftforge/client/extensions/IForgeGuiGraphics.class`
这种它自带的 stub）。所以这是 **OptiFine 1.20.2 这个构建与 Forge 48 的相互作用**，不是加载器对它做了错误替换。
该线只有这一个构建，若以后有新的 1.20.2 构建可以再试。
### 0.9.1：修两个只在"版本隔离启动器"上暴露的问题

用户用 PCL 的 `versions/1.20.1-Forge_47.4.20/` 实例测试时出现
`VerifyError: Bad invokespecial instruction: interface method reference is in an indirect superinterface`
（`Mob.lambda$jumpInFluid$4` → `IForgeLivingEntity.jumpInFluid`），而同一组合在测试环境正常。

两个原因，都在"库目录定位"上：

1. **`libraries` 在上两级**：PCL 的实例目录是 `.minecraft/versions/<id>`，库在 `.minecraft/libraries`。
   `LaunchPaths` 原来只查 `gameDir/libraries` 和上一级，于是类名索引几乎为空。
2. **空索引导致误删**：索引不全时 `ForgeInterfaces.strip` 把**真实存在**的 Forge 接口当成"运行时没有"
   从补丁类上摘掉（那次摘了 10 个，`Mob` 的 `IForgeLivingEntity` 就在里面）→ `invokespecial` 的
   直接父接口要求不满足 → VerifyError。

修法：`LaunchPaths` / `findBaseJar` 都**向上最多找 4 层**的 `libraries`；另外新增 `SuperInterfaceFixer`
（建包期发现 `invokespecial` 到接口方法却没有声明该接口时补上，作为兜底）；`PACK_VERSION` 提到 `v4`，
让所有旧缓存重建。

实测（用户同实例、Java 22、PCL profile）：`Setting user`、`Sound engine started`、112 条 `[OptiFine]`、
0 崩溃；索引 38351 个类、误删 0 个。回归：1.21.7 仍 `joined the game`、0 崩溃。
### 0.9.2：视频设置界面崩溃（OptiFine 改父类后误并了旧继承链的方法）

用户点"视频设置"时崩：

    VerifyError: Bad invokespecial instruction: current class isn't assignable to reference class.
      at VideoSettingsScreen.m_6050_(DDD)Z @82

原因：OptiFine 的 `VideoSettingsScreen` 被**改父类**成 `net.optifine.gui.GuiScreenOF`，
而运行时合并把原版（父类 `OptionsSubScreen`）的 `mouseClicked`（`m_6050_`）也并了进来——
里面的 `super.mouseClicked(...)` 指向已经不在继承链上的类 → 校验失败。

修法：合并时若**父类不一致**（`hierarchyChanged`），跳过所有含 `super.` 调用（`invokespecial` 到非自身类）
的方法。离线验证：对同一份入参，合并后不再包含 `m_6050_`。
### 0.9.3：OptiFine 文本显示成键名（mod jar 没有 pack.mcmeta）

现象：视频设置里 OptiFine 的条目是键名（`of.options.AO_LEVEL`、`of.options.DYNAMIC_LIGHTS`、
`of.options.shadersTitle`…），原版条目正常。截图确认后看日志，有两处线索：

1. 启动警告界面：`optiforge-optifine-classes.jar failed to load a valid ResourcePackInfo`；
2. 日志：`Missing metadata in pack mod:optiforge_optifine_classes`。

原因：我们 stage 的 jar 只有 `assets/**` 而没有 `pack.mcmeta`，Forge 不把它注册成资源包，于是
OptiFine 的 `Lang.resourcesReloaded()` 读不到 `assets/minecraft/optifine/lang/<locale>.lang`
（OptiFine 的 UI 文本全在这些 `.lang` 里，`zh_cn.lang` 的 672 个键是齐的，所以不是翻译缺条目）。

修法：stage 时写入 `pack.mcmeta`（`pack_format` 按 MC 版本给：1.20.1 → 15 等），`PACK_VERSION` → `v5`。
验证：两条警告都不再出现，`pack v5-…` 正常构建，118 条 `[OptiFine]`、0 崩溃。
### 0.10.0：mod 身份、协议、缺失 OptiFine 时的提示、版本号修正

- **modId 改为 `optiforge`**（原来是 `optiforge_optifine_classes`），`displayName="OptiForge"`，
  mods 列表里就是一个叫 OptiForge 的 mod；描述与 GitHub 仓库一致
  （"OptiFine on Forge, with Forge Mod Loader loaded alongside."）。
- **协议改为 MPL 2.0**（`mods.toml` 的 `license` 字段 + 仓库根目录 `LICENSE`）。
- **没有 OptiFine 时**：创建 `<game>/optiforge/`，写入 `PUT-OPTIFINE-HERE.txt`（中英双语，含下载地址与
  "不要放 mods/" 的说明），同时清掉上一轮遗留的 `mods/optiforge-optifine-classes.jar`；找到 OptiFine 后
  自动删除该提示文件。实测：无 OptiFine → 目录+提示文件出现、游戏按原版 Forge 启动、0 崩溃；
  放回 OptiFine → 提示文件消失、132 条 `[OptiFine]`、0 崩溃。
- **版本号修正**：`Diagnose.VERSION` 原来是硬编码的 `0.1.0`（诊断报告和生成的 mods.toml 都跟着错）。
  现在由构建写入 `optiforge/version.txt`（`build.ps1` 与 Gradle `writeVersion` 两处），运行时读取；
  实测报告与 mods.toml 均为 `0.10.0`。
### 0.11.0：缺 OptiFine 改成游戏内提示（照 OptiFabric 的做法），并给生成 jar 起可读名字

OptiFabric 的做法（`MixinTitleScreen`）：缺 OptiFine 时在标题界面打开一个 ConfirmScreen（红色标题），
带"打开 mods 文件夹 / 帮助"按钮。OptiForge 没有 Mixin，改成等价的实现：

- 服务仍然创建 `<game>/optiforge/`，并**总是**在 `mods/` 里生成 `OptiForge-<版本>-classes.jar`
  （缺 OptiFine 时里面只有提示类，没有 OptiFine 类），mod 列表里始终是一个 **OptiForge**；
- 生成的 mod 构造函数启动一个守护线程，等标题界面出现后弹出 ConfirmScreen：
  `OptiForge 需要 OptiFine`，正文含 `optiforge/` 绝对路径、下载地址、"不要放 mods/"，点 Yes 打开该文件夹；
  拿不到 ConfirmScreen 时退化为系统 toast，再不行只写日志，绝不崩；
- `Notice` 类随生成 jar 一起打包，并用 ASM `ClassRemapper` 重定位到 `optiforge/generated/Notice`
  （只改名字会留下指向旧类的常量池引用，实测 `IllegalAccessError: ... private field`）；
- 移除 `PUT-OPTIFINE-HERE.txt`（用户要求不要文件提示）；旧版本的 `optiforge-optifine-classes.jar`
  启动时自动删除，避免两个模组抢同一个 modId；
- `findOptifineJar` 照 OptiFabric 的挑选方式：优先文件名含 `OptiFine`，其次验证 jar 里确实有
  `net/optifine/` 类，其它 jar 会被忽略并在诊断里记录。

实测（1.20.1 + Forge 47.4.20）：缺 OptiFine → 标题界面弹出提示框、0 崩溃、mods 里只有
`OptiForge-0.11.0-classes.jar`；放回 OptiFine → 提示不再出现、132 条 `[OptiFine]`、0 崩溃。
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
