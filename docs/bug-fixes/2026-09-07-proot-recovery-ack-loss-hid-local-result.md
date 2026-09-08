# Bug Fix: Lost PRoot acknowledgement response hid a verified local result

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: PRoot result recovery coordinator

## Problem

ProotResultRecovery let RemoteException from acknowledgement escape after the archive was already durably saved and read back.

## Impact

The UI mapped the exception to unavailable output even though the private verified archive was intact.

## Root cause

Normal execution already separated persistence from acknowledgement availability, but the interrupted-result recovery path did not.

## Fix and invariants

A RemoteException during ACK now returns the verified local archive with acknowledged=false. A non-null receipt must match the original terminalCommit and contain its reconciliation timestamp. Other failures remain errors. Explicit coordinator recovery can revalidate the local archive against the original queried record and retry acknowledgement without fetching the archive again. Local-only reading still performs no Runtime operations and never changes the Turn or replays a command.

## Alternatives considered

Treating ACK response loss as confirmed cleanup would fabricate a receipt. Dropping local data or retrying execution would discard evidence or duplicate effects. Returning an unavailable result conflates valid local bytes with remote cleanup availability.

## Regression verification

API29 red test exposed RemoteException after successful persistence. The revised fixture simulates a lost ACK response with Runtime already acknowledged; a later query returns that same original terminal record with its receipt timestamp. API29/36 each passed 14/14 result-store and real executor tests. The new test verifies acknowledged=false initially, local-only read without another ACK, explicit retry success, unchanged local hash, one fetch, one artifact, and unchanged INTERRUPTED Turn.

Evidence: build/main-verification/proot-recovery-ack-summary.json, proot-recovery-ack-red-api29.log and proot-recovery-ack-emulator-5596.log / proot-recovery-ack-emulator-5598.log. App/test builds, Spotless, Detekt and root lintDebug passed in proot-recovery-ack-build.log.

## Residual risk

The lost-response callback is injected; it is not a real Binder process-kill test at that boundary. Coordinator retry is covered, but the product's pending receipt visibility and explicit retry entry remain open. The existing view action prefers local-only data, so this change does not claim automatic cleanup retry.

## Related records

- [Recovery gap](../development/proot-result-durable-recovery-gap.md)
- [Output failure review](2026-09-07-proot-output-failure-missing-review-recovery.md)
- [Runtime lifecycle](../adr/0007-companion-runtime-lifecycle.md)
