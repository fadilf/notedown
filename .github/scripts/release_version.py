"""Decide whether a push advances the app's major/minor release version."""

import argparse
import os
import re
import subprocess


def read_version(source):
    name = re.search(r'\bversionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"', source)
    code = re.search(r"\bversionCode\s*=\s*(\d+)\b", source)
    if not name or not code:
        raise ValueError("Expected numeric versionName X.Y.Z and versionCode in app/build.gradle.kts")
    return tuple(map(int, name.groups())), int(code.group(1))


def should_release(before, after):
    old_version, old_code = read_version(before)
    new_version, new_code = read_version(after)
    if new_version[:2] <= old_version[:2]:
        return False
    if new_code <= old_code:
        raise ValueError("A release must also increase versionCode so Android can install updates")
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("before")
    parser.add_argument("after")
    args = parser.parse_args()
    path = "app/build.gradle.kts"

    def source_at(ref):
        return subprocess.check_output(["git", "show", f"{ref}:{path}"], text=True)

    after = source_at(args.after)
    version, _ = read_version(after)
    # A new branch has no prior version to compare; it does not create a release.
    release = args.before != "0" * 40 and should_release(source_at(args.before), after)
    name = ".".join(map(str, version))
    print(f"Version {name}: {'release' if release else 'skip build'}")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"release={str(release).lower()}\nversion={name}\n")


if __name__ == "__main__":
    main()
