---
name: helix-contract-change-review
description: "Review Helix changes to identity, authorization, lifecycle, paths, results or plugin contribution contracts across their consumers. Use for design or implementation reviews and refactor impact; not for unrelated UI copy or formatting."
---

# Helix Contract Change Review

Follow the changed contract from its production entry to affected consumers. Check the applicable Helix failure cases:

- **Persistence and identity.** After retry, process death or reconnect, does Room still identify the original execution? Can a stale revision, generation or callback overwrite newer state?
- **Authorization.** Recheck current scope and the exact execution binding after an approval wait. Trace delegated scripts and background display input back to their original session, Turn, call and target. See [permissions](../../../docs/adr/permissions/001-session-authorization.md) and [Jobs](../../../docs/adr/runtime/002-terminal-and-jobs.md).
- **Paths and capacity.** Can a shortened path or changed cwd retarget an in-flight call? Can cleanup follow a link outside scope or cancellation free a slot before its executor exits?
- **Effects and results.** Trace interruption before dispatch, after an external effect but before durable commit, and after commit but before notification. Preserve UNKNOWN for uncertain effects, original-call-order backfill and the distinction between artifact publication and model consumption.
- **Plugins and channels.** Verify the plugin owns its contribution, the host governs execution, and a channel-specific restriction stays local to that channel. Check Standard's supported path.
