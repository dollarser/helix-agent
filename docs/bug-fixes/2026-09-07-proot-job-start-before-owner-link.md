# Bug Fix: PRoot job could start before owner death registration

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: PRoot owned submission

## Problem

Owned submission queued the job before registering its owner Binder death listener.

## Impact

A command could run even when linkToDeath then reported that its owner was already dead.

## Root cause

submitOwned called the legacy submit method first. That method started asynchronous execution before owned submission established its lifecycle cancellation source.

## Fix and invariants

A shared synchronized admission path performs duplicate/budget checks, writes the original pending record, installs the cancellation flag and owner listener, and only then queues execution. Already-dead registration therefore marks the pending job cancelled before command launch. Duplicate submissions retain their original owner. Legacy callers continue through the same admission path without an owner.

## Alternatives considered

A separate isBinderAlive check races with subsequent death. Registering after queueing and then cancelling permits effects before cancellation. Registering before creating the pending identity would make the cancellation callback target an unknown job.

## Regression verification

An injected owner delays link registration then throws RemoteException. Before the fix API29 returned SUCCEEDED instead of CANCELLED. After the fix API29/36 each passed all 12 runner tests, including CANCELLED and absence of the command-created file for this owner. Evidence: build/main-verification/proot-dead-owner-red-api29.log and proot-dead-owner-emulator-5596.log / proot-dead-owner-emulator-5598.log. Runtime/test builds, Spotless, Detekt and root lintDebug passed in proot-dead-owner-build.log.

## Residual risk

The dead-at-registration test uses a controlled Binder fixture and real runner execution, not a separate caller process. Real running-owner death has its own SIGKILL coverage. Queued-owner death and release/duplicate race coverage remain separate checks.

## Related records

- [Owner death handling](2026-09-07-proot-owner-death-not-cancelled.md)
- [Runtime lifecycle](../adr/0007-companion-runtime-lifecycle.md)
- [Result recovery gap](../development/proot-result-durable-recovery-gap.md)
