#!/usr/bin/env bash
# HXA-209 Phase A PoC: run the seccomp-network-guard experiment on ONE owned
# emulator (SERIAL required, must be an emulator this session started).
#
# Evidence questions answered on-device:
#   Q1 Does a PRoot guest file operation work (normal task usable)?
#   Q2 Can a PRoot guest reach the network TODAY (baseline gap)?
#   Q3 Does the seccomp guard wrap the SAME proot chain and block inet
#      (socket/connect AF_INET/AF_INET6 -> EPERM) while keeping AF_UNIX,
#      file ops, sh, and proot itself working?
#
# No product code is involved: raw proot launch mirroring ProotJobRunner's
# prootArgs (see runtime/proot-app/.../ProotJobRunner.kt LAUNCH CHAIN).
set -euo pipefail
cd "$(dirname "$0")/../../../../"
: "${SERIAL:?run with SERIAL=<owned-emulator-serial>}"
ADB=(adb -s "$SERIAL")
APK=app/build/outputs/apk/developer/debug/app-developer-debug.apk
STAGE=build/hxa209-poc
P=/data/local/tmp/hxa209
mkdir -p "$STAGE/assets"

echo "== extract runtime assets from the built APK"
unzip -oq "$APK" 'assets/runtime/proot/*' 'assets/runtime/rootfs/*' -d "$STAGE/assets"
TAR=$(ls "$STAGE/assets/assets/runtime/rootfs/"*.tar | head -1)
echo "rootfs archive: $TAR ($(du -h "$TAR" | cut -f1))"

# The Alpine archive ships absolute member paths (/bin/...); on-device toybox
# tar rejects them (the PRODUCTION installer never hits this — it uses the
# Kotlin TarStream, which strips the leading "/" and validates). For the PoC's
# device-side extraction, re-tar once host-side with ./-prefixed members.
LOCAL_TAR="$STAGE/rootfs-local.tar"
if [ ! -f "$LOCAL_TAR" ]; then
  echo "== re-tar rootfs with ./-prefixed member paths (host side, one-time)"
  rm -rf "$STAGE/rootfs-x"
  mkdir -p "$STAGE/rootfs-x"
  tar -xf "$TAR" -C "$STAGE/rootfs-x" 2>/dev/null
  # bin/ash is a symlink to /bin/busybox (broken on the macOS host) — sanity
  # check the real payload instead.
  test -s "$STAGE/rootfs-x/bin/busybox" || { echo "re-tar sanity check failed"; exit 1; }
  tar -cf "$LOCAL_TAR" -C "$STAGE/rootfs-x" .
fi

echo "== push to device"
"${ADB[@]}" shell "rm -rf $P && mkdir -p $P/lib $P/gtmp $P/gws"
"${ADB[@]}" push "$STAGE/assets/assets/runtime/proot/proot" "$P/proot" >/dev/null
"${ADB[@]}" push "$STAGE/assets/assets/runtime/proot/loader" "$P/loader" >/dev/null
"${ADB[@]}" push "$STAGE/assets/assets/runtime/proot/lib/libtalloc.so.2" "$P/lib/" >/dev/null
"${ADB[@]}" push "$STAGE/assets/assets/runtime/proot/lib/libandroid-shmem.so" "$P/lib/" >/dev/null
"${ADB[@]}" push "$STAGE/guard" "$P/guard" >/dev/null
"${ADB[@]}" push "$STAGE/netprobe" "$P/gtmp/netprobe" >/dev/null
"${ADB[@]}" push "$LOCAL_TAR" "$P/rootfs.tar" >/dev/null
"${ADB[@]}" shell "chmod 755 $P/proot $P/loader $P/guard $P/lib/* $P/gtmp/netprobe && mkdir -p $P/rootfs && tar -xf $P/rootfs.tar -C $P/rootfs"

PROOT_BASE="/system/bin/linker64 $P/proot -r $P/rootfs -b /dev -b /proc -b $P/gtmp:/tmp -b $P/gws:/workspace -w /workspace"
# PRODUCTION env set (ProotJobRunner LAUNCH CHAIN, HXA-084): LD_LIBRARY_PATH
# (no RPATH in this Termux proot build), PROOT_LOADER (proot rewrites the
# tracee's in-flight execve through it), PROOT_TMP_DIR (glue rootfs scratch),
# PATH. Production also wraps the chain in /system/bin/setsid for group kill;
# the PoC does not need process-group semantics, everything else is identical.
ENV="LD_LIBRARY_PATH=$P/lib PROOT_LOADER=$P/loader PROOT_TMP_DIR=$P/gtmp PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

run_guest () { # $1=wrapper(""), $2..=guest sh -c script
  local wrap="$1"; shift
  "${ADB[@]}" shell "cd $P && env $ENV $wrap $PROOT_BASE /bin/sh -c \"$*\""
}

echo; echo "== Q1 baseline: guest file ops (no guard)"
run_guest "" 'echo hi > /workspace/q1.txt && cat /workspace/q1.txt && cat /etc/alpine-release && ls /bin | head -3'

echo; echo "== Q2 baseline: guest network WITHOUT guard (the gap to document)"
run_guest "" 'netprobe 8977; echo "host file untouched:"; ls /workspace'

echo; echo "== Q2b host-side control: host dataDir is NOT mounted (expected ENOENT)"
run_guest "" 'cat /data/data/com.helix.agent.developer/.. 2>&1 | head -2; echo RC=$?'

echo; echo "== Q3 guard: wrap the same chain, network must be EPERM"
run_guest "$P/guard" 'netprobe 8977'

echo; echo "== Q4 guard: normal guest work still usable under the guard"
run_guest "$P/guard" 'echo guarded > /workspace/q4.txt && cat /workspace/q4.txt && ls /etc | head -3 && echo PIPES-OK: $(echo x | tr x y)'

echo; echo "== Q5 guard inheritance: child grandchild sees the filter (sh -c nested)"
run_guest "$P/guard" "sh -c 'sh -c \"netprobe 8977\"'"

echo; echo "== cleanup device side"
"${ADB[@]}" shell "rm -rf $P"
echo "== PoC transcript complete"
