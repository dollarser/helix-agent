# Bug Fix: Interrupted turns displayed an unactionable approval wait

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: app chat timeline and recovery device tests

## Problem

After main-process death while awaiting approval, recovery terminalized the Turn as INTERRUPTED, but the persisted tool timeline still displayed Awaiting approval. Approval cards exist only in live memory, so the reopened session offered no corresponding approval action.

## Impact

The historical tool row suggested that the user should approve a call whose dispatcher no longer existed. The interrupted Turn and its tool row gave conflicting status information.

## Root cause

ChatService.toolTimelineFor derived its persisted state label solely from tool_calls.state. Recovery deliberately preserves undecided approvals and AWAITING_APPROVAL calls as historical facts; the display did not account for the owning Turn being INTERRUPTED.

## Fix and invariants

For an INTERRUPTED Turn with an AWAITING_APPROVAL call, the historical timeline now uses the existing localized Interrupted label. All other states keep their existing mapping. No approval decision, proof, ToolCall state, budget, recovery action or database schema is changed. The application does not reconstruct a runnable dispatcher or imply that approval resumes a terminated Turn.

## Alternatives considered

Changing the stored call to DENIED would invent a user decision. Recreating an approval card would imply a live execution path that does not exist. Keeping Awaiting approval with no action leaves the user stranded. The display therefore reflects the enclosing interrupted execution while retaining the underlying record.

## Regression verification

JavascriptProcessKillDeviceTest now opens the recovered production session and asserts the interrupted label and absent live card, while still asserting AWAITING_APPROVAL in storage. API 29 and 36 each passed actual approval-wait SIGKILL plus three recovery phases, including explicit service-level denial and a subsequent process restart. Logs and installed APK hashes are under build/main-verification/recovered-approval-label-emulator-5596/ and recovered-approval-label-emulator-5598/. These are production screen-state assertions, not screenshot or UI-tap acceptance.

Normal ApprovalFlowDeviceTest also passed 3/3 on each device: profile switching while pending, denial without another prompt, and stop while pending. Consumer JVM 295/295 and developer JVM 307/307 passed with Connector sample conditions supplied, zero skips. Root lintDebug, Spotless, Detekt and both debug APK builds passed. Evidence: recovered-approval-label-host.log, recovered-approval-label-jvm.json, recovered-approval-label-build.log and recovered-approval-live-emulator-*.log.

## Residual risk

This is a historical status-display correction. It does not implement approving or continuing interrupted calls, nor does it close the remaining HXA-102 backend matrix or physical/soak gates. Prior broader evaluation snapshots used the previous production APK and remain labeled as such.

## Related records

- [Main verification](../development/main-merged-verification.md)
- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
