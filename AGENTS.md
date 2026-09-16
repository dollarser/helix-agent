# Helix Agent Instructions

## Scope

Implement the Android single-device Helix plan documented in `docs/`, with a complete store-facing Standard, developer/Advanced capability, and task completion as product priorities. Remote workers, cloud sandboxes, desktop pairing, HarmonyOS, payments, and autonomous outbound messaging remain outside the current plan. M7 may implement the client-only A2A interoperability defined in `docs/architecture/extensions.md`: a user-configured remote A2A Agent is an external service, not a Helix execution target or remote Worker, and cannot inherit local capability, approval, Secret, or verifier authority. A2A Server hosting, recursive multi-Agent orchestration, arbitrary peer messaging, webhook hosting, and remote-to-local Tool invocation are outside M7, not declared permanently impossible; promote them only through an explicit future roadmap/ADR update with Android reachability, foreground/background lifecycle, identity, recovery, budget, and device-test boundaries. ADB/Shizuku are likewise unscheduled future capabilities: do not implement them inside an unrelated HXA, but the project owner may promote them into scope through an explicit roadmap/ADR update with Android feasibility, lifecycle, dependency, and device-test boundaries. Accessibility automation, all-files access, Root, Tasker interoperability, and Auto.js-compatible execution follow `docs/architecture/android-platform-capabilities.md` and the active roadmap.

## Required reading

Read `README.md`, `docs/development/status.md`, the current HXA in `docs/development/roadmap.md`, and the relevant topic under `docs/adr/README.md`. Current ADRs use topic-local identifiers. Old decisions live on v0.0.1, not in a compatibility chain. Do not restart completed HXA work or restore obsolete behavior.

Authorization work reads `docs/adr/permissions/README.md`; HXA-209 implements the accepted session presets, CUSTOM effects and enabled/disabled tools. Goal work reads `docs/adr/goal/README.md`; Runtime and terminal work reads `docs/adr/runtime/README.md`. Workspace binding remains proposed under `docs/adr/workspace/README.md`. Use the Provider, MCP, Skill, Connector, A2A, Agent and platform topic entries for their respective contracts. Accepted design is not implementation evidence: check current source and HXA records. Owner-authorized migrations may intentionally replace existing behavior; do not pause solely because implementation lags the accepted design.

## Architecture rules

- Treat Standard as the complete store-facing product, not a safety-reduced edition. Preserve every capability allowed by the target channel; remove or replace a capability only for a current, cited platform/store constraint or failed review, and keep that difference local to the affected channel.
- The model may request tools and propose a reusable rule, but only user actions or previously user-created rules grant capability/scope or approval.
- Every tool call goes through schema validation, capability/policy, authorization resolution, execution limits, verification and audit. Authorization resolution does not require a dialog for every call: accepted session presets and CUSTOM determine automatic resolution, confirmation or denial. Execution and audit follow ADR-PERMISSIONS-003; implementation of the new presets remains HXA-209 work.
- HXA-209 uses the session authorization decision: tools are ENABLED/DISABLED, operations are resolved by presets or CUSTOM, and model output never grants permission. Older HXA evidence is not acceptance of this new behavior.
- Tool concurrency is platform-decided from normalized effect footprints, never model/MCP/A2A/Skill self-declaration. Only proven non-conflicting reads may run in parallel; results enter model context in original call sequence, and cancel/recovery leaves durable outcomes for every queued call.
- Android system permission, Accessibility connection, Root/Shizuku grant, or ADB pairing satisfies only the corresponding Capability gate. Do not repeatedly ask for the same live system grant, but continue evaluating tool Policy and any approval requirement; MCP annotation and Skill/script instructions are untrusted hints.
- Plan mode is read-only for user files and external effects; accepted ADR-PERMISSIONS-002 additionally admits closed built-in METADATA operations bound to the current session/Turn, subject to its production integration gates. Ordinary research may finish as text. Goal persistence never expands user scopes or mints approval.
- Under accepted ADR-RUNTIME-001, subscription credentials are owned by the developer subscription module in its private process, sharing the host UID. Official CLI ownership is preferred but currently infeasible on Android; accepted ADR-PROVIDER-002 permits an explicitly labeled third-party protocol adapter to obtain and refresh its own OAuth grant inside that Runtime. Normal main-process APIs never receive tokens; shared UID is not credential isolation, and no implementation may extract browser cookies or import credentials from another App/CLI.
- Generated code never runs in the main app process.
- “Local execution” means on the phone, not necessarily in the main process and not a VM. QuickJS relies on an isolated UID; PRoot/CLI use private same-UID processes in the developer APK under ADR-RUNTIME-001. Do not describe either as kernel virtualization or a remote worker.
- QuickJS must run in a non-exported Android isolated process with no privileged host bridge.
- PRoot and CLI backends are developer-only libraries hosted in non-exported :proot and :subscriptions processes (ADR-RUNTIME-001). They share host UID, data and network permissions. Never claim PRoot is offline or isolates app/subscription secrets. Exchange bounded snapshots over private Binder/PFD IPC; generated code never runs in the main process. Consumer excludes these modules.
- Authorization for HXA-209 follows accepted ADR-PERMISSIONS-001: the user selects session presets or CUSTOM, tools are enabled/disabled, and current operation rules determine automatic resolution, confirmation or denial. Agent-tool network restrictions do not disable the model Provider. The special deletion confirmation rule is limited to explicit `rm -rf dir`; do not broaden it into an unauthorized universal deletion detector. Preserve capability checks, execution limits, cancellation and durable audit. The model, MCP, A2A, Skill or script cannot change user authorization. Read the permissions topic for old implementation boundaries.
- Optimize Advanced for capability and low friction. Prefer stable scopes, reusable low-risk rules, batch review, clear recovery, and user responsibility over repeated warnings. Do not add stricter prompts, denials, or security work that is unrelated to the current HXA or unsupported by the accepted ADRs.
- A Git binary in PRoot is job-local; network capability follows ADR-RUNTIME-001, while product integration stays outside the separately scoped persistent Git implementation authorized by accepted ADR-WORKSPACE-003. Do not add Git UI, import partial `.git` state, remote Git, hooks, or credential flows early.
- Child agents and declarative workflows are not implemented. HXA-105 may evaluate only the bounded read-only design in accepted ADR-AGENT-004; architectural acceptance does not authorize product integration before its enablement gates; do not add recursive agents, peer communication, child approvals/write tools, executable workflow/policy DSL, self-modifying plugins, cloud tasks, or deferred network approval. M7 A2A Client calls are ordinary network-backed ToolCalls to user-enabled external agents, not child-agent execution: remote output is untrusted, remote task state is persisted, and any follow-on local effect must return through the normal Dispatcher/Policy/Approval path.
- PRoot/CLI private services are cold-bound only for a user-triggered verification/repair/login or an approved Job. App startup, switching Advanced, or passive Registry refresh never starts them; Binder loss is reconciled by job ID and never causes blind replay.
- UI does not access DAO, OkHttp, QuickJS, or PRoot directly.
- WebView is owned by the browser feature. Never register a privileged permanent JavaScript bridge on untrusted pages.
- `read`, `write`, `edit`, and `bash` are short public tool names backed by the same scoped implementations and Policy as namespaced tools.
- Core modules do not depend on Android UI or infrastructure modules.
- Do not add Hilt, LangChain-style frameworks, unscoped storage access, arbitrary Maven repositories, or mutable dependency versions.
- Do not add remote/Harmony placeholder modules.

## Task discipline

- Continue until the full user-authorized task is complete; finishing one HXA or one test is not a stopping condition.
- Create a persistent development Goal only when the user explicitly requests one. Derive its objective and checkpoints from the authorized scope, `docs/development/status.md`, and the roadmap; do not hard-code historical milestone state.
- A persistent development goal may span a milestone, but it keeps only one HXA checkpoint in progress. After a checkpoint passes, update its completion record and continue to the next task without asking for routine confirmation.
- Keep edits inside the task's allowed modules.
- Dependency upgrades are authorized when needed for compatibility or maintenance. Select a supported stable version, update lockfiles and verification metadata, and verify supported Android APIs; do not retain an incompatible version solely because it was locked.
- Add tests for failure, cancellation, boundaries, and recovery as applicable.
- Never delete or skip tests to make a task pass.
- Do not return success from catch-all exception handlers.
- No placeholder implementations or unresolved TODOs in accepted work.
- Follow `docs/adr/README.md` for qualifying decisions. A small model defaults new ADRs to `proposed`; `accepted` is not implementation evidence, and changing an accepted decision requires an explicit superseding ADR and authorization.
- Make reasonable, reversible implementation choices inside an approved HXA. Prefer delivering usable capability over speculative hardening, and treat the informed Advanced user as responsible for explicitly enabled capabilities, selected scopes, backups, and approved effects. Pause only for a real architecture decision, missing authority, external dependency, irreversible data-loss risk, or evidence-backed blocker.

## Third-party code

Reference repositories listed in `docs/references/open-source-projects.md` are evidence and design references, not a source to copy. Do not copy AGPL/GPL/CPAL/MPL code from reference agents, browsers, file managers, or automation apps unless the task explicitly adopts the license and records the decision. Preserve all license and source obligations for bundled QuickJS, PRoot, Termux-derived libraries, RootFS packages, and official CLI artifacts.

## Verification

- Save temporary debugging and test scripts before execution under `scripts/debug/YYYY-MM-DD/`; archive surviving historical scripts with provenance and redact secrets, user data, and host-specific paths. Keep generated artifacts in ignored `build/` directories.
- Start an exclusive emulator process for each test run, reject existing device serials, and shut down only the owned process in `finally`. Never borrow emulators started by another person or agent.

Run the exact commands listed by the HXA task. Report the commands and actual results. Build success alone is not functional or security acceptance. Never commit secrets, real user data, machine-specific absolute paths, downloaded RootFS content, or signing material.

## Baseline acceptance before each HXA

Before starting the next HXA, resolve known failures in the mandatory local host and device gates, including inherited failures outside the previous slice. Record the baseline, root cause, scoped fix and fresh verification; unchanged baseline failures are not permission to proceed. Keep external-account/network profiles opt-in, with missing profile inputs explicitly skipped and malformed supplied inputs rejected. Preserve parallel work and stage only owned paths/hunks. A task-specific completion record does not mean the complete product suite passed.
