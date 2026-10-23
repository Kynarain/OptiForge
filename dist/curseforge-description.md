# 发布用文案（简要描述 / 详细描述）

直接复制粘贴用的成品。**简要描述**用于 CurseForge 项目页的"简介"栏（以及 GitHub 的 About）；
**详细描述**用于 CurseForge 项目正文。

> ⚠️ CurseForge 规则：描述与简介**可以有其他语言，但英文必须排在前面**。所以本文件英文在前、中文在后。

> 写作原则：陈述事实与可核查的数据，不使用宣传性措辞；已验证与未验证分开写；限制条件写在显眼位置。

---

## 一、简要描述(English)

> Loads OptiFine on Forge, including builds that OptiFine lists as "Forge N/A". At startup OptiForge applies
> OptiFine's own patcher to a user-supplied OptiFine jar and hands the patched classes to Forge. OptiFine is
> not included. Verified in-world on 28 Minecraft versions, 1.8.9 to 26.2; two more lines start but can crash in-world.

**One-liner:**

> Loads OptiFine on Forge, including the builds OptiFine marks "Forge N/A", from Minecraft 1.7.10 to 26.2.

---

## 二、详细描述(English)

### OptiForge — the OptiFine builds that do not support Forge, now on Forge

OptiFine's download page has a "Forge" column, and for a growing number of builds it says **"Forge N/A"** —
no Forge support at all. Those builds ship no Forge-aware patch payload and no installer path, so a Forge user
had no supported way to load them. On the newest lines this is already the normal case: every 1.21.x preview
and every 26.x build in the snapshot this project tracks is marked N/A.

**OptiForge makes those builds work on Forge.** It applies OptiFine's own patcher to **a user-supplied
OptiFine jar** at startup and delivers the patched game classes plus a small resource mod to Forge; the builds
OptiFine already lists as Forge-compatible run through it as well.

**OptiFine is not included and is not redistributed.** The published jars contain no OptiFine code and no
OptiFine assets; every user has to download OptiFine from <https://optifine.net/downloads> themselves.

### "Forge N/A" builds

OptiFine's download page lists a Forge compatibility value per build. In the snapshot this project tracks
(`data/optifine-forge-matrix.csv`, 645 rows), **90 rows are marked "Forge N/A"** and 379 rows list a Forge
version. The N/A rows are concentrated on recent version lines: every 1.21.x preview and every 26.x build in
that snapshot is marked N/A.

A Forge-N/A build ships patches for the vanilla game jar only and has no Forge-side entry point, so Forge has
no supported way to load it. Separately, Forge does not treat an OptiFine jar in `mods/` as a mod at all: the
jar declares a ModLauncher transformation service, so Forge picks it up as a service and excludes it from mod
discovery.

OptiForge handles both points by running OptiFine's payload itself, outside OptiFine's installer, and serving
the resulting classes to Forge through its own transformation service.

### Builds verified through OptiForge

Verification method for each row: start the client with the listed Forge version, enter a world (singleplayer
or a Forge dedicated server), confirm OptiFine's version banner and its `[OptiFine]` log lines, and confirm
`crash-reports/` stays empty.

| Minecraft | OptiFine build | OptiFine's Forge column | Forge | Result |
|---|---|---|---|---|
| 1.21 | `preview_OptiFine_1.21_HD_U_J1_pre9.jar` | Forge N/A | 51.0.33 | world loads, no crash report |
| 1.21.1 | `preview_OptiFine_1.21.1_HD_U_J1_pre9.jar` | Forge N/A | 52.1.20 | world loads, no crash report |
| 1.21.6 | `preview_OptiFine_1.21.6_HD_U_J6_pre3.jar` | Forge N/A | 56.0.9 | world loads, no crash report |
| 1.21.7 | `preview_OptiFine_1.21.7_HD_U_J6_pre7.jar` | Forge N/A | 57.0.3 | starts; this OptiFine build does not load shader packs |
| 1.21.9 | `preview_OptiFine_1.21.9_HD_U_J7_pre2.jar` | Forge N/A | 59.0.5 | world loads, no crash report |
| 26.1.2 | `preview_OptiFine_26.1.2_HD_U_K1_pre2.jar` | Forge N/A | 64.1.5 | world loads (Java 25) |
| 26.2 | `preview_OptiFine_26.2_HD_U_K2_pre4.jar` | Forge N/A | 65.1.5 | world loads (Java 25) |
| 1.13.2 | `OptiFine_1.13.2_HD_U_G5.jar` | Forge N/A | 25.0.219 | starts; entering a world can crash (see Known issues) |
| 1.17.1 | `OptiFine_1.17.1_HD_U_G9.jar` | Forge N/A | 37.0.103 | does not work; this build requires mappings that are not public (use `HD_U_H1`) |

Builds that OptiFine already lists as Forge-compatible run through OptiForge as well; verified examples:
1.16.5 `HD_U_G8`, 1.17.1 `HD_U_H1`, 1.19.3 `HD_U_I2_pre4`, 1.21.8 `HD_U_J6_pre13`.

### Artifacts

Four artifacts. Each targets one era of Forge's mod loading mechanism, and they are not interchangeable.

| Minecraft | File | Java |
|---|---|---|
| 1.17 – 1.20.1, 1.20.6, 1.21.x, 26.1.2, 26.2 | `OptiForge-1.0.1.jar` | 17 / 21 / 25 |
| 1.14.4, 1.15.2, 1.16.5 | `OptiForge-1.0.1-oldml.jar` | 8 |
| 1.13.2 | `OptiForge-1.0.1-oldml.jar` | 8 |
| 1.8.9 – 1.12.2 | `OptiForge-1.0.1-legacy.jar` | 8 |
| 1.7.10 | `OptiForge-1.0.1-legacy17.jar` | 8 |

The Java column lists the version those Forge releases run on: Java 17 for 1.17–1.20.4, Java 21 for
1.20.5/1.20.6/1.21.x, Java 25 for 26.x, and Java 8 for 1.13–1.16 and 1.8–1.12. OptiForge cannot change the JVM
the game runs on; it only reports a warning when the jar or the Java version does not match the running line.

### What it does at startup

1. Looks for the OptiFine jar in `<game directory>/optiforge/` (not in `mods/`).
2. Applies OptiFine's own xdelta payload with OptiFine's own patcher to the vanilla client jar of the running
   version.
3. Remaps names only when the runtime namespace differs from the payload's (1.17+ with official names). For
   the 1.13.2 flat obfuscated payload a dedicated mapping table is used.
4. Re-applies the running version line's access transformers to the patched classes.
5. Applies a small set of compatibility repairs where OptiFine's patch and Forge's own class changes overlap.
6. Writes OptiFine's classes (and, if enabled, its CTM texture payloads) into a generated
   `mods/OptiForge-<version>-classes.jar`, which appears as "OptiForge" in the mods list.
7. Caches the result under `<game directory>/optiforge/pack/<key>/`; later launches reuse the cache.

### Installation

1. Install Forge for the Minecraft version.
2. Download OptiFine for the same Minecraft version from <https://optifine.net/downloads>. For a Forge-N/A
   line, use the newest OptiFine build published for that version.
3. Create `.minecraft/optiforge/`.
4. Put the OptiFine jar there without renaming it (for example
   `.minecraft/optiforge/preview_OptiFine_26.2_HD_U_K2_pre4.jar`). OptiFine's installer does not need to be run.
5. Put the OptiForge jar for the same Minecraft version into `.minecraft/mods/`.
6. Start the game.

The first launch performs the patching and takes longer than later ones; the console logs when it starts. On
1.13–1.16 OptiFine additionally compiles its default connected-textures set during the first resource reload,
which takes several minutes. `-Doptiforge.assets=none` skips the texture staging step.

### Requirements

- Forge for the matching version line.
- Java version as listed in the artifacts table.
- An OptiFine jar obtained from the official site.

### Verified state

The following lines were started and brought into a world (singleplayer or a Forge dedicated server) with
OptiFine active and no crash reports. OptiFine log line counts are from those runs.

- 1.8.9, 1.9.4, 1.10.2, 1.11.2 (legacy artifact): 3314–3316 `[OptiFine]` lines each.
- 1.12.2 (legacy artifact): 1669 `[OptiFine]` lines.
- 1.14.4, 1.15.2 (old-loader artifact): 1684 and 3320 `[OptiFine]` lines.
- 1.16.5 (old-loader artifact): starts and OptiFine loads; entering a world can crash (see Known issues).
- 1.17.1, 1.18, 1.18.1, 1.18.2, 1.19, 1.19.1, 1.19.2, 1.19.3, 1.19.4, 1.20.1, 1.20.6, 1.21, 1.21.1, 1.21.6,
  1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1.2, 26.2 (main artifact).
- 1.13.2 (old-loader artifact): starts and OptiFine loads; entering a world can crash (see Known issues).

Shader test, run on 1.21.8 with Forge 58.1.26 and OptiFine `HD_U_J6_pre13`. Counted from the game log, which
prints one `Program loaded:` line per successfully compiled and linked GL program:

| Shader pack | Compiled programs | Compile errors | Crash reports |
|---|---|---|---|
| Complementary Reimagined r5.9.3 | 27 | 0 | 0 |
| BSL v10.1.8 | 26 | 0 | 0 |
| MakeUp Ultra Fast 9.6 | 25 | 0 | 0 |

Versions not listed above have not been tested and are not claimed to work.

### Known issues

- **1.16.5 and 1.13.2**: the game starts and OptiFine loads, but entering a world can crash inside OptiFine's
  patched classes — `ClientWorld.getPartEntities` on 1.16.5 and `RenderChunkCache` on 1.13.2. OptiFine's payload
  replaces classes that Forge has already binpatch-ed, and Forge's changes inside those classes are not fully
  merged yet. Planned fix: 1.1.0.
- **OptiFine `1.17.1 HD_U_G9`**: does not work. Its payload references pre-release MCP ids, and no public
  mapping for them exists. Use `HD_U_H1` on 1.17.1.
- **1.20.2**: OptiFine itself fails against Forge 50 with `IllegalAccessError ForgeGui.f_291320_`, independently
  of OptiForge.
- **1.7.10**: the artifact passes offline verification (payload md5, class layout), but in the author's test
  environment Forge 1.7.10's own launchwrapper pass throws `ConcurrentModificationException`, with or without
  OptiForge present. An in-game run has not been confirmed.
- **OptiFine `1.21.7 HD_U_J6_pre7`**: does not load shader packs. The game logs `[Shaders] No shaderpack
  loaded.`; the build's `Shaders.loadShaderPack` contains an unconditional early return, and this is present
  without OptiForge as well.

### Troubleshooting

**OptiFine appears to be absent.** Open `<game directory>/optiforge/diagnose.txt` (legacy lines:
`diagnose-legacy.txt`). It records the detected versions, runtime namespace, access-transformer grants, patch
counts and the generated jar. The log line `[OptiForge] NO ACTIVE PACK` means no OptiFine jar was found in
`optiforge/`.

**Log says "OptiFine's own transformation service is also loaded".** The OptiFine jar is in `mods/`; it belongs
in `optiforge/`.

**A warning names another artifact or another Java version.** The jar or JVM does not match the running line.
Use the file and Java version named in the warning.

**Log repeats `Using missing texture: ... optifine/ctm/...`.** The CTM payload was skipped. Remove
`-Doptiforge.assets=none` and delete `optiforge/pack/` so the next launch rebuilds it.

**First launch on 1.13–1.16 appears to hang.** It is OptiFine compiling its default CTM set; the console says
so. Wait for it to finish, or use `-Doptiforge.assets=none`.

### Reporting a problem

Include:

- `<game directory>/logs/latest.log`
- `<game directory>/optiforge/diagnose.txt` (or `diagnose-legacy.txt`)
- the newest file from `crash-reports/`, if present
- Minecraft version, Forge version, OptiFine file name, OptiForge file name and Java version

### License and credits

- OptiForge: Mozilla Public License 2.0; source at <https://github.com/Kynarain/OptiForge>.
- OptiFine: © sp614x, <https://optifine.net>. Required, not included, not redistributed here.
- OptiForge is not affiliated with or endorsed by OptiFine, Mojang or Forge.

---

## 三、简要描述(中文)

> 在 Forge 上加载 OptiFine，包括 OptiFine 官方标注为 "Forge N/A" 的构建。启动时 OptiForge 用 OptiFine 自带的
> patcher 处理用户自备的 OptiFine jar，并把补丁后的类交给 Forge。本项目不含 OptiFine。已在 1.8.9 到 26.2
> 之间的 28 个 Minecraft 版本上实测进世界；另有 2 条线能启动、进世界会崩。

**一句话版：**

> 在 Forge 上加载 OptiFine，包括官方标注 "Forge N/A" 的构建，覆盖 Minecraft 1.7.10 到 26.2。

---

## 四、详细描述(中文)

### OptiForge —— 官方不支持 Forge 的 OptiFine 构建，现在能在 Forge 上跑

OptiFine 的下载页有一列"Forge"，越来越多的构建在那列写的是 **"Forge N/A"**（官方不兼容 Forge）。这些构建
没有给 Forge 用的补丁入口，也没有安装路径，Forge 用户此前没有受支持的加载方式。在最新的版本线上这已经是
常态：本项目跟踪的快照里，1.21.x 的各个 preview 与 26.x 的各个构建全部标为 N/A。

**OptiForge 就是补上这一块。** 启动时它用 OptiFine 自带的 patcher 处理**用户自备的 OptiFine jar**，
把补丁后的游戏类与一个小资源 mod 交给 Forge；官方本来就标为兼容 Forge 的构建同样可以通过它运行。

**本项目不含、也不分发 OptiFine。** 发布的 jar 里没有任何 OptiFine 代码或资源；使用者需要自己到
<https://optifine.net/downloads> 下载。

### 关于 "Forge N/A" 构建

OptiFine 下载页为每个构建标出一个 Forge 兼容值。本项目跟踪的快照
（`data/optifine-forge-matrix.csv`，645 行）里，**90 行标为 "Forge N/A"**，379 行标出了具体 Forge 版本；
N/A 集中在较新的版本线上——该快照里 1.21.x 的各个 preview 与 26.x 的各个构建都是 N/A。

Forge-N/A 构建只带针对原版 jar 的补丁，没有 Forge 侧入口，因此 Forge 没有支持它的加载方式。另外，Forge
不会把 `mods/` 里的 OptiFine jar 当普通模组：该 jar 声明了 ModLauncher 变换服务，Forge 会把它当作服务并从
模组发现中排除。

OptiForge 处理这两点的做法是：在 OptiFine 安装器之外自行运行它的负载，并通过自己的变换服务把结果交给
Forge。

### 已通过 OptiForge 验证的构建

每行的验证方法：用表中列出的 Forge 版本启动客户端，进入世界（单人世界或 Forge 专用服务器），确认日志里
有 OptiFine 版本横幅与 `[OptiFine]` 行，并确认 `crash-reports/` 为空。

| Minecraft | OptiFine 构建 | OptiFine 的 Forge 列 | Forge | 结果 |
|---|---|---|---|---|
| 1.21 | `preview_OptiFine_1.21_HD_U_J1_pre9.jar` | Forge N/A | 51.0.33 | 进世界，无崩溃报告 |
| 1.21.1 | `preview_OptiFine_1.21.1_HD_U_J1_pre9.jar` | Forge N/A | 52.1.20 | 进世界，无崩溃报告 |
| 1.21.6 | `preview_OptiFine_1.21.6_HD_U_J6_pre3.jar` | Forge N/A | 56.0.9 | 进世界，无崩溃报告 |
| 1.21.7 | `preview_OptiFine_1.21.7_HD_U_J6_pre7.jar` | Forge N/A | 57.0.3 | 能启动；该 OptiFine 构建不加载光影包 |
| 1.21.9 | `preview_OptiFine_1.21.9_HD_U_J7_pre2.jar` | Forge N/A | 59.0.5 | 进世界，无崩溃报告 |
| 26.1.2 | `preview_OptiFine_26.1.2_HD_U_K1_pre2.jar` | Forge N/A | 64.1.5 | 进世界（Java 25） |
| 26.2 | `preview_OptiFine_26.2_HD_U_K2_pre4.jar` | Forge N/A | 65.1.5 | 进世界（Java 25） |
| 1.13.2 | `OptiFine_1.13.2_HD_U_G5.jar` | Forge N/A | 25.0.219 | 能启动；进世界可能崩（见已知问题） |
| 1.17.1 | `OptiFine_1.17.1_HD_U_G9.jar` | Forge N/A | 37.0.103 | 不可用；该构建需要未公开的映射（请用 `HD_U_H1`） |

OptiFine 官方已标为兼容 Forge 的构建同样可以通过 OptiForge 运行，已验证的例子：1.16.5 `HD_U_G8`、
1.17.1 `HD_U_H1`、1.19.3 `HD_U_I2_pre4`、1.21.8 `HD_U_J6_pre13`。

### 产物

四份产物，各自对应一代 Forge 的模组加载机制，不能互换。

| Minecraft | 文件 | Java |
|---|---|---|
| 1.17 – 1.20.1、1.20.6、1.21.x、26.1.2、26.2 | `OptiForge-1.0.1.jar` | 17 / 21 / 25 |
| 1.14.4、1.15.2、1.16.5 | `OptiForge-1.0.1-oldml.jar` | 8 |
| 1.13.2 | `OptiForge-1.0.1-oldml.jar` | 8 |
| 1.8.9 – 1.12.2 | `OptiForge-1.0.1-legacy.jar` | 8 |
| 1.7.10 | `OptiForge-1.0.1-legacy17.jar` | 8 |

Java 列是这些 Forge 版本自身的运行要求：1.17–1.20.4 用 17，1.20.5/1.20.6/1.21.x 用 21，26.x 用 25，
1.13–1.16 与 1.8–1.12 用 8。OptiForge 无法改变游戏使用的 JVM，只会在 jar 或 Java 版本与所启动的版本线不匹配时
输出警告。

### 启动时做什么

1. 在 `<游戏目录>/optiforge/` 中查找 OptiFine jar（不是 `mods/`）。
2. 用 OptiFine 自带的 xdelta 负载与它自己的 patcher，对当前版本的原版客户端 jar 打补丁。
3. 仅当运行时命名空间与负载不一致时重映射（1.17+ 使用官方名）；1.13.2 的扁平混淆负载使用专门的映射表。
4. 把当前版本线的 access transformer 重新应用到补丁类上。
5. 在 OptiFine 补丁与 Forge 自身改动重叠处做少量兼容修复。
6. 把 OptiFine 的类（以及启用时的 CTM 纹理负载）写入生成的 `mods/OptiForge-<版本>-classes.jar`，
   该文件在模组列表中显示为 "OptiForge"。
7. 结果缓存到 `<游戏目录>/optiforge/pack/<key>/`，后续启动复用缓存。

### 安装

1. 安装对应 Minecraft 版本的 Forge。
2. 到 <https://optifine.net/downloads> 下载同一 Minecraft 版本的 OptiFine；Forge-N/A 版本线请用该版本已发布的
   最新构建。
3. 创建 `.minecraft/optiforge/`。
4. 把 OptiFine jar 原样放入（例如 `.minecraft/optiforge/preview_OptiFine_26.2_HD_U_K2_pre4.jar`），
   不需要运行 OptiFine 的安装器。
5. 把同一 Minecraft 版本的 OptiForge jar 放入 `.minecraft/mods/`。
6. 启动游戏。

首次启动会执行补丁流程，比后续启动慢，控制台会打印开始信息。1.13–1.16 上 OptiFine 还会在首次资源重载时
编译默认连接纹理，这需要数分钟。`-Doptiforge.assets=none` 可跳过纹理打包步骤。

### 依赖

- 对应版本线的 Forge。
- 与产物表一致的 Java 版本。
- 从官网获取的 OptiFine jar。

### 验证状态

下列版本线均已启动并进入世界（单人世界或 Forge 专用服务器），OptiFine 正常加载且无崩溃报告。OptiFine 日志
行数来自这些运行。

- 1.8.9、1.9.4、1.10.2、1.11.2（legacy 产物）：各 3314–3316 条 `[OptiFine]`。
- 1.12.2（legacy 产物）：1669 条 `[OptiFine]`。
- 1.14.4、1.15.2（old-loader 产物）：1684 与 3320 条 `[OptiFine]`。
- 1.16.5（old-loader 产物）：能启动、OptiFine 正常加载；进世界可能崩（见已知问题）。
- 1.17.1、1.18、1.18.1、1.18.2、1.19、1.19.1、1.19.2、1.19.3、1.19.4、1.20.1、1.20.6、1.21、1.21.1、
  1.21.6、1.21.7、1.21.8、1.21.9、1.21.10、1.21.11、26.1.2、26.2（主产物）。
- 1.13.2（old-loader 产物）：能启动、OptiFine 正常加载；进世界可能崩（见已知问题）。

光影测试在 1.21.8（Forge 58.1.26、OptiFine `HD_U_J6_pre13`）上进行，按日志统计：每个成功编译并链接的 GL
program 会打印一行 `Program loaded:`。

| 光影包 | 编译成功数量 | 编译错误 | 崩溃报告 |
|---|---|---|---|
| Complementary Reimagined r5.9.3 | 27 | 0 | 0 |
| BSL v10.1.8 | 26 | 0 | 0 |
| MakeUp Ultra Fast 9.6 | 25 | 0 | 0 |

未列出的版本未做测试，不作可用性声明。

### 已知问题

- **1.16.5 与 1.13.2**：能启动、OptiFine 正常加载，但进世界可能崩在 OptiFine 的补丁类里——1.16.5 是
  `ClientWorld.getPartEntities`，1.13.2 是 `RenderChunkCache`。原因是 OptiFine 的负载替换了 Forge 已
  binpatch 过的类，而 Forge 写在这些类内部的方法体改动尚未完全合并。计划在 1.1.0 修复。
- **OptiFine `1.17.1 HD_U_G9`**：不可用。其负载引用发布前的 MCP 编号，且没有公开映射。1.17.1 请用
  `HD_U_H1`。
- **1.20.2**：OptiFine 自身与 Forge 50 冲突，报 `IllegalAccessError ForgeGui.f_291320_`，与 OptiForge 无关。
- **1.7.10**：产物通过离线验证（负载 md5、类布局），但在作者的测试环境中，无论是否加载 OptiForge，
  Forge 1.7.10 自身的 launchwrapper 阶段都会抛 `ConcurrentModificationException`。真机运行尚未确认。
- **OptiFine `1.21.7 HD_U_J6_pre7`**：不加载光影包，日志为 `[Shaders] No shaderpack loaded.`；该构建的
  `Shaders.loadShaderPack` 中存在无条件提前返回，未加载 OptiForge 时同样如此。

### 常见问题

**感觉 OptiFine 没生效。** 打开 `<游戏目录>/optiforge/diagnose.txt`（旧版本线为 `diagnose-legacy.txt`），
其中有检测到的版本、运行时命名空间、AT 授权数、补丁数量与生成的 jar。日志出现
`[OptiForge] NO ACTIVE PACK` 表示 `optiforge/` 中没有找到 OptiFine jar。

**日志提示 "OptiFine's own transformation service is also loaded"。** OptiFine jar 被放在了 `mods/`，
应放在 `optiforge/`。

**出现指名另一个产物或另一个 Java 版本的警告。** jar 或 JVM 与所启动的版本线不匹配，按警告里给出的文件与
Java 版本替换。

**日志反复出现 `Using missing texture: ... optifine/ctm/...`。** CTM 负载被跳过。去掉
`-Doptiforge.assets=none` 并删除 `optiforge/pack/`，让下次启动重建。

**1.13–1.16 首次启动像卡住。** 这是 OptiFine 在编译默认 CTM 集，控制台有对应提示；等待完成，或使用
`-Doptiforge.assets=none`。

### 反馈问题时请附上

- `<游戏目录>/logs/latest.log`
- `<游戏目录>/optiforge/diagnose.txt`（或 `diagnose-legacy.txt`）
- 若有崩溃，附 `crash-reports/` 中最新的一份
- Minecraft 版本、Forge 版本、OptiFine 文件名、OptiForge 文件名、Java 版本

### 许可与致谢

- OptiForge：Mozilla Public License 2.0，源码 <https://github.com/Kynarain/OptiForge>。
- OptiFine：© sp614x，<https://optifine.net>。本项目需要它但不包含它，也不分发它。
- OptiForge 与 OptiFine、Mojang、Forge 无隶属关系，也未获其背书。
