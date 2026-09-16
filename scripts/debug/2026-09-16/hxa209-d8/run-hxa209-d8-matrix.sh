#!/bin/sh
# HXA-209 D8 device matrix — the D-gate device acceptance.
#
# Quadrants (each on its OWN exclusive emulator, 1080x2400@420, refuses a reused serial,
# finally closes only its own process group):
#   app  consumer/API29  port 5554   the 3 session-permission classes (+ two-phase recovery)
#   app  consumer/API36  port 5556
#   app  developer/API29 port 5558
#   app  developer/API36 port 5560
#   storage/API29        port 5562   RoomMigrationFixtureTest (flavor-agnostic, one APK)
#   storage/API36        port 5564
#
# Runs TWO emulators at a time (batches of 2) to bound RAM/CPU, records a per-quadrant
# manifest, and exits non-zero if ANY quadrant exits non-zero. The runner scripts are the
# single source of the actual `am instrument` commands.
#
# Usage: run-hxa209-d8-matrix.sh <output base dir>
set -u
out_base=$1
mkdir -p "$out_base"
manifest="$out_base/manifest.jsonl"
: > "$manifest"

log() { echo "[$(date +%H:%M:%S)] $*"; }

run_quadrant() {
    variant=$1; api=$2; port=$3; outdir=$4
    scripts/debug/2026-09-16/hxa209-d8/run-hxa209-d8-device.sh "$variant" "$api" "$port" "$outdir" \
        > "$out_base/$variant-api$api.log" 2>&1
    rc=$?
    printf '{"quadrant":"%s-api%s","port":%s,"exit":%s}\n' "$variant" "$api" "$port" "$rc" >> "$manifest"
    log "app $variant-api$api exit=$rc"
}

run_storage() {
    api=$1; port=$2; outdir=$3
    scripts/debug/2026-09-16/hxa209-d8/run-hxa209-d8-storage.sh "$api" "$port" "$outdir" \
        > "$out_base/storage-api$api.log" 2>&1
    rc=$?
    printf '{"quadrant":"storage-api%s","port":%s,"exit":%s}\n' "$api" "$port" "$rc" >> "$manifest"
    log "storage api$api exit=$rc"
}

log "=== batch 1: consumer (api29+api36) ==="
run_quadrant consumer 29 5554 "$out_base/consumer-api29" &
a=$!
run_quadrant consumer 36 5556 "$out_base/consumer-api36" &
b=$!
wait "$a"; wait "$b"

log "=== batch 2: developer (api29+api36) ==="
run_quadrant developer 29 5558 "$out_base/developer-api29" &
a=$!
run_quadrant developer 36 5560 "$out_base/developer-api36" &
b=$!
wait "$a"; wait "$b"

log "=== batch 3: storage migrations (api29+api36) ==="
run_storage 29 5562 "$out_base/storage-api29" &
a=$!
run_storage 36 5564 "$out_base/storage-api36" &
b=$!
wait "$a"; wait "$b"

log "=== D8 matrix complete ==="
cat "$manifest"
if grep -Eq '"exit":(1[0-9]|[2-9][0-9])' "$manifest" || grep -Eq '"exit":[1-9][^0-9]' "$manifest" || grep -Eq '"exit":[1-9]$' "$manifest"; then
    log "RESULT: FAIL (at least one quadrant non-zero)"
    exit 1
fi
log "RESULT: PASS (all six quadrants zero)"
