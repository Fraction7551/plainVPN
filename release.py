"""Builds the release APK and publishes it as a GitHub release the in-app updater picks up.

    python plainvpn\release.py --notes "What changed"            build + publish
    python plainvpn\release.py --notes "What changed" --dry-run  build + write dist/, publish nothing

Bump versionCode/versionName in plainvpn/app/build.gradle.kts first. The repo name and
release names are kept bland on purpose (the repo is public). This file lives in the app's
git repo; the project folder above it holds the toolchain, sources, keystore and dist/.
"""
import argparse
import base64
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

APP = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(APP)
REPO = "Fraction7551/plainVPN"
ASSET = "app.apk"
SOURCE_ASSET = "source.zip"
GH = os.path.expanduser(r"~\.local\bin\gh.exe")
JAVA_HOME = os.path.join(ROOT, "toolchain", "jdk-21.0.12.1+1")
BUILD_TOOLS = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\build-tools")
# SHA-256 of the plainvpn-release.jks signing certificate (the key formerly named ovpntv-release.jks). Every installed copy only accepts
# updates signed with this key, so never publish anything else.
RELEASE_CERT = "ce3199ecec9d4b3fae1c79d260a4ff25743312f42e6fd979c56a6e1c7bd67e51"


def run(cmd, **kw):
    print("> " + " ".join(cmd))
    return subprocess.run(cmd, check=True, **kw)


def read_version():
    text = open(os.path.join(APP, "app", "build.gradle.kts"), encoding="utf-8").read()
    code = int(re.search(r"versionCode = (\d+)", text).group(1))
    # "1.5.0" (older builds had a suffix like "1.3.2 (OpenVPN 2.7.8)", which is cut off here)
    name = re.search(r'versionName = "([^" ]+)', text).group(1)
    return code, name


def published_version_code():
    url = f"https://github.com/{REPO}/releases/latest/download/version.json"
    try:
        with urllib.request.urlopen(url, timeout=15) as r:
            return json.load(r)["versionCode"]
    except Exception:
        return 0


def build():
    if not os.path.isfile(os.path.join(APP, "keystore.properties")):
        sys.exit("keystore.properties is missing: the APK would be debug-signed and every TV would reject it.")
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    run([os.path.join(APP, "gradlew.bat"), "assembleRelease", "testReleaseUnitTest", "-q"], cwd=APP, env=env, shell=True)
    return os.path.join(APP, "app", "build", "outputs", "apk", "release", "app-release.apk")


def check_signature(apk):
    apksigner = sorted(glob.glob(os.path.join(BUILD_TOOLS, "*", "apksigner.bat")))[-1]
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    out = subprocess.run([apksigner, "verify", "--print-certs", apk], capture_output=True, text=True, env=env, shell=True)
    if out.returncode != 0 or f"SHA-256 digest: {RELEASE_CERT}" not in out.stdout:
        sys.exit("The APK is not signed with the release key. Not publishing.\n" + out.stdout + out.stderr)


def check_not_debuggable(apk):
    # A debuggable build lets anyone with adb read the app's profiles and passwords (run-as).
    aapt2 = sorted(glob.glob(os.path.join(BUILD_TOOLS, "*", "aapt2.exe")))[-1]
    out = subprocess.run([aapt2, "dump", "badging", apk], capture_output=True, text=True)
    if out.returncode != 0 or "application-debuggable" in out.stdout:
        sys.exit("The APK is debuggable (or couldn't be checked). Not publishing.\n" + out.stderr)


# GPL compliance: every release ships the OpenVPN executable (OpenVPN and LZO are GPL-2.0, and
# Mbed TLS is used under its GPL-2.0-or-later option), so each release carries the complete
# corresponding source: the unmodified upstream archives plus the build files that turn them
# into libovpnexec.so. The Kotlin app is a separate program and isn't included.
UPSTREAM = [
    ("openvpn-src", "openvpn-2.7.8.zip"),
    ("deps", "mbedtls-3.6.7.tar.bz2"),
    ("deps", "lzo-2.10.tar.gz"),
    ("deps", "lz4-1.10.0.tar.gz"),
]
SOURCE_README = """plainVPN: source code for the bundled OpenVPN executable
==========================================================

plainVPN for Android TV runs OpenVPN as a separate executable (libovpnexec.so in the APK).
That executable is built from the unmodified upstream sources in upstream/:

  openvpn-2.7.8.zip       OpenVPN 2.7.8          GPL-2.0, with OpenVPN Inc's linking exception
  mbedtls-3.6.7.tar.bz2   Mbed TLS 3.6.7         dual Apache-2.0 / GPL-2.0-or-later; used here under GPL-2.0-or-later
  lzo-2.10.tar.gz         LZO 2.10               GPL-2.0-or-later
  lz4-1.10.0.tar.gz       LZ4 1.10.0 (lib/ only) BSD-2-Clause

build/ has the files that control the build:
  CMakeLists.txt          builds Mbed TLS, LZ4, LZO and OpenVPN into libovpnexec.so
  openvpn-config/config.h OpenVPN's Android configuration header

To rebuild: unpack the archives so that, next to an Android project whose
app/src/main/cpp/ holds the build/ files, you have openvpn-src/openvpn-2.7.8/ and
deps/{mbedtls-3.6.7,lzo-2.10,lz4-1.10.0}/ (CMakeLists.txt looks five levels up for them).
Then build with the Android NDK (r27) through Gradle's externalNativeBuild, or directly:
  cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a \\
        -DANDROID_PLATFORM=android-24 -DANDROID_STL=none -S <cpp dir> -B out && cmake --build out --target ovpnexec
ABIs shipped: armeabi-v7a, arm64-v8a, x86, x86_64.

licenses/ has each component's licence text. "OpenVPN" is a registered trademark of OpenVPN Inc.;
plainVPN is not affiliated with OpenVPN Inc.
"""


def make_source_zip(dist):
    path = os.path.join(dist, SOURCE_ASSET)
    cpp = os.path.join(APP, "app", "src", "main", "cpp")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("README.txt", SOURCE_README)
        for folder, name in UPSTREAM:
            src = os.path.join(ROOT, folder, name)
            if not os.path.isfile(src):
                sys.exit(f"Missing upstream source {src}: refusing to publish without it (GPL).")
            z.write(src, f"upstream/{name}")
        z.write(os.path.join(cpp, "CMakeLists.txt"), "build/CMakeLists.txt")
        z.write(os.path.join(cpp, "openvpn-config", "config.h"), "build/openvpn-config/config.h")
        licenses = os.path.join(APP, "app", "src", "main", "assets", "licenses")
        for name in ("openvpn.txt", "mbedtls.txt", "lzo.txt", "lz4.txt"):
            z.write(os.path.join(licenses, name), f"licenses/{name}")
    return path


def ensure_repo_has_commit():
    # GitHub can't attach releases to an empty repo.
    probe = subprocess.run([GH, "api", f"repos/{REPO}/commits", "--jq", ".[0].sha"], capture_output=True, text=True)
    if probe.returncode == 0:
        return
    content = base64.b64encode(b"# plainVPN\n").decode()
    run([GH, "api", "-X", "PUT", f"repos/{REPO}/contents/README.md", "-f", "message=Initial commit", "-f", f"content={content}"],
        capture_output=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--notes", required=True, help="Shown in the app's update banner")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    code, name = read_version()
    latest = published_version_code()
    if code <= latest:
        sys.exit(f"versionCode {code} is not newer than the published {latest}. Bump it in app/build.gradle.kts.")

    apk = build()
    check_signature(apk)
    check_not_debuggable(apk)

    dist = os.path.join(ROOT, "dist")
    shutil.rmtree(dist, ignore_errors=True)
    os.makedirs(dist)
    shutil.copy(apk, os.path.join(dist, ASSET))
    sha256 = hashlib.sha256(open(os.path.join(dist, ASSET), "rb").read()).hexdigest()
    source_zip = make_source_zip(dist)
    tag = f"v{name}"
    manifest = {
        "versionCode": code,
        "versionName": name,
        # Pinned to this release's file so the checksum always matches it.
        "apkUrl": f"https://github.com/{REPO}/releases/download/{tag}/{ASSET}",
        "sha256": sha256,
        "notes": args.notes,
    }
    with open(os.path.join(dist, "version.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=2)
    print(json.dumps(manifest, indent=2))

    if args.dry_run:
        print(f"Dry run: files are in {dist}")
        return
    ensure_repo_has_commit()
    run([GH, "release", "create", tag, os.path.join(dist, ASSET), os.path.join(dist, "version.json"), source_zip,
         "--repo", REPO, "--title", tag, "--notes", args.notes, "--latest"])
    print(f"Published {tag}. First-install link: https://github.com/{REPO}/releases/latest/download/{ASSET}")


if __name__ == "__main__":
    main()
