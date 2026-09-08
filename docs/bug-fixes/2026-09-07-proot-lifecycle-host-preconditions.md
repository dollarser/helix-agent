# Bug Fix: PRoot host lifecycle preconditions were not verified

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: scripts

## Problem

The HXA-086 host script called `am kill` while the main app was in the foreground and labelled the next passing job as low-memory recovery. It also claimed that emulator Doze was unavailable because a `device_idle` service was absent.

## Impact

A successful shell command and subsequent job could pass without any process death. The service-name error also incorrectly excluded a feasible forced-idle emulator check.

## Root cause

`am kill` targets background processes. On the dedicated API 34 emulator the command returned zero while PID 16195 remained alive. The script neither backgrounded the app nor verified its exit. Android's controller is named `deviceidle`; the dedicated API 29/36 devices expose it. A separate API 34 probe showed that deep idle was initially disabled, but enabling it allowed `force-idle deep` to reach `IDLE`; the probe restored the disabled state afterward.

## Fix and invariants

The lifecycle script requires a live main-app PID, sends HOME, calls `am kill`, and polls for process absence before continuing. Failure to establish the state fails the script. The phase is now explicitly controlled background process termination, not observed low-memory pressure.

The independent `accept-hxa-086-forced-idle.py` script checks actual idle support/state, enables and forces deep idle, runs the existing real PRoot job test, and requires `IDLE` plus forced mode before and after the job. It rejects instrumentation failure and assumption skips. Cleanup restores the original enabled setting and removes forced mode; cleanup failure cannot become a passing result. An already-forced device is left untouched and rejected as an unsuitable precondition.

## Alternatives considered

A longer sleep after foreground `am kill` cannot establish process death. Renaming the service without checking enabled/current state also cannot establish Doze. Real memory-pressure testing remains a distinct scenario.

## Regression verification

The original foreground-kill evidence is retained in `build/main-verification/proot-host-lifecycle/foreground-am-kill-check.json`. Shell syntax and the Python CLI parse pass. HXA-083 lifecycle acceptance passes all eight phases on each of API 29/36. The corrected HXA-086 script passes on both APIs, including explicit PID-exit logs (19354 and 17190 respectively), mid-job death reconciliation, wake-lock sampling and isolation. The independent forced-idle job passes 1/1 on each API without skips, confirms IDLE/forced mode before and after, and verifies restoration. Evidence: `083-results.json`, `086-results.json`, `api29-forced-idle/result.json` and `api36-forced-idle/result.json` under the same directory.

## Residual risk

Forced device idle around an instrumentation job does not prove natural idle entry, background scheduling eligibility, real low-memory pressure, secure keyguard, thermal behavior, physical-device acceptance or long-duration stability. No such claims are made by these scripts.

## Related records

- [Main verification](../development/main-merged-verification.md)
- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
