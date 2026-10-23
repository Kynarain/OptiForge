# OptiForge — 中文对照（自行阅读用，不要贴到 CurseForge）

> 让 OptiFine 跑在 Forge 上：OptiForge 用 **你自己那份 OptiFine jar** 走 OptiFine 自带的 patcher，把补丁后的
> 类和一个小的资源 mod 交给游戏，从而让 OptiFine 和 Forge 模组共存，覆盖 **Minecraft 1.7.10 到 26.2**。
> 不含 OptiFine，请自行从 optifine.net 下载。

**一句话版：**

> 让 OptiFine 跑在 Forge 上，覆盖 1.7.10 到 26.2，需自备 OptiFine jar。

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
