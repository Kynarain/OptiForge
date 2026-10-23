# 发布用文案（简要描述 / 详细描述）

直接复制粘贴用的成品。**简要描述**用于 CurseForge 项目页的"简介"栏（以及 GitHub 的 About）；
**详细描述**用于 CurseForge 项目正文。

> ⚠️ CurseForge 规则：描述与简介**可以有其他语言，但英文必须排在前面**。所以本文件英文在前、中文在后；
> 粘贴时每个字段先贴英文、再贴中文即可（只用英文就只贴英文那节）。

> 📌 定位：本项目解决的是 **OptiFine 官方标注 "Forge: N/A" 的构建**——那些构建官方就不支持 Forge，
> 用普通方式根本跑不起来。OptiForge 让它们能在 Forge 上跑（官方本来就兼容 Forge 的构建也一并支持）。

---

## 一、简要描述(English)

> OptiFine's download page marks many builds **"Forge: N/A"** — no Forge support at all, mostly every
> 1.21.x and 26.x preview. OptiForge runs those builds on Forge: it applies OptiFine's own patcher to **your
> own OptiFine jar** at startup so OptiFine and Forge mods work together, from **Minecraft 1.7.10 to 26.2**.

**One-liner:**

> Run the OptiFine builds that OptiFine says do not support Forge — now on Forge, 1.7.10 to 26.2.

---

## 二、详细描述(English)

### OptiForge — the OptiFine builds that do not support Forge, now on Forge

OptiFine's download page has a "Forge" column, and for a growing number of builds it says **"Forge N/A"**.
Those builds ship no Forge support at all: no Forge-aware patch payload, no installer path, nothing to load
them with. On the newest lines this is the *normal* case — every 1.21.x preview and every 26.x build has been
marked N/A so far, so a Forge user could not use OptiFine on those versions at all.

**OptiForge makes those builds work on Forge.** Put the OptiForge jar for your game version into `mods/`, put
**your own OptiFine jar** into `optiforge/`, and start the game: OptiFine's own patcher is applied at startup,
and the result is delivered to Forge as game classes plus a small resource mod.

> ⚠️ **OptiFine is not bundled or redistributed.** Download the build for your Minecraft version from the
> official site (<https://optifine.net/downloads>) and drop the jar in as-is — you do **not** need to run
> OptiFine's installer. OptiForge never ships OptiFine code; the artifacts contain zero `net/optifine/**`
> entries and zero OptiFine assets.

### What "Forge: N/A" actually means

- The build is compiled against one particular vanilla Minecraft build and only ships patches for the vanilla
  jar. There is no Forge-side entry point, so Forge never learns how to load it.
- OptiFine for Forge normally works by being installed *into* the game; on an N/A build even that is missing,
  and running its installer on a Forge setup does not produce a working game.
- Forge itself refuses to treat an OptiFine jar in `mods/` as a mod: the jar declares a ModLauncher
  transformation service, so Forge picks it up as a service and excludes it from mod discovery — leaving two
  sides patching the same classes.

OptiForge takes over exactly that missing piece: it runs OptiFine's own xdelta payload with OptiFine's own
patcher, outside its installer, and hands the patched classes to Forge.

### Forge-N/A builds verified through OptiForge

Each row was started with Forge and brought into a world (singleplayer or a Forge dedicated server) with no
crash reports; the log shows OptiFine's own version banner and hundreds of `[OptiFine]` lines.

| Minecraft | OptiFine build (official status) | Forge | Result through OptiForge |
|---|---|---|---|
| 1.21 | `preview_OptiFine_1.21_HD_U_J1_pre9.jar` (Forge N/A) | 51.0.33 | world loads, OptiFine active |
| 1.21.1 | `preview_OptiFine_1.21.1_HD_U_J1_pre9.jar` (Forge N/A) | 52.1.20 | world loads, OptiFine active |
| 1.21.6 | `preview_OptiFine_1.21.6_HD_U_J6_pre3.jar` (Forge N/A) | 56.0.9 | world loads, OptiFine active |
| 1.21.7 | `preview_OptiFine_1.21.7_HD_U_J6_pre7.jar` (Forge N/A) | 57.0.3 | starts and works; shader packs are skipped by OptiFine itself on this build |
| 1.21.9 | `preview_OptiFine_1.21.9_HD_U_J7_pre2.jar` (Forge N/A) | 59.0.5 | world loads, OptiFine active |
| 26.1.2 | `preview_OptiFine_26.1.2_HD_U_K1_pre2.jar` (Forge N/A) | 64.1.5 | world loads (Java 25) |
| 26.2 | `preview_OptiFine_26.2_HD_U_K2_pre4.jar` (Forge N/A) | 65.1.5 | world loads (Java 25) |
| 1.13.2 | `OptiFine_1.13.2_HD_U_G5.jar` (Forge N/A) | 25.0.219 | **experimental**: starts, but entering a world can crash (see Known issues) |
| 1.17.1 | `OptiFine_1.17.1_HD_U_G9.jar` (Forge N/A) | 37.0.103 | **does not work**: payload uses pre-release ids; use `HD_U_H1` |

### Official Forge-compatible builds also work

OptiForge is not limited to N/A builds — the builds that OptiFine already marks as Forge-compatible run through
it as well, on the same four artifacts. For example, verified end-to-end: 1.16.5 `HD_U_G8`,
1.17.1 `HD_U_H1`, 1.19.3 `HD_U_I2_pre4`, 1.21.8 `HD_U_J6_pre13`.

### Supported artifacts

Four artifacts, one per era. They are **not interchangeable**: each era loads mods through a different
mechanism, so pick the jar that matches your Minecraft version.

| Minecraft | OptiForge file | Java |
|---|---|---|
| **1.17 – 1.20.1, 1.20.6, 1.21.x, 26.1.2, 26.2** | `OptiForge-1.0.1.jar` | 17 / 21 / 25 |
| **1.14.4, 1.15.2, 1.16.5** | `OptiForge-1.0.1-oldml.jar` | **8** |
| **1.13.2** | `OptiForge-1.0.1-oldml.jar` | **8** |
| **1.8.9 – 1.12.2** | `OptiForge-1.0.1-legacy.jar` | **8** |
| **1.7.10** | `OptiForge-1.0.1-legacy17.jar` | **8** |

The Java column is Forge's own requirement, not a preference: Forge 1.13–1.16 and 1.8–1.12 do not run on newer
JVMs. On 1.17–1.20.4 use Java 17, on 1.20.5/1.20.6/1.21.x Java 21, and on 26.x Java 25.

### What it does

At startup OptiForge:

1. finds the OptiFine jar in `<game directory>/optiforge/` (never in `mods/`);
2. applies **OptiFine's own xdelta payload** with OptiFine's own patcher to the **vanilla** client jar for your
   version — the same payload the installer would apply, but without needing an N/A build's Forge support;
3. remaps only when the runtime really uses a different namespace (1.17+ with official names), with a dedicated
   obfuscation bridge for the old 1.13.2-style flat payload;
4. replays **only the running version line's** access transformers onto the patched classes;
5. applies small compatibility repairs where OptiFine's patch and Forge's rewrite collide;
6. stages OptiFine's own classes (and, on request, its CTM texture payloads) as a generated
   `mods/OptiForge-<version>-classes.jar`, which is the "OptiForge" entry you see in the mods list;
7. caches everything under `<game directory>/optiforge/pack/<key>/` so later launches reuse it.

### Installation

1. Install **Forge** for your Minecraft version, as usual.
2. Download **OptiFine** for the *same* Minecraft version from <https://optifine.net/downloads> — for N/A
   builds, pick the newest preview for that version.
3. Create a folder named `optiforge` inside your game directory (`.minecraft/optiforge/`).
4. Put the OptiFine jar there, keeping its original file name
   (e.g. `.minecraft/optiforge/preview_OptiFine_26.2_HD_U_K2_pre4.jar`).
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
  those classes are not fully merged yet. Fix planned for 1.1.0.
- **OptiFine `1.17.1 HD_U_G9`** is the one N/A build that does not work: its payload uses pre-release MCP ids
  and no public mapping exists for them. Use `HD_U_H1` on 1.17.1 (also N/A-free: it is officially compatible).
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

> OptiFine 的下载页上，越来越多构建被标成 **"Forge: N/A"**——官方完全不支持 Forge，主要是 1.21.x 的各个
> preview 和 26.x 的构建。OptiForge 让这些构建能在 Forge 上跑：启动时用 OptiFine 自己的 patcher 处理
> **你自己那份 OptiFine jar**，从而让 OptiFine 与 Forge 模组共存，覆盖 **Minecraft 1.7.10 到 26.2**。

**一句话版：**

> 让那些官方标注"不支持 Forge"的 OptiFine 构建跑在 Forge 上，覆盖 1.7.10 到 26.2。

---

## 四、详细描述(中文)

### OptiForge —— 官方不支持 Forge 的 OptiFine 构建，现在能在 Forge 上跑

OptiFine 下载页有一列"Forge"，越来越多的构建在那列写的是 **"Forge N/A"**（官方不兼容 Forge）。
这些构建根本没有 Forge 支持：没有给 Forge 用的补丁入口，也没有安装路径，普通方式装上去也跑不起来。
在最新的版本线上这已经是常态——1.21.x 的各个 preview、26.x 的各个构建目前全是 N/A，Forge 用户在这些版本上
根本用不了 OptiFine。

**OptiForge 就是补上这一块。** 把对应版本的 OptiForge jar 放进 `mods/`，把**你自己那份 OptiFine jar**
放进 `optiforge/`，启动游戏：OptiFine 自带的 patcher 会在启动时被调用，结果以游戏类 + 一个小资源 mod 的
形式交给 Forge。

> ⚠️ **本项目不含、也不二次分发 OptiFine。** 请到官网 <https://optifine.net/downloads> 下载对应你
> Minecraft 版本的构建，原样放进去即可（**不需要**运行 OptiFine 的安装器）。产物里 `net/optifine/**`
> 与 OptiFine 资源都是 0 条目。

### "Forge: N/A" 到底意味着什么

- 构建是针对**某一个具体原版 Minecraft 构建**编译的，只带原版 jar 的补丁；没有 Forge 侧入口，
  Forge 根本不知道该怎么加载它。
- 官方支持 Forge 的 OptiFine 是"装进游戏"的方式工作的；N/A 构建连这套都没有，把它的安装器跑在 Forge
  上也装不出能用的游戏。
- Forge 还会拒绝把 `mods/` 里的 OptiFine jar 当普通模组：它声明了 ModLauncher 变换服务，Forge 会把它
  当成"服务"并从模组发现里排除——结果就是两边同时改同一批类。

OptiForge 接手的正是这块缺失：它把 OptiFine 自己的 xdelta 负载用 OptiFine 自己的 patcher 在安装器之外
跑一遍，再把补丁后的类交给 Forge。

### 已通过 OptiForge 验证的 Forge-N/A 构建

下表每一行都用 Forge 启动并进入了世界（单人世界或 Forge 专用服务器），无崩溃报告；日志里有 OptiFine 自己的
版本横幅和成百上千条 `[OptiFine]`。

| Minecraft | OptiFine 构建（官方状态） | Forge | 通过 OptiForge 的结果 |
|---|---|---|---|
| 1.21 | `preview_OptiFine_1.21_HD_U_J1_pre9.jar`（N/A） | 51.0.33 | 进世界，OptiFine 正常 |
| 1.21.1 | `preview_OptiFine_1.21.1_HD_U_J1_pre9.jar`（N/A） | 52.1.20 | 进世界，OptiFine 正常 |
| 1.21.6 | `preview_OptiFine_1.21.6_HD_U_J6_pre3.jar`（N/A） | 56.0.9 | 进世界，OptiFine 正常 |
| 1.21.7 | `preview_OptiFine_1.21.7_HD_U_J6_pre7.jar`（N/A） | 57.0.3 | 能启动能玩；该构建自身会跳过光影包 |
| 1.21.9 | `preview_OptiFine_1.21.9_HD_U_J7_pre2.jar`（N/A） | 59.0.5 | 进世界，OptiFine 正常 |
| 26.1.2 | `preview_OptiFine_26.1.2_HD_U_K1_pre2.jar`（N/A） | 64.1.5 | 进世界（Java 25） |
| 26.2 | `preview_OptiFine_26.2_HD_U_K2_pre4.jar`（N/A） | 65.1.5 | 进世界（Java 25） |
| 1.13.2 | `OptiFine_1.13.2_HD_U_G5.jar`（N/A） | 25.0.219 | **实验性**：能启动，进世界可能崩（见已知问题） |
| 1.17.1 | `OptiFine_1.17.1_HD_U_G9.jar`（N/A） | 37.0.103 | **不可用**：负载用的是发布前的编号；请用 `HD_U_H1` |

### 官方本来就兼容 Forge 的构建也能用

OptiForge 不只处理 N/A 构建——OptiFine 官方标为"兼容 Forge"的构建同样能通过它运行，用的是同一套四份产物。
已端到端验证的例子：1.16.5 `HD_U_G8`、1.17.1 `HD_U_H1`、1.19.3 `HD_U_I2_pre4`、1.21.8 `HD_U_J6_pre13`。

### 支持的产物

四份产物，按版本世代区分，**不可互换**——每个世代加载模组的机制不同，请选与你 Minecraft 版本对应的那份。

| Minecraft | OptiForge 文件 | Java |
|---|---|---|
| **1.17 – 1.20.1、1.20.6、1.21.x、26.1.2、26.2** | `OptiForge-1.0.1.jar` | 17 / 21 / 25 |
| **1.14.4、1.15.2、1.16.5** | `OptiForge-1.0.1-oldml.jar` | **8** |
| **1.13.2** | `OptiForge-1.0.1-oldml.jar` | **8** |
| **1.8.9 – 1.12.2** | `OptiForge-1.0.1-legacy.jar` | **8** |
| **1.7.10** | `OptiForge-1.0.1-legacy17.jar` | **8** |

Java 那一列是 **Forge 自己的要求**：1.13–1.16 与 1.8–1.12 的 Forge 不支持更新的 JVM。1.17–1.20.4 用 17，
1.20.5/1.20.6/1.21.x 用 21，26.x 用 25。

### 它做了什么

启动时 OptiForge 会：

1. 在 `<游戏目录>/optiforge/` 找 OptiFine jar（**不是** `mods/`）；
2. 用 **OptiFine 自带的 patcher 与它自己的 xdelta 负载**，对本版本**原版**客户端 jar 打补丁——
   就是安装器会做的那一步，但不需要该构建具备 Forge 支持；
3. 仅当运行时命名空间确实不同时重映射（1.17+ 的官方名），并对 1.13.2 那种扁平混淆负载用专门的桥接；
4. 只重放**本版本线**的 access transformer；
5. 在 OptiFine 补丁与 Forge 改写相撞处做少量兼容修复；
6. 把 OptiFine 自有类（以及可选的 CTM 纹理负载）打包成 `<mods>/OptiForge-<版本>-classes.jar`，
   这就是 mod 列表里那个 OptiForge；
7. 缓存到 `<游戏目录>/optiforge/pack/<key>/`，之后启动直接复用。

### 安装

1. 按正常方式装好对应版本的 **Forge**。
2. 到 <https://optifine.net/downloads> 下载**同一 Minecraft 版本**的 **OptiFine**（N/A 构建就选该版本最新的
   preview）。
3. 在游戏目录里建一个名为 `optiforge` 的文件夹（`.minecraft/optiforge/`）。
4. 把 OptiFine jar 原样放进去（例如 `.minecraft/optiforge/preview_OptiFine_26.2_HD_U_K2_pre4.jar`）。
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
- **OptiFine `1.17.1 HD_U_G9`** 是唯一救不回来的 N/A 构建：它引用发布前的 MCP 编号且没有公开映射。
  1.17.1 请用 `HD_U_H1`（官方标为兼容）。
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
