# Bug Fix: Accessibility evaluation supplied a token before model execution

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: app androidTestDeveloper

## Problem

The second unified evaluation failed accessibility-001 with STALE_TOKEN and stopped after one of the three accessibility records. Its context supplied a token obtained by fixture setup before the model's turn.

## Impact

The scenario could fail on a token invalidated before the model attempted the action. The other 42 cases passing did not establish a complete 45/45 evaluation.

## Root cause

Fixture setup captured a token and inserted it into a user message. Production invalidates snapshot generations when Accessibility events arrive, so the pre-captured token was not guaranteed to remain current through setup and model execution. The rejection was observed, but the exact event invalidating this token was not recorded; no specific platform event is claimed as the cause.

## Fix and invariants

The model now obtains its own current ui.snapshot, identifies the synthetic Fixture click control and uses that result for the click. A STALE_TOKEN response requires a new snapshot before another request. The verifier now requires a completed snapshot in addition to exactly one successful click and the fixture's clicked state. Test approval resolves the exact token from the saved snapshot and no longer accepts the setup token shortcut. The immutable corpus, production generation checks, session scope, app-switch pause and sensitive-UI rules are unchanged.

## Alternatives considered

Extending token validity or suppressing window invalidation would weaken the production contract without evidence. Sleeping before capturing another setup token would retain the race. Letting the model acquire the snapshot matches the fixed corpus's requirement to use a current UI snapshot token.

## Regression verification

Original FAIL: `build/main-verification/fixed45-final-api34/accessibility/`, one failed record of three expected. After the fixture change, real SGLang evaluation passes 3/3 on API 34:

- accessibility-001: ui.snapshot COMPLETED, ui.click COMPLETED, one successful click.
- accessibility-002: snapshot completed, no successful click, session remains paused after foreground change.
- accessibility-003: snapshot completed, click rejected, sensitive UI probe retained.

Evidence: `build/main-verification/fixed45-current-snapshot-api34/accessibility/`, including model/corpus/context hashes and installed App/test APK hashes. `spotlessCheck detekt :app:assembleDeveloperDebugAndroidTest :app:assembleDeveloperDebug` passes in `access-current-snapshot-gates.log`. Current App and test APKs were installed before the run.

## Residual risk

This is one real-model run against a synthetic Android fixture, not physical-device acceptance or a proof that UI tokens never become stale. The subsequent same-APK aggregate verifies 45/45, including one retained browser probe retry; see `build/main-verification/fixed45-current-snapshot-api34/aggregate-result.json`. This is not a first-attempt clean pass.

## Related records

- [Main verification](../development/main-merged-verification.md)
- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
