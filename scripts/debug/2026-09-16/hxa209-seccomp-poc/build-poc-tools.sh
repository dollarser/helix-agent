#!/usr/bin/env bash
# HXA-209 Phase A PoC: build guard + netprobe for the aarch64 emulator ABI.
# Artifacts land in the git-ignored build/hxa209-poc/.
set -euo pipefail
cd "$(dirname "$0")/../../../../"
NDK="${NDK:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
CLANG="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang"
OUT=build/hxa209-poc
mkdir -p "$OUT"
# guard runs on the host (through /system/bin/linker64 like proot): dynamic PIE.
"$CLANG" -O2 -Wall -Wextra -o "$OUT/guard" scripts/debug/2026-09-16/hxa209-seccomp-poc/guard.c
# filterprobe: on-device bisection of which filter construct the kernel
# rejects; same runtime domain as guard, dynamic PIE.
"$CLANG" -O2 -Wall -Wextra -o "$OUT/filterprobe" scripts/debug/2026-09-16/hxa209-seccomp-poc/filterprobe.c
# diag: instrumented install probe (hexdump of program bytes, seccomp
# state before/after); run as both root and shell.
"$CLANG" -O2 -Wall -Wextra -o "$OUT/diag" scripts/debug/2026-09-16/hxa209-seccomp-poc/diag.c
# netprobe runs INSIDE the alpine guest: fully static (bionic static is
# self-contained; musl userspace cannot load a dynamic bionic binary).
"$CLANG" -O2 -Wall -Wextra -static -o "$OUT/netprobe" scripts/debug/2026-09-16/hxa209-seccomp-poc/netprobe.c
file "$OUT/guard" "$OUT/filterprobe" "$OUT/diag" "$OUT/netprobe"
shasum -a 256 "$OUT/guard" "$OUT/filterprobe" "$OUT/diag" "$OUT/netprobe"

for name in fdprobe delegation-probe; do
  "$CLANG" -O2 -Wall -Wextra -Werror -o "$OUT/$name" "scripts/debug/2026-09-16/hxa209-seccomp-poc/$name.c"
done
