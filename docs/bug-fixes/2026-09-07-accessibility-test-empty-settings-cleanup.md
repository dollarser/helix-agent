# Bug Fix: Accessibility tests failed to clear an empty enabled-service list

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: tools/automation androidTest

## Problem

After the ordinary Accessibility service test passed, the host force-stop setup failed on API 29 and 36 while waiting for a connected service. It never reached the intended force-stop scenario.

## Impact

The fixture could leave its component in system settings despite claiming to restore an empty list. Later installation/instrumentation lifecycle changes encountered stale enabled state. A passing service test did not establish clean fixture teardown.

## Root cause

Both fixture helpers interpolated an empty component set into `settings put secure enabled_accessibility_services` without a value. A direct shell reproduction returned `Bad arguments`. The helpers ignored that output and set only `accessibility_enabled` to zero; the component list remained unchanged. Logs from the failed subsequent setup show a service started during package replacement and stopped again by instrumentation, followed by the connection timeout. No production Accessibility policy failure is established by those logs.

## Fix and invariants

Both helpers now delete the secure setting when the requested component set is empty, retain the existing put operation for nonempty sets, and read back the actual list to assert equality. Other enabled services remain preserved. No production permission, service lifecycle or session behavior changes.

## Alternatives considered

Ignoring the later setup timeout would lose force-stop coverage. Increasing its timeout would not repair the malformed cleanup command. Clearing all device Accessibility settings would discard unrelated services.

## Regression verification

Original API 29/36 failures and the shell reproduction remain in `build/main-verification/current-automation-force-stop/`. Spotless, Detekt, module Debug Lint and the Android test APK build pass (`automation-empty-setting-gates.log`). The original sequence passes on API 29/36: service test 1/1, read-back cleanup with no fixture component, then the existing host setup/recovery script. Results are stored separately in `current-automation-cleanup-fixed/`. Subsequent system-log review found that the old host script issued force-stop only after instrumentation had already killed the setup process. That separate ordering gap was subsequently corrected and verified on both APIs; see the [host-kill ordering fix](2026-09-07-accessibility-host-kill-after-instrumentation-exit.md). The original cleanup results alone do not establish a live-session host kill.

## Residual risk

These are dedicated-emulator fixtures. Their settings mutations and host force-stop do not establish physical-device behavior or long-duration reliability.

## Related records

- [Main verification](../development/main-merged-verification.md)
- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
