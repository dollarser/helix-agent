#!/usr/bin/env bash
# Run androidTest classes one at a time on a connected device/emulator.
#
# Why one class per instrumentation launch: Compose UI tests from different classes
# pollute each other inside a single `am instrument -e class A,B,C` run and fail
# wholesale with ComposeTimeoutException. Isolating the launch is the only way to
# tell a real failure from cross-class pollution.
#
# Usage: run-isolated.sh <classes-file> <out-dir> [flavor]
#   classes-file: one fully-qualified test class per line
#   out-dir:      where per-class logs + summary.tsv are written
#   flavor:       consumer (default) | developer
#
# NOTE: the class list must use real class names, not file names. Some files declare
# more than one class (e.g. RunControlUiDeviceTest.kt -> RunControlModeUiDeviceTest
# + RunControlSettingsUiDeviceTest); a file name that is not a class yields a
# `ClassNotFoundException` that looks like a product failure but is a harness error.
set -uo pipefail

export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

CLASSES_FILE="${1:?classes file required}"
OUT_DIR="${2:?out dir required}"
FLAVOR="${3:-consumer}"

case "$FLAVOR" in
  consumer) TARGET="com.helix.agent" ;;
  developer) TARGET="com.helix.agent.developer" ;;
  *) echo "unknown flavor: $FLAVOR" >&2; exit 2 ;;
esac
RUNNER="$TARGET.test/com.helix.app.HelixAndroidJUnitRunner"

mkdir -p "$OUT_DIR"
: >"$OUT_DIR/summary.tsv"

while IFS= read -r cls; do
  [ -z "$cls" ] && continue
  log="$OUT_DIR/${cls}.log"
  # `adb shell` forwards its own stdin to the remote shell, which would eat this
  # `while read` loop's remaining input after the first iteration. Always close it.
  adb shell pm clear "$TARGET" >/dev/null 2>&1 </dev/null
  adb shell am instrument -w -r -e class "$cls" "$RUNNER" >"$log" 2>&1 </dev/null
  verdict=$(grep -E '^(OK \(|FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED)' "$log" | tail -1)
  if [ -z "$verdict" ]; then
    crash=$(grep -E '^INSTRUMENTATION_RESULT: shortMsg=' "$log" | tail -1)
    verdict="NO-VERDICT ${crash:-（no verdict line）}"
  fi
  printf '%s\t%s\n' "$cls" "$verdict" | tee -a "$OUT_DIR/summary.tsv"
done <"$CLASSES_FILE"

echo
echo "=== FAILED / NOT-OK ==="
# BSD grep does not expand \t inside a pattern, so match a literal tab with $'\t'.
grep -v -F $'\tOK (' "$OUT_DIR/summary.tsv" || echo "(none)"
