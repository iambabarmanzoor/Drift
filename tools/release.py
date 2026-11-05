#!/usr/bin/env python3
# Drift launcher. Copyright (c) 2026 Babar Manzoor. All rights reserved.
"""
Prepares an over-the-air update for Drift.

Usage:  python3 tools/release.py "What changed in this build"
        (a versionName like 1.1.1-beta.2, or --beta, writes beta.json for the beta channel instead)

Reads the build number from tools/build.py and the GitHub repo from Updater.java, fingerprints
../Drift.apk and writes ../release/update.json. Then:
  1. On GitHub, create a release with the tag it prints (e.g. build-17) and attach ../Drift.apk.
  2. Replace update.json in the repo with ../release/update.json.
Drift on the car picks it up the next time it's online (or tap About in Settings).
"""
import hashlib, json, os, re, sys

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.join(here, "..")
apk = os.path.join(root, "..", "Drift.apk")
build = open(os.path.join(here, "build.py")).read()
code = int(re.search(r'A\("versionCode", T_DEC, (\d+)\)', build).group(1))
name = re.search(r'A\("versionName", T_STR, "([^"]+)"\)', build).group(1)
upd = open(os.path.join(root, "src", "com", "drift", "launcher", "Updater.java")).read()
m = re.search(r'raw\.githubusercontent\.com/([^/]+)/([^/]+)/', upd)
owner, repo = m.group(1), m.group(2)
if owner == "OWNER":
    sys.exit("Set your GitHub user in Updater.UPDATE_URL first.")
args = [x for x in sys.argv[1:] if x != "--beta"]
is_beta = "--beta" in sys.argv[1:] or "-beta" in name
notes = args[0] if args else "Improvements and fixes."
if is_beta:
    apk = os.path.join(root, "..", "Drift-%s.apk" % name)  # betas: the test build, not the published Drift.apk
sha = hashlib.sha256(open(apk, "rb").read()).hexdigest()
tag = "v" + name  # standard release tags: v1.0.1, v1.1.0 ... (build 16 was published as build-16)
info = {
    "versionCode": code,
    "versionName": name,
    # betas live in the repo's beta/ folder (pushed with GitHub Desktop); stable builds on the Releases page
    "apk": ("https://raw.githubusercontent.com/%s/%s/main/beta/Drift.apk" % (owner, repo)) if is_beta
           else "https://github.com/%s/%s/releases/download/%s/Drift.apk" % (owner, repo, tag),
    "sha256": sha,
    "notes": notes,
}
out = os.path.join(root, "..", "release")
os.makedirs(out, exist_ok=True)
if is_beta:
    # straight into the GitHub Desktop clone: commit and push, nothing else
    import shutil
    clone = os.path.join(root, "..", "Drift", "beta")
    os.makedirs(clone, exist_ok=True)
    shutil.copyfile(apk, os.path.join(clone, "Drift.apk"))
    with open(os.path.join(clone, "beta.json"), "w") as f:
        json.dump(info, f, indent=2)
    print("Beta %s is in Drift/beta/. In GitHub Desktop: commit and push." % name)
else:
    with open(os.path.join(out, "update.json"), "w") as f:
        json.dump(info, f, indent=2)
    print("Release tag:      ", tag)
    print("Attach this file:  Drift.apk")
    print("Then replace update.json in %s/%s with release/update.json" % (owner, repo))
print(json.dumps(info, indent=2))
