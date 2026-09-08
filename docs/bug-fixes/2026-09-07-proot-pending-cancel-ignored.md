# Bug Fix: PRoot queued cancellation was ignored

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: PRoot job runner cancellation

## Problem

Cancelling a PENDING job did not prevent its command from running.

## Impact

A queued command could execute after a stop request. This also prevented reliable cancellation from a future caller-death notification.

## Root cause

The execution thread created its own AtomicBoolean. The cancel method only updated liveJobs, which does not contain a queued job. The comment claiming a shared pending flag did not match the implementation.

## Fix and invariants

Submission, cancellation and execution now share a per-job flag. The execution thread checks it before launch and immediately after publishing the live process handle, closing the cancellation handoff gap. Lifecycle cleanup removes the flag. Terminal records remain unchanged by cancel.

## Alternatives considered

Only checking liveJobs misses pending work. Deleting a queued record loses the original identity and permits replay. The shared flag keeps the original job and produces CANCELLED through its existing terminal path.

## Regression verification

On API29 the new queued-job test failed before the fix: expected CANCELLED but observed SUCCEEDED. After the fix API29/36 each passed all 11 ProotJobRunnerDeviceTest methods. The new test queues behind a sleeping job, cancels while PENDING, and verifies both CANCELLED and absence of the command-created file. Evidence: build/main-verification/proot-pending-cancel-red-api29.log and proot-pending-cancel-emulator-5596.log / proot-pending-cancel-emulator-5598.log. Runtime/test builds, Spotless and Detekt passed.

## Residual risk

This does not implement caller Binder death notification; the protocol still needs that connection before owner death can reliably trigger cancellation. Successful-result kill windows remain open.

## Related records

- [Runtime lifecycle](../adr/0007-companion-runtime-lifecycle.md)
- [Result recovery gap](../development/proot-result-durable-recovery-gap.md)
- [HXA-102](../completion-records/HXA-102.md)
