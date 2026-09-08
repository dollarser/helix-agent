# Bug Fix: Provider UI fixtures assumed all rows were editable

Status: fixed
Date: 2026-09-07
Related HXA: HXA-102
Affected modules: app Android UI tests

## Problem

Developer Provider discovery tests failed before their bodies with `managed provider cannot be deleted`. Their shared setup attempted to delete Runtime-managed subscription rows introduced by M11.

## Impact

Both API 29/36 developer core groups failed four discovery cases. Preserving those rows then exposed assumptions that the editable row was always visible and that generic control/status labels occurred only once.

## Root cause

The old `deleteAllProviders` helper predated managed Provider ownership. UI selectors and viewport assumptions also treated the settings list as a single-row fixture.

## Fix and invariants

Rename cleanup to `deleteEditableProviders`, filter by the existing `managedExternally` row property, and update all callers including SGLang/Ollama UI smoke compilation. Keep subscription rows intact. Provider discovery and flow tests scope controls/status text to the editable row and explicitly scroll to the target. Connection-test, model-list, failure, picker exclusion, edit/cancel and deletion assertions remain. Production deletion restrictions and Runtime credentials are unchanged.

## Alternatives considered

Deleting subscription rows directly through storage would bypass ownership. Hiding them in production or removing assertions would change the task or weaken verification. Assuming a specific row order would leave the tests fragile.

## Regression verification

Initial evidence: `build/main-verification/current-core-api29-developer/` and `current-core-api36-developer/`, each with 4 failures out of 87; consumer groups passed 87/87. Intermediate viewport and ambiguous-label failures remain in `current-provider-ui-api*/` and `current-provider-ui-scoped-api*/`.

Final targeted results are recorded per flavor/API under `build/main-verification/current-provider-ui-final-api*/`, including installed hashes and exact commands. API 29 consumer and developer each pass 5/5. API 36 consumer and developer also pass 5/5, giving 20/20 with no skips across four combinations. Full developer 87-test reruns remain separate from these five targeted cases.

JDK 17 `spotlessCheck detekt :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest` passes in `provider-row-final-gates.log`. The earlier compilation failure from missed developer helper callers is retained in `provider-test-cleanup-gates.log`; all references were then updated.

## Residual risk

SGLang/Ollama UI callers compile with the new helper; their real-service UI behavior was not exercised by this targeted group. Managed account calls remain outside this fixture. Other outstanding issues include the independent QuickJS deep-JSON stack overflow and Accessibility stale-token evaluation failure.

## Related records

- [Main verification](../development/main-merged-verification.md)
- [Current TODO](../development/main-optimization-todo.md)
- [HXA-102](../completion-records/HXA-102.md)
