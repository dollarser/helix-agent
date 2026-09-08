# Bug Fix: Initial PRoot output transfer failure hid a durable successful result

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: PRoot Runtime output delivery and terminal record

## Problem

A failed write to the caller output PFD changed a successful command to FAILED even after Runtime had durably saved its output archive.

## Impact

The result fetch handler only accepts successful terminal records. An intact archive became unavailable through that recovery API.

## Root cause

The output helper returned its manifest only after both persistence and transient transfer succeeded. The runner interpreted any exception as absence of a usable archive.

## Fix and invariants

The helper returns separate manifest and transfer status after atomic archive persistence. An IOException during transient transfer is recorded in output-delivery-failed.txt; the runner retains the original execution state and output manifest. Archive construction or persistence exceptions still propagate into the existing terminal failure path. Cancellation and timeout are not promoted to success. Fetch remains bounded, read-only, bound to the original record, and subject to manifest verification by the recipient. No command replay or automatic import is added.

## Alternatives considered

Retrying the command could repeat effects. Returning a manifest before persistence would advertise unrecoverable bytes. Marking every exception as a successful transfer would hide delivery failure; transfer status is explicitly separate here.

## Regression verification

A real runner command receives a read-only initial output PFD. API29 before the fix returned FAILED instead of expected SUCCEEDED. API29/36 after the fix each passed 13/13 runner tests. The new test checks successful exit, empty initial destination, equality of fetched and queried records, archive manifest verification and original stdout. Evidence: build/main-verification/proot-delivery-summary.json, proot-delivery-red-api29.log, and proot-delivery-emulator-5596.log / proot-delivery-emulator-5598.log. Runtime/test APK builds, Spotless, Detekt and root lintDebug passed in proot-delivery-final-build.log.

## Residual risk

The new test uses real descriptors and runner execution within the Runtime test process; it does not prove App UI recovery after a transfer failure. The App executor currently returns output-missing/verification errors without requiresReview, so integration into the interrupted-result UI remains open. Partial-transfer, archive-build failure injection, pending ACK retry and the final merged test matrix remain separate work.

## Related records

- [Retained archive fix](2026-09-07-proot-result-missing-runtime-archive.md)
- [Runtime lifecycle](../adr/0007-companion-runtime-lifecycle.md)
- [Open recovery work](../development/proot-result-durable-recovery-gap.md)
