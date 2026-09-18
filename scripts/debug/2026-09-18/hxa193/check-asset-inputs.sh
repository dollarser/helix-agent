#!/usr/bin/env bash
# Negative paths use real pipeline validation; no permissive downloader/ELF stubs.
set -euo pipefail
mkdir -p build/193-closeout
expect_failure() {
    local name="$1" expected="$2"
    shift 2
    if "$@" > "build/193-closeout/$name.log" 2>&1; then
        printf 'Unexpected success: %s\n' "$name" >&2
        exit 1
    fi
    grep -F "$expected" "build/193-closeout/$name.log"
}
expect_failure unknown 'Unknown option:' bash scripts/build-proot-assets.sh --invalid
expect_failure conflict 'Explicit rebuild cannot' env HELIX_ROOTFS_ARCHIVE=unused bash scripts/build-proot-assets.sh --rebuild-rootfs
printf 'not a rootfs' > build/193-closeout/invalid.tar
expect_failure corrupt 'rootfs archive hash mismatch' env HELIX_ROOTFS_ARCHIVE=build/193-closeout/invalid.tar bash scripts/build-proot-assets.sh
printf 'PASS: unknown option, conflicting sources, corrupt archive rejected\n'
