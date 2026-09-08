# Bug Fix: Goal reminder navigation fixture omitted first-launch state

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: app androidTest

## Problem

Fresh API 29/36 consumer/developer installations failed the notification-shade Goal visibility assertion. The fixture seeded a Goal and session without completing first-launch acknowledgement.

## Impact

The Goal device matrix depended on previous tests having dismissed the first-launch notice. A warm API 34 installation passed while all four fresh-install combinations failed the same visibility assertion.

## Root cause

The notification was consumed and selected the expected Goal, but MainActivity correctly displayed the first-launch notice over the shell. API 29 diagnostic screenshots confirm the notice, rather than the Goal dialog, occupied the foreground. This was a fixture precondition failure, not evidence of lost notification routing.

## Fix and invariants

The existing-user reminder fixture explicitly sets `firstLaunch.markSeen()` before launching MainActivity. `FirstLaunchNoticeTest` separately covers acknowledgement and persistence. All reminder visibility, session binding, unchanged Goal/run and no automatic Continue assertions remain. Production code is unchanged.

## Alternatives considered

Removing the visibility assertion would conceal UI regressions. Depending on test ordering would retain the fresh-install failure. Changing the production first-launch gate would alter unrelated behavior.

## Regression verification

The original four groups each ran 44 tests; notification visibility failed in all four. API 29 developer also recorded one Worker startup timeout. Raw results remain under `build/main-verification/current-goal-api*/`. After the fixture fix, API 29 consumer and developer each pass 44/44 with no skipped tests; the Worker timeout did not recur in this rerun, but its underlying cause is not established. API 36 consumer and developer also pass 44/44. All four reruns total 176/176, with no skips.

`spotlessCheck :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest` passes with JDK 17. Evidence: `build/main-verification/goal-nav-first-launch-build.log`, `goal-nav-api29-diagnostic/`, and `current-goal-fixed-api*/`.

## Residual risk

This is a simulated-device fixture correction, not physical-device acceptance. First-launch copy still contains historical wording and is tracked for the unified UI phase. Host-driven process-kill tests are separate from this 44-test group.

## Related records

- [Main verification](../development/main-merged-verification.md)
- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
