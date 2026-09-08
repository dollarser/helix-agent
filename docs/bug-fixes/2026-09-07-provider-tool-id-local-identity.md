# Bug Fix: Reused Provider tool IDs collided with local execution rows

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: app chat production and tests

## Problem

A Provider returned `fixture-call` in two independent Turns. The second execution failed with `SQLiteConstraintException: UNIQUE constraint failed: tool_calls.id`, leaving the Turn FAILED/INTERNAL.

## Impact

A protocol identifier supplied by an external Provider acted as the global database primary key and approval/result correlation. Independent calls could collide before dispatch. Merely making test fixture IDs unique hid the product failure.

## Root cause

ChatService used the buffered Provider tool ID directly for the local scheduler, ToolCall row primary key, approval binding and audit. Provider streaming validation rejected duplicates within one response but could not establish global uniqueness across responses or sessions. Reproduction evidence is `build/main-verification/mcp-live-kill-api29-fixed/system.log` and the owned synthetic fixture snapshot in that directory.

## Fix and invariants

Each completed tool round now allocates application-generated local IDs before beginning the batch. The local IDs flow through normal dispatch, rejection, budget reservation, approval, result storage and recovery. The persisted assistant TOOL_CALLS envelope retains the original protocol `id` and adds its `localId` mapping. Model-visible history reads the original `id`; settled results map back by local identity before model backfill, preserving original order and protocol correlation. Existing history without localId remains readable; no schema migration or authority change is needed.

LocalToolCallBatch rejects invalid local IDs and cannot resolve an ID from another batch. Provider duplicate validation within a single response remains unchanged. A new round receives fresh local identities even when the Provider repeats its protocol ID.

## Alternatives considered

A new random database primary key alone would leave approvals, results and audit correlated by the ambiguous remote ID. Prefixing by Turn would still collide across model rounds within the same Turn. Rewriting the Provider ID in model-visible history would lose the original protocol association. Deleting previous rows or rejecting all reused Provider IDs would avoid neither the underlying authority separation nor the intended usable behavior.

## Regression verification

The app consumer/developer JVM suites declare 295/307 tests, with 292/304 passes, no failures and three existing assumptions each. New identity/history cases pass in both variants. Developer main/test APK build, developer lint, Spotless and Detekt pass (`local-tool-id-build.log`, `local-tool-id-gates.log`). The current JVM run does not count its existing skipped sample-dependent tests as passed.

With the original colliding `fixture-call` preserved in the API 29 database, two successive new host MCP kill runs using that same Provider ID pass independently: `mcp-duplicate-id-api29-first/` and `mcp-duplicate-id-api29-second/` under `build/main-verification/`. Each requires actual execution, consumed approval, SIGKILL/socket EOF and two startup recoveries with no request replay. API 36 uses the same installed APK hashes in the additional recorded run. These are scripted Provider/remote fixtures, not real-service acceptance.

The broader core device matrix additionally exposed an old budget-test lookup that assumed wire IDs `one`/`two` were local primary keys. All four groups retained 88 passes and that single failure. The test now resolves the persisted `id` to `localId` mapping before asserting the first call completed, the second was budget-rejected, both results are durable and exactly one tool was charged. Its semantic assertions are unchanged; prior failures remain under `local-id-core-api*/`.

## Residual risk

The full JVM/root lint verification has now been refreshed: 2610/2610 tests, zero skips or failures across 34 actual XML-producing tasks, with root lintDebug, Spotless and Detekt passing (`local-tool-id-full-host.log`, `local-tool-id-full-host-result.json`). XML evidence is archived in `local-tool-id-full-host-xml/`. Remaining fixed-evaluation and device snapshots need refresh after this production change. Normal completion/backfill, valid unknown-tool rejection and same-Turn repeated-round cases now pass 16/16 across API 29/36 and both variants (`repeated-tool-round-device-final/`). Malformed names/arguments intentionally fail earlier in protocol/history validation and are not represented as multi-round rejection passes. Physical and long-duration gates remain excluded from this stage.

## Related records

- [Main verification](../development/main-merged-verification.md)
- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
