#!/bin/sh
# HXA-192 device matrix — the Plan review->execute closed loop + 209 authorization linkage.
#
# Quadrants (each on its OWN exclusive emulator, 1080x2400@420, refuses a reused serial, finally
# closes only its own process group). Run SEQUENTIALLY (not batched) to avoid the 2-concurrent-run
# timeout flake noted in the device-test gotchas memory:
#   app  consumer/API29  port 5554
#   app  consumer/API36  port 5556
#   app  developer/API29 port 5558
#   app  developer/API36 port 5560
#
# There is NO storage quadrant: HXA-192 reuses the existing PlanReviewService/StoragePlanReviewPort
# and adds no Room schema change (unlike HXA-209's v21->v22 migration, which had one).
#
# Usage: run-hxa192-matrix.sh <output base dir>
set -eu
out_base=$1
mkdir -p "$out_base"
manifest="$out_base/manifest.jsonl"
: > "$manifest"

log() { echo "[$(date +%H:%M:%S)] $*"; }

matrix_failed=0

run_quadrant() {
    variant=$1; api=$2; port=$3; outdir=$4
    rc=0
    scripts/debug/2026-09-17/hxa192/run-hxa192-device.sh "$variant" "$api" "$port" "$outdir" \
        > "$out_base/$variant-api$api.log" 2>&1 || rc=$?
    if [ "$rc" -ne 0 ]; then matrix_failed=1; fi
    printf '{"quadrant":"%s-api%s","port":%s,"exit":%s}\n' "$variant" "$api" "$port" "$rc" >> "$manifest"
    log "app $variant-api$api exit=$rc"
}

log "=== HXA-192 device matrix (sequential, 4 app quadrants) ==="
run_quadrant consumer 29 5554 "$out_base/consumer-api29"
run_quadrant consumer 36 5556 "$out_base/consumer-api36"
run_quadrant developer 29 5558 "$out_base/developer-api29"
run_quadrant developer 36 5560 "$out_base/developer-api36"

log "=== matrix complete ==="
cat "$manifest"
if [ "$matrix_failed" -ne 0 ]; then
    log "RESULT: FAIL (at least one quadrant non-zero)"
    exit 1
fi
log "RESULT: PASS (all four quadrants zero)"
