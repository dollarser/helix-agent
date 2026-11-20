# Bug Fix: PRoot 结果过期、待核查和已完成调用的恢复资格

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102

以下按独立缺陷保留当时的原因、失败与验收范围；各节的 fixed 只指原候选，不合并测试计数或把后续通过回填到早期失败。当前契约仍查主题 ADR。

## Problem

<a id="case-2026-09-07-proot-expired-success-status"></a>

### Expired PRoot success was presented as recoverable success

Affected modules: PRoot interrupted-job status mapping

ProotJobRecovery selected its success label solely from SUCCEEDED, ignoring evidenceExpired. The retained terminal metadata can describe a successful execution whose payload has already expired.

<a id="case-2026-09-07-proot-output-failure-missing-review-recovery"></a>

### PRoot output verification failures lacked review and recovery access

Affected modules: PRoot executor, Chat timeline, PRoot recovery

Missing or invalid initial output returned requiresReview=false. The result could not be trusted, yet the failure did not enter side-effect review. Recovery eligibility only recognized process-interrupted calls.

<a id="case-2026-09-07-proot-completed-call-state-mismatch"></a>

### Completed PRoot result access compared the wrong state domain

Affected modules: PRoot result recovery eligibility

The newly added completed-call eligibility compared App ToolCall state with SUCCEEDED, a Runtime Job state. App ToolCall success is COMPLETED.

## Impact

### Expired PRoot success was presented as recoverable success

An interrupted task could display a success-oriented recovery status even though the Runtime no longer held its result evidence.

### PRoot output verification failures lacked review and recovery access

A successful Runtime job with an unavailable initial result could leave a failed App call without access to its retained archive through the recovery controls.

### Completed PRoot result access compared the wrong state domain

A normally completed call could not expose the saved-result and confirmation actions even though its artifact existed.

## Root cause

### Expired PRoot success was presented as recoverable success

The UI mapping did not give evidence-expired bookkeeping precedence over the historical execution state.

### PRoot output verification failures lacked review and recovery access

Output failure branches omitted the existing review flag. ChatService persists uncertain tool failures as NEEDS_REVIEW, while recovery controls and services only accepted INTERRUPTED Turns and calls.

### Completed PRoot result access compared the wrong state domain

Runtime and App state names were conflated. The initial state-matrix test also used a nonexistent string in its expected set, so enumeration never exercised that expected pair. A full production Goal exposed the actual stored COMPLETED call state.

## Fix and invariants

### Expired PRoot success was presented as recoverable success

Extract the existing status mapping and prioritize evidenceExpired. The result is an explicit expired-result explanation without a stop action. Query still uses the original execution and input binding; no resubmission, reconciliation, file import or Goal completion is added. Chinese and English strings are included.

### PRoot output verification failures lacked review and recovery access

Output missing, manifest missing, archive verification failure and manifest mismatch now require review with sideEffectFree=false. A shared eligibility predicate permits INTERRUPTED Turns with INTERRUPTED/NEEDS_REVIEW calls and FAILED Turns with NEEDS_REVIEW calls. The Chat timeline, original-job query/stop and archive recovery use this predicate. Active executions remain ineligible. Recovery still validates original identity and archive hashes, persists before exact ACK, never resubmits a command, and never marks a failed Turn or Goal complete.

### Completed PRoot result access compared the wrong state domain

Completed eligibility now uses TurnState.COMPLETED.name and ToolCallState.COMPLETED.name. The test's expected pairs use enum values, so a nonexistent state cannot silently pass. Interrupted/review behavior remains covered. Result viewing and exact acknowledgement never change a completed Turn or advance the Goal.

## Alternatives considered

### Expired PRoot success was presented as recoverable success

Reclassifying the historical execution as failed would erase the distinction between execution outcome and evidence retention. Automatically retrying would create a new execution. The corrected label describes missing evidence while retaining the existing record.

### PRoot output verification failures lacked review and recovery access

Automatically retrying the command risks duplicate effects. Requiring an App restart to expose recovery would not fix terminal failed Turns. Enabling all failed calls or active Turns would exceed the unresolved-effect recovery scope.

### Completed PRoot result access compared the wrong state domain

Renaming the persisted App state or treating all terminal calls as successful would corrupt established semantics. Enum-backed comparisons preserve the existing state machines.

## Regression verification

### Expired PRoot success was presented as recoverable success

ProotRecoveryReportTest constructs a valid SUCCEEDED record with evidenceExpired=true and asserts the expired label and disabled stop. It failed against the old mapping and passed after the precedence correction. The red log is build/main-verification/proot-expiry-report-red.log. The full related test and build outcomes are recorded in HXA-102, including any external Connector failure and rerun.

### PRoot output verification failures lacked review and recovery access

The real production executor and cross-UID Runtime execute a command; the test damages only the App's transient archive before the executor consumes it. API29 red test returned OUTPUT_MISSING with requiresReview=false. Empty and invalid archives now both require review, then the original Runtime result is recovered, persisted and acknowledged. The recovery fixture seeds FAILED/NEEDS_REVIEW state and verifies the Turn stays FAILED, stdout is unchanged, one artifact is registered and submit count remains one.

API29/36 each passed 15/15 executor, result-store and UI component tests. A JVM test checks all TurnState/ToolCallState pairs against recovery eligibility. Evidence: build/main-verification/proot-output-review-summary.json, proot-output-review-red-api29.log and proot-output-review-verified-emulator-5596.log / proot-output-review-verified-emulator-5598.log. App builds, Spotless, Detekt and root lintDebug passed in proot-output-review-eligibility-build.log; the corrected fixture build passed in proot-output-review-fixture-final-build.log.

### Completed PRoot result access compared the wrong state domain

The corrected expected-pair JVM test failed against the old predicate; its XML is retained in build/main-verification/proot-completed-state-red.xml. After the fix the state matrix passed. API29/36 both passed a real normal Goal: model requests the approved PRoot job then finishes its response, the App persists COMPLETED Turn/Call and PAUSED Goal with RUN_FINISHED, saved stdout is viewed and an exact ACK is repeated through production UI actions. Goal/Turn/Call and settled budget remain identical before and after viewing/confirmation. Each endpoint observed five requests including probes.

The output-loss Goal branch also passed both APIs with four requests each and unchanged INPUT_REQUIRED state. Evidence: build/main-verification/proot-normal-goal-summary.json and proot-normal-goal-verified-emulator-*/result.json / proot-after-normal-failure-emulator-*/result.json. App/test builds, Spotless, Detekt and root lintDebug passed in proot-normal-goal-fixed-build.log.

## Residual risk

### Expired PRoot success was presented as recoverable success

This corrects status reporting only. PRoot archive fetch, durable private import, acknowledgement and successful-result UI remain open. The mapping is JVM-verified; actual expired-record navigation remains a device follow-up.

### PRoot output verification failures lacked review and recovery access

The fixture creates persisted failed/review state; it is not a full Goal failure-through-navigation scenario. Existing UI component tests are not proof of that scenario either. Missing-manifest/hash-mismatch fault injection and full Goal transfer-failure navigation remain separate verification work. Pending ACK retries and the final merged test matrix also remain open.

### Completed PRoot result access compared the wrong state domain

The model is scripted and recovery controls are rendered in a Compose fixture using production ChatService, not a full app-navigation walkthrough. Actual ACK response-loss UI testing and the final merged gates remain separate work.

## Related records

- [Result recovery gap](../evidence/development/proot-result-durable-recovery-gap.md)
- [Runtime lifecycle](../adr/runtime/001-execution-domains.md)
- [HXA-102](../completion-records/HXA-102.md)
- [Runtime transfer classification](2026-09-07-proot-transfer-failure-discarded-terminal-success.md)
- [Recovery work](../evidence/development/proot-result-durable-recovery-gap.md)
- [ACK response loss](2026-09-07-proot-recovery-ack-loss-hid-local-result.md)
- [Goal semantics](../adr/goal/001-lifecycle-and-completion.md)
