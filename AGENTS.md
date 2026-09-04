# Helix Agent Instructions

## Scope

Implement the Android single-device Helix plan documented in `docs/`, with a complete store-facing Standard, developer/Advanced capability, and task completion as product priorities. Remote workers, cloud sandboxes, desktop pairing, HarmonyOS, payments, and autonomous outbound messaging remain outside the current plan. M7 may implement the client-only A2A interoperability defined in `docs/architecture/provider-mcp-skills-modes.md`: a user-configured remote A2A Agent is an external service, not a Helix execution target or remote Worker, and cannot inherit local capability, approval, Secret, or verifier authority. A2A Server hosting, recursive multi-Agent orchestration, arbitrary peer messaging, webhook hosting, and remote-to-local Tool invocation are outside M7, not declared permanently impossible; promote them only through an explicit future roadmap/ADR update with Android reachability, foreground/background lifecycle, identity, recovery, budget, and device-test boundaries. ADB/Shizuku are likewise unscheduled future capabilities: do not implement them inside an unrelated HXA, but the project owner may promote them into scope through an explicit roadmap/ADR update with Android feasibility, lifecycle, dependency, and device-test boundaries. Accessibility automation, all-files access, Root, Tasker interoperability, and Auto.js-compatible execution follow `docs/architecture/android-platform-capabilities.md` and the active roadmap.

## Required reading

Before editing, read `README.md`, `docs/development/status.md`, the current HXA task in `docs/development/roadmap.md`, and only the task-relevant architecture/security documents. If `In progress` is non-empty, continue that HXA; otherwise use `Next task`. Never repeat an HXA that already has a completion record. If the task meets an ADR trigger in `docs/adr/README.md` or changes an existing decision, read that convention and the related ADRs. Safety Profile/Provider/Policy/PRoot or persistent-grant work reads accepted `docs/adr/0012-capability-first-advanced-grants.md` and superseded `docs/adr/0005-standard-advanced-safety-profiles.md`; A2A protocol/module/task work reads `docs/architecture/provider-mcp-skills-modes.md` and accepted `docs/adr/0016-a2a-client-interoperability.md`: HXA-077 remains the mandatory SDK/transport/Android Spike, and HXA-078/079 cannot start until it records a viable implementation choice; variant/distribution/applicationId/store work reads accepted `docs/adr/0013-standard-store-capability-preserving-distribution.md` and superseded `docs/adr/0006-single-direct-main-package.md`; PRoot/CLI IPC, lifecycle or background execution reads `docs/adr/0007-companion-runtime-lifecycle.md`; Git Workspace/UI/transport work reads proposed `docs/adr/0008-git-workspace-management.md`; child-agent/workflow/cloud-task work reads `docs/architecture/mobile-tool-orchestration.md` and proposed `docs/adr/0009-bounded-local-orchestration.md`. Proposed ADRs must not be treated as accepted. Goal run/wake, PAUSED reason, crash recovery or Goal budget accounting work must read accepted `docs/adr/0004-goal-run-wake-budget-semantics.md`; changing it requires an authorized superseding ADR. Do not infer that planned or accepted work is implemented; inspect the repository and tests.

## Architecture rules

- Treat Standard as the complete store-facing product, not a safety-reduced edition. Preserve every capability allowed by the target channel; remove or replace a capability only for a current, cited platform/store constraint or failed review, and keep that difference local to the affected channel.
- The model may request tools and propose a reusable rule, but only user actions or previously user-created rules grant capability/scope or approval.
- Every tool call goes through schema validation, policy, approval resolution when required, execution limits, verification, and audit. “Approval resolution” does not require a dialog for every call: L0, eligible L1, Trusted Workspace rules, bounded long-term rules, and exact pre-approved batches may resolve automatically under ADR-0012.
- Tool concurrency is platform-decided from normalized effect footprints, never model/MCP/A2A/Skill self-declaration. Only proven non-conflicting reads may run in parallel; results enter model context in original call sequence, and cancel/recovery leaves durable outcomes for every queued call.
- Android system permission, Accessibility connection, Root/Shizuku grant, or ADB pairing satisfies only the corresponding Capability gate. Do not repeatedly ask for the same live system grant, but continue evaluating tool Policy and any approval requirement; MCP annotation and Skill/script instructions are untrusted hints.
- Plan mode is read-only. Goal mode is persistent and may reuse valid user-created scopes/rules and exact approved batches; persistence does not expand those bindings or mint new approval.
- Subscription credentials remain owned by official CLIs in an isolated CLI Runtime; never extract cookies or OAuth tokens.
- Generated code never runs in the main app process.
- “Local execution” means on the phone, not necessarily in the main process and not a VM. QuickJS relies on an isolated UID; PRoot/CLI rely on separate APK/UID boundaries. Do not describe either as kernel virtualization or a remote worker.
- QuickJS must run in a non-exported Android isolated process with no privileged host bridge.
- PRoot and CLI backends run in separately signed Runtime application/UIDs, not a secondary process of the main app. Exchange bounded snapshots over signature-protected Binder/PFD IPC.
- Follow ADR-0012's capability-first authorization model. Trusted Workspace and bounded long-term rules may auto-run L0 and calls whose current dynamic risk remains L1; `Full Workspace Access` may widen only the user-selected file roots. A user may approve a finite, fully disclosed L2/L3 batch in one interaction, with a separate exact one-time proof for each call. Generic L2/L3 remains bound to exact ToolCalls: do not let a model, MCP, A2A Agent, Skill, webpage, or script mint approval, and do not interpret Advanced, Android permission, Root/Shizuku/ADB state, or an egress rule as a global Approval Proof. Only typed `APPROVED` is consumable; `DENIED` is audit-only.
- Optimize Advanced for capability and low friction. Prefer stable scopes, reusable low-risk rules, batch review, clear recovery, and user responsibility over repeated warnings. Do not add stricter prompts, denials, or security work that is unrelated to the current HXA or unsupported by the accepted ADRs.
- A Git binary in PRoot is job-local and offline until HXA-088/ADR-0008 decides persistent repository ownership. Do not add Git UI, import partial `.git` state, remote Git, hooks, or credential flows early.
- Child agents and declarative workflows are not implemented. HXA-105 may evaluate only the bounded read-only design in proposed ADR-0009; do not add recursive agents, peer communication, child approvals/write tools, executable workflow/policy DSL, self-modifying plugins, cloud tasks, or deferred network approval. M7 A2A Client calls are ordinary network-backed ToolCalls to user-enabled external agents, not child-agent execution: remote output is untrusted, remote task state is persisted, and any follow-on local effect must return through the normal Dispatcher/Policy/Approval path.
- PRoot/CLI companions are cold-bound only for a user-triggered verification/repair/login or an approved Job. App startup, switching Advanced, or passive Registry refresh never starts them; Binder loss is reconciled by job ID and never causes blind replay.
- UI does not access DAO, OkHttp, QuickJS, or PRoot directly.
- WebView is owned by the browser feature. Never register a privileged permanent JavaScript bridge on untrusted pages.
- `read`, `write`, `edit`, and `bash` are short public tool names backed by the same scoped implementations and Policy as namespaced tools.
- Core modules do not depend on Android UI or infrastructure modules.
- Do not add Hilt, LangChain-style frameworks, unscoped storage access, arbitrary Maven repositories, or mutable dependency versions.
- Do not add remote/Harmony placeholder modules.

## Task discipline

- Work on one HXA task at a time.
- Create a persistent development Goal only when the user explicitly requests one. Derive its objective and checkpoints from the authorized scope, `docs/development/status.md`, and the roadmap; do not hard-code historical milestone state.
- A persistent development goal may span a milestone, but it keeps only one HXA checkpoint in progress. After a checkpoint passes, update its completion record and continue to the next task without asking for routine confirmation.
- Keep edits inside the task's allowed modules.
- Do not upgrade dependencies unless the task explicitly requires it.
- Add tests for failure, cancellation, boundaries, and recovery as applicable.
- Never delete or skip tests to make a task pass.
- Do not return success from catch-all exception handlers.
- No placeholder implementations or unresolved TODOs in accepted work.
- Follow `docs/adr/README.md` for qualifying decisions. A small model defaults new ADRs to `proposed`; `accepted` is not implementation evidence, and changing an accepted decision requires an explicit superseding ADR and authorization.
- Make reasonable, reversible implementation choices inside an approved HXA. Prefer delivering usable capability over speculative hardening, and treat the informed Advanced user as responsible for explicitly enabled capabilities, selected scopes, backups, and approved effects. Pause only for a real architecture decision, missing authority, external dependency, irreversible data-loss risk, or evidence-backed blocker.

## Third-party code

Reference repositories listed in `docs/references/open-source-projects.md` are evidence and design references, not a source to copy. Do not copy AGPL/GPL/CPAL/MPL code from reference agents, browsers, file managers, or automation apps unless the task explicitly adopts the license and records the decision. Preserve all license and source obligations for bundled QuickJS, PRoot, Termux-derived libraries, RootFS packages, and official CLI artifacts.

## Verification

Run the exact commands listed by the HXA task. Report the commands and actual results. Build success alone is not functional or security acceptance. Never commit secrets, real user data, machine-specific absolute paths, downloaded RootFS content, or signing material.
