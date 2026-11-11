"""Generate the source patch and allocate a monotonically increasing release code."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def select_release(upstream, minimum, fingerprint, releases):
    published = []
    for release in releases:
        match = re.fullmatch(r"fork-(\d+)", release["tag_name"])
        if match:
            published.append((int(match[1]), release))
    highest = max((code for code, _ in published), default=0)
    for code, release in published:
        if (code == highest and not release["draft"] and not release["prerelease"]
                and f"Build fingerprint: {fingerprint}" in (release.get("body") or "")
                and any(a["name"] == f"fork-{code}-arm64.apk" for a in release.get("assets", []))):
            return code, True
    revision = max(minimum, (highest // 10 + 1) - upstream * 100)
    if not 0 <= revision <= 99:
        raise ValueError("No higher version available in this upstream version; update upstream")
    return (upstream * 100 + revision) * 10 + 9, False


def main():
    root = Path("automation-repo")
    source = Path("source")
    base = (root / "automation/upstream-base.txt").read_text().strip()
    # Include every tracked application/build change; maintenance files are excluded.
    patch = git(root, "diff", "--binary", "--full-index", base, "HEAD", "--", ".",
                ":(exclude)automation", ":(exclude).github", ":(exclude).gitignore",
                ":(exclude).gitattributes", ":(exclude)README.md")
    Path("generated-customizations.patch").write_bytes(patch)
    props = (source / "gradle.properties").read_text()
    upstream = int(re.search(r"^APP_VERSION_CODE=(\d+)$", props, re.M)[1])
    name = re.search(r"^APP_VERSION_NAME=(.+)$", props, re.M)[1].strip()
    minimum = int((root / "automation/revision.txt").read_text().strip())
    if not 0 <= minimum <= 99:
        raise ValueError("Minimum revision must be between 0 and 99")
    digest = hashlib.sha256()
    for value in (git(source, "rev-parse", "HEAD"), patch,
                  (root / "automation/prepare_build.py").read_bytes(),
                  (root / ".github/workflows/fork-release.yml").read_bytes(),
                  Path(__file__).read_bytes(), str(minimum).encode(),
                  os.environ.get("APP_PACKAGE", "").encode(),
                  os.environ.get("ENABLE_PASSKEYS", "").encode()):
        digest.update(len(value).to_bytes(8, "big"))
        digest.update(value)
    fingerprint = digest.hexdigest()
    releases = []
    for page in range(1, 1001):
        request = urllib.request.Request(
            f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}/releases?per_page=100&page={page}",
            headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
                     "Accept": "application/vnd.github+json", "User-Agent": "Fork-release-planner"})
        with urllib.request.urlopen(request, timeout=30) as response:
            batch = json.load(response)
        releases.extend(batch)
        if len(batch) < 100:
            break
    else:
        raise ValueError("Too many release pages")
    code, exists = select_release(upstream, minimum, fingerprint, releases)
    if code > 2100000000:
        raise ValueError("Version code exceeds Android limit")
    summary = f"upstream={upstream}; versionCode={code}; already published={exists}; fingerprint={fingerprint}"
    print(summary)
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as out:
        out.write(summary + "\n")
    with open(os.environ["GITHUB_OUTPUT"], "a") as out:
        out.write(f"base={code // 10}\ncode={code}\nname={name}\ntag=fork-{code}\n")
        out.write(f"exists={str(exists).lower()}\nfingerprint={fingerprint}\n")


if __name__ == "__main__":
    main()
