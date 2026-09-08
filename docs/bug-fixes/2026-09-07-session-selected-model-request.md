# Bug Fix: Chat requests ignored the stored session model

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: main ChatService request construction

## Problem

Both initial and tool-backfill requests used ProviderConfig.model even though SessionEntity.modelId stored the selected model. A distinct per-session selection did not reach the provider.

## Impact

The defect obstructed production subscription recovery or caused the requested model/lifecycle behavior to differ from the persisted user selection.

## Root cause

Both initial and tool-backfill requests used ProviderConfig.model even though SessionEntity.modelId stored the selected model. A distinct per-session selection did not reach the provider.

## Fix and invariants

Both request builders now use the stored session model, with the Provider default only for a legacy null model. No wire format, provider binding or credential changes were made.

## Alternatives considered

Changing the Provider default to match each session would mutate other sessions. Discarding the stored selection would preserve the wrong product behavior.

## Regression verification

CliGoalProcessKillDeviceTest and run-cli-goal-process-kill.py cover production Goal/Chat/subscription adapter and independent Runtime with an account-free DEBUG wait model. API29/36 times CODEX/CLAUDE/GROK/COPILOT passed 8/8 actual main-process kills and 16 recovery checks. The original binding/request hash survives, no new cli.job_prepared entry appears, Goal remains PAUSED, budget is not refunded, and explicit original-Job cancellation/reconciliation ends CANCELLED. Evidence: build/main-verification/cli-goal-binding-summary.json with matching main/test/Runtime APK hashes.

Initial failed runs and scoped cleanup remain in cli-goal-binding-api29-codex, cli-goal-model-fixed-api29-codex and cli-goal-*-failure-cleanup.log. Runtime OOM evidence is cli-goal-runtime-crash.log and cli-goal-runtime-context.log; the latter identifies TLS certificate initialization. Build-development failures (terminal property name and line length) were corrected without weakening checks. Test model/profile/mode/budget changes were restored; no Runtime data or credentials were reset.

Consumer JVM295/295, developer307/307 and CLI Runtime102/102 passed, zero skips. Root lintDebug, Spotless, Detekt and applicable builds passed; cli-binding-host-summary.json, cli-binding-final-host.log and cli-lazy-runtime-host.log retain the evidence. These results do not refresh all earlier APK matrices.

## Residual risk

The CLI Goal matrix proves initial requests select helix-fixture-wait while the Provider default is helix-fixture. The dedicated differing-model and legacy-null-model regressions now each validate all three outgoing requests (initial plus two tool backfills), including the existing duplicate-wire-ID execution/result assertions. Full AttachmentE2eDeviceTest passed 28 tests on each API29/36 and consumer/developer combination (112/112, zero skips), with matching installed artifact hashes per group in session-model-backfill-summary.json. Earlier first-run failures also encountered Runtime OOM; they are not attributed solely to model selection.

## Related records

- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
