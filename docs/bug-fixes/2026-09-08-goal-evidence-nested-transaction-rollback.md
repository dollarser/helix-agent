# Bug Fix: Invalid Goal evidence rolled back terminal settlement

Status: fixed
Date: 2026-09-08
Related HXA: HXA-102
Affected modules: App Goal evidence reads and completion settlement

## Problem

Both API 29 and 36 reproduced a missing-content negative case: expected PAUSED but persisted RUNNING.

## Impact

Invalid evidence prevented terminal settlement from being persisted, leaving a completed Turn with a RUNNING Goal. The evidence was rejected, but the expected pause and related settlement writes were rolled back.

## Root cause

The verifier correctly returned no evidence, but its nested Room read transaction had already failed and marked the outer terminal transaction rollback-only. Catching the validation exception outside that inner transaction did not remove the rollback marker.

## Fix and invariants

Goal evidence checks retain expected validation/IO failures inside the read transaction and rethrow after its normal exit. The terminal caller can then record invalid evidence and commit PAUSED without producing proof. Database/programming exceptions and deliberate outer transaction failure still propagate and roll back. Snapshot cache creation validates before row insertion; existing corrupt/deleted snapshots are not overwritten or rebuilt. This is a scoped Goal evidence helper; global HelixStorage transaction semantics remain unchanged.

## Alternatives considered

Ignoring the mismatch would leave a completed Turn with a RUNNING Goal. Treating the missing file as success violates ADR-0028. Changing every nested storage transaction would affect unrelated write rollback semantics. The scoped helper keeps expected evidence rejection distinct from a failed terminal write.

## Regression verification

Initial failures are retained in build/main-verification/criterion-completion-emulator-5596.log and criterion-completion-emulator-5598.log. After the fix each API passed 22 evidence/completion tests including missing-content pause, explicit outer rollback, idempotent completion, pending-call rejection and Continue-based manual consumption; each also passed 30 existing Goal tests. The five affected JVM tasks passed 1,032 cases with no failures/errors/skips. APK hashes and logs are recorded in build/main-verification/criterion-completion-result.json.

## Residual risk

This is component/transaction acceptance, not physical-device, true process-kill, full UI or real-model completion acceptance. Those remain in HXA-102 and the active optimization checklist.

## Related records

- [HXA-102 verification records](../completion-records/HXA-102.md)
- [ADR-0028 evidence verification contract](../adr/0028-goal-criterion-verification-bindings.md)
- [Current optimization checklist](../development/main-optimization-todo.md)
