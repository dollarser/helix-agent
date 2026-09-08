# Bug Fix: CLI unacknowledged evidence had no expiry marker

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: CLI journal, result protocol, subscription recovery UI

## Problem

Unacknowledged terminal CLI results retained payloads indefinitely. ADR-0007 requires a thirty-day evidence limit followed by an evidence-expired marker, while prohibiting replay.

## Impact

Old request and output bytes accumulated without an explicit expired state. Deleting the entire record would lose original Job identity and make recovery ambiguous.

## Root cause

The journal previously implemented terminal and reconciliation state only. Neither maintenance nor the main App had a distinct evidence-expired outcome.

## Fix and invariants

Maintenance at startup, admission, query and result retrieval persists EVIDENCE_EXPIRED before deleting payloads and temporary payload files. It keeps the Job ID, request hash and terminal timestamp, clears model/output success proof, and never creates an acknowledgement receipt. Failed deletion propagates; subsequent maintenance retries from the durable marker. Active and not-yet-expired records are protected, including clock rollback. Existing marker identity prevents duplicate execution and remains counted against journal capacity.

The bounded record codec carries the state to the main App, whose explicit recovery query displays an expired-result explanation. An existing verified local artifact remains independently readable. No task is completed, resumed or resubmitted by expiry. Legacy reconciliation cannot overwrite the marker with an acknowledgement. An older decoder cannot interpret the new state and fails closed rather than treating it as success.

## Alternatives considered

Deleting the complete directory loses identity. Acknowledging unread evidence fabricates receipt. A permanent maintenance service would violate cold-binding expectations; maintenance occurs only on existing Runtime work paths.

## Regression verification

Initial valid expiry regression failed while the pre-expiry boundary passed. Three final JVM tests cover expiry/codec/reopen, duplicate non-execution, rollback/boundary preservation and failed deletion with subsequent cleanup. Runtime107/107 and Client30/30 passed without skips; App Consumer300/300 and Developer312/312 also passed. Production builds, root lintDebug, Spotless and Detekt passed.

Dedicated API29/36 emulators ran four successful owner-kill scenarios: fetched without a local copy and persisted with a local copy, each followed by two actual recovery UI passes. The production Binder returned the marker without events. UI showed expiry, preserved INTERRUPTED state and displayed HELIX_OK only from the existing local copy. Host inspection confirmed only record.json remained. Another two kills/four recoveries verified unexpired result behavior. Evidence: build/main-verification/cli-evidence-expiry-summary.json, cli-evidence-expiry-verified.log, cli-expiry-device-locale-build.log and cli-expiry-app-jvm.log. Earlier API29 language/scroll test failures and subsequent original-fixture recovery are retained separately.

## Residual risk

The expiry interval was accelerated by aging only the owned synthetic Job metadata, not by waiting thirty-one days or changing the system clock. The device fixture uses seeded call ownership and production UI, not a complete Goal execution. An entirely protected-marker journal still refuses new admission; it does not silently forget Job identity. Physical devices and long soak remain outside this checkpoint.

## Related records

- [Runtime lifecycle](../adr/0007-companion-runtime-lifecycle.md)
- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
