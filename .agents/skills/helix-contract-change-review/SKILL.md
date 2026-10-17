---
name: helix-contract-change-review
description: "Review Helix changes to identity, authorization, lifecycle, paths, results or plugin contribution contracts across their consumers. Use for design or implementation reviews and refactor impact; not for unrelated UI copy or formatting."
---

# Helix Contract Change Review

## Establish the target contract

Work from the repository root. Read applicable [AGENTS.md](../../../AGENTS.md), the current work in [status](../../../docs/development/status.md), the owner request/HXA, and the relevant accepted topic ADR, including its Decision history. The [implementation guide](../../../docs/development/implementation-guide.md) defines authority; the [refactor strategy](../../../docs/development/feature-refactor-strategy.md) distinguishes accepted work from future proposals.

Identify the intended user-visible outcome and the actual diff. Capture branch/HEAD and preserve pre-existing work. Assess the best current target and future extension cost; do not retain an obsolete interface, alias, dual write or implementation solely for compatibility. Removing compatibility does not authorize deleting user data or losing supported capabilities.

## Trace the shared assumptions

Use one applicable existing CodeGraph or configured GitNexus entry as described in [navigation](../../../docs/development/context-navigation.md); confirm candidates against current source. For a known small change, direct source reading and scoped `rg` suffice. Do not build another parser or index.

Locate the authoritative state owner and follow affected producers and consumers. Select the relevant questions, rather than requiring every topic for every change:

- **Identity and persistence:** Which durable record is authoritative? Do retries, cancellation, process death and recovery preserve original execution identity and uncertainty?
- **Permissions:** Where do schema validation, policy, authorization, limits and audit apply? Does new discovery, UI, extension output or recovery accidentally grant scope or bypass a boundary?
- **Resources and paths:** Are resource identity, content version, display label and execution mapping distinct? Does cleanup follow links or release capacity before physical execution exits?
- **Results:** Do execution, artifact publication, persistence, context backfill and model consumption retain truthful outcomes and original call order? Is UNKNOWN being misreported as failure, success or rollback?
- **Plugins and channels:** Does the plugin own its contribution while the host owns common governance? Does Standard retain its supported product behavior? Do UI and core layers preserve their accepted dependencies?

Check tests and fixtures for the same assumptions. Replace assertions tied only to a deliberately removed design with tests for the accepted contract; retain failure, boundary and recovery coverage. A fake consumer or passing mock cannot establish a missing production path.

## Decide and verify within scope

For a new design choice, consult relevant current primary documentation/source where practical and record adopted behavior and tradeoffs in the existing topic ADR/HXA. Research is evidence, not authorization or permission to copy reference code. Distinguish adopted contracts, implemented behavior and future plans.

When implementation is requested, finish the authorized vertical path and remove its replaced implementation. For review-only requests, report findings without silently changing code. Stop dependent implementation only for a concrete unresolved architecture/authority decision or external blocker, while completing independent checks. Do not introduce speculative remote services, generic frameworks or unrelated features.

Choose tests that exercise the changed boundary through real consumers; use the [verification matrix](../../../docs/development/verification-matrix.md) and current HXA for mandatory gates. This skill does not grant device, account, deletion or Git publication authority. Keep host, compilation, device and real-service evidence separate.

## Report a reviewable result

Lead each finding with trigger, location, impact and recommended correction; state observed evidence and uncertainty. Report fixed paths, deleted obsolete paths, remaining consumers and verification results. Amend the same feature ADR and append Decision history when its accepted contract changes; update the relevant HXA/refactor plan only for actual remaining work. Keep temporary inventories under ignored `build/<task>/`, not in permanent architecture documents. If no defect is found, state the reviewed scope and untested boundaries.
