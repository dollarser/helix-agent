#!/usr/bin/env bash
# HXA-081 build-time PRoot runtime asset pipeline (build machine ONLY — the device never
# downloads anything; every artifact below is verified against runtime-lock.json and then
# embedded into the Runtime APK assets).
#
# Usage:
#   scripts/build-proot-assets.sh                # verify + build + place (lock must exist)
#   scripts/build-proot-assets.sh --generate-lock  # first run: fetch pinned upstreams,
#                                                    # measure, WRITE the lock, then verify
#
# Pipeline:
#   1. Fetch every component URL from runtime-lock.json into the workdir (curl, pinned).
#   2. Verify SHA-256 + size of each download against the lock.
#   3. Extract the PRoot binary + loader + Termux libraries from the .debs (bsdtar).
#   4. Build the Alpine rootfs in Docker (pinned apk versions from the lock), clean
#      Docker artifacts, tar it.
#   5. Deterministically repack the rootfs (scripts/deterministic_tar.py) → stable bytes.
#   6. Run the Kotlin asset gate (./gradlew :runtime:proot-core:assetGate) over EVERY ELF:
#      16 KiB PT_LOAD alignment + aarch64 ABI (the same checker the installer reuses).
#   7. Verify the final RAW tar hash against the lock (or write it in --generate-lock
#      mode); the .tar.gz is a reproducible build artifact whose hash is only logged.
#   8. Place assets into runtime/proot-app/src/main/assets/runtime/ (proot/, rootfs/).
#
# Requirements: curl, bsdtar (macOS `tar`), python3, docker (Linux daemon), JDK 17
# (JAVA_HOME), the repo's Gradle wrapper. Nothing here runs on a device.

set -euo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

readonly assets_dir="$project_root/runtime/proot-app/src/main/assets/runtime"
readonly lock_path="$assets_dir/runtime-lock.json"

# Pinned base-image digest for alpine:3.22.5 (Docker Hub). Bumping the Alpine version in
# runtime-lock.json requires updating this digest and re-running the asset gate.
readonly ALPINE_IMAGE_DIGEST="sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce"
# HXA-073 published the current rootfs lock from this repository snapshot. Exact package
# versions are not sufficient to reproduce bytes after another mutable mirror advances, as the
# official CDN demonstrated in the verification-gap run. Keep the transport origin explicit and
# stable by default; callers may override it only for diagnosis, and Alpine signatures plus the
# final raw-tar hash still fail closed.
readonly CANONICAL_ALPINE_MIRROR="https://mirrors.aliyun.com/alpine"

if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/bin/java ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
fi
export PATH="$HOME/Library/Android/sdk/platform-tools:/Applications/Docker.app/Contents/Resources/bin:$PATH"

# Docker on this host has a broken credsStore ("desktop"); public pulls need no
# credentials, so use a minimal isolated config instead of touching ~/.docker.
if [[ ! -d "${HELIX_DOCKER_CONFIG:-}" ]]; then
    export HELIX_DOCKER_CONFIG="$(mktemp -d)"
    printf '{\n\t"auths": {}\n}\n' > "$HELIX_DOCKER_CONFIG/config.json"
fi
export DOCKER_CONFIG="$HELIX_DOCKER_CONFIG"

workdir="$(mktemp -d "${TMPDIR:-/tmp}/helix-proot-assets.XXXXXX")"
trap 'rm -rf "$workdir"' EXIT

mode="verify"
if [[ "${1:-}" == "--generate-lock" ]]; then
    mode="generate"
fi

component_field() { # component_field <id> <field>
    python3 - "$2" <<PY
import json, sys
lock = json.load(open("$lock_path"))
for c in lock["components"]:
    if c["id"] == "$1":
        print(c[sys.argv[1]])
        break
else:
    sys.exit(f"component not found in lock: $1")
PY
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
    else shasum -a 256 "$1" | awk '{print $1}'; fi
}

size_of() { stat -f%z "$1" 2>/dev/null || stat -c%s "$1"; }

# --- 1+2. fetch and verify every downloadable component ----------------------

fetch_component() { # <id>
    local id="$1" url sha size dest
    url="$(component_field "$id" url)"
    sha="$(component_field "$id" sha256)"
    size="$(component_field "$id" size)"
    dest="$workdir/$(basename "$url")"
    curl -sfL --retry 3 --max-time 600 -o "$dest" "$url"
    local actual_sha actual_size
    actual_sha="$(sha256_of "$dest")"
    actual_size="$(size_of "$dest")"
    if [[ "$actual_sha" != "$sha" || "$actual_size" != "$size" ]]; then
        printf 'lock mismatch for component %s: sha256=%s size=%s (expected %s / %s)\n' \
            "$id" "$actual_sha" "$actual_size" "$sha" "$size" >&2
        exit 1
    fi
    printf 'fetched+verified %-18s sha256=%s size=%s\n' "$id" "$sha" "$size"
}

# --- 3. extract PRoot artifacts from the termux debs --------------------------

extract_deb() { # <deb> <out-dir>
    local deb="$1" out="$2"
    mkdir -p "$out"
    tar -xf "$deb" -C "$out"
    tar -xJf "$out/data.tar.xz" -C "$out"
    rm -f "$out/control.tar.xz" "$out/data.tar.xz" "$out/debian-binary" 2>/dev/null || true
}

prefix_dir() { # the com.termux prefix inside an extracted deb
    local out="$1"
    find "$out" -type d -path "*/com.termux/files/usr" | head -1
}

# --- 4. Docker rootfs build ----------------------------------------------------

build_rootfs() {
    # Pinned apk versions come from the lock's alpine-rootfs packages[] entries.
    local pin_spec="" pkg
    while IFS= read -r pkg; do
        if [[ -n "$pkg" ]]; then
            pin_spec+="$pkg "
        fi
    done < <(python3 -c "
import json
lock = json.load(open('$lock_path'))
for c in lock['components']:
    if c['id'] == 'alpine-rootfs':
        for p in c['packages']:
            print(f\"{p['name']}={p['version']}\")
")
    if [[ -z "$pin_spec" ]]; then
        printf 'lock alpine-rootfs has no pinned packages\n' >&2
        exit 1
    fi
    # The base image is pinned by DIGEST (recorded in licenses/ALPINE-README.md): the tag
    # is a moving name, the digest is the provenance. Fail closed on mismatch.
    local image_tag="alpine:$(apk_branch_version)"
    if ! docker image inspect "$image_tag" >/dev/null 2>&1; then
        docker pull "$image_tag"
    fi
    local image_digest
    image_digest="$(docker image inspect "$image_tag" --format '{{index .RepoDigests 0}}')"
    if [[ "$image_digest" != "alpine@${ALPINE_IMAGE_DIGEST}" ]]; then
        printf 'base image digest mismatch: %s (expected alpine@%s)\n' "$image_digest" "$ALPINE_IMAGE_DIGEST" >&2
        exit 1
    fi

    rm -rf "$workdir/rootfs-out"
    mkdir -p "$workdir/rootfs-out"
    # The current content lock was built from the canonical mirror snapshot below. A caller may
    # override ALPINE_MIRROR for diagnosis, but mutable repositories can serve different signed
    # package bytes for the same versions; the final archive hash remains the authority.
    local mirror="${ALPINE_MIRROR:-$CANONICAL_ALPINE_MIRROR}"
    local branch
    branch="$(apk_branch_version | cut -d. -f1,2)"
    # The built tar is STREAMED to the container's stdout and written by the host:
    # Docker Desktop (macOS) corrupts large files written through the bind mount
    # (observed: busybox `tar` short read / apk "I/O error" on the same volume).
    # Only the small log files use the bind mount.
    docker run --rm \
        -e HELIX_ALPINE_MIRROR="$mirror" \
        -e HELIX_ALPINE_BRANCH="$branch" \
        -v "$workdir/rootfs-out:/out" "$image_tag" sh -c '
set -e
set -o pipefail
if [ -n "$HELIX_ALPINE_MIRROR" ]; then
  printf "%s/v%s/main\n%s/v%s/community\n" "$HELIX_ALPINE_MIRROR" "$HELIX_ALPINE_BRANCH" "$HELIX_ALPINE_MIRROR" "$HELIX_ALPINE_BRANCH" > /etc/apk/repositories
fi
apk update > /out/apk-update.log 2>&1
apk add --no-cache '"$pin_spec"' > /out/apk-install.log 2>&1
apk info -v > /out/installed-packages.txt
# A mirror is only a transport optimization. Restore the pinned image default before
# archiving so ALPINE_MIRROR cannot change the embedded RootFS bytes or provenance.
if [ -n "$HELIX_ALPINE_MIRROR" ]; then
  printf "https://dl-cdn.alpinelinux.org/alpine/v%s/main\nhttps://dl-cdn.alpinelinux.org/alpine/v%s/community\n" "$HELIX_ALPINE_BRANCH" "$HELIX_ALPINE_BRANCH" > /etc/apk/repositories
fi
# /etc/{resolv.conf,hostname,hosts} are Docker bind mounts (EBUSY on unlink):
# truncate them instead of deleting (an offline RootFS needs no DNS/hostname).
: > /etc/resolv.conf
: > /etc/hostname
printf "127.0.0.1 localhost\n" > /etc/hosts
rm -f /etc/motd.sh
rm -rf /tmp/* /var/tmp/* /root/.cache
# busybox tar recursing into the live /proc and /sys (procfs) dies with a deterministic
# "short read" on sysfs files (observed: always ./sys/kernel/warn_count); archiving "."
# from "/" with relative --exclude skips them. Also excluded: ./out (the bind mount
# holding the raw tar output of this build — otherwise the archive self-includes a
# garbage copy of its own raw tar) and ./.dockerenv (a Docker marker, not part of the
# Alpine rootfs). Entries land as ./... which scripts/deterministic_tar.py normalizes.
# NOTE: no apostrophes anywhere inside this sh -c string (it is single-quoted on the
# host side; an apostrophe would terminate the string).
tar cf - \
    --exclude=./proc --exclude=./sys --exclude=./dev \
    --exclude=./out --exclude=./.dockerenv \
    -C / .
' > "$workdir/rootfs-out/rootfs-raw.tar"
    printf 'rootfs built: %s (pinned: %s)\n' "$(size_of "$workdir/rootfs-out/rootfs-raw.tar")" "$pin_spec"
    # Full read-back of the streamed tar: the Docker VM has transient I/O glitches that
    # truncate archives mid-stream (observed); fail closed and rerun the build rather
    # than repack a truncated tree.
    if ! tar tf "$workdir/rootfs-out/rootfs-raw.tar" > /dev/null 2>&1; then
        printf 'rootfs raw tar failed read-back verification (truncated or corrupt); rerun the build\n' >&2
        exit 1
    fi
    # Keep a copy of the apk db for the per-package license listing (ALPINE-README).
    tar -xf "$workdir/rootfs-out/rootfs-raw.tar" -C "$workdir" ./lib/apk/db/installed 2>/dev/null || true
}

apk_branch_version() { # e.g. 3.22.5 from the minirootfs URL
    python3 -c "
import json
lock = json.load(open('$lock_path'))
for c in lock['components']:
    if c['id'] == 'alpine-rootfs':
        import re
        m = re.search(r'alpine-minirootfs-([\d.]+)-aarch64', c['url'])
        print(m.group(1))
"
}

# --- 5+6. deterministic repack + Kotlin asset gate -----------------------------

run_asset_gate() {
    local min_align=16384 machine=183
    local rootfs_tree="$workdir/rootfs-tree"
    rm -rf "$rootfs_tree"
    mkdir -p "$rootfs_tree"
    tar -xf "$workdir/alpine-rootfs.tar" -C "$rootfs_tree"
    local paths="$workdir/proot-assets/proot $workdir/proot-assets/loader $workdir/proot-assets/lib $rootfs_tree"
    "$project_root/gradlew" :runtime:proot-core:assetGate \
        -PassetGateArgs="--min-align $min_align --expect-machine $machine $paths" \
        --console=plain -q
}

# --- 7+8. verify final archive, place assets ----------------------------------

place_assets() {
    mkdir -p "$assets_dir/proot/lib" "$assets_dir/rootfs"
    cp "$workdir/proot-assets/proot" "$assets_dir/proot/proot"
    cp "$workdir/proot-assets/loader" "$assets_dir/proot/loader"
    cp "$workdir/proot-assets/lib/libtalloc.so.2" "$assets_dir/proot/lib/libtalloc.so.2"
    cp "$workdir/proot-assets/lib/libandroid-shmem.so" "$assets_dir/proot/lib/libandroid-shmem.so"
    # Place the RAW tar under the embedded name (lock URL basename minus the ".gz"
    # suffix — the same rule the on-device installer uses to locate the archive).
    local embedded_name
    embedded_name="$(basename "$(component_field alpine-rootfs url)")"
    embedded_name="${embedded_name%.gz}"
    cp "$workdir/alpine-rootfs.tar" "$assets_dir/rootfs/$embedded_name"
    printf 'assets placed under %s\n' "$assets_dir"
}

main() {
    if [[ ! -f "$lock_path" && "$mode" != "generate" ]]; then
        printf 'runtime-lock.json missing at %s (run with --generate-lock first)\n' "$lock_path" >&2
        exit 1
    fi

    local ids
    ids="$(python3 -c "
import json
lock = json.load(open('$lock_path'))
print(' '.join(c['id'] for c in lock['components']))
")"

    for id in $ids; do
        if [[ "$id" == "alpine-rootfs" || "$id" == "proot-loader" ]]; then
            continue  # rootfs is built, not fetched; loader ships inside the proot deb
        fi
        fetch_component "$id"
    done

    mkdir -p "$workdir/proot-assets/lib"
    local pfx
    extract_deb "$workdir/$(basename "$(component_field proot url)")" "$workdir/x-proot"
    pfx="$(prefix_dir "$workdir/x-proot")"
    cp "$pfx/bin/proot" "$workdir/proot-assets/proot"
    cp "$pfx/libexec/proot/loader" "$workdir/proot-assets/loader"
    extract_deb "$workdir/$(basename "$(component_field libtalloc url)")" "$workdir/x-talloc"
    pfx="$(prefix_dir "$workdir/x-talloc")"
    cp "$pfx/lib/libtalloc.so.2.4.3" "$workdir/proot-assets/lib/libtalloc.so.2"
    extract_deb "$workdir/$(basename "$(component_field libandroid-shmem url)")" "$workdir/x-shmem"
    pfx="$(prefix_dir "$workdir/x-shmem")"
    cp "$pfx/lib/libandroid-shmem.so" "$workdir/proot-assets/lib/libandroid-shmem.so"

    build_rootfs
    # The RAW deterministic tar is the authoritative embedded archive (what the lock
    # pins and what AGP stores in the APK); the gz is a reproducible build artifact.
    python3 "$project_root/scripts/deterministic_tar.py" \
        "$workdir/rootfs-out/rootfs-raw.tar" "$workdir/alpine-rootfs.tar.gz" "$workdir/alpine-rootfs.tar"

    if [[ "$mode" == "generate" ]]; then
        write_lock
    fi

    # Final archive integrity against the (possibly just written) lock. The lock pins
    # the RAW tar — the exact bytes the device reads from the APK. The gz hash is a
    # build-artifact log line, not a lock value.
    local expected actual
    expected="$(component_field alpine-rootfs sha256)"
    actual="$(sha256_of "$workdir/alpine-rootfs.tar")"
    if [[ "$actual" != "$expected" ]]; then
        printf 'rootfs archive hash mismatch: actual=%s expected=%s\n' "$actual" "$expected" >&2
        printf 're-run with --generate-lock to publish the measured values, or restore the lock\n' >&2
        exit 1
    fi
    printf 'rootfs archive verified: sha256=%s size=%s\n' "$actual" "$(size_of "$workdir/alpine-rootfs.tar")"
    printf 'gz build artifact: sha256=%s size=%s\n' \
        "$(sha256_of "$workdir/alpine-rootfs.tar.gz")" "$(size_of "$workdir/alpine-rootfs.tar.gz")"

    run_asset_gate
    place_assets

    # Size accounting for the completion record (APK embedding happens in the next build).
    du -sh "$assets_dir/proot" "$assets_dir/rootfs" 2>/dev/null || true
    printf 'build-proot-assets: PASS (%s mode)\n' "$mode"
}

# --- lock generation (first run only) ------------------------------------------

write_lock() {
    # The lock pins the RAW deterministic tar (the bytes AGP stores in the APK);
    # the gz hash stays a logged build artifact only.
    local rootfs_sha rootfs_size
    rootfs_sha="$(sha256_of "$workdir/alpine-rootfs.tar")"
    rootfs_size="$(size_of "$workdir/alpine-rootfs.tar")"
    python3 - "$workdir" "$rootfs_sha" "$rootfs_size" "$lock_path" <<'PY'
import json, os, sys

workdir, rootfs_sha, rootfs_size, lock_path = sys.argv[1:5]
rootfs_size = int(rootfs_size)
lock = json.loads(open(lock_path).read())
by_id = {c["id"]: c for c in lock["components"]}

# Full installed package set (name + version + SPDX license) from the built
# rootfs apk database (one record per package: P:/V:/L: fields).
pkgs = []
installed = os.path.join(workdir, "lib", "apk", "db", "installed")
name = version = lic = None
def flush():
    if name is not None:
        pkgs.append({
            "name": name,
            "version": version or "",
            "licenseSpdx": lic or "NOASSERTION",
        })
if os.path.exists(installed):
    for line in open(installed):
        line = line.rstrip("\n")
        if line.startswith("P:"):
            flush()
            name, version, lic = line[2:], None, None
        elif line.startswith("V:") and name is not None and version is None:
            version = line[2:]
        elif line.startswith("L:") and name is not None and lic is None:
            lic = line[2:]
    flush()
pkgs.sort(key=lambda p: p["name"])

if "alpine-rootfs" not in by_id:
    sys.exit("lock has no alpine-rootfs component")
by_id["alpine-rootfs"]["sha256"] = rootfs_sha
by_id["alpine-rootfs"]["size"] = rootfs_size
by_id["alpine-rootfs"]["packages"] = pkgs

open(lock_path, "w").write(json.dumps(lock, indent=2) + "\n")
print(f"wrote {lock_path}: alpine-rootfs sha256={rootfs_sha} size={rootfs_size} packages={len(pkgs)}")
PY
}

main "$@"
