"""Splits the bilingual CurseForge description into a pure-English paste-ready file and a Chinese reference."""

from pathlib import Path

BASE = Path(__file__).resolve().parent.parent / "dist"
SRC = BASE / "curseforge-description.md"

lines = SRC.read_text(encoding="utf-8").splitlines()


def index_of(prefix):
    for i, line in enumerate(lines):
        if line.startswith(prefix):
            return i
    raise SystemExit(f"anchor not found: {prefix}")


en_start = index_of("## 二、详细描述(English)")
zh_start = index_of("## 三、简要描述(中文)")

# ---- English: heading + detailed section, no meta text, no separators -------------------------------
body = []
for line in lines[en_start + 1:zh_start]:
    if line.strip() == "---":
        continue
    if line.startswith("### OptiForge —"):
        continue
    body.append(line)

header = [
    "# OptiForge — OptiFine on Forge (1.7.10 – 26.2)",
    "",
    "Run **OptiFine** on **Forge**: put the OptiForge jar for your game version into `mods/`, put **your own",
    "OptiFine jar** into `optiforge/`, and start the game. OptiFine itself is not included.",
    "",
]

def squeeze(rows):
    out = []
    for row in rows:
        if row.strip() == "" and out and out[-1].strip() == "":
            continue
        out.append(row)
    while out and out[-1].strip() == "":
        out.pop()
    return out

english = squeeze(header + body)
(BASE / "curseforge-description-en.md").write_text("\n".join(english) + "\n", encoding="utf-8")

# ---- Chinese reference --------------------------------------------------------------------------------
zh_brief = lines[index_of("## 三、简要描述(中文)"):index_of("## 四、详细描述(中文)")]
zh_detail = lines[index_of("## 四、详细描述(中文)"):]

chinese = ["# OptiForge — 中文对照（自行阅读用，不要贴到 CurseForge）", ""]
for line in zh_brief + zh_detail:
    if line.strip() == "---":
        continue
    if line.startswith("## 三、") or line.startswith("## 四、"):
        continue
    chinese.append(line)

(BASE / "curseforge-description-zh.md").write_text("\n".join(squeeze(chinese)) + "\n", encoding="utf-8")

print("en bytes:", (BASE / "curseforge-description-en.md").stat().st_size)
print("zh bytes:", (BASE / "curseforge-description-zh.md").stat().st_size)
