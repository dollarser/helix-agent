# Bug Fix: CLI Runtime eagerly initialized four TLS clients on cold bind

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: runtime cli-app service lifecycle

## Problem

API29 Runtime cold creation crashed with OutOfMemoryError while initializing OkHttp certificate chains in OkHttpGrokDeviceTransport. onCreate eagerly constructed all four OAuth transports/controllers, including for account-free fixture Jobs and status queries.

## Impact

The defect obstructed production subscription recovery or caused the requested model/lifecycle behavior to differ from the persisted user selection.

## Root cause

API29 Runtime cold creation crashed with OutOfMemoryError while initializing OkHttp certificate chains in OkHttpGrokDeviceTransport. onCreate eagerly constructed all four OAuth transports/controllers, including for account-free fixture Jobs and status queries.

## Fix and invariants

OAuth transports and controllers now initialize lazily when the selected platform needs them. onDestroy closes only initialized transports and does not create unused clients while cleaning up. There are no TLS trust, certificate-validation, credential or dependency changes.

## Alternatives considered

Increasing the heap or suppressing OOM would hide the unnecessary work. Initializing all platforms for every bind is not needed for the active Job. On-demand initialization retains each platform implementation and its resource cleanup.

## Regression verification

CliGoalProcessKillDeviceTest and run-cli-goal-process-kill.py cover production Goal/Chat/subscription adapter and independent Runtime with an account-free DEBUG wait model. API29/36 times CODEX/CLAUDE/GROK/COPILOT passed 8/8 actual main-process kills and 16 recovery checks. The original binding/request hash survives, no new cli.job_prepared entry appears, Goal remains PAUSED, budget is not refunded, and explicit original-Job cancellation/reconciliation ends CANCELLED. Evidence: build/main-verification/cli-goal-binding-summary.json with matching main/test/Runtime APK hashes.

Initial failed runs and scoped cleanup remain in cli-goal-binding-api29-codex, cli-goal-model-fixed-api29-codex and cli-goal-*-failure-cleanup.log. Runtime OOM evidence is cli-goal-runtime-crash.log and cli-goal-runtime-context.log; the latter identifies TLS certificate initialization. Build-development failures (terminal property name and line length) were corrected without weakening checks. Test model/profile/mode/budget changes were restored; no Runtime data or credentials were reset.

Consumer JVM295/295, developer307/307 and CLI Runtime102/102 passed, zero skips. Root lintDebug, Spotless, Detekt and applicable builds passed; cli-binding-host-summary.json, cli-binding-final-host.log and cli-lazy-runtime-host.log retain the evidence. These results do not refresh all earlier APK matrices.

## Residual risk

The successful cold/debug recovery matrix does not establish paid-account network memory acceptance. Real transport/refresh behavior remains covered by the existing JVM tests; user-excluded paid calls were not performed.

## Related records

- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
