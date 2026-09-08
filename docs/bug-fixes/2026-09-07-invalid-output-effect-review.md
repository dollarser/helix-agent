# Bug Fix: Invalid executor output lost the effect-review signal

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: tools/framework

## Problem

Output-schema rejection runs after the executor completes. It returned INVALID_OUTPUT with requiresReview=false, so ChatService could settle the call as ordinary FAILED despite possible effects from execution.

## Impact

Rejecting an invalid result does not undo the operation. The persistent call state must preserve that distinction for review and Goal settlement.

## Root cause

The post-execution bindOutput failure used the default review flag, even though it could no longer verify a successful outcome.

## Fix and invariants

INVALID_OUTPUT now carries requiresReview=true and retains sideEffectFree=false. The invalid payload remains rejected, no output hash is published, and this change does not permit technical retry or expand approval authority. ChatService already maps this signal to NEEDS_REVIEW.

## Alternatives considered

Accepting malformed output would bypass the registered schema. Treating rejection as no execution would deny effects that may already exist. Explicitly preserving uncertainty keeps validation and execution facts separate.

## Regression verification

The existing schema test now records an executor-side counter increment and checks the review signal, non-effect-free flag, INVALID_OUTPUT and absent output hash. Before the fix, one of150 framework tests failed on the new flag assertion; after it, framework150/150 and files110/110 pass with no skips. This is a JVM executor-effect fixture, not a physical or file-write claim.

Commands: ./gradlew :tools:framework:test (red); ./gradlew spotlessApply :tools:framework:test :tools:files:test :app:assembleConsumerDebug :app:assembleDeveloperDebug lintDebug detekt (green, exit0). Evidence: build/main-verification/invalid-output-review-red.log and invalid-output-review-green.log. Both debug builds and static gates passed; unchanged tasks may use Gradle up-to-date results.

## Residual risk

The ChatService mapping was source-checked; this change does not add a new device-level malformed-output scenario. The preceding2620-test full-repository snapshot predates this correction and remains separately scoped.

## Related records

- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
