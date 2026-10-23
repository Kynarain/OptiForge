# 发布到 CurseForge：完整操作手册

本文档是给项目作者（你自己）的上架清单，不是给最终用户的说明。所有文件都在 `dist/` 里。

---

## 0. 先明确三件合规事实（重要）

1. **产物里不含 OptiFine 的任何代码或资源**。已核对 1.0.1 的四份 jar：`net/optifine/**` 0 个条目，
   `assets/**` 0 个条目。用户必须自己去 optifine.net 下载 OptiFine 并放进 `optiforge/`。
2. 产物里只带**映射表**：
   - `optiforge/mappings/1.21-srg-to-official.tsv`（Mojang 官方映射派生，Forge/MCP 生态普遍分发）
   - `optiforge/mappings/1.13.2-obf-to-srg.tsrg`（来自 Forge maven 的 `mcp_config` 工件，ForgeGradle 常态分发）
   - `optiforge/mappings/1.17.1-srg-renumber.tsv`（由 OptiFine H1 与 G9 对比推出的**数字编号对照表**，
     不含 OptiFine 代码；若审核方有疑问，这是"兼容性数据"）
3. 项目许可证 **MPL 2.0**；描述里必须写清"不含 OptiFine，OptiFine 版权归 sp614x，需自行从其官网获取"。

CurseForge 上已有同类做法（OptiFabric、Connector 等"需要用户自备上游 mod"的加载器），
只要不打包 OptiFine 本体，通常不会因"二次分发"被拒。

---

## 1. 创建项目（网页操作，约 5 分钟）

1. 打开 <https://www.curseforge.com/minecraft/mc-mods> → `Add a Project`（或 <https://authors.curseforge.com/>）。
2. 项目类型选 **Mods → Minecraft**。
3. 名称：`OptiForge`；slug（URL）：`optiforge`（若被占用可 `optiforge-loader`）。
4. 摘要（Summary，≤ 240 字符，英文）：

   > Load OptiFine on Forge. Runs your own OptiFine jar through its own patcher and delivers the patched
   > classes plus a resource mod, from Minecraft 1.7.10 to 26.x. Does not include OptiFine.

5. 分类：`API and Library` / `Utility`（次选 `Performance`、`Optimization`）。游戏版本与 Mod Loader：
   **Forge**。
6. 许可证：`Mozilla Public License 2.0`。
7. 图标：上传 `dist/icon-512.png`（透明底 OF）。

---

## 2. 上传文件（每个上传 = 一个 File，版本范围各不相同）

CurseForge 的"文件"是**一份 jar + 该 jar 支持的游戏版本集合**，所以四份产物要分成四个（或三个）文件上传。
建议先只传三个稳定线，`-legacy17`（1.7.10）等真机验证通过后再补。

| # | 上传的 jar | 显示名（Display name） | 文件类型 | 勾选的游戏版本 |
|---|---|---|---|---|
| 1 | `OptiForge-1.0.1.jar` | `OptiForge 1.0.1 — Forge 1.17–26.2` | Release | 1.17、1.17.1、1.18、1.18.1、1.18.2、1.19、1.19.1、1.19.2、1.19.3、1.19.4、1.20.1、1.20.6、1.21、1.21.1、1.21.6、1.21.7、1.21.8、1.21.9、1.21.10、1.21.11、26.1.2、26.2 |
| 2 | `OptiForge-1.0.1-oldml.jar` | `OptiForge 1.0.1 — Forge 1.14.4–1.16.5 (Java 8)` | Release | 1.14.4、1.15.2、1.16.5 |
| 3 | `OptiForge-1.0.1-oldml.jar` | `OptiForge 1.0.1 — Forge 1.13.2 (experimental)` | Beta | 1.13.2 |
| 4 | `OptiForge-1.0.1-legacy.jar` | `OptiForge 1.0.1 — Forge 1.8.9–1.12.2 (Java 8)` | Release | 1.8.9、1.9.4、1.10.2、1.11.2、1.12.2 |
| 5 | `OptiForge-1.0.1-legacy17.jar` | `OptiForge 1.0.1 — Forge 1.7.10 (alpha)` | Alpha | 1.7.10 |

注意：

- 同一份 jar 可以上传多次（#2 与 #3），只要显示名/文件类型不同即可——这样 1.13.2 会明确标成实验性。
- **只勾选已实测进世界的版本**（上表就是），没验证的版本不要勾，避免差评。
- 每个文件的 Changelog 用 `dist/changelog-*.md` 里对应的内容粘贴。
- 依赖关系：不需要填依赖（不依赖别的 CurseForge 项目）；OptiFine 不是 CurseForge 依赖项。

---

## 3. 项目描述（直接粘贴）

> 描述文件三份：`curseforge-description.md` 是**中英合一的母本**（改内容改这一份），
> `curseforge-description-en.md` 是**纯英文成品**（贴 CurseForge 用），
> `curseforge-description-zh.md` 是**中文对照**（只看不贴）。
> 重新拆分：`python tools/split-curseforge-description.py`。


用 `dist/curseforge-description-en.md` 的全部内容（纯英文，直接粘贴）。中文对照见 `dist/curseforge-description-zh.md`，只给你自己看，不要贴到 CurseForge。要点已包含：

- 一句话定位 + 支持矩阵 + 安装步骤（OptiFine 放 `optiforge/`，不放 `mods/`）
- "不含 OptiFine"声明与 OptiFine 官网链接
- Java 版本要求（1.13–1.16 和 1.8.9–1.12.2 必须是 Java 8；1.17–1.20.4 用 17；1.20.6/1.21 用 21；26.x 用 25）
- 放错 jar/Java 时日志会给出 `[OptiForge] WARNING`
- 已知限制（1.16.5/1.13.2 实验性、1.17.1 G9 不支持、1.20.2 撞 OptiFine 自身问题、1.7.10 仅离线验证）

---

## 4. 上架后建议

1. 在 GitHub Release 里同步加一句 "CurseForge: <链接>"，README 也加。
2. 关注前几个 issue：最常见的是"把 OptiFine jar 放进了 mods/"和"用错 Java"，描述里都已写明。
3. 版本更新时：新 jar 上传为新 File（不要覆盖旧 File），旧版本保留，方便用户回退。
4. 如果审核要求说明映射表来源，回复：`1.21-srg-to-official` 派生自 Mojang 官方映射、
   `1.13.2-obf-to-srg` 来自 Forge maven 的 `mcp_config`、`1.17.1-srg-renumber` 是纯数字编号对照。

---

## 5. 若想用 API 自动上传（可选）

CurseForge 的自动上传需要作者在 <https://authors.curseforge.com/#/api-tokens> 生成 API token，
并且只能用官方 `curseforge-upload` 工具或自己的 HTTP 调用。token 不要写进仓库。
本项目不包含任何 token；要不要做自动化由你决定。
