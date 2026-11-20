# Bug Fix: Dispatcher 执行后取消、超时和输出拒绝的效果分类

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102

以下按独立缺陷保留当时的原因、失败与验收范围；各节的 fixed 只指原候选，不合并测试计数或把后续通过回填到早期失败。当前契约仍查主题 ADR。

## Problem

<a id="case-2026-09-07-cancelled-execution-review"></a>

### Cancellation after execution lost its unknown-effect review signal

Affected modules: tools/framework

The dispatcher described cancellation after execution as having unknown side effects but returned requiresReview=false. ChatService derives NEEDS_REVIEW from this flag when settling returned failures.

<a id="case-2026-09-07-timeout-execution-boundary"></a>

### Timeout outcomes did not distinguish executor submission

Affected modules: tools/framework

An expired budget before executor submission and a timeout after submission returned indistinguishable effect flags. The former did not confirm zero effects; the latter did not request review.

<a id="case-2026-09-07-invalid-output-effect-review"></a>

### Invalid executor output lost the effect-review signal

Affected modules: tools/framework

Output-schema rejection runs after the executor completes. It returned INVALID_OUTPUT with requiresReview=false, so ChatService could settle the call as ordinary FAILED despite possible effects from execution.

## Impact

### Cancellation after execution lost its unknown-effect review signal

An interrupted executor could have made changes before cancellation, yet its call would be settled as ordinary FAILED instead of NEEDS_REVIEW.

### Timeout outcomes did not distinguish executor submission

ChatService could settle an execution timeout as ordinary FAILED even though the worker might already have produced effects. A never-submitted attempt also lost its known zero-effect classification.

### Invalid executor output lost the effect-review signal

Rejecting an invalid result does not undo the operation. The persistent call state must preserve that distinction for review and Goal settlement.

## Root cause

### Cancellation after execution lost its unknown-effect review signal

The Cancelled executor branch used the default ExecutionFailed.requiresReview value despite explicitly declaring unknown effects in its detail.

### Timeout outcomes did not distinguish executor submission

executeWithinDeadline returned the same TimedOut singleton for both boundaries, and its conversion to ExecutionFailed used default flags.

### Invalid executor output lost the effect-review signal

The post-execution bindOutput failure used the default review flag, even though it could no longer verify a successful outcome.

## Fix and invariants

### Cancellation after execution lost its unknown-effect review signal

The Cancelled branch now sets requiresReview=true and retains sideEffectFree=false and CANCELLED_AFTER_START. Cancellation before execution continues to return the distinct Cancelled dispatch outcome. No automatic retry or approval behavior is added.

### Timeout outcomes did not distinguish executor submission

The private deadline helper now returns null only when the deadline expires before submission. The caller emits TIMEOUT with sideEffectFree=true and requiresReview=false for that case. Submitted or executor-reported timeouts keep TIMEOUT with sideEffectFree=false and requiresReview=true. Interrupt remains best effort. Existing bounded retry limits and proof handling are unchanged; confirming zero effects is not an unlimited retry permission.

### Invalid executor output lost the effect-review signal

INVALID_OUTPUT now carries requiresReview=true and retains sideEffectFree=false. The invalid payload remains rejected, no output hash is published, and this change does not permit technical retry or expand approval authority. ChatService already maps this signal to NEEDS_REVIEW.

## Alternatives considered

### Cancellation after execution lost its unknown-effect review signal

Changing every failure to require review would conflate known terminal failures with uncertain effects. The correction targets the executor cancellation contract, which explicitly declares uncertainty.

### Timeout outcomes did not distinguish executor submission

Marking every timeout uncertain would discard the explicit never-submitted boundary. Marking submitted timeouts effect-free would claim knowledge the framework does not have. The nullable private result keeps this distinction internal without expanding public executor result variants.

### Invalid executor output lost the effect-review signal

Accepting malformed output would bypass the registered schema. Treating rejection as no execution would deny effects that may already exist. Explicitly preserving uncertainty keeps validation and execution facts separate.

## Regression verification

### Cancellation after execution lost its unknown-effect review signal

Two existing dispatcher tests now assert the review flag and nonzero-effect uncertainty: an executor-reported cancellation and a blocked executor interrupted by the dispatch watchdog. Both assertions fail before the fix in build/main-verification/cancel-review-red.log (148 pass, two fail). After the fix, framework150/150 and files110/110 pass with no skips. The before-start cancellation test remains in the same suite.

Command: ./gradlew spotlessApply :tools:framework:test :tools:files:test detekt :app:assembleConsumerDebug :app:assembleDeveloperDebug lintDebug. Exit0, evidence build/main-verification/cancel-review-green.log. Related unchanged Gradle tasks can use up-to-date results; this is not a forced full-repository run.

### Timeout outcomes did not distinguish executor submission

The exhausted-budget test proves the executor was never entered and checks both flags. The watchdog test enters a blocked executor exactly once and checks uncertain effects/review while preserving its deadline assertion. Both fail before the fix: build/main-verification/timeout-review-red.log (148 pass/two fail). Final framework150/150 and files110/110 pass, zero skips.

Command: ./gradlew spotlessApply :tools:framework:test :tools:files:test detekt :app:assembleConsumerDebug :app:assembleDeveloperDebug lintDebug. Final exit0 in timeout-review-final.log. The first implementation passed tests but failed Detekt ReturnCount; timeout-review-green.log preserves that failure. The control flow was adjusted without a suppression. Unchanged Gradle tasks may use up-to-date results; this is not a forced full-repository run.

### Invalid executor output lost the effect-review signal

The existing schema test now records an executor-side counter increment and checks the review signal, non-effect-free flag, INVALID_OUTPUT and absent output hash. Before the fix, one of150 framework tests failed on the new flag assertion; after it, framework150/150 and files110/110 pass with no skips. This is a JVM executor-effect fixture, not a physical or file-write claim.

Commands: ./gradlew :tools:framework:test (red); ./gradlew spotlessApply :tools:framework:test :tools:files:test :app:assembleConsumerDebug :app:assembleDeveloperDebug lintDebug detekt (green, exit0). Evidence: build/main-verification/invalid-output-review-red.log and invalid-output-review-green.log. Both debug builds and static gates passed; unchanged tasks may use Gradle up-to-date results.

## Residual risk

### Cancellation after execution lost its unknown-effect review signal

This verifies dispatcher behavior with an actual blocked JVM worker, not device process death. The ChatService flag-to-NEEDS_REVIEW path was inspected, not newly exercised end to end in this change. Timeout and other execution boundaries remain separately under review.

### Timeout outcomes did not distinguish executor submission

Tests cover JVM submission/deadline behavior, not file-write SIGKILL or a new device-level conversation recovery run. The executor can continue after a best-effort interrupt, which is why the submitted outcome remains uncertain.

### Invalid executor output lost the effect-review signal

The ChatService mapping was source-checked; this change does not add a new device-level malformed-output scenario. The preceding2620-test full-repository snapshot predates this correction and remains separately scoped.

## Related records

- [Historical follow-up](../evidence/development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
