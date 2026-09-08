# Bug Fix: PRoot successful result lacked a retained Runtime archive

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: PRoot Runtime output delivery

## Problem

The new original-result fetch operation returned unavailable after a real successful PRoot execution.

## Impact

The caller could receive its initial output, but could not fetch the same result again for durable recovery.

## Root cause

ProotJobRunner built the archive directly into the caller's PFD. ProotResultArchiveStore reads the Runtime job's output.zip, which normal execution never created. Earlier archive-store tests supplied that file and therefore did not cover the production writer.

## Fix and invariants

ProotOutputDelivery builds a temporary Runtime archive, syncs its file descriptor, atomically renames it to output.zip, and only then copies it into the caller's PFD. The original job retains its archive until acknowledgement or expiry. Temporary output is removed on exit. Delivery failure retains existing failure classification; this change does not declare a failed delivery successful.

## Alternatives considered

Rebuilding output from workspace files during recovery could return changed evidence. Keeping only the caller's PFD repeats the process-lifetime dependency. A complete retained archive preserves the terminal manifest's bytes.

## Regression verification

The same real cross-APK PRoot execution failed on API29/36 before the fix because the first fetch returned null. Both passed after the fix: repeated fetch, wrong-commit rejection, private Room artifact persistence before exact ACK, receipt identity/idempotence, unavailable Runtime payload after ACK, and local archive read after cleanup. The interrupted Turn binding is seeded; the command itself runs under PRoot. Evidence: build/main-verification/proot-cross-uid-result-emulator-5596.log and proot-cross-uid-result-emulator-5598.log (red); proot-cross-uid-result-fixed-emulator-5596.log and proot-cross-uid-result-fixed-emulator-5598.log (green). Runtime/test APK builds, Spotless, Detekt and root lintDebug passed.

## Residual risk

UI integration and process-kill windows remain open. This is process recovery groundwork, not power-loss/filesystem durability acceptance. Failed initial delivery still follows the prior FAILED classification even if a local archive exists; broadening recovery to that state needs explicit implementation and tests.

## Related records

- [Result recovery gap](../development/proot-result-durable-recovery-gap.md)
- [Runtime lifecycle](../adr/0007-companion-runtime-lifecycle.md)
- [HXA-102](../completion-records/HXA-102.md)
