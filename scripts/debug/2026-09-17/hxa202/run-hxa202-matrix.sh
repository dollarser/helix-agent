#!/bin/sh
# HXA-202 slice 4 device matrix — the task-journey device acceptance.
#
# Quadrants (each on its OWN exclusive emulator, 1080x2400@420, refuses a reused serial,
# finally closes only its own process group):
#   app consumer/API29  port 5566   TaskJourneyDeviceTest (+ two-phase process recovery)
#   app consumer/API36  port 5568
#   app developer/API29 port 5570
#   app developer/API36 port 5572
#
# Builds the consumer AND developer debug + androidTest APKs from the CURRENT source first (the
# slice gate: the artifacts under test must correspond to the source that is committed). Runs
# TWO emulators at a time (batches of 2) to bound RAM/CPU, records a per-quadrant manifest, and
# exits non-zero if ANY quadrant (or the build) exits non-zero. The runner scripts are the
# single source of the actual `am instrument` commands.
#
# Usage: run-hxa202-matrix.sh <output base dir>
set -u
ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/../../../.." && pwd)
cd "$ROOT_DIR"
out_base=$1
# The owned runner refuses to reuse an existing output dir (mkdir exist_ok=False), so a
# re-run of the matrix must start from a clean base.
rm -rf "$out_base"
mkdir -p "$out_base"
manifest="$out_base/manifest.jsonl"
: > "$manifest"

log() { echo "[$(date +%H:%M:%S)] $*"; }

: "${JAVA_HOME:?Set JAVA_HOME to the JDK home}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

log "=== building both flavors (debug + androidTest) from current source ==="
if ! ./gradlew assembleConsumerDebug assembleDeveloperDebug \
    assembleConsumerDebugAndroidTest assembleDeveloperDebugAndroidTest \
    > "$out_base/build.log" 2>&1; then
    log "RESULT: FAIL (build failed, see $out_base/build.log)"
    printf '{"quadrant":"build","port":0,"exit":1}\n' >> "$manifest"
    exit 1
fi
log "build ok"

run_quadrant() {
    variant=$1; api=$2; port=$3; outdir=$4
    scripts/debug/2026-09-17/hxa202/run-hxa202-device.sh "$variant" "$api" "$port" "$outdir" \
        > "$out_base/$variant-api$api.log" 2>&1
    rc=$?
    printf '{"quadrant":"%s-api%s","port":%s,"exit":%s}\n' "$variant" "$api" "$port" "$rc" >> "$manifest"
    log "app $variant-api$api exit=$rc"
}

log "=== batch 1: consumer (api29+api36) ==="
run_quadrant consumer 29 5566 "$out_base/consumer-api29" &
a=$!
run_quadrant consumer 36 5568 "$out_base/consumer-api36" &
b=$!
wait "$a"; wait "$b"

log "=== batch 2: developer (api29+api36) ==="
run_quadrant developer 29 5570 "$out_base/developer-api29" &
a=$!
run_quadrant developer 36 5572 "$out_base/developer-api36" &
b=$!
wait "$a"; wait "$b"

log "=== HXA-202 slice 4 matrix complete ==="
cat "$manifest"
if grep -Eq '"exit":(1[0-9]|[2-9][0-9])' "$manifest" || grep -Eq '"exit":[1-9][^0-9]' "$manifest" || grep -Eq '"exit":[1-9]$' "$manifest"; then
    log "RESULT: FAIL (at least one quadrant non-zero)"
    exit 1
fi
log "RESULT: PASS (all four quadrants zero)"
