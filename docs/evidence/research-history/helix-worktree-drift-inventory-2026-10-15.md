# Helix worktree drift inventory — 2026-10-15

This inventory records the pre-existing parallel documentation drift found while closing the Helix Docs preview. The baseline at review start had 37 modified tracked files and four untracked documents, alongside the Helix Docs feature work. The pre-existing changes were not normalized, reverted, staged, or deleted.

## Classification

| Area | Paths present in the worktree | Classification and handling |
| --- | --- | --- |
| Architecture and planning | `docs/architecture/README.md`, `harness-refactor-plan.md`, `overview.md`, `plugin-platform-plan.md`; `docs/development/candidate-decisions.md`, `roadmap.md`, `status.md` | Broad parallel plan/history edits. `roadmap.md` and `status.md` overlap with the preview's small Helix Docs notes. Their full-file changes remain unstaged; this task does not collapse or rebase the parallel material. |
| Research synthesis | `docs/research/modules/01-architecture-and-execution-engine.md`, `02-context-input-and-session.md`, `04-tools-browser-and-extensions.md`; `docs/research/topics/README.md`, `agent-plugin-architecture-research-2026-10-11.md`, `helix-plugin-architecture-optimization-2026-10-11.md`, `mobile-use-device-readiness-and-reliability-2026-09-29.md` | Cross-task research updates, retained without interpretation or edits. |
| Historical evidence and completion indexes | `docs/README.md`, `docs/adr/README.md`, `docs/completion-records/HXA-185.md`, `docs/evidence/development/` dated HXA-210/227/241/245 and review records, `docs/evidence/research-history/README.md` | Historical and index drift from parallel work. These files were preserved as found. |
| Untracked research drafts | `docs/evidence/research-history/architecture-evolution-2026-10-14.md`, `docs/research/topics/computer-use-call-path-2026-10-12.md`, `context-optimization-2026-10-14.md` | Existing parallel drafts; not promoted, rewritten, or staged by this task. |
| Helix Docs owned slice | `docs/development/release-readiness.md`, `docs/product/README.md`, and the pre-existing `helix-docs-design-2026-10-14.md` draft | The release-readiness caveat and product index note describe this feature. The design draft was retained and its implementation record preserved. Its status/roadmap overlap remains outside a broad documentation commit. |

## Dated debug-script archive

There were 131 untracked scripts under `scripts/debug/`, dated 2026-09-30 through 2026-10-12. They are unrelated to this feature and their content/ownership was not assessed. Originals remain at their existing paths. For recoverability, a byte-for-byte duplicate archive was created at the ignored local path `build/debug-archive-2026-10-15/untracked-debug-scripts.tar.gz`; its SHA-256 is `0931c9265b5f4b8f1813628d8d4e2a2f076b7b3657e12625cd827634753a07d0`. The tracked inventory `scripts/debug/archive/2026-10-15-worktree-inventory.json` records each path, size, date directory, archive location and archive checksum. Nothing was permanently deleted.

## Commit boundary

Only Helix Docs implementation and narrowly owned acceptance files belong in the feature commit. The inventory and archive manifest can be committed separately. Broad roadmap/status/research history, unrelated dated scripts, and the ignored archive payload stay out of those commits. The conflicting shared documentation files remain visibly dirty so their parallel owner can reconcile them later.
