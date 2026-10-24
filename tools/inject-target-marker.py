"""Writes a per-Minecraft-version copy of an OptiForge artifact with an embedded target marker.

The marker (`optiforge/target-mc.txt`) makes every CurseForge file a genuinely distinct build instead of a byte
copy of the four range artifacts, and lets the loader name the right download when the wrong file is installed.

The rewrite is done entry by entry with the standard zip writer. .NET's `ZipArchive` in Update mode produced
archives that Java rejected with `ZipException: invalid entry size`, so it is not used here.
"""

import os
import sys
import zipfile

MARKER = "optiforge/target-mc.txt"


def build(source, target, mc_version, project, version):
    marker_text = (
        f"mc={mc_version}\n"
        f"{project} {version} built for Minecraft {mc_version}\n"
    ).encode("utf-8")

    with zipfile.ZipFile(source) as reader, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as writer:
        for item in reader.infolist():
            if item.filename == MARKER:
                continue
            data = reader.read(item.filename)
            info = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            info.compress_type = item.compress_type
            info.external_attr = item.external_attr
            info.internal_attr = item.internal_attr
            info.create_system = item.create_system
            writer.writestr(info, data)
        marker = zipfile.ZipInfo(MARKER, date_time=(2026, 1, 1, 0, 0, 0))
        marker.compress_type = zipfile.ZIP_DEFLATED
        writer.writestr(marker, marker_text)


def main():
    if len(sys.argv) != 6:
        raise SystemExit("usage: inject-target-marker.py <source.jar> <target.jar> <mc> <project> <version>")
    source, target, mc_version, project, version = sys.argv[1:6]
    if not os.path.isfile(source):
        raise SystemExit(f"missing source: {source}")
    build(source, target, mc_version, project, version)


if __name__ == "__main__":
    main()
