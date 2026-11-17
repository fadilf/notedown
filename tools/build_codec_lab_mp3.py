#!/usr/bin/env python3
"""Build pinned LAME for the x86_64 Android codec experiment, outside application sources."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SHA256 = "ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, default=Path.home() / "Android/Sdk/ndk/27.1.12297006")
    args = parser.parse_args()
    root = ROOT / ".gradle/codec-lab-native"
    root.mkdir(parents=True, exist_ok=True)
    archive = root / "lame-3.100.tar.gz"
    if not archive.exists():
        urllib.request.urlretrieve("https://deb.debian.org/debian/pool/main/l/lame/lame_3.100.orig.tar.gz", archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise ValueError("LAME source checksum mismatch")
    source = root / "lame-3.100"
    if not source.exists():
        with tarfile.open(archive) as tar:
            tar.extractall(root, filter="data")
    build = root / "build"
    build.mkdir(exist_ok=True)
    bin_dir = args.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    env = dict(os.environ, CC=str(bin_dir / "x86_64-linux-android29-clang"),
               CXX=str(bin_dir / "x86_64-linux-android29-clang++"), AR=str(bin_dir / "llvm-ar"),
               RANLIB=str(bin_dir / "llvm-ranlib"), NM=str(bin_dir / "llvm-nm"), CFLAGS="-O3 -fPIC")
    if not (build / "Makefile").exists():
        subprocess.run([str(source / "configure"), "--host=x86_64-linux-android", "--disable-frontend",
                        "--disable-decoder", "--disable-shared", "--enable-static", "--with-pic", "--disable-nasm"],
                       cwd=build, env=env, check=True)
    subprocess.run(["make", "-s", "-j4"], cwd=build, env=env, check=True)
    output = root / "jni/x86_64"
    output.mkdir(parents=True, exist_ok=True)
    subprocess.run([env["CXX"], "-shared", "-O3", "-fPIC", "-fno-exceptions", "-fno-rtti", "-static-libstdc++",
                    "-I" + str(source / "include"), str(ROOT / "app/src/androidTest/cpp/lab_mp3_encoder.cpp"),
                    str(build / "libmp3lame/.libs/libmp3lame.a"), "-lm", "-o", str(output / "libcodec_lab_lame.so")], check=True)
    (root / "build-info.json").write_text(json.dumps(dict(lame_version="3.100", source_sha256=SHA256,
                                                         ndk=args.ndk.name, abi="x86_64", quality=5), indent=2))
    print("Test-only JNI directory:", root / "jni")


if __name__ == "__main__":
    main()
