"""Checks every jar in a directory: opens the zip and reads every entry to the end."""

import glob
import os
import sys
import zipfile

target = sys.argv[1] if len(sys.argv) > 1 else os.path.join("dist", "cf")
ok = 0
bad = []
for path in sorted(glob.glob(os.path.join(target, "*.jar"))):
    try:
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                with archive.open(name) as handle:
                    while handle.read(1 << 16):
                        pass
        ok += 1
    except Exception as error:  # noqa: BLE001 - report anything the zip reader raises
        bad.append((os.path.basename(path), type(error).__name__, str(error)[:90]))

print("readable:", ok, "broken:", len(bad))
for name, kind, message in bad[:12]:
    print("  ", name, kind, message)
