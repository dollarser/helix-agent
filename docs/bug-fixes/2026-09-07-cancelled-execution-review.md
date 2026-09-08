# Bug Fix: Cancellation after execution lost its unknown-effect review signal

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: tools/framework

## Problem

The dispatcher described cancellation after execution as having unknown side effects but returned requiresReview=false. ChatService derives NEEDS_REVIEW from this flag when settling returned failures.

## Impact

An interrupted executor could have made changes before cancellation, yet its call would be settled as ordinary FAILED instead of NEEDS_REVIEW.

## Root cause

The Cancelled executor branch used the default ExecutionFailed.requiresReview value despite explicitly declaring unknown effects in its detail.

## Fix and invariants

The Cancelled branch now sets requiresReview=true and retains sideEffectFree=false and CANCELLED_AFTER_START. Cancellation before execution continues to return the distinct Cancelled dispatch outcome. No automatic retry or approval behavior is added.

## Alternatives considered

Changing every failure to require review would conflate known terminal failures with uncertain effects. The correction targets the executor cancellation contract, which explicitly declares uncertainty.

## Regression verification

Two existing dispatcher tests now assert the review flag and nonzero-effect uncertainty: an executor-reported cancellation and a blocked executor interrupted by the dispatch watchdog. Both assertions fail before the fix in build/main-verification/cancel-review-red.log (148 pass, two fail). After the fix, framework150/150 and files110/110 pass with no skips. The before-start cancellation test remains in the same suite.

Command: ./gradlew spotlessApply :tools:framework:test :tools:files:test detekt :app:assembleConsumerDebug :app:assembleDeveloperDebug lintDebug. Exit0, evidence build/main-verification/cancel-review-green.log. Related unchanged Gradle tasks can use up-to-date results; this is not a forced full-repository run.

## Residual risk

This verifies dispatcher behavior with an actual blocked JVM worker, not device process death. The ChatService flag-to-NEEDS_REVIEW path was inspected, not newly exercised end to end in this change. Timeout and other execution boundaries remain separately under review.

## Related records

- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
