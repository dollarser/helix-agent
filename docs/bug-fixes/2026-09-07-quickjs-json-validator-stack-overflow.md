# Bug Fix: QuickJS JSON validation overflowed the Android call stack

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: runtime/quickjs

## Problem

`JsAbiAttackTest.deepNestingRoundTrip` failed with `StackOverflowError` on API 34. The input is a valid 300-level object fixture below the validator's existing 512-container limit.

## Impact

The shared JSON validator runs at the client input, service input and client output boundaries. Recursive validation could throw instead of accepting valid bounded input, even though the JVM tests passed. The original Android group passed only 74/75.

## Root cause

`parseValue` recursively called `parseContainer` and `parseEntries` for every nested container. The depth limit bounded JSON structure but did not ensure those call frames fit the Android instrumentation thread's roughly 1 MiB stack.

## Fix and invariants

Use a per-document explicit container stack and an iterative entry/separator loop. Pushes still reject depth above 512; scalar parsing, strict UTF-8, string escapes, numeric syntax and complete-document validation retain their contracts. Object keys and array entries resume at the correct parent after a child closes. Trailing commas remain invalid, including whitespace before a closing delimiter. No new exception suppression or relaxed bound is introduced.

Extend existing JVM grammar cases with mixed nested siblings, malformed closing delimiters, whitespace after trailing commas, and object depth 512/513. Preserve the original Android 300-level actual QuickJS round-trip unchanged.

## Alternatives considered

Reducing the depth limit would reject previously supported input and defeat the existing regression. Catching StackOverflowError would conceal exhaustion and convert valid documents to failures. Increasing thread stack size would retain platform-dependent behavior.

## Regression verification

Initial failure: `build/main-verification/current-api34-extra-modules/quickjs.log` (74/75, stack at Parser recursion). After the fix:

- QuickJS JVM 85/85, no skips/failures/errors.
- API 29/34/36 module instrumentation each 75/75: 225/225, no skips, including the unchanged failing round-trip. Each installed test APK hash matches the same built artifact.
- QuickJS Debug/Release Lint, root Spotless and Detekt pass.
- Consumer/developer Debug/Release App APK builds pass; their four hashes are recorded. Those new App APKs have not been installed by this verification.

Evidence: `build/main-verification/json-stack-jvm-result.json`, `json-stack-device/result.json` and per-device logs, `json-stack-gates-fixed.log`, `json-stack-app-builds.log`, `json-stack-app-apks.json`. Initial TooManyFunctions/ReturnCount static failures are preserved in `json-stack-gates.log`; the parser was simplified and reverified without new suppressions.

## Residual risk

These are JVM and emulator results, not physical-device or long-soak acceptance. Whole-main tests and fixed evaluations retain their original APK/source snapshots until refreshed; the pending Accessibility evaluation failure is independent.

## Related records

- [Main verification](../development/main-merged-verification.md)
- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
