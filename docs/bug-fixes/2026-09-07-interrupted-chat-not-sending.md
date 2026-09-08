# Bug Fix: Reopened interrupted conversations appeared to still be sending

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: app chat screen state and recovery tests

## Problem

Opening a recovered INTERRUPTED Turn made ChatScreenState.isSending true even though no live model/tool coroutine existed. The PRoot recovery UI test queried and stopped the original Job successfully, then timed out waiting for this false sending state to clear.

## Impact

A historical interrupted Turn looked active, confusing action availability and status feedback. This was reproduced on API29 and API36, with the cleanup failure retained in proot-recovery-ui-emulator-*/ logs.

## Root cause

isSending was defined as not TurnState.isTerminal. INTERRUPTED is deliberately nonterminal in the core recovery state machine; that does not imply a currently running send.

## Fix and invariants

isSending now excludes INTERRUPTED explicitly. The core state machine and persistent Turn/Goal semantics remain unchanged. Live approval waits, running tools and cancellation still count as sending; terminal turns do not. Three JVM tests cover those distinctions.

## Alternatives considered

Changing INTERRUPTED to globally terminal would alter recovery semantics. Hiding the issue by closing the session before checking sending state would miss the product defect. The correction belongs to the screen's live-activity projection.

## Regression verification

After actual Goal/PRoot main-process SIGKILL, both devices reopen the production session and assert isSending=false, render the production recovery component, click query and stop, query again, and verify the original Runtime Job reaches CANCELLED. A second restart repeats identity/state checks. Final evidence is build/main-verification/proot-recovery-ui-summary.json (two kills/four recoveries/two actual stop clicks); prior failed cleanup runs and subsequent scoped cleanup are retained.

Consumer JVM298/298 and developer310/310 passed, zero skips; root lintDebug, Spotless, Detekt and both debug builds passed. The initial developer run had one unrelated public Cloudflare MCP socket timeout, preserved under proot-recovery-ui-first-jvm-xml and proot-recovery-ui-host.log. The corrected-state run is proot-recovery-ui-final-host.log, with counts in proot-recovery-ui-jvm.json. These passes do not erase the first network failure.

## Residual risk

Recovery component clicks are device-tested; full navigation, screen layout and the broader final UI matrix remain separate. This correction does not automatically resume or complete an interrupted Goal.

## Related records

- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
