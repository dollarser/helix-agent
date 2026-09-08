# Bug Fix: Timeout outcomes did not distinguish executor submission

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: tools/framework

## Problem

An expired budget before executor submission and a timeout after submission returned indistinguishable effect flags. The former did not confirm zero effects; the latter did not request review.

## Impact

ChatService could settle an execution timeout as ordinary FAILED even though the worker might already have produced effects. A never-submitted attempt also lost its known zero-effect classification.

## Root cause

executeWithinDeadline returned the same TimedOut singleton for both boundaries, and its conversion to ExecutionFailed used default flags.

## Fix and invariants

The private deadline helper now returns null only when the deadline expires before submission. The caller emits TIMEOUT with sideEffectFree=true and requiresReview=false for that case. Submitted or executor-reported timeouts keep TIMEOUT with sideEffectFree=false and requiresReview=true. Interrupt remains best effort. Existing bounded retry limits and proof handling are unchanged; confirming zero effects is not an unlimited retry permission.

## Alternatives considered

Marking every timeout uncertain would discard the explicit never-submitted boundary. Marking submitted timeouts effect-free would claim knowledge the framework does not have. The nullable private result keeps this distinction internal without expanding public executor result variants.

## Regression verification

The exhausted-budget test proves the executor was never entered and checks both flags. The watchdog test enters a blocked executor exactly once and checks uncertain effects/review while preserving its deadline assertion. Both fail before the fix: build/main-verification/timeout-review-red.log (148 pass/two fail). Final framework150/150 and files110/110 pass, zero skips.

Command: ./gradlew spotlessApply :tools:framework:test :tools:files:test detekt :app:assembleConsumerDebug :app:assembleDeveloperDebug lintDebug. Final exit0 in timeout-review-final.log. The first implementation passed tests but failed Detekt ReturnCount; timeout-review-green.log preserves that failure. The control flow was adjusted without a suppression. Unchanged Gradle tasks may use up-to-date results; this is not a forced full-repository run.

## Residual risk

Tests cover JVM submission/deadline behavior, not file-write SIGKILL or a new device-level conversation recovery run. The executor can continue after a best-effort interrupt, which is why the submitted outcome remains uncertain.

## Related records

- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
