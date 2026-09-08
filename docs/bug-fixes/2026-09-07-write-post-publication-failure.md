# Bug Fix: File write failure incorrectly denied an already published effect

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: tools/files; tools/framework documentation

## Problem

The write executor described every IOException as a write that was not performed. WorkspaceArtifactStore can throw after atomic publication while checking quota usage, probing content or recording metadata.

## Impact

An existing target may already contain the requested bytes. Reporting no write could mislead recovery and retry decisions.

## Root cause

One catch covered both admission and post-publication I/O without tracking the publication boundary.

## Fix and invariants

An unclassified IOException now requires review and asks the caller to verify the target before retrying. It remains sideEffectFree=false and never exposes the raw exception. Known precondition and quota rejection handling is unchanged. An internal publisher injection seam delegates to the existing store implementation in production.

## Alternatives considered

Treating every IOException as a confirmed pre-publication failure repeats the defect. Automatically repeating the write cannot resolve uncertainty about metadata or intervening target changes. The bounded correction preserves unknown effects explicitly.

## Regression verification

The regression performs a real store publication and then injects IOException. It verifies the published bytes, requiresReview=true, sideEffectFree=false, and absence of the false no-write statement and private exception text. Before the fix, write-after-publish-red-compiled.log records 14 passing tests and this one failing assertion. The earlier write-after-publish-red.log was a test compilation error, not a reproduced behavior failure.

After the fix, tools/files 110/110, tools/framework 150/150 and core/workspace 87/87 pass with no skips. Commands: ./gradlew spotlessApply :tools:files:test :tools:framework:test :core:workspace:test detekt; ./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug spotlessCheck lintDebug. Both commands exited 0. Evidence: build/main-verification/write-after-publish-green.log and write-after-publish-build.log. Unchanged related tasks may use Gradle up-to-date results; this is not a forced full-repository rerun.

## Residual risk

The injected exception follows actual publication but is not a device SIGKILL or a naturally occurring metadata failure. File-write process-death phases and broader recovery/UI acceptance remain pending.

## Related records

- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
