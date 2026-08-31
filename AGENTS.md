# Helix Agent Instructions

## Scope

Implement only the Android single-device Helix plan documented in `docs/`. Remote workers, cloud sandboxes, desktop pairing, HarmonyOS, ADB/Shizuku, payments, and autonomous outbound messaging are out of scope. Accessibility automation, all-files access, and Root are in scope only through `docs/09-android-platform-capabilities.md`.

## Required reading

Before editing, read `README.md`, `docs/implementation-status.md`, the current HXA task in `docs/04-roadmap-and-backlog.md`, and only the task-relevant architecture/security documents. A continuing coding agent also reads `docs/small-model-handoff.md`. If the task meets an ADR trigger in `docs/adr/README.md` or changes an existing decision, read that convention and the related ADRs. Do not infer that planned or accepted work is implemented; inspect the repository and tests.

## Architecture rules

- The model may request tools but never grants permission.
- Every tool call goes through schema validation, policy, approval when required, execution limits, verification, and audit.
- Android system permission, Accessibility connection, Root grant, MCP annotation, or Skill instruction never replaces per-call policy.
- Plan mode is read-only. Goal mode is persistent but does not weaken approval or risk rules.
- Subscription credentials remain owned by official CLIs in an isolated CLI Runtime; never extract cookies or OAuth tokens.
- Generated code never runs in the main app process.
- QuickJS must run in a non-exported Android isolated process with no privileged host bridge.
- PRoot and CLI backends run in separately signed Runtime application/UIDs, not a secondary process of the main app. Exchange bounded snapshots over signature-protected Binder/PFD IPC.
- UI does not access DAO, OkHttp, QuickJS, or PRoot directly.
- WebView is owned by the browser feature. Never register a privileged permanent JavaScript bridge on untrusted pages.
- `read`, `write`, `edit`, and `bash` are short public tool names backed by the same scoped implementations and Policy as namespaced tools.
- Core modules do not depend on Android UI or infrastructure modules.
- Do not add Hilt, LangChain-style frameworks, unscoped storage access, arbitrary Maven repositories, or mutable dependency versions.
- Do not add remote/Harmony placeholder modules.

## Task discipline

- Work on one HXA task at a time.
- A persistent development goal may span a milestone, but it keeps only one HXA checkpoint in progress. After a checkpoint passes, update its completion record and continue to the next task without asking for routine confirmation.
- Keep edits inside the task's allowed modules.
- Do not upgrade dependencies unless the task explicitly requires it.
- Add tests for failure, cancellation, boundaries, and recovery as applicable.
- Never delete or skip tests to make a task pass.
- Do not return success from catch-all exception handlers.
- No placeholder implementations or unresolved TODOs in accepted work.
- Follow `docs/adr/README.md` for qualifying decisions. A small model defaults new ADRs to `proposed`; `accepted` is not implementation evidence, and changing an accepted decision requires an explicit superseding ADR and authorization.
- Make reasonable, reversible implementation choices inside an approved HXA. Pause only for a real architecture/security decision, missing authority, external dependency, or evidence-backed blocker.

## Third-party code

Reference repositories listed in `docs/06-open-source-references.md` are evidence and design references, not a source to copy. Do not copy AGPL/GPL/CPAL/MPL code from reference agents, browsers, file managers, or automation apps unless the task explicitly adopts the license and records the decision. Preserve all license and source obligations for bundled QuickJS, PRoot, Termux-derived libraries, RootFS packages, and official CLI artifacts.

## Verification

Run the exact commands listed by the HXA task. Report the commands and actual results. Build success alone is not functional or security acceptance. Never commit secrets, real user data, machine-specific absolute paths, downloaded RootFS content, or signing material.
