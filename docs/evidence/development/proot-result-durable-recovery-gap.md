> 证据快照：本页的状态、分工、命令结果和设备仅对应记录时点，不作为当前开发指令；当前入口为[实施状态](../../development/status.md)。

# PRoot successful-result recovery gap

Status: closed
Date: 2026-09-08
Related HXA: HXA-102

## Closure scope and evidence

The four required closure items below are implemented and verified within the documented test boundaries. This closes the successful-result recovery gap, not HXA-102 or the overall optimization Goal.

| Required item | Implementation and evidence |
| --- | --- |
| Non-destructive original-result retrieval | Optional fetch protocol, bounded PFD, original execution/input/terminal identity checks; client/IPC JVM, Android protocol tests and real cross-UID fetch/ACK. Evidence: proot-result-fetch-summary.json, proot-cross-uid-result-summary.json and current post-proot-full-host-result.json. |
| Durable private ownership before exact ACK | Session artifact registration and hash readback, rejection without ACK, cleanup failure/idempotence, local read with disabled Runtime. Evidence: proot-result-store-summary.json, proot-ack-summary.json, proot-recovery-ack-summary.json and proot-offline-summary.json. |
| Explicit result and confirmation UI | Production ChatScreen session selection, original call query/view/confirmation; normal and unresolved Goal paths preserve execution state/budget. Evidence: proot-result-navigation-summary.json and proot-normal-goal-summary.json. |
| Failure and recovery boundaries | Missing/tampered/expired evidence, partial transfer, queued/dead owners, pre/post ACK App SIGKILL with repeated full-page recovery. Evidence: proot-output-failures-summary.json, proot-owner-boundaries-summary.json, proot-expiry-report-jvm-summary.json and proot-ack-kill-navigation-summary.json. |

All evidence files are under build/main-verification; dated entries preserve the original scope and APK hashes. Lost ACK transport response is covered by an injected RemoteException; real SIGKILL covers loss before confirmation and after Runtime confirmation but before the recovery callback/UI receives it. These are complementary evidence, not a claim of intercepting a Binder kernel reply. The protocol outcome under an unknown reply is explicit unconfirmed local data plus exact-id retry. No background retry loop or replay is introduced.

General device regression, outer application navigation, layout/accessibility optimization, Goal completion-evidence policy and deferred physical/soak gates remain owned by the main checklist. Historical open statements below describe the state at their date and are superseded by this closure table.

## Initial source evidence before the dated implementation below

ProotJobRecovery checks the original execution/input binding and exposes query/stop. A successful terminal record only yields a status label. ProotJobClient exposes submit/query/cancel/reconcile; there is no result-fetch transaction. ProductionLinuxExecutor receives output through the PFD supplied at submission, validates its archive manifest, and optionally imports result.txt. Its randomly named per-call scratch directory is deleted in finally and is not part of the durable job binding. A process-killed scratch remnant is therefore not a supported recovery contract.

The Runtime retains output.zip in ProotJobStore. Its reconcile method writes reconciliation metadata and deletes non-record files; it does not establish that the App durably imported the archive. The existing query/stop matrix must not be described as successful-result import acceptance.

## Required closure within ADR-RUNTIME-001

1. Add bounded, original-identity, non-destructive archive retrieval. Verify the terminal record, execution/input binding and output manifest. Unknown or older protocol support must remain unavailable, without fallback to a destructive transaction or resubmission.
2. Persist verified bytes as a private session-owned artifact before an exact, idempotent acknowledgement. Include privacy deletion ownership and local readback when Runtime is unavailable. Deletion failure must not fabricate successful acknowledgement.
3. Expose explicit read-only result viewing from the interrupted call. Show stdout/stderr and verified artifact availability without running output code, completing the Goal, or automatically importing files into a user workspace. Any new workspace mutation must follow the existing Tool/Policy/Approval path.
4. Verify fetch/persist/ack process-kill boundaries, repeated recovery, missing/tampered archive, expiry and API29/36 production navigation. Distinguish seeded binding fixtures from complete Goal execution and real PRoot execution from synthetic archives.

## Initial correction

Expired terminal records previously reached the SUCCEEDED UI branch. The status mapper now prioritizes evidenceExpired and disables stop for that terminal record. This correction does not implement archive retrieval. Validation and remaining boundaries are recorded in HXA-102 and the main verification report.

## Related records

- [Runtime lifecycle](../../adr/runtime/001-execution-domains.md)
- [Historical optimization checklist](main-optimization-todo.md)
- [HXA-102](../../completion-records/HXA-102.md)

## Dated implementation evidence

The 2026-09-07–08 implementation, failure, correction and validation entries are retained once in [M10 closure follow-up](m10-closure-followup.md#2026-09-07-proot-非破坏性结果取回原语), through its final 2026-09-08 closure section. The duplicate body is consolidated there; the closure table and original gap above remain the bounded summary. Historical failures and candidate/device distinctions remain in the linked record, not rewritten as current acceptance.
