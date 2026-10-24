"""Finds which jar under a directory holds the vanilla build an OptiFine payload was made for.

For a few sample classes it compares the payload's expected md5 with the bytes inside every jar found below the
given root, so the report says "this jar matches 40/40" instead of guessing.
"""

import hashlib
import os
import sys
import zipfile

root = sys.argv[1]
optifine_jar = sys.argv[2]

with zipfile.ZipFile(optifine_jar) as payload:
    entries = [n for n in payload.namelist() if n.startswith("patch/srg/") and n.endswith(".md5")]
    samples = {}
    for entry in entries[:60]:
        cls = entry[len("patch/srg/"):-len(".md5")]
        samples[cls] = payload.read(entry).decode().strip().lower()

print("sampling", len(samples), "classes from", os.path.basename(optifine_jar))

candidates = []
for base, _dirs, files in os.walk(root):
    for name in files:
        if not name.endswith(".jar"):
            continue
        path = os.path.join(base, name)
        try:
            if os.path.getsize(path) < 5 * 1024 * 1024:
                continue
        except OSError:
            continue
        candidates.append(path)

print("jar candidates over 5 MB:", len(candidates))
for path in candidates:
    try:
        with zipfile.ZipFile(path) as jar:
            names = set(jar.namelist())
            if not names.intersection(samples):
                continue
            match = 0
            for cls, want in samples.items():
                if cls in names and hashlib.md5(jar.read(cls)).hexdigest() == want:
                    match += 1
            if match:
                print(f"  match {match}/{len(samples)}  {path}")
    except Exception:  # noqa: BLE001 - unreadable jars are irrelevant here
        continue
