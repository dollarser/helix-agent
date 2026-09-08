# Bug Fix: Acknowledged CLI journal records exhausted capacity

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: CLI Runtime journal retention

## Problem

A Runtime that had accumulated 128 completed Job directories rejected new requests even when their results had already been acknowledged. Both dedicated API29/36 emulators reached this limit during Goal recovery verification.

## Impact

Provider connection probes and new subscription requests failed permanently until journal storage was manually cleared. Clearing all journal records would destroy pending recovery evidence.

## Root cause

CodexModelJobStore.canAcceptNew counted every retained directory against the 128-entry limit. Acknowledgement deleted payloads but retained metadata indefinitely; there was no acknowledged-record expiry or capacity reclamation.

## Fix and invariants

Before admission and startup recovery, reclaim only valid terminal records carrying a reconciliation receipt. Remove receipts older than seven days, or the oldest acknowledged records when the entry/metadata budget needs space. Active, unacknowledged and undecodable records remain protected and count against capacity. A failed deletion fails closed. This implements bounded acknowledged tombstones under ADR-0007; seven days is a maximum, not a guaranteed minimum retention period.

## Alternatives considered

Increasing the cap merely postpones exhaustion. Removing all terminal records could discard unread results. Clearing application data would also affect unrelated account configuration and was not used.

## Regression verification

CodexJournalRetentionTest first produced two assertion failures with valid record fixtures, then passed after the fix. It covers oldest acknowledged eviction at capacity and expired acknowledged cleanup while preserving fresh and unacknowledged records. The existing full-unacknowledged-cap rejection test also passes. CLI Runtime104/104 and Client30/30 JVM tests passed, zero skips, with forced execution; Runtime build, root lintDebug, Spotless and Detekt passed. Logs: build/main-verification/cli-journal-retention-valid-red.log and cli-journal-retention-green.log. An earlier invalid test fixture failure is retained separately in cli-journal-retention-red.log.

After installing the fixed Runtime on both previously full emulators, all four adapters completed production Goal successful-result recovery: 8 host SIGKILLs and16 recoveries. The running CODEX branch additionally passed2 kills/4 recoveries. Evidence: build/main-verification/cli-goal-success-summary.json; installed APK hashes are included. Models are debug synthetic fixtures, with no paid-account or remote-model invocation.

## Residual risk

A journal containing only active or unacknowledged records can still refuse admission by design. Thirty-day unacknowledged evidence-expired markers remain a separate implementation follow-up; this fix never silently deletes those records. Old acknowledged Job IDs may be absent after reclamation, and clients must not resubmit them automatically. No physical-device or long-soak claim is made.

## Related records

- [Runtime lifecycle](../adr/0007-companion-runtime-lifecycle.md)
- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
