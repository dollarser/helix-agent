# Bug Fix: PRoot job did not observe submitting App death

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: PRoot IPC, client and Runtime lifecycle

## Problem

Killing the submitting App left its approved shell job running without a valid foreground continuation path. On API36 the cached Runtime was frozen and its journal remained RUNNING until rebind, then timed out.

## Impact

The original job could outlive its owner or remain frozen instead of following ADR-0007 cancellation semantics.

## Root cause

The submit protocol transferred input/output PFDs but no process death token. Query unbinds cannot identify owner death because query connections are intentionally short-lived.

## Fix and invariants

The client now submits through TX_JOB_SUBMIT_OWNED with a process-lifetime Binder. Runtime installs a per-job death recipient only for a newly accepted job and cancels that original job on death. Links are released at terminal completion; an already-dead owner triggers cancellation. Duplicates do not replace ownership. Ordinary query/unbind leaves the owner link intact. Unsupported peers do not receive a fallback legacy submit. The token grants no capability or approval.

## Alternatives considered

Cancelling on every unbind breaks normal cold query/polling. Keeping a started service or assigning dataSync to arbitrary shell work violates the accepted lifecycle scope. Automatically replaying after restart loses the original execution identity.

## Regression verification

API29/36 each ran a production Goal/Chat/Dispatcher/approval/PRoot job and SIGKILLed the App after guest execution started. Host-side journal inspection proved CANCELLED before restarting App. Each then passed two restart/recovery phases with unchanged model request count and original job identity. Evidence: build/main-verification/proot-owner-death-emulator-5596/result.json and proot-owner-death-emulator-5598/result.json. Root lintDebug, Spotless, Detekt, Runtime/App/test builds and Client/IPC JVM tasks passed.

## Residual risk

Legacy raw submission remains available for compatibility and does not carry an owner; the current production client uses only the owned transaction. This verifies running-owner death, not all pending/dead-at-submit/terminal race windows. Terminal-success-before-App-consumption kill acceptance remains open. No foreground continuation or freezer override was added.

## Related records

- [Runtime lifecycle](../adr/0007-companion-runtime-lifecycle.md)
- [Queued cancellation prerequisite](2026-09-07-proot-pending-cancel-ignored.md)
- [Result recovery gap](../development/proot-result-durable-recovery-gap.md)
