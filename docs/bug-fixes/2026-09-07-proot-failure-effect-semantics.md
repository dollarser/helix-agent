# Bug Fix: PRoot failures after submission claimed zero side effects

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: developer PRoot tool and device tests

## Problem

ProductionLinuxExecutor used the same sideEffectFree=true failure helper before and after submission. Accepted guest execution, unknown wait outcomes and output verification/import failures were therefore reported as confirmed zero-effect attempts.

## Impact

The framework uses sideEffectFree as an executor guarantee for bounded technical retry eligibility. An executed or uncertain Job must not carry that guarantee. Submit DEAD_OBJECT and malformed reply outcomes can also occur after the Runtime accepted the Job.

## Root cause

The failure helper hard-coded true; runJob did not distinguish admission failures from post-submission execution and verification failures. SubmitOutcome.Unavailable combines connection failures and ambiguous transaction replies.

## Fix and invariants

All failures after acceptance now explicitly set sideEffectFree=false. Unknown wait outcomes additionally set requiresReview=true. Submit DEAD_OBJECT and PROTOCOL_MISMATCH also carry false/true and an unknown-submission message, because those causes can arise after transact; no claim that nothing was submitted remains on those paths. Known non-success terminal records remain ordinary failures with sideEffectFree=false. Proven admission refusals retain their existing zero-effect behavior. No automatic resubmission is added, and successful execution/import is unchanged.

## Alternatives considered

Leaving the flag true because the guest is isolated confuses isolation with proof of no execution effects. Marking every failure uncertain would misrepresent known terminal records and pre-admission failures. The fix distinguishes these phases explicitly.

## Regression verification

LinuxRunToolE2eDeviceTest now runs a real guest command that writes a file then exits 3, asserting the stable failure with sideEffectFree=false and requiresReview=false. API 29/36 each passed all five tests, including normal output import, environment refusal and Standard target denial. Installed main/test APK hashes match current artifacts in build/main-verification/proot-failure-semantics-device-summary.json. This directly tests the known failed-terminal path; unknown wait and malformed-submit branches are source-verified here, not claimed as newly fault-injected device passes.

Developer JVM passed 307/307, zero skips, in proot-failure-semantics-jvm-forced.log with Connector sample conditions and forced Test execution. An earlier run lacked those conditions and skipped three tests; preserved logs are not counted as full passes. Developer main/test builds, root lintDebug, Spotless and Detekt passed. Evidence resides under build/main-verification/proot-failure-semantics-*.

## Residual risk

The production executor still lacks a durable pre-submit ToolCall-to-Job association; currently IDs are only included in successful output audit metadata. That is a separate remaining HXA-102 repair, essential for locating the original Job after main-process death. This fix does not claim full Goal/PRoot recovery closure or refresh all previous APK-level matrices.

## Related records

- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
