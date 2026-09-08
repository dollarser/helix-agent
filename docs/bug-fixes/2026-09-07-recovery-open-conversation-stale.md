# Bug Fix: Open conversation missed the startup recovery commit

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: Application startup recovery, ChatService observable state

## Problem

A conversation opened while the asynchronous startup recovery was running could read the pre-recovery Turn/model-call state. Recovery later committed INTERRUPTED, but did not publish a refreshed conversation snapshot. The subscription recovery entry could remain absent.

## Impact

The user could not reach recovery actions until another operation happened to refresh the conversation. In the API36 result-recovery UI test, the database contained INTERRUPTED rows while the screen's recovery list remained empty.

## Root cause

HelixApplication invoked RecoveryCoordinatorApp on a background thread without notifying ChatService after the durable recovery transaction. ChatService's initial and open-session reads were not live Room subscriptions.

## Fix and invariants

After recovery commits, Application calls ChatService.onRecoveryCompleted. The service refreshes session rows and the currently open conversation on its own scope. This publishes persisted state without resuming a Goal, dispatching a tool, querying Runtime, granting approval or replaying a model request.

## Alternatives considered

Making the test wait for recovery before opening the screen would hide the product race. Polling Runtime or reopening the session automatically would add unnecessary effects or change user navigation. A post-commit service notification updates only the affected observable state.

## Regression verification

RecoveryPublicationDeviceTest opens a CREATED conversation, commits INTERRUPTED, invokes the production notification and confirms that the recovery entry appears and sending state clears. API29/36 passed1/1 each. The original result UI fixture was then repeated across three result handoff boundaries on both APIs:6 actual main-process kills and12 recoveries passed, using production ChatScreen query/recover buttons and asserting HELIX_OK while Turn/modelCall remain INTERRUPTED.

Evidence: build/main-verification/recovery-publication-api29.log, recovery-publication-api36.log and cli-result-ui-final-api*-*/. Original API36 timeout and scoped fixture cleanup remain in cli-result-ui-api36-fetched/ and cli-result-ui-api36-failed-cleanup.log. Two Consumer JVM external Connector HTTP read timeouts remain in the earlier logs; a serial full Consumer rerun passed300/300 and Developer passed312/312, zero skips. Their network root cause is not claimed. Builds, root lintDebug, Spotless and Detekt passed their tasks; an intermediate misplaced KDoc was corrected without suppressing the rule.

## Residual risk

These result-recovery UI fixtures seed call bindings and are not complete Goal-budget executions. Full Goal successful-result handoff and Runtime-unavailable local-result access remain separate follow-ups.

## Related records

- [Current TODO](../development/main-optimization-todo.md)
- [Result recovery gap](../development/cli-result-durable-recovery-gap.md)
- [HXA-102](../completion-records/HXA-102.md)
