# Bug Fix: PRoot reconciliation recorded receipt before payload cleanup

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: PRoot journal reconciliation

## Problem

ProotJobStore.reconcile wrote reconciledAtEpochMs before deleting payload files and ignored deleteRecursively failures.

## Impact

A receipt could claim cleanup despite retained payload. Repeated reconciliation could also replace the original receipt timestamp.

## Root cause

The commit preceded its required filesystem operation, and deletion return values were not checked.

## Fix and invariants

Reconciliation now checks all payload deletions before writing the receipt, keeps the first receipt on repetition, and leaves expired evidence unchanged. The new exact-acknowledgement method accepts only the original terminalCommit and serializes with reconciliation. A failed cleanup propagates and does not stamp the record. This does not prove App-side persistence; the product must persist verified output before calling the new ACK interface.

## Alternatives considered

Ignoring cleanup failures violates the receipt's meaning. Deleting the whole Job record loses original execution identity. Updating the receipt on every retry obscures the first completed acknowledgement.

## Regression verification

On API29, a read-only payload subdirectory reproduced the old failure: reconciliation returned without throwing. After the fix, the same failure leaves the original record unchanged. API29/36 each passed five tests covering cleanup failure, wrong/exact/repeated acknowledgement, result retrieval and a real PRoot job's legacy reconciliation. Evidence: build/main-verification/proot-ack-red-api29.log, proot-ack-summary.json and proot-ack-api29/36.log. Runtime/test builds, root lintDebug, Spotless and Detekt passed in proot-ack-build.log; existing Client/IPC JVM tasks also passed.

## Residual risk

Partial cleanup may already have removed some files when another deletion fails; no receipt is fabricated and no job is replayed. New ACK cross-UID transport and App durable-result integration remain unverified. Legacy callers can still request reconciliation without proving App persistence, so the new product flow must use durable import followed by exact ACK.

## Related records

- [Result recovery gap](../development/proot-result-durable-recovery-gap.md)
- [Runtime lifecycle](../adr/0007-companion-runtime-lifecycle.md)
- [HXA-102](../completion-records/HXA-102.md)
