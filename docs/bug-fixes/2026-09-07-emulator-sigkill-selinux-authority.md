# Bug Fix: Emulator SIGKILL used a SELinux-denied run-as signal path

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: host process-kill scripts and app androidTest

## Problem

The model-stream kill matrix failed on API 29 before delivering SIGKILL. Toybox reported `unknown pid` even though `pidof` confirmed the ready application PID was alive.

## Impact

The intended crash/recovery boundary was never reached. Waiting for instrumentation to time out or subsequently checking recovery cannot count as a host SIGKILL pass.

## Root cause

A shell-builtin signal probe reported Permission denied. The system AVC explicitly denied `runas_app` sending `{ sigkill }` to `untrusted_app` with SELinux enforcing. The shared application UID did not bypass that domain restriction. Original evidence is retained under `build/main-verification/model-kill-api29-36/`, including `api29-signal-denials.log` and both command probes.

## Fix and invariants

The three host scripts now use a shared dedicated-emulator signal helper. It requires an explicit emulator serial, verifies the existing host `su 0` authority before creating fixtures, accepts only the named Helix application packages, checks the ready PID belongs to that package immediately before signalling, and verifies the PID is absent afterward. Result records explicitly name the host signal authority. Model tests still require readiness, a real held HTTP request, socket EOF and two startup recovery checks with unchanged request counts.

An explicit `abort` phase cleans only the owned failed model fixture and restores its prior run controls. It skips recovery assertions and is documented solely as cleanup, never as recovery acceptance. This preserves failure evidence without leaving fixture state behind.

## Alternatives considered

Disabling SELinux would alter the environment being tested. Switching toybox to shell builtin did not fix the denied domain transition. Force-stop has different lifecycle semantics from SIGKILL. The dedicated emulator already provides host administrative signal control, so no app privilege or policy change is necessary.

## Regression verification

Consumer Android test APK build, Spotless and Detekt pass in `model-kill-host-signal-build.log`. All four Python scripts parse. Explicit abort cleanup passed and removed the owned marker; that result is not included in recovery counts. API 29/36 three-protocol/two-boundary model matrix passes 12/12 with 12 SIGKILLs and 24 startup recovery checks, preserving request counts. Budget/deletion regression passes all four groups with another eight SIGKILLs. Evidence: `model-kill-api29-36-host-signal-fixed/verified-summary.json` and `goal-kill-api29-36-host-signal-fixed/`, under `build/main-verification/`.

## Residual risk

Host administrative control on a dedicated emulator is not app Root capability, physical-device acceptance, natural low-memory killing or long-duration stability. The helper deliberately rejects physical-device serials and lacks an unprivileged fallback. No SELinux setting is changed.

## Related records

- [Main verification](../development/main-merged-verification.md)
- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
