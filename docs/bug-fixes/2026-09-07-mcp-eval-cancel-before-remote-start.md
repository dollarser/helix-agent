# Bug Fix: MCP cancellation evaluation raced remote execution

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: app androidTestDeveloper, scripts MCP fixture

## Problem

The current fixed evaluation reported PASS on device for mcp-004, while the host correctly rejected the suite because the fixture recorded zero tool executions.

## Impact

A local cancelled ToolCall did not prove cancellation during a remote MCP execution. The four-case suite remained FAIL despite all device records saying PASS.

## Root cause

The evaluation stopped two seconds after the local ToolCall entered RUNNING. That state precedes completion of the SDK connection and request delivery, so elapsed time could not establish that the fixture was executing tools/call.

## Fix and invariants

The synthetic local fixture exposes a read-only execution-start probe. Before receiving mcp-004 tools/call it returns 404; after recording tool_start under its lock it returns 204. The Android test polls this probe with bounded connect/read timeouts and triggers Stop only after confirmation. Unexpected probe status or network failure fails the test. The fixed two-second sleep is removed. Production transport and approval behavior are unchanged, and the host still requires exact counts 0/1/1/1. Evidence now labels the route host_stop_after_remote_tool_start.

## Alternatives considered

Increasing a sleep retains the race. Relaxing the host execution count would accept cancellation before remote execution and defeat the scenario. Local RUNNING alone remains insufficient evidence.

## Regression verification

Initial evidence: `build/main-verification/current-fixed45-api34/mcp/` records mcp-004 execution count 0 and suite FAIL. The full initial batch has 41 cases in passed suites, not 45/45.

A direct HTTP probe verified 404 before a tools/call request and 204 after the actual request entered the fixture, with one recorded execution. Existing Python tests pass 2/2. Spotless and Detekt plus developer Android test APK build pass; the initial ComplexCondition and NestedBlockDepth failures are preserved in their logs and resolved by a small test helper without new suppressions.

On API 34 with the rebuilt test APK, all four MCP cases pass and host execution counts are exactly 0/1/1/1. Evidence: `build/main-verification/fixed45-final-api34/mcp/result.json`, its config with installed hashes, server events and per-case device records; build log `mcp-eval-start-gates-final.log`.

## Residual risk

The model is real SGLang; the MCP server is a synthetic local fixture. This proves the scenario's execution-start and cancellation boundary, not protected-account acceptance or compensation of an external side effect. Remaining 41 cases are being rerun on the same new APK before a unified 45/45 claim.

## Related records

- [Main verification](../development/main-merged-verification.md)
- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
