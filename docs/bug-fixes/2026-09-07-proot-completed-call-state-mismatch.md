# Bug Fix: Completed PRoot result access compared the wrong state domain

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: PRoot result recovery eligibility

## Problem

The newly added completed-call eligibility compared App ToolCall state with SUCCEEDED, a Runtime Job state. App ToolCall success is COMPLETED.

## Impact

A normally completed call could not expose the saved-result and confirmation actions even though its artifact existed.

## Root cause

Runtime and App state names were conflated. The initial state-matrix test also used a nonexistent string in its expected set, so enumeration never exercised that expected pair. A full production Goal exposed the actual stored COMPLETED call state.

## Fix and invariants

Completed eligibility now uses TurnState.COMPLETED.name and ToolCallState.COMPLETED.name. The test's expected pairs use enum values, so a nonexistent state cannot silently pass. Interrupted/review behavior remains covered. Result viewing and exact acknowledgement never change a completed Turn or advance the Goal.

## Alternatives considered

Renaming the persisted App state or treating all terminal calls as successful would corrupt established semantics. Enum-backed comparisons preserve the existing state machines.

## Regression verification

The corrected expected-pair JVM test failed against the old predicate; its XML is retained in build/main-verification/proot-completed-state-red.xml. After the fix the state matrix passed. API29/36 both passed a real normal Goal: model requests the approved PRoot job then finishes its response, the App persists COMPLETED Turn/Call and PAUSED Goal with RUN_FINISHED, saved stdout is viewed and an exact ACK is repeated through production UI actions. Goal/Turn/Call and settled budget remain identical before and after viewing/confirmation. Each endpoint observed five requests including probes.

The output-loss Goal branch also passed both APIs with four requests each and unchanged INPUT_REQUIRED state. Evidence: build/main-verification/proot-normal-goal-summary.json and proot-normal-goal-verified-emulator-*/result.json / proot-after-normal-failure-emulator-*/result.json. App/test builds, Spotless, Detekt and root lintDebug passed in proot-normal-goal-fixed-build.log.

## Residual risk

The model is scripted and recovery controls are rendered in a Compose fixture using production ChatService, not a full app-navigation walkthrough. Actual ACK response-loss UI testing and the final merged gates remain separate work.

## Related records

- [Recovery work](../development/proot-result-durable-recovery-gap.md)
- [ACK response loss](2026-09-07-proot-recovery-ack-loss-hid-local-result.md)
- [Goal semantics](../adr/0004-goal-run-wake-budget-semantics.md)
