# 光影实测：OptiForge 下 shader pack 的编译与渲染

一句话：**OptiForge 能把 OptiFine 的光影管线跑起来——测试的三个高下载光影包都真的启用了、编译了 25–27 个
GL program、0 条错误、0 崩溃、并且进了世界渲染**。但**不是在 1.21.7 上**：这条线最新的 OptiFine 构建
`1.21.7 HD_U_J6_pre7` 自己就把 pack 加载关掉了（见下），和 OptiForge 无关。

## 为什么判定看编译日志

OptiFine 的 shader 事件全部走 `net.optifine.shaders.SMCLog`，前缀固定是 `[Shaders]`。所以：

| 日志行 | 含义 |
|---|---|
| `[Shaders] Loaded shaderpack: <file>` | 这个包被 OptiFine 接受并启用 |
| `[Shaders] Program loaded: <name>` | 一个 GL program 编译+链接成功 |
| `[Shaders] Error compiling vertex shader: ...` / `Error linking program: ...` / `[Shaders] Error: Invalid program` | 编译/链接失败，**同一个通道**里会留下错误行 |
| `[Shaders] No shaderpack loaded.` | pack 名字没读到 / 该构建不加载 pack |

`Program loaded:` 只在 `Program.getId() > 0` 时打印（`Shaders.init()` 里 `setupProgram(...)` 之后，
反汇编确认），所以这一行就是"真的拿到 GL program id"，不是"尝试过加载"。所以"游戏启动了"不算证据，
**program 行数和错误行数**才是。一个编不过的包照样会把这些错误打进 `latest.log`。

## shaderPack 写在哪个文件（顺带纠正一个猜测）

不是 `optionsof.txt`，是 **`<game>/optionsshaders.txt`**，一行 `shaderPack=<zip 文件名>`：

- `Shaders.<clinit>`：`new File(Minecraft.gameDirectory, "optionsshaders.txt")` → `Shaders.configFile`；
- key 来自 `EnumShaderOption.SHADER_PACK.getPropertyKey()`，javap 出来就是 `shaderPack`，文件是
  `java.util.Properties`（`key=value`）。

实测把同样的行写进 `optionsof.txt` 只会得到
`[OptiFine] Skipping bad option: shaderPack=ComplementaryReimagined_r5.9.3.zip`，包不会加载。
`tools/shader-test.ps1` 因此只写 `optionsshaders.txt`。

## 1.21.7 这一行为什么没有编译证据

请求的版本线是 `1.21.7 / Forge 57.0.3 / OptiFine 1.21.7 HD_U_J6_pre7`。同一套实例、三个包各跑 150 s
（`shaderpacks/` 里就是该 zip、`optionsshaders.txt` 已设置），三次结果**完全一样**：世界都进了、
`[OptiFine]` 各 247 行、0 崩溃，但 **`[Shaders] No shaderpack loaded.`，program 0 个**，
`[Shaders]` 行也都只有同样的 11 行。

原因在 OptiFine 自己的字节码里：反汇编该 jar 的 `Shaders.loadShaderPack()`，在读完 `shaderPack`
属性之后有一条**无条件的 `flag = true`**（`iconst_1; istore_2; iload_2; ifne`），于是
`getShaderPack()` 永远不会被调用。用同样方法扫 `build/optifine-pristine/*.jar`
（`javap -c -p srg/net/optifine/shaders/Shaders.class`）：

| OptiFine 构建 | loadShaderPack | pack 能否启用 |
|---|---|---|
| `1.20.1 HD_U_I6`、`1.21.1 HD_U_J1_pre9`、`1.21.4 HD_U_J3` | `iload_2; ifne` | 能 |
| **`1.21.6 HD_U_J6_pre3`、`1.21.7 HD_U_J6_pre7`** | **`iconst_1; istore_2`（跳过加载）** | **不能** |
| `1.21.8 HD_U_J6_pre13/pre16`、`1.21.9 HD_U_J7_pre2`、`1.21.10 HD_U_J7_pre9`、`1.21.11 HD_U_J9_pre4`、`26.2 HD_U_K2_pre4` | `iload_2; ifne` | 能 |

而且 1.21.7 只有 `pre4`–`pre7` 四个 OptiFine 构建（BMCLAPI versionlist 快照），pre7 已是最新
——**1.21.7 上现阶段不可能拿到编译证据，不是我们跳过**。1.21.6 的 pre3 同样是坏的，1.21.8 的 pre13
修好了，所以 1.21.8 是"和 1.21.7 同一代码线、又真的能加载 pack"的最近版本线。

## 实测结果

运行环境：OptiForge `0.14.0`，OptiFine 见每行；AMD Radeon RX 7800 XT（OpenGL 3.2 Core），
`--quickPlaySingleplayer=CaptureWorld`，每个包单独启动 150 s。测试实例
`build/rig-instance-1.21.8`（Forge `58.1.26` + `OptiFine 1.21.8 HD_U_J6_pre13`）。

### 下载来源（Modrinth 快照 2026-10-22）

| 项目 | slug | 下载量 | 覆盖 1.21.7 的最新版本 | 文件 | 字节 |
|---|---|---|---|---|---|
| Complementary Shaders - Reimagined | `complementary-reimagined` | 72 241 091 | r5.9.3 | `ComplementaryReimagined_r5.9.3.zip` | 553 397 |
| BSL Shaders | `bsl-shaders` | 31 646 913 | 10.1.8 | `BSL_v10.1.8.zip` | 1 134 153 |
| MakeUp - Ultra Fast | `makeup-ultra-fast-shaders` | 14 080 516 | 9.6 | `MakeUp-UltraFast-9.6.zip` | 406 830 |

URL：
`https://cdn.modrinth.com/data/HVnmMxH1/versions/Bqen1mJX/ComplementaryReimagined_r5.9.3.zip`、
`https://cdn.modrinth.com/data/Q1vvjJYV/versions/Y68yiql9/BSL_v10.1.8.zip`、
`https://cdn.modrinth.com/data/izsIPI7a/versions/t4YRxQug/MakeUp-UltraFast-9.6.zip`。
包都落在 `I:\mods\optifineoforge-test\downloads\shaders\`（`packs.json` 记录版本/大小/URL）。

### 逐包结果

| 版本线（MC / Forge / OptiFine） | 光影包 | 启用 | 编译 program 数 | 错误行 | 崩溃报告 |
|---|---|---|---|---|---|
| 1.21.8 / 58.1.26 / `HD_U_J6_pre13` | Complementary Reimagined r5.9.3 | 是 | 27 | 0 | 0 |
| 1.21.8 / 58.1.26 / `HD_U_J6_pre13` | BSL 10.1.8 | 是 | 26 | 0 | 0 |
| 1.21.8 / 58.1.26 / `HD_U_J6_pre13` | MakeUp - Ultra Fast 9.6 | 是 | 25 | 0 | 0 |
| 1.21.7 / 57.0.3 / `HD_U_J6_pre7` | Complementary Reimagined r5.9.3 | **否**（OptiFine 自身跳过） | 0 | 0 | 0 |
| 1.21.7 / 57.0.3 / `HD_U_J6_pre7` | BSL 10.1.8 | **否**（同上） | 0 | 0 | 0 |
| 1.21.7 / 57.0.3 / `HD_U_J6_pre7` | MakeUp - Ultra Fast 9.6 | **否**（同上） | 0 | 0 | 0 |

三个 1.21.8 的 run 都进了世界（`Dev[local:E:...] logged in with entity id 1 at (0.0, 140.0, 0.0)`），
`[OptiFine]` 行数分别 901 / 369 / 6438，`[Shaders]` 行 380 / 272 / 381，崩溃报告 0。

### 证据行（`latest.log`，原文）

```
[Render thread/INFO] [net.optifine.shaders.SMCLog/]: [Shaders] Loaded shaderpack: ComplementaryReimagined_r5.9.3.zip
[Render thread/INFO] [net.optifine.shaders.SMCLog/]: [Shaders] Program loaded: world0/shadow
...（Complementary 27 行 Program loaded，含 gbuffers_* / composite* / deferred* / final / shadow）
[Server thread/INFO] [net.minecraft.server.players.PlayerList/]: Dev[local:E:5f9d6053] logged in with entity id 1 at (0.0, 140.0, 0.0)

[Render thread/INFO] [net.optifine.shaders.SMCLog/]: [Shaders] Loaded shaderpack: BSL_v10.1.8.zip
[Render thread/INFO] [net.optifine.shaders.SMCLog/]: [Shaders] Program loaded: world0/shadow
...（BSL 26 行）

[Render thread/INFO] [net.optifine.shaders.SMCLog/]: [Shaders] Loaded shaderpack: MakeUp-UltraFast-9.6.zip
[Render thread/INFO] [net.optifine.shaders.SMCLog/]: [Shaders] Program loaded: world0/shadow
...（MakeUp 25 行）
```

1.21.7 的同一条通道（11 行 `[Shaders]`，其中就是这两行决定判定）：

```
[Render thread/INFO] [net.optifine.shaders.SMCLog/]: [Shaders] Load shaders configuration.
[Render thread/INFO] [net.optifine.shaders.SMCLog/]: [Shaders] No shaderpack loaded.
```

完整日志：`I:\mods\optifineoforge-test\downloads\shaders\logs\<版本线>-<包名>.log`，
其中 `<版本线>-<包名>.shaders.log` 只留 `[Shaders]` 行；结构化结果在 `downloads\shaders\results.json`。

1.21.7 日志里还有 2 行 `Failed to parse post chain at minecraft:post_effect/fxaa_of_2x.json`
（和 `_4x`）：这是 OptiFine 自带 FXAA post-chain 在该 preview 上的解析失败，**在 program 数为 0 时
就出现**，与光影包无关，1.21.8 的日志里没有。

## 复现

```powershell
# 真实编译证据（1.21.8 实例，能加载 pack 且 quickPlay 进世界）
powershell -File tools/shader-test.ps1 -Pack all -Seconds 150 `
    -VersionId 1.21.8-forge-58.1.26 -GameDir build\rig-instance-1.21.8

# 默认参数 = 1.21.7；会打印 "No shaderpack loaded." 的 note（OptiFine 构建限制，不是失败）
powershell -File tools/shader-test.ps1 -Pack complementary-reimagined -Seconds 150
```

`tools/shader-test.ps1` 做的事：Modrinth API 选包（`-Pack all|<zip>|<slug>`）→ 下载/校验大小 →
检查没有其它 Minecraft 客户端在跑（有就 30 s 重试，最多 10 min）→ 清空 `shaderpacks/`、写
`optionsshaders.txt`、清 crash-reports/`latest.log` → 用 `rig-verify.ps1` 启动
（`--quickPlaySingleplayer=CaptureWorld`）→ 从 `latest.log` 计数并落盘。每次都**不**保留
`optiforge/pack` 缓存（缓存 key 只哈希 OptiFine/原版输入 jar，旧 OptiForge 建的包会被继续复用）。

## 局限与没做的部分

- **1.21.7 / 1.21.6 的 pack 加载要等 OptiFine 新构建**。脚本默认值仍是 1.21.7，哪天 J6 线出了修掉
  `iconst_1` 的 pre8+，原命令重跑就能出数据，不需要改脚本。
- **没有拿 1.20.1 兜底**：`build/rig-instance-1.20.1-4720` 的 `1.20.1 HD_U_I6` 确实会加载 pack
  （上面扫描表），但 Forge 47 不响应 quickPlay（README 已记录），自动测试只能到标题界面、拿不到
  进世界后的编译行；1.21.8 是能自动进世界的最近版本线，所以用它出真实证据。
- 编译数是**默认设置**下该包实际启用的 program 数量；开/关包内选项会改变这个数。
- `Invalid block ID mapping` / `Invalid entity ID mapping` 这类 `[Shaders]` 行是包内 block/entity
  alias 对不上 1.21.8 的提示（Complementary 38 + 14 条），不是编译错误，不影响 0 错误的结论。
