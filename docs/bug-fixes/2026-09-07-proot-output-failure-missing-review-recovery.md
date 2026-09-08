# Bug Fix: PRoot output verification failures lacked review and recovery access

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: PRoot executor, Chat timeline, PRoot recovery

## Problem

Missing or invalid initial output returned requiresReview=false. The result could not be trusted, yet the failure did not enter side-effect review. Recovery eligibility only recognized process-interrupted calls.

## Impact

A successful Runtime job with an unavailable initial result could leave a failed App call without access to its retained archive through the recovery controls.

## Root cause

Output failure branches omitted the existing review flag. ChatService persists uncertain tool failures as NEEDS_REVIEW, while recovery controls and services only accepted INTERRUPTED Turns and calls.

## Fix and invariants

Output missing, manifest missing, archive verification failure and manifest mismatch now require review with sideEffectFree=false. A shared eligibility predicate permits INTERRUPTED Turns with INTERRUPTED/NEEDS_REVIEW calls and FAILED Turns with NEEDS_REVIEW calls. The Chat timeline, original-job query/stop and archive recovery use this predicate. Active executions remain ineligible. Recovery still validates original identity and archive hashes, persists before exact ACK, never resubmits a command, and never marks a failed Turn or Goal complete.

## Alternatives considered

Automatically retrying the command risks duplicate effects. Requiring an App restart to expose recovery would not fix terminal failed Turns. Enabling all failed calls or active Turns would exceed the unresolved-effect recovery scope.

## Regression verification

The real production executor and cross-UID Runtime execute a command; the test damages only the App's transient archive before the executor consumes it. API29 red test returned OUTPUT_MISSING with requiresReview=false. Empty and invalid archives now both require review, then the original Runtime result is recovered, persisted and acknowledged. The recovery fixture seeds FAILED/NEEDS_REVIEW state and verifies the Turn stays FAILED, stdout is unchanged, one artifact is registered and submit count remains one.

API29/36 each passed 15/15 executor, result-store and UI component tests. A JVM test checks all TurnState/ToolCallState pairs against recovery eligibility. Evidence: build/main-verification/proot-output-review-summary.json, proot-output-review-red-api29.log and proot-output-review-verified-emulator-5596.log / proot-output-review-verified-emulator-5598.log. App builds, Spotless, Detekt and root lintDebug passed in proot-output-review-eligibility-build.log; the corrected fixture build passed in proot-output-review-fixture-final-build.log.

## Residual risk

The fixture creates persisted failed/review state; it is not a full Goal failure-through-navigation scenario. Existing UI component tests are not proof of that scenario either. Missing-manifest/hash-mismatch fault injection and full Goal transfer-failure navigation remain separate verification work. Pending ACK retries and the final merged test matrix also remain open.

## Related records

- [Runtime transfer classification](2026-09-07-proot-transfer-failure-discarded-terminal-success.md)
- [Runtime lifecycle](../adr/0007-companion-runtime-lifecycle.md)
- [Recovery work](../development/proot-result-durable-recovery-gap.md)
