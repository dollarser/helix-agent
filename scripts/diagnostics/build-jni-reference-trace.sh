#!/usr/bin/env bash
set -euo pipefail
trace_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
trace_ndk="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the installed Android NDK}"
trace_jdk="${JAVA_HOME:?Set JAVA_HOME to the installed JDK with include/jvmti.h}"
case "$(uname -s)" in
  Darwin) trace_host=darwin-x86_64 ;;
  Linux) trace_host=linux-x86_64 ;;
  *) echo 'Unsupported diagnostic build host' >&2; exit 1 ;;
esac
mkdir -p "$trace_root/build/reference-trace"
"$trace_ndk/toolchains/llvm/prebuilt/$trace_host/bin/aarch64-linux-android29-clang++" \
  -shared -fPIC -std=c++17 -O1 -g -fno-omit-frame-pointer -Wall -Wextra -Werror \
  -idirafter "$trace_jdk/include" "$trace_root/scripts/diagnostics/jni-reference-trace.cpp" \
  -static-libstdc++ -llog -ldl -o "$trace_root/build/reference-trace/libjni-reference-trace.so"
