#!/usr/bin/env bash
# One-time cross-build of ffmpeg for the UxPlay mirror kernel audio engine
# (Phase 2 M4). The upstream android-airplay-server builds this same
# configuration as a CMake ExternalProject (cmake/BuildFFmpeg.cmake); like
# OpenSSL, that path needs a Unix host, so we cross-build once in WSL with
# the Linux NDK and install static libs into
# app/src/main/cpp/prebuilt/ffmpeg/<abi>/ consumed via CMake IMPORTED targets.
#
# Configuration replicated from upstream BuildFFmpeg.cmake, plus the AAC
# decoder (2026-10-22: the field firmware's platform AAC path is the prime
# silent-audio suspect; commercial receivers decode AAC-ELD in software on
# this chipset class, so we mirror that choice with ffmpeg's LGPL decoder):
#   --disable-all --enable-avcodec --enable-decoder=alac --enable-decoder=aac
#   --enable-static --enable-pic --disable-asm --disable-x86asm --disable-network
#   --disable-autodetect
# ffmpeg source pinned to the upstream submodule commit
#   38b88335f99e76ed89ff3c93f877fdefce736c13 (LGPL subset: ALAC + AAC
#   decoders only; no GPL components).
#
# Usage (from WSL):  bash scripts/build-ffmpeg-wsl.sh <ndk-root> <repo-root-on-/mnt/c>
set -euo pipefail

NDK=$1
REPO=$2
SHA=38b88335f99e76ed89ff3c93f877fdefce736c13
API=23
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
SYSROOT=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

cd "$WORK"
echo "== fetching FFmpeg @ $SHA =="
curl -sL --retry 3 -o ff.tar.gz "https://github.com/FFmpeg/FFmpeg/archive/$SHA.tar.gz"
tar xzf ff.tar.gz
cd FFmpeg-*

build() { # $1=configure-arch $2=triple $3=abi-dir [$4=extra-flags]
    local arch=$1 triple=$2 abi=$3 extra=${4:-}
    echo "== building $arch -> $abi =="
    make clean >/dev/null 2>&1 || true
    ./configure \
        --prefix="$WORK/out-$abi" \
        --target-os=android --arch="$arch" $extra --enable-cross-compile \
        --cc="$TC/$triple$API-clang" \
        --cxx="$TC/$triple$API-clang++" \
        --ar="$TC/llvm-ar" --nm="$TC/llvm-nm" \
        --ranlib="$TC/llvm-ranlib" --strip="$TC/llvm-strip" \
        --sysroot="$SYSROOT" \
        --enable-pic --disable-asm --disable-x86asm \
        --disable-all --disable-debug --disable-network --disable-autodetect \
        --enable-avcodec --enable-decoder=alac --enable-decoder=aac --enable-static --disable-shared
    make -j"$(nproc)"
    make install
    mkdir -p "$REPO/app/src/main/cpp/prebuilt/ffmpeg/$abi"
    cp -r "$WORK/out-$abi/include" "$REPO/app/src/main/cpp/prebuilt/ffmpeg/$abi/"
    cp -r "$WORK/out-$abi/lib" "$REPO/app/src/main/cpp/prebuilt/ffmpeg/$abi/"
    echo "== $abi done: $(ls -l "$REPO/app/src/main/cpp/prebuilt/ffmpeg/$abi/lib/" | tr '\n' ' ')"
}

# optional fourth arg: build only one ABI (armeabi-v7a = 32-bit TVs like the
# Sony XR-85X90K; --cpu selects the armv7-a profile for the arm base arch)
if [ -z "${3:-}" ] || [ "$3" = "armeabi-v7a" ]; then build arm armv7a-linux-androideabi armeabi-v7a "--cpu=armv7-a"; fi
if [ -z "${3:-}" ] || [ "$3" = "arm64-v8a" ]; then build aarch64 aarch64-linux-android arm64-v8a; fi
if [ -z "${3:-}" ] || [ "$3" = "x86_64" ]; then build x86_64 x86_64-linux-android x86_64; fi
if [ -z "${3:-}" ] || [ "$3" = "x86" ]; then build x86 i686-linux-android x86 "--cpu=i686"; fi
echo "ALL DONE"
