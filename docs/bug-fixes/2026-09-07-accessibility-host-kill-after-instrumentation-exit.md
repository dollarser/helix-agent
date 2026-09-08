# Bug Fix: Accessibility host kill ran after instrumentation had already exited

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: tools/automation androidTest and host script

## Problem

The host force-stop script waited for setup instrumentation to return before issuing `am force-stop`. Setup and the Accessibility service share the same process, which Android terminates when instrumentation finishes.

## Impact

Setup and recovery could both report JUnit success without the host ever force-stopping a live session. Post-kill PID/notification absence alone did not prove the intended causal sequence.

## Root cause

API 29 system logs show `finished inst` killing PID 21966 at 15:40:43.724, followed by the host force-stop at 15:40:43.980. The script checked absence afterward but never required a live PID or notification immediately before its kill. Evidence is retained in `build/main-verification/current-automation-cleanup-fixed/api29-kill-order.log`.

## Fix and invariants

Setup now establishes the session, snapshot token and notification, publishes a readiness status, then stays alive for a bounded host-kill window. If the host never kills it, the test fails. The host runs setup asynchronously, waits for readiness, requires a live PID and active notification, and only then force-stops the package. It confirms process/notification absence and runs the existing recovery assertions for no restored session, unusable old token and no restored notification. Fixture settings are restored on exit.

The intentionally interrupted setup is not counted as a passed JUnit test. Its evidence is the readiness/live PID/notification/host-kill sequence; recovery must independently pass 1/1 without changing its assertions.

## Alternatives considered

Removing the post-kill checks would weaken coverage. Merely checking PID after synchronous setup would correctly fail but could never establish the desired scenario. A fixed sleep before killing, without readiness, could kill before the session existed.

## Regression verification

Shell syntax, Spotless, Detekt, module Debug Lint and the Android test APK build pass (`automation-live-kill-gates.log`). API 29/36 each pass the ordinary service test 1/1 with an empty fixture-component list afterward, then the live host-kill sequence and recovery 1/1. Setup reports readiness and is intentionally interrupted with Process crashed; it is not counted as a passed test. Both runs use the same test APK SHA-256 `5c9f2e5d2d310278dabf1108d598acd2ce67af5ca4a21d333307535c1982d5a7`. Evidence is under `build/main-verification/current-automation-live-force-stop/`, preserving earlier non-live results.

## Residual risk

This proves only the controlled emulator process boundary. It does not establish natural low-memory killing, physical-device behavior or long-duration stability.

## Related records

- [Empty-list cleanup fix](2026-09-07-accessibility-test-empty-settings-cleanup.md)
- [Main verification](../development/main-merged-verification.md)
- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
