#!/usr/bin/env bash
# 交叉编译 uinputd（虚拟键盘守护进程）到 app/src/main/jniLibs/<abi>/libuinputd.so
#
# 打包成 lib*.so 是为了让 Android 把它解压到 nativeLibraryDir 并保持可执行位，
# 由 `su -c` 直接运行；配合 manifest 的 extractNativeLibs=true。
set -euo pipefail

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.0.12077973}}"
if [ ! -d "$NDK" ]; then
    echo "找不到 NDK：$NDK（用 ANDROID_NDK_HOME 指定）" >&2
    exit 1
fi
HOST=linux-x86_64
[ "$(uname -s)" = "Darwin" ] && HOST=darwin-x86_64
BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
SRC=app/src/main/cpp/uinputd.c

for abi in arm64-v8a armeabi-v7a; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android26 ;;
        armeabi-v7a) target=armv7a-linux-androideabi26 ;;
        *) echo "未支持的 abi $abi" >&2; exit 1 ;;
    esac
    out="app/src/main/jniLibs/$abi/libuinputd.so"
    mkdir -p "$(dirname "$out")"
    "$BIN/${target}-clang" -O2 -Wall -Wextra -o "$out" "$SRC"
    "$BIN/llvm-strip" "$out"
    echo "built $out"
done
