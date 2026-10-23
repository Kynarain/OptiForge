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
2. 项目类型选 **Mods → Minecraft**。定位一句话：**在 Forge 上加载 OptiFine，包括官方标注 "Forge N/A" 的构建**；文案要求客观、可核查，不写宣传语。
3. 名称：**`OptiForge Reforged`**；slug（URL）：`optiforge-reforged`。

   > ⚠️ 名称冲突（实测）：`OptiForge` / `optiforge` 已被另一位作者的项目占用
   > （[curseforge.com/minecraft/mc-mods/optiforge](https://www.curseforge.com/minecraft/mc-mods/optiforge)，
   > 作者 ZekerZhayard，[GitHub](https://github.com/ZekerZhayard/OptiForge)，面向 Forge 1.17.1、
   > 需要先运行 OptiFine 安装器）。CurseForge 要求项目名唯一，所以必须换名。
   >
   > 备选名（按推荐顺序，逐个试）：`OptiForge Reforged` → `OptiForge Loader` → `ForgeOptiFine Loader`。
   > 游戏内显示名与 modId 不变（`OptiForge` / `optiforge`），产物文件名也不变；描述里已加一段
   > "Relation to other projects" 说明本项目与那个同名项目无关。

4. 摘要（Summary，≤ 240 字符，英文）：

   > Loads OptiFine on Forge, including builds that OptiFine lists as "Forge N/A". Applies OptiFine's own
   > patcher to a user-supplied OptiFine jar at startup. OptiFine is not included. Tested on 24 Minecraft
   > versions from 1.7.10 to 26.2.

5. 分类：`API and Library` / `Utility`（次选 `Performance`、`Optimization`）。游戏版本与 Mod Loader：
   **Forge**。
6. 许可证：`Mozilla Public License 2.0`。
7. 图标：上传 `dist/icon-512.png`（透明底 OF）。

---

## 2. 上传文件（命名参照 OptiFabric Reforged）

命名规则：`OptiForge-Reforged-<版本>+mc<Minecraft 版本>.jar`，**每个 Minecraft 版本一个文件**。
生成方式：

```powershell
pwsh -File tools\make-cf-bundle.ps1 -Version 1.0.1
```

产物在 `dist/cf/`：

- `OptiForge-Reforged-1.0.1+mc<版本>.jar` — 共 **31 个**（Release 28 / Beta 2 / Alpha 1）
- `upload-list.md` — 逐行列出：文件名、Release 类型、MC 版本、Forge 版本、OptiFine 示例构建、Java、changelog
- `upload-manifest.csv` — 同样的表 + 每个文件的 SHA-256（传完可核对）

上传时**每个文件单独创建一个 CurseForge File**，只勾该文件对应的那一个 Minecraft 版本：

| 分组 | 文件 | 类型 |
|---|---|---|
| 主产物线 | `…+mc1.17.1.jar` … `…+mc26.2.jar`（21 个） | Release |
| 旧 ModLauncher | `…+mc1.14.4.jar`、`…+mc1.15.2.jar` | Release |
| 旧 ModLauncher（实验性） | `…+mc1.16.5.jar`、`…+mc1.13.2.jar` | **Beta** |
| launchwrapper | `…+mc1.8.9.jar` … `…+mc1.12.2.jar`（5 个） | Release |
| 1.7.10 | `…+mc1.7.10.jar` | **Alpha** |

注意：

- 同一份构建产物在一个区间内是共享的，逐版本文件内容相同（用硬链接生成的，不占额外空间）。
- 显示名（Display name）建议与文件名一致，例如 `OptiForge Reforged 1.0.1 for 1.16.5`。
- 每个文件的 Changelog 用 `dist/cf/../changelog-*.md` 里对应那份（见 upload-list 的最后一列）。
- 只上传 `upload-list.md` 里列出的版本，未验证过的不要建文件。
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
