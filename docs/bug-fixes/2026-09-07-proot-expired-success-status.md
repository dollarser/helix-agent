# Bug Fix: Expired PRoot success was presented as recoverable success

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: PRoot interrupted-job status mapping

## Problem

ProotJobRecovery selected its success label solely from SUCCEEDED, ignoring evidenceExpired. The retained terminal metadata can describe a successful execution whose payload has already expired.

## Impact

An interrupted task could display a success-oriented recovery status even though the Runtime no longer held its result evidence.

## Root cause

The UI mapping did not give evidence-expired bookkeeping precedence over the historical execution state.

## Fix and invariants

Extract the existing status mapping and prioritize evidenceExpired. The result is an explicit expired-result explanation without a stop action. Query still uses the original execution and input binding; no resubmission, reconciliation, file import or Goal completion is added. Chinese and English strings are included.

## Alternatives considered

Reclassifying the historical execution as failed would erase the distinction between execution outcome and evidence retention. Automatically retrying would create a new execution. The corrected label describes missing evidence while retaining the existing record.

## Regression verification

ProotRecoveryReportTest constructs a valid SUCCEEDED record with evidenceExpired=true and asserts the expired label and disabled stop. It failed against the old mapping and passed after the precedence correction. The red log is build/main-verification/proot-expiry-report-red.log. The full related test and build outcomes are recorded in HXA-102, including any external Connector failure and rerun.

## Residual risk

This corrects status reporting only. PRoot archive fetch, durable private import, acknowledgement and successful-result UI remain open. The mapping is JVM-verified; actual expired-record navigation remains a device follow-up.

## Related records

- [Result recovery gap](../development/proot-result-durable-recovery-gap.md)
- [Runtime lifecycle](../adr/0007-companion-runtime-lifecycle.md)
- [HXA-102](../completion-records/HXA-102.md)
