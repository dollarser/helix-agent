# Bug Fix: CLI model Jobs lost their production Turn association

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: developer subscription provider, main model collection and recovery tests

## Problem

RuntimeSubscriptionJobExecutor generated a Job ID only in memory. Main-process death lost the association between the production model call and its Runtime Job.

## Impact

The defect obstructed production subscription recovery or caused the requested model/lifecycle behavior to differ from the persisted user selection.

## Root cause

RuntimeSubscriptionJobExecutor generated a Job ID only in memory. Main-process death lost the association between the production model call and its Runtime Job.

## Fix and invariants

ChatService now installs a local coroutine context containing the Turn and model-call IDs while collecting the stream. RuntimeSubscriptionJobExecutor records a versioned cli.job_prepared event before submitting. The stable event ID prevents silently replacing the same model-call binding. Session-correlated metadata includes platform, original Job and request hash, never the body or credentials. The context is not a ModelRequest field and is not serialized to providers. Probes without Turn ownership retain their existing behavior.

## Alternatives considered

An in-memory map or success-only logging cannot survive the boundary. Reissuing a model request would create another Job instead of reconciling the original. The write-ahead event states preparation, not acceptance or completion.

## Regression verification

CliGoalProcessKillDeviceTest and run-cli-goal-process-kill.py cover production Goal/Chat/subscription adapter and independent Runtime with an account-free DEBUG wait model. API29/36 times CODEX/CLAUDE/GROK/COPILOT passed 8/8 actual main-process kills and 16 recovery checks. The original binding/request hash survives, no new cli.job_prepared entry appears, Goal remains PAUSED, budget is not refunded, and explicit original-Job cancellation/reconciliation ends CANCELLED. Evidence: build/main-verification/cli-goal-binding-summary.json with matching main/test/Runtime APK hashes.

Initial failed runs and scoped cleanup remain in cli-goal-binding-api29-codex, cli-goal-model-fixed-api29-codex and cli-goal-*-failure-cleanup.log. Runtime OOM evidence is cli-goal-runtime-crash.log and cli-goal-runtime-context.log; the latter identifies TLS certificate initialization. Build-development failures (terminal property name and line length) were corrected without weakening checks. Test model/profile/mode/budget changes were restored; no Runtime data or credentials were reset.

Consumer JVM295/295, developer307/307 and CLI Runtime102/102 passed, zero skips. Root lintDebug, Spotless, Detekt and applicable builds passed; cli-binding-host-summary.json, cli-binding-final-host.log and cli-lazy-runtime-host.log retain the evidence. These results do not refresh all earlier APK matrices.

## Residual risk

User-facing reconciliation remains separate. Validation uses the Runtime DEBUG fixture and production Goal/Chat/adapter pipeline, not paid-account inference.

## Related records

- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
