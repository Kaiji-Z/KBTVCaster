#!/usr/bin/env bash
# One-time cross-build of OpenSSL for the UxPlay mirror kernel (Phase 2 M1).
#
# Why: the vendored openssl-cmake builds OpenSSL as a CMake ExternalProject
# on the build HOST; that path requires a Unix host (./Configure + GNU make)
# and fails on Windows. This script cross-builds the same configuration once
# in WSL with the Linux NDK and installs static libs into
# app/src/main/cpp/prebuilt/openssl/<abi>/ which the app consumes via
# CMake IMPORTED targets (same pattern as app/libs jars for Cling).
#
# Configuration replicated from third_party/openssl-cmake BuildOpenSSL.cmake:
#   targets android-arm64 / android-x86_64, -D__ANDROID_API__=23 (app minSdk),
#   openssl 3.4.4, module set "no-cast no-md2 no-md4 no-mdc2 no-rc4 no-rc5
#   no-engine no-idea no-camellia no-ssl3 no-heartbeats no-gost no-deprecated
#   no-capieng no-comp no-dtls no-psk no-srp no-dso no-dsa no-rc2 no-des
#   no-tests no-hw".
#
# Usage (from WSL):  bash scripts/build-openssl-wsl.sh <ndk-root> <repo-root-on-/mnt/c>
set -euo pipefail

NDK=$1
REPO=$2
VER=3.4.4
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

cd "$WORK"
echo "== fetching openssl-$VER =="
curl -sL --retry 3 -o o.tar.gz "https://mirror.viaduck.org/openssl/openssl-$VER.tar.gz" \
  || curl -sL --retry 3 -o o.tar.gz "https://www.openssl.org/source/openssl-$VER.tar.gz"
tar xzf o.tar.gz
cd openssl-$VER

MODULES="no-cast no-md2 no-md4 no-mdc2 no-rc4 no-rc5 no-engine no-idea no-camellia no-ssl3 no-heartbeats no-gost no-deprecated no-capieng no-comp no-dtls no-psk no-srp no-dso no-dsa no-rc2 no-des no-tests no-hw"

build() { # $1=openssl-target $2=abi-dir
    local target=$1 abi=$2
    echo "== building $target -> $abi =="
    make clean >/dev/null 2>&1 || true
    # android-* targets locate the toolchain via ANDROID_NDK_ROOT themselves
    ANDROID_NDK_ROOT="$NDK" PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH" \
        ./Configure "$target" -D__ANDROID_API__=23 --libdir=lib \
            $MODULES --prefix="$WORK/out-$abi"
    ANDROID_NDK_ROOT="$NDK" PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH" \
        make -j"$(nproc)" build_sw
    make install_sw
    mkdir -p "$REPO/app/src/main/cpp/prebuilt/openssl/$abi"
    cp -r "$WORK/out-$abi/include" "$REPO/app/src/main/cpp/prebuilt/openssl/$abi/"
    mkdir -p "$REPO/app/src/main/cpp/prebuilt/openssl/$abi/lib"
    cp "$WORK/out-$abi/lib/libcrypto.a" "$REPO/app/src/main/cpp/prebuilt/openssl/$abi/lib/"
    echo "== $abi done: $(ls -la "$REPO/app/src/main/cpp/prebuilt/openssl/$abi/lib/libcrypto.a")"
}

build android-arm64 arm64-v8a
build android-x86_64 x86_64
echo "ALL DONE"
