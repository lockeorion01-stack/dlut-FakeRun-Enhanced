"""Reject mismatched tags and Android versionCode regressions before signing a release."""
import os
from pathlib import Path
import re
import subprocess


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def version(contents):
    values = dict(line.split("=", 1) for line in contents.splitlines()
                  if "=" in line and not line.lstrip().startswith("#"))
    code = int(values["versionCode"])
    name = values["versionName"]
    if not 1 <= code <= 2100000000 or not re.fullmatch(r"\d+\.\d+\.\d+", name):
        raise ValueError("Use a positive Android versionCode and a major.minor.patch versionName")
    return code, name


def main():
    code, name = version(Path("version.properties").read_text(encoding="utf-8"))
    candidate = "v" + name
    if os.environ.get("GITHUB_REF_TYPE") == "tag" and os.environ.get("GITHUB_REF_NAME") != candidate:
        raise ValueError("The release tag must match versionName: " + candidate)
    for tag in git("tag", "--list", "v*").splitlines():
        if tag == candidate:
            if git("rev-list", "-n", "1", tag) != git("rev-parse", "HEAD"):
                raise ValueError("This version was already tagged at a different commit; increment both version fields")
            continue
        exists = subprocess.run(["git", "cat-file", "-e", tag + ":version.properties"],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
        if exists:
            previous_code, _ = version(git("show", tag + ":version.properties"))
            if code <= previous_code:
                raise ValueError("versionCode must be greater than " + tag + " (" + str(previous_code) + ")")
    print("Release version verified:", candidate, "(versionCode", str(code) + ")")


if __name__ == "__main__":
    main()
