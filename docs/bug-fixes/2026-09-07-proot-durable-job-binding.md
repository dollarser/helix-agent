# Bug Fix: PRoot submission identity was lost with the main process

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: developer PRoot tool, binding store and device tests

## Problem

The production Linux executor generated Job and execution IDs in memory. Only successful output audit metadata recorded them. Main-process death during execution left the durable ToolCall without the identity needed to locate the original Runtime Job.

## Impact

Direct ProotJobClient recovery tests supplied their own saved record and did not demonstrate that the production Goal/Chat path could recover the original Job association. Retrying the command would be unsafe and would not reconcile the previous execution.

## Root cause

ParsedLinuxCall dropped the trusted ToolCall/Turn identity. ProductionLinuxExecutor had no write-ahead persistence hook before its Binder submission.

## Fix and invariants

ParsedLinuxCall now carries the trusted local ToolCall and Turn IDs. A mandatory beforeSubmit callback runs after the input snapshot and Job specification are fixed but before any submission or PFD transfer. Production wiring records a versioned proot.job_prepared audit entry through ProotJobBindingStore, correlated to the owning session and containing only local IDs and the input manifest hash. The store checks the original persisted RUNNING call, and uses a stable event ID so a second preparation for the same call cannot silently replace its Job binding. Existing session audit deletion covers this record.

A persistence exception prevents submission. The record describes preparation, not acceptance, completion or approval. It contains no command, output body, environment or credential. resolve retrieves the binding by the original ToolCall ID without scanning Runtime Jobs. No automatic execution or replay was added, and no schema migration was needed.

## Alternatives considered

Logging IDs only after success misses the failure boundary. Searching Runtime Jobs by time or guessing a new ID cannot prove original-call ownership. Retrying submission as recovery would risk another effect. Write-ahead identity is required independently of Runtime terminal evidence.

## Regression verification

ProotGoalProcessKillDeviceTest and run-proot-goal-process-kill.py use scripted model HTTP plus the production Goal/Chat/Dispatcher/approval/PRoot pipeline. The host observes a real guest shell start marker before SIGKILL of the main App. API 29/36 both passed two restarts: the original binding survives, Runtime execution ID/input hash match, original Job query/cancel/reconcile works, terminal commit is stable within reconciliation, Goal stays PAUSED, approval remains consumed, budget is not refunded and model requests are not replayed. Evidence: build/main-verification/proot-goal-binding-summary.json and per-device logs/hash records.

LinuxRunToolE2eDeviceTest additionally injects a beforeSubmit persistence failure and verifies the prepared Job is unknown to the real Runtime. All six tests passed on each device (12/12), including normal output import and failed guest execution. These later test APKs add that failure test; the full Goal matrix retains its earlier test APK hash. Production APK is unchanged between them.

Developer JVM 307/307 passed with forced Test execution and supplied Connector sample conditions, zero skips; root lintDebug, Spotless, Detekt and developer main/test builds passed. Logs: proot-binding-host.log, proot-binding-jvm.json, proot-binding-failure-test-build.log and proot-binding-failure-emulator-*.log.

## Residual risk

Explicit original-Job reconciliation is exercised through production client/store APIs in the recovery test; the subsequent recovery entry now allows users to query and stop the original Job through the timeline, with production-component device clicks. Result verification/import and full navigation/layout acceptance remain separate. Missing/expired Runtime evidence still requires review. Remaining pre-submit/terminal/import boundary tests and other backends remain in HXA-102; this is not full main or physical/soak acceptance.

## Related records

- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
- [Failure effect semantics](2026-09-07-proot-failure-effect-semantics.md)
