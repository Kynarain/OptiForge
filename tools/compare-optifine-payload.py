"""Compares an OptiFine payload's expected input md5s with a base jar, to tell whether the payload targets it."""

import hashlib
import sys
import zipfile

base_jar = sys.argv[1]
optifine_jars = sys.argv[2:]

with zipfile.ZipFile(base_jar) as base:
    base_names = set(base.namelist())
    base_hashes = {}

    def base_md5(name):
        if name not in base_hashes:
            base_hashes[name] = hashlib.md5(base.read(name)).hexdigest()
        return base_hashes[name]

    for path in optifine_jars:
        with zipfile.ZipFile(path) as jar:
            md5_entries = [n for n in jar.namelist() if n.startswith("patch/srg/") and n.endswith(".md5")]
            match = mismatch = absent = 0
            examples = []
            for entry in md5_entries:
                cls = entry[len("patch/srg/"):-len(".md5")]
                want = jar.read(entry).decode().strip().lower()
                if cls not in base_names:
                    absent += 1
                    continue
                if base_md5(cls) == want:
                    match += 1
                else:
                    mismatch += 1
                    if len(examples) < 4:
                        examples.append(cls)
            print(f"{path.rsplit(chr(92), 1)[-1]}: patches={len(md5_entries)} match={match} "
                  f"mismatch={mismatch} absent={absent}")
            for cls in examples:
                print("   mismatch example:", cls)
