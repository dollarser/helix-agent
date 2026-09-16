#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../../../"
ndk=${NDK:-$HOME/Library/Android/sdk/ndk/28.2.13676358}
bin="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin"
src=scripts/debug/2026-09-16/hxa209-seccomp-poc
for target in aarch64 x86_64; do
  for name in guard filterprobe diag; do
    "$bin/$target-linux-android29-clang" -Wall -Wextra -Werror -fsyntax-only "$src/$name.c"
  done
  echo "$target UAPI/static assertions PASS"
done
printf 'struct S { unsigned char code,jt,jf; unsigned short k; };\n_Static_assert(sizeof(struct S)==6,"normal layout");\n' | clang -x c -fsyntax-only -
echo 'Host clang original-layout sizeof=6 PASS'
