# 发布用文案（简要描述 / 详细描述）

直接复制粘贴用的成品。**简要描述**用于 CurseForge 项目页的"简介"栏（以及 GitHub 的 About）；
**详细描述**用于 CurseForge 项目正文。

> ⚠️ CurseForge 规则：描述与简介**可以有其他语言，但英文必须排在前面**。所以本文件英文在前、中文在后；
> 粘贴时每个字段先贴英文、再贴中文即可（只用英文就只贴英文那节）。

---

## 一、简要描述(English)

> Load OptiFine on Forge: OptiForge runs **your own OptiFine jar** through OptiFine's own patcher and delivers
> the patched classes plus a small resource mod, so OptiFine and Forge mods run together from **Minecraft 1.7.10
> through 26.2**. OptiFine is not included — download it from optifine.net.

**One-liner:**

> OptiFine on Forge, from Minecraft 1.7.10 to 26.2, using your own OptiFine jar.

---

## 二、详细描述(English)

### OptiForge — OptiFine on Forge (1.7.10 – 26.2)

A loader that brings **OptiFine** to **Forge**. Put the OptiForge jar for your game version into `mods/`, put
**your own OptiFine jar** into `optiforge/`, and start the game.

> ⚠️ **OptiFine is not bundled or redistributed.** Download the build for your Minecraft version from the
> official site (<https://optifine.net/downloads>) and drop the jar in as-is — you do **not** need to run
> OptiFine's installer. OptiForge never ships OptiFine code; the artifacts contain zero `net/optifine/**`
> entries and zero OptiFine assets.

### Supported versions

Four artifacts, one per era. They are **not interchangeable**: each era loads mods through a different
mechanism, so pick the jar that matches your Minecraft version.

| Minecraft | OptiForge file | Example OptiFine build | Java |
|---|---|---|---|
| **1.17 – 1.20.1, 1.20.6, 1.21.x, 26.1.2, 26.2** | `OptiForge-1.0.1.jar` | matching build, any | 17 / 21 / 25 |
| **1.14.4, 1.15.2, 1.16.5** | `OptiForge-1.0.1-oldml.jar` | `HD_U_G5` / `HD_U_G6` / `HD_U_G8` | **8** |
| **1.13.2** (experimental) | `OptiForge-1.0.1-oldml.jar` | `1.13.2 HD_U_G5` | **8** |
| **1.8.9 – 1.12.2** | `OptiForge-1.0.1-legacy.jar` | `HD_U_M5` / `I5` / `I5` / `G5` / `G5` | **8** |
| **1.7.10** (alpha) | `OptiForge-1.0.1-legacy17.jar` | `1.7.10 HD_U_E7` | **8** |

The Java column is Forge's own requirement, not a preference: Forge 1.13–1.16 and 1.8–1.12 do not run on newer
JVMs. On 1.17–1.20.4 use Java 17, on 1.20.5/1.20.6/1.21.x Java 21, and on 26.x Java 25.

### Why it is needed

Forge does not treat OptiFine as a mod. Any jar in `mods/` that declares a ModLauncher transformation service
is picked up as a **service** and excluded from mod discovery — and OptiFine genuinely is one, because that is
how it patches the game. Dropping the OptiFine jar into `mods/` therefore either does nothing useful or ends in
two sides patching the same classes.

The second half of the problem is that OptiFine's payload is built against **one specific Minecraft build per
release**, while Forge also rewrites those same classes (binpatches, access wideners, its own transformation
services). The two disagree in ways that are hard to diagnose: missing access, methods whose bodies moved,
constructors whose initialisation was replaced, and so on.

### What it does

At startup OptiForge:

1. finds the OptiFine jar in `<game directory>/optiforge/` (never in `mods/`);
2. applies **OptiFine's own xdelta payload** with OptiFine's own patcher to the **vanilla** client jar for your
   version;
3. remaps only when the runtime really uses a different namespace (1.17+ with official names), with a dedicated
   obfuscation bridge for the old 1.13.2-style flat payload;
4. replays **only the running version line's** access transformers onto the patched classes;
5. applies small compatibility repairs where OptiFine's patch and Forge's rewrite collide;
6. stages OptiFine's own classes (and, on request, its CTM texture payloads) as a generated
   `mods/OptiForge-<version>-classes.jar`, which is the "OptiForge" entry you see in the mods list;
7. caches everything under `<game directory>/optiforge/pack/<key>/` so later launches reuse it.

### Installation

1. Install **Forge** for your Minecraft version, as usual.
2. Download **OptiFine** for the *same* Minecraft version from <https://optifine.net/downloads>.
3. Create a folder named `optiforge` inside your game directory (`.minecraft/optiforge/`).
4. Put the OptiFine jar there, keeping its original file name
   (e.g. `.minecraft/optiforge/OptiFine_1.16.5_HD_U_G8.jar`).
5. Put the matching **OptiForge** jar into `.minecraft/mods/`.
6. Start the game.

**First launch takes longer.** OptiForge prints a console line when it has to prepare something for the first
time. On 1.13–1.16 OptiFine additionally compiles its default connected-textures set on the first resource
reload, which can take a few minutes; after that it is cached. If you do not use connected textures, add
`-Doptiforge.assets=none` to your JVM arguments to skip the texture staging entirely.

### Requirements

- Forge for the matching version line (no other mod needed).
- Java 8 / 17 / 21 / 25 as listed above.
- OptiFine jar obtained by you from the official site — not included here.

### Verified state

Every line below was started and brought into a world (singleplayer or a Forge dedicated server) with
OptiFine active and no crash reports:

- **1.8.9, 1.9.4, 1.10.2, 1.11.2, 1.12.2** (legacy artifact)
- **1.14.4, 1.15.2** (old-loader artifact)
- **1.17.1, 1.18, 1.18.1, 1.18.2, 1.19, 1.19.1, 1.19.2, 1.19.3, 1.19.4, 1.20.1, 1.20.6, 1.21, 1.21.1,
  1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1.2, 26.2** (main artifact)

Shader packs were tested on **1.21.8** (Forge 58.1.26, OptiFine `HD_U_J6_pre13`) by counting compiled GL
programs in the log:

| Shader pack | Compiled programs | Errors | Crashes |
|---|---|---|---|
| Complementary Reimagined r5.9.3 | 27 | 0 | 0 |
| BSL v10.1.8 | 26 | 0 | 0 |
| MakeUp Ultra Fast 9.6 | 25 | 0 | 0 |

### Known issues

- **1.16.5 and 1.13.2 are experimental.** The game starts and OptiFine works, but entering a world can crash
  inside OptiFine's patched classes — `ClientWorld.getPartEntities` on 1.16.5, `RenderChunkCache` on 1.13.2.
  Cause: OptiFine's payload replaces classes Forge has already binpatch-ed, and Forge's code changes *inside*
  those classes are not fully merged yet. Fix planned for 1.1.0. Download the 1.16.5 file from the "Beta"
  section if you want to try it.
- **OptiFine `1.17.1 HD_U_G9`** (marked N/A on optifine.net) does not work: its payload uses pre-release MCP
  ids and no public mapping exists for them. Use `HD_U_H1` on 1.17.1.
- **1.20.2**: OptiFine itself crashes against Forge 50 (`IllegalAccessError ForgeGui.f_291320_`), independent
  of OptiForge.
- **1.7.10**: the artifact passes offline verification (payload md5, class layout) but Forge 1.7.10's own
  launchwrapper pass throws `ConcurrentModificationException` in the author's test environment, so an in-game
  pass is not confirmed. Treat it as alpha.
- **OptiFine `1.21.7 HD_U_J6_pre7`** skips shader packs on its own (an OptiFine bug, not OptiForge). Use 1.21.8
  if you want shaders on that line.

### Troubleshooting

**The game starts but OptiFine seems absent.**
Check `<game directory>/optiforge/diagnose.txt` (on legacy lines: `diagnose-legacy.txt`). It records the
detected versions, namespace, access-transformer grants, patch counts and the staged jar. The log line
`[OptiForge] NO ACTIVE PACK` means the OptiFine jar was not found — it must be in `optiforge/`, not `mods/`.

**"OptiFine's own transformation service is also loaded".**
Your OptiFine jar is inside `mods/`. Move it to `optiforge/`.

**A warning naming another artifact or Java version.**
OptiForge prints `[OptiForge] WARNING: ...` when the jar or JVM does not belong to the line you launched.
Use the artifact named in the message, with the Java version listed for that line.

**Textures look wrong / log full of `Using missing texture: ... optifine/ctm/...`.**
The CTM payload was skipped. Remove `-Doptiforge.assets=none` (staging is on by default) and delete
`optiforge/pack/` so the next launch rebuilds it.

**First launch looks frozen on 1.13–1.16.**
It is OptiFine compiling its default CTM set. The console line says so; wait it out, or add
`-Doptiforge.assets=none`.

### Reporting a problem

Please attach:

- `<game directory>/logs/latest.log`
- `<game directory>/optiforge/diagnose.txt` (or `diagnose-legacy.txt`)
- the crash report from `crash-reports/` if there is one
- Minecraft version, Forge version, the OptiFine file name, the OptiForge file name and your Java version

### License and credits

- OptiForge: **Mozilla Public License 2.0**, source at <https://github.com/Kynarain/OptiForge>.
- OptiFine: © **sp614x**, <https://optifine.net> — required, **not included**, all rights belong to its author.
- Not affiliated with or endorsed by OptiFine, Mojang or Forge.

---

## 三、简要描述(中文)

> 让 OptiFine 跑在 Forge 上：OptiForge 用 **你自己那份 OptiFine jar** 走 OptiFine 自带的 patcher，把补丁后的
> 类和一个小的资源 mod 交给游戏，从而让 OptiFine 和 Forge 模组共存，覆盖 **Minecraft 1.7.10 到 26.2**。
> 不含 OptiFine，请自行从 optifine.net 下载。

**一句话版：**

> 让 OptiFine 跑在 Forge 上，覆盖 1.7.10 到 26.2，需自备 OptiFine jar。

---

## 四、详细描述(中文)

### OptiForge —— 让 OptiFine 在 Forge 上跑起来(1.7.10 – 26.2)

把 **OptiFine** 装到 **Forge** 上的加载器。把对应你的游戏版本的 OptiForge jar 放进 `mods/`，
把你自己的 **OptiFine jar** 放进 `optiforge/`，启动游戏即可。

> ⚠️ **本项目不含、也不二次分发 OptiFine。** 请到官网 <https://optifine.net/downloads> 下载对应你
> Minecraft 版本的构建，原样放进去即可（**不需要**运行 OptiFine 的安装器）。产物里 `net/optifine/**`
> 与 OptiFine 资源都是 0 条目。

### 支持的版本

四份产物，按版本世代区分，**不可互换**——每个世代加载模组的机制不同，请选与你 Minecraft 版本对应的那份。

| Minecraft | OptiForge 文件 | OptiFine 构建示例 | Java |
|---|---|---|---|
| **1.17 – 1.20.1、1.20.6、1.21.x、26.1.2、26.2** | `OptiForge-1.0.1.jar` | 对应版本任意构建 | 17 / 21 / 25 |
| **1.14.4、1.15.2、1.16.5** | `OptiForge-1.0.1-oldml.jar` | `HD_U_G5` / `G6` / `G8` | **8** |
| **1.13.2**（实验性） | `OptiForge-1.0.1-oldml.jar` | `1.13.2 HD_U_G5` | **8** |
| **1.8.9 – 1.12.2** | `OptiForge-1.0.1-legacy.jar` | `M5` / `I5` / `I5` / `G5` / `G5` | **8** |
| **1.7.10**（alpha） | `OptiForge-1.0.1-legacy17.jar` | `1.7.10 HD_U_E7` | **8** |

Java 那一列是 **Forge 自己的要求**：1.13–1.16 与 1.8–1.12 的 Forge 不支持更新的 JVM。1.17–1.20.4 用 17，
1.20.5/1.20.6/1.21.x 用 21，26.x 用 25。

### 为什么需要它

Forge 不把 OptiFine 当普通模组：`mods/` 里任何声明了 ModLauncher 变换服务的 jar 都会被当成"服务"并从模组
发现里排除——而 OptiFine 本身就是这么打补丁的。所以直接把 OptiFine 放进 `mods/` 要么没效果，要么变成两边
同时改同一批类。

另一半问题是：OptiFine 的每个构建只针对**某一个具体 Minecraft 构建**打补丁，而 Forge 也在改同一批类
（binpatch、访问放宽、自己的变换服务）。两边冲突起来很难查：访问权限丢失、方法体被搬走、构造器初始化
被替换等等。

### 它做了什么

启动时 OptiForge 会：

1. 在 `<游戏目录>/optiforge/` 找 OptiFine jar（**不是** `mods/`）；
2. 用 **OptiFine 自带的 patcher 与它自己的 xdelta 负载**，对本版本**原版**客户端 jar 打补丁；
3. 仅当运行时命名空间确实不同时重映射（1.17+ 的官方名），并对 1.13.2 那种扁平混淆负载用专门的桥接；
4. 只重放**本版本线**的 access transformer；
5. 在 OptiFine 补丁与 Forge 改写相撞处做少量兼容修复；
6. 把 OptiFine 自有类（以及可选的 CTM 纹理负载）打包成 `<mods>/OptiForge-<版本>-classes.jar`，
   这就是 mod 列表里那个 OptiForge；
7. 缓存到 `<游戏目录>/optiforge/pack/<key>/`，之后启动直接复用。

### 安装

1. 按正常方式装好对应版本的 **Forge**。
2. 到 <https://optifine.net/downloads> 下载**同一 Minecraft 版本**的 **OptiFine**。
3. 在游戏目录里建一个名为 `optiforge` 的文件夹（`.minecraft/optiforge/`）。
4. 把 OptiFine jar 原样放进去（例如 `.minecraft/optiforge/OptiFine_1.16.5_HD_U_G8.jar`）。
5. 把对应版本的 **OptiForge** jar 放进 `.minecraft/mods/`。
6. 启动游戏。

**第一次启动会更久。** 需要准备东西时控制台会打印提示。1.13–1.16 上 OptiFine 还会在第一次资源重载时
编译默认连接纹理，可能要几分钟，之后走缓存。不用连接纹理的话，加 JVM 参数 `-Doptiforge.assets=none`
可以完全跳过纹理打包。

### 依赖

- 对应版本线的 Forge（不需要其它模组）。
- Java 8 / 17 / 21 / 25（见上表）。
- 你自己从官网下载的 OptiFine jar（本项目不含）。

### 验证状态

下列每一条都启动并进入了世界（单人世界或 Forge 专用服务器），OptiFine 正常加载且无崩溃报告：

- **1.8.9、1.9.4、1.10.2、1.11.2、1.12.2**（legacy 产物）
- **1.14.4、1.15.2**（old-loader 产物）
- **1.17.1、1.18、1.18.1、1.18.2、1.19、1.19.1、1.19.2、1.19.3、1.19.4、1.20.1、1.20.6、1.21、1.21.1、
  1.21.6 – 1.21.11、26.1.2、26.2**（主产物）

光影在 **1.21.8**（Forge 58.1.26、OptiFine `HD_U_J6_pre13`）上实测，按日志里编译成功的 GL program 计数：

| 光影包 | 编译成功 | 错误 | 崩溃 |
|---|---|---|---|
| Complementary Reimagined r5.9.3 | 27 | 0 | 0 |
| BSL v10.1.8 | 26 | 0 | 0 |
| MakeUp Ultra Fast 9.6 | 25 | 0 | 0 |

### 已知问题

- **1.16.5 与 1.13.2 是实验性的。** 能启动、OptiFine 正常工作，但进世界可能崩在 OptiFine 补丁类里
  （1.16.5 是 `ClientWorld.getPartEntities`，1.13.2 是 `RenderChunkCache`）。原因：OptiFine 的负载替换了
  Forge 已经 binpatch 过的类，而 Forge 写在这些类**方法体内部**的改动尚未完全合并。计划在 1.1.0 修。
  想试 1.16.5 请下载 Beta 区那份文件。
- **OptiFine `1.17.1 HD_U_G9`**（官网标 N/A）不可用：它引用发布前的 MCP 编号且没有公开映射。
  1.17.1 请用 `HD_U_H1`。
- **1.20.2**：OptiFine 自身与 Forge 50 冲突（`IllegalAccessError ForgeGui.f_291320_`），与 OptiForge 无关。
- **1.7.10**：产物离线验证全绿（负载 md5、类布局），但作者测试环境里 Forge 1.7.10 自身的 launchwrapper
  阶段会抛 `ConcurrentModificationException`，真机尚未确认，请按 alpha 对待。
- **OptiFine `1.21.7 HD_U_J6_pre7`** 会自己跳过光影包（OptiFine 的 bug，不是 OptiForge）。要光影请用 1.21.8。

### 常见问题

**能启动，但感觉 OptiFine 没生效。**
看 `<游戏目录>/optiforge/diagnose.txt`（旧版本线是 `diagnose-legacy.txt`），里面有版本、命名空间、AT 授权数、
补丁数量、生成 jar 等信息。日志里出现 `[OptiForge] NO ACTIVE PACK` 表示没找到 OptiFine jar——它必须在
`optiforge/` 里，不能放 `mods/`。

**日志提示 `OptiFine's own transformation service is also loaded`。**
OptiFine jar 被放进了 `mods/`，移到 `optiforge/`。

**出现指名"另一个产物 / 另一个 Java 版本"的 WARNING。**
说明 jar 或 JVM 不属于你启动的这条线。按提示换成对应的产物和 Java 版本。

**贴图异常 / 日志刷 `Using missing texture: ... optifine/ctm/...`。**
CTM 纹理负载被跳过了。去掉 `-Doptiforge.assets=none`（默认是打包的）并删掉 `optiforge/pack/` 让它重建。

**1.13–1.16 首次启动像卡住。**
那是 OptiFine 在编译默认 CTM，控制台有提示；等它完成，或加 `-Doptiforge.assets=none`。

### 反馈问题时请附上

- `<游戏目录>/logs/latest.log`
- `<游戏目录>/optiforge/diagnose.txt`（或 `diagnose-legacy.txt`）
- 有崩溃的话附 `crash-reports/` 里最新那份
- Minecraft 版本、Forge 版本、OptiFine 文件名、OptiForge 文件名、Java 版本

### 许可与致谢

- OptiForge：**Mozilla Public License 2.0**，源码 <https://github.com/Kynarain/OptiForge>。
- OptiFine：© **sp614x**，<https://optifine.net>——本项目的必需前置，**不随本项目分发**。
- 与 OptiFine、Mojang、Forge 均无隶属关系，也未获其背书。
