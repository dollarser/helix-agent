# Bug Fix: Unrelated window content invalidated Accessibility action tokens

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: tools/automation; developer instrumentation fixtures

## Problem

A fresh ui.snapshot followed by ui.click failed with STALE_TOKEN while the synthetic target screen was unchanged. Captured event metadata showed repeated System UI content changes in a different window.

## Impact

Unrelated status/notification window updates could prevent an approved action against an unchanged target. The Goal UI process-death test could not reach its actual-click boundary.

## Root cause

HelixAccessibilityService incremented a single snapshot generation for every received event. It treated TYPE_WINDOW_CONTENT_CHANGED from a known unrelated window as a change to the active target.

## Fix and invariants

Only content-change events with known, different event and active window IDs preserve generation. Content changes in the active window, unknown IDs, unavailable roots and all other event types still invalidate it. The service still observes the active package for every event; lock handling, session scope, approval, target-window/package checks and node fingerprint verification remain intact. The observed root is recycled in the existing finally block.

## Alternatives considered

Ignoring all System UI events by package would be too broad, particularly when System UI becomes the active target. Increasing token lifetimes or bypassing generation checks would hide real target changes. The correction uses the actual window identity and remains conservative when it is unknown.

## Regression verification

A tracker regression reproduces the prior global invalidation: twenty unrelated-window content events change generation before the fix and preserve it after. Active-window and unknown-window cases continue to invalidate. tools/automation JVM43/43 pass, zero skips. The first static run found a complex condition; it was simplified without a suppression. Evidence: build/main-verification/ui-generation-red.log, ui-generation-green.log, ui-generation-final.log. Developer main/test APK builds, root lintDebug, Spotless and Detekt passed.

The final production Goal fixture runs ui.snapshot, uses its returned token for ui.click on an explicitly allowlisted test-APK button, confirms a durable click counter and held model backfill, and then SIGKILLs the main app. API29/36 each pass two recovery checks: three model calls, exact budget settlement, completed tool state, no new model requests, and click counter remaining one. Evidence: ui-generation-kill-summary.json and ui-generation-final-api29/36 directories, with matching APK hashes. This is two actual clicks/two actual kills/four recovery tests, not a physical-device claim.

## Residual risk

Initial target-window initialization is legitimately token-invalidating; the fixture waits for idle before starting the Goal. Its budget leaves room beyond the three intended model calls so recovery tests interruption rather than budget exhaustion. Earlier failures, including test-APK IntRef crash, malformed fixture IDs, prefixed result parsing, stale pre-Goal tokens, API29 service binding state and a transient API36 adb outage are preserved separately. API29's dedicated emulator was rebooted before the final run; that does not establish a fix for repeated instrumentation service binding.

The completed-click/backfill boundary is covered. Kill before ToolCall settlement and broader UI/lifecycle matrices remain distinct acceptance items.

## Related records

- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
