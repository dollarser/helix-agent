---
name: helix-contract-change-review
description: "Review Helix changes to identity, authorization, lifecycle, paths, results or plugin contribution contracts across their consumers. Use for design or implementation reviews and refactor impact; not for unrelated UI copy or formatting."
---

# Helix Contract Change Review

Review the ongoing task's actual diff and affected consumers. Its compaction summary carries continuity; repository plans do not restart old work. Relevant accepted ADRs define contracts, while [AGENTS.md](../../../AGENTS.md) retains project boundaries. Preserve parallel edits and unrelated data. Apply current AGENTS pre-launch test-data policy without adding legacy compatibility; replacement does not itself authorize device use or clearing.

## Review relevant shared assumptions

- **Identity and persistence:** Which durable record is authoritative? Do retries, cancellation, process death and recovery preserve original execution identity and uncertainty? Do expected revisions and generation checks prevent stale writes or late callbacks from overwriting newer state?
- **Permissions:** Where do schema validation, policy, authorization, limits and audit apply? Are current permissions and the exact execution binding rechecked at execution-start, including changes during approval waits? Does new discovery, UI, extension output or recovery accidentally grant scope or bypass a boundary?
- **Resources and paths:** Does a cwd or short path accidentally replace the user-authorized scope? Are resource identity, content version, display label and execution mapping distinct? Does cleanup follow links or release capacity before physical execution exits?
- **Execution identity:** Do script delegation and background display observation/input retain the original session, Turn, call, authorization and target identity through cancellation/reconnection? Review the applicable [Jobs](../../../docs/adr/runtime/002-terminal-and-jobs.md) and [permissions](../../../docs/adr/permissions/001-session-authorization.md) contracts when affected; extension metadata never grants authority.
- **Results:** Do execution, artifact publication, persistence, context backfill and model consumption retain truthful outcomes and original call order? Check interruption before execution, after effects but before durable commit, and after commit but before notification: transactions cannot roll back external effects, notification failure cannot undo a commit, and recovery must not replay uncertain effects. Is UNKNOWN being misreported as failure, success or rollback?
- **Plugins and channels:** Does the plugin own its contribution while the host owns common governance? Does Standard retain its supported product behavior? Do UI and core layers preserve their accepted dependencies?

Use direct source/search or existing [navigation tools](../../../docs/development/context-navigation.md) as useful. Review only relevant assumptions and tests; a mock does not establish a missing production path. Replace obsolete implementation assertions with accepted behavior coverage while retaining permission, failure and recovery boundaries.

For substantial design choices, primary documentation/current source can inform the applicable ADR/HXA; research does not authorize scope expansion or code copying. Review-only work reports findings without changing code. The [refactor strategy](../../../docs/development/feature-refactor-strategy.md) and [verification reference](../../../docs/development/verification-matrix.md) are optional aids, not a fixed workflow.

Distinguish unchanged inherited evidence, intentionally extended behavior and fresh checks; preserve provenance in ignored receipts. Report concrete triggers, locations, impacts, corrections and evidence, including uncertainty and untested conditions. Update the same feature ADR when its contract changes; distinguish accepted design from implementation and validation. This skill grants no device, account, deletion or publication authority.
