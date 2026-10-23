# OptiForge — 中文对照（自行阅读用，不要贴到 CurseForge）

> 在 Forge 上加载 OptiFine，包括 OptiFine 官方标注为 "Forge N/A" 的构建。启动时 OptiForge 用 OptiFine 自带的
> patcher 处理用户自备的 OptiFine jar，并把补丁后的类交给 Forge。本项目不含 OptiFine。已在 1.8.9 到 26.2
> 之间的 28 个 Minecraft 版本上实测进世界；另有 2 条线能启动、进世界会崩。

**一句话版：**

> 在 Forge 上加载 OptiFine，包括官方标注 "Forge N/A" 的构建，覆盖 Minecraft 1.7.10 到 26.2。

### OptiForge —— 官方不支持 Forge 的 OptiFine 构建，现在能在 Forge 上跑

OptiFine 的下载页有一列"Forge"，越来越多的构建在那列写的是 **"Forge N/A"**（官方不兼容 Forge）。这些构建
没有给 Forge 用的补丁入口，也没有安装路径，Forge 用户此前没有受支持的加载方式。在最新的版本线上这已经是
常态：本项目跟踪的快照里，1.21.x 的各个 preview 与 26.x 的各个构建全部标为 N/A。

**OptiForge 就是补上这一块。** 启动时它用 OptiFine 自带的 patcher 处理**用户自备的 OptiFine jar**，
把补丁后的游戏类与一个小资源 mod 交给 Forge；官方本来就标为兼容 Forge 的构建同样可以通过它运行。

**本项目不含、也不分发 OptiFine。** 发布的 jar 里没有任何 OptiFine 代码或资源；使用者需要自己到
<https://optifine.net/downloads> 下载。

**与其它项目的关系。** 本项目是独立项目。另有一个由 ZekerZhayard 发布的同名模组 "OptiForge"（面向 Forge
1.17.1，且需要先运行 OptiFine 安装器）；本项目与它没有隶属关系、不共享代码，也完全不需要 OptiFine 安装器。
游戏内的模组名仍为 "OptiForge"（mod id `optiforge`）。

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
