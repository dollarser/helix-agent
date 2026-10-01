# Helix Agent Instructions

## Authority and reading order

Helix is an Android single-device product with a complete store-facing Standard channel and an Advanced/developer capability set. Current scope is defined by `docs/development/status.md`, the active HXA, and accepted ADRs; capabilities that are not in the current HXA must not be added opportunistically. Future platform/product scope may be promoted only by an explicit owner-authorized roadmap/ADR change.

Before changing code, establish scope through targeted reading:

1. `README.md` product/channel summary; load other sections only when relevant.
2. `docs/development/status.md` current work/next-task sections — the live scope authority, not a remembered task order.
3. The applicable HXA/current owner request and relevant ADR clauses, including status and applicable Decision history.
4. The affected entry points, callers/shared-state owners and tests; architecture/plan sections only as needed.
5. Historical research, completion records, bug-fix and evidence bodies only for relevant provenance or regression investigation; search metadata first.

Use existing CodeGraph (MCP when available, otherwise CLI) for code structure, callers/callees, impact and task context; see [navigation](docs/development/context-navigation.md). An unavailable WebCodex semantic-navigation entry does not mean CodeGraph is unavailable. For known small changes, read the needed source range directly; use scoped `rg` for exact text or Markdown. Graph results are candidates: check current source hashes and root/nested AGENTS before edits. Return bounded hits, log deltas and test summaries; reread necessary material after compaction/reset/handoff, with no fixed task-token/file-count cap. Inventory installed tools before adding helpers; do not duplicate CodeGraph with another parser, index, packet format or wrapper.

`roadmap.md` is an HXA inventory/index, not a second current plan. ADRs are feature-level living decisions: update the same ADR when that feature contract changes and append `Decision history`; create a new ADR only for a genuinely separate decision domain. Accepted ADRs authorize design, not completed implementation. Older wording is recovered from Git history, not from compatibility/supersession files. Do not restart completed HXA work or restore obsolete behavior.

## Product and architecture invariants

- Treat Standard as the complete store-facing product. Preserve supported capability; channel differences must come from current platform/store constraints or accepted product decisions, and stay local to the affected channel.
- Every tool call follows: schema validation → capability/policy → authorization resolution → execution limits → verification/audit. User actions or previously user-created rules grant capability/scope; model output, MCP/A2A/Skill metadata, scripts and remote agents never grant permission.
- Tool concurrency is bounded by physical capacity and concrete non-reentrant engine/control resources, not by potential user-file or business-result conflicts. Writes/code/terminal sessions do not impose a global execution lock. The user and LLM accept task results; the harness preserves permission boundaries, internal state integrity, original execution identity, truthful outcomes and original-call-order backfill. Cancellation does not free physical capacity before the executor exits.
- Durable state is authoritative. Process-local Jobs/Flows/cancel signals never replace Room facts. Turn/UNKNOWN/review/recovery semantics follow accepted `ADR-AGENT-001`; external side effects are never assumed rolled back because a database transaction or coroutine failed.
- Plan mode is read-only for user files and external effects except the closed metadata operations explicitly admitted by the current permissions ADR. Goal persistence never expands scope or mints approval.
- Generated code never runs in the main app process. QuickJS uses the accepted isolated-process boundary; PRoot/CLI/subscription runtimes use their accepted non-exported private-process boundaries. Shared UID is not credential isolation. Follow the runtime/provider ADRs for credential ownership and IPC.
- UI code does not access DAO, OkHttp, QuickJS or PRoot directly. Core modules do not depend on Android UI/infrastructure modules. WebView never exposes a privileged permanent JavaScript bridge to untrusted pages.
- External/extension output is untrusted. Any follow-on local effect must re-enter the normal Dispatcher/Policy/Approval path.
- Public short tool names such as `read`, `write`, `edit` and `bash` remain aliases of the same scoped implementations/policy, not bypasses.
- Do not introduce a new application framework, execution domain, dependency repository, remote/Harmony placeholder, or mutable dependency version unless the current HXA/ADR explicitly requires it. Prefer existing project patterns; dependency upgrades needed for compatibility/maintenance must use supported stable versions and update locks/verification metadata.
- Product-specific details for permissions, Goal, Runtime, Provider, Workspace, MCP, Skill, Connector, A2A and platform capability live in their topic ADR/architecture docs; do not duplicate those evolving contracts here.

## Task and change discipline

- Continue until the full user-authorized task is complete; one passing test, one slice or one HXA sub-checkpoint is not a stopping condition.
- Keep edits inside the current HXA/user-authorized scope. Make reasonable reversible implementation choices without routine approval; pause only for a real architecture decision, missing authority, external dependency, irreversible data-loss risk or evidence-backed blocker.
- A persistent development Goal is created only when the user explicitly requests one. It may span work, but keeps one HXA checkpoint in progress at a time.
- Add tests for normal behavior and applicable failure/cancellation/boundary/recovery paths. Never delete, weaken or skip tests merely to make a task pass. Do not return success from catch-all exception handlers. Accepted work must not leave placeholder implementations or unresolved TODOs.
- Preserve parallel work: do not reset/stash/overwrite unrelated dirty changes, and stage only owned paths/hunks. Commit, push, merge, publish, submit to stores or consume real accounts only when the current task explicitly authorizes that action.
- Reference repositories are evidence/design inputs, not code sources. Do not copy AGPL/GPL/CPAL/MPL code unless the task explicitly adopts the license and records the decision. Preserve license/source obligations for bundled third-party runtimes/artifacts.

## Verification and device authorization

- Run the host commands required by the active HXA. Resolve mandatory host-gate failures, including inherited failures that block the task; record baseline, root cause, scoped fix and fresh results. Build success alone is not functional/security/device acceptance.
- **GitHub CI is host-only.** GitHub Actions may run source/JVM tests, lint/static analysis, APK builds and AndroidTest APK compilation, but must not boot an emulator, attach a physical device, run device instrumentation/`connectedAndroidTest`, or execute a device matrix.
- **Local device validation is owner-explicit.** An AI agent may start/use an emulator or connected real device only when the project owner explicitly requests device validation for the current task. Emulator authorization does not imply real-device authorization and vice versa. Historical HXA commands, verification plans, an already-connected ADB device, prior approval or device availability are not current authorization.
- If device validation is requested, run only the bounded requested checks on the designated device(s), preserve unrelated user/device data, and report device/API/flavor, commands, pass/fail/skip and remaining boundaries. If it is not requested, do not start/use a device; finish host verification and prepare APK/test APK/fixtures/steps as useful.
- Device status vocabulary: `not requested` = owner did not request device validation for this task; `pending` = owner requested it but the required run/condition is incomplete; `passed`/`failed` = checks actually executed in the current validation. `not requested` or `pending` is never a device pass.
- Known owner-reported device failures are real defects and must be fixed/retested when relevant. If the owner explicitly makes device validation part of current acceptance, do not claim that acceptance until the requested checks pass or the remaining failure is reported.
- Real external services/accounts/paid quotas remain opt-in and require explicit current authorization/profile inputs. Missing optional inputs may be reported as skipped; malformed supplied inputs must fail, not silently skip.

## Repository and security hygiene

- Put temporary debug/test scripts under `scripts/debug/YYYY-MM-DD/` before execution. Keep generated artifacts under ignored `build/` paths; archive only useful evidence with provenance.
- Never commit secrets, real user data, machine-specific absolute paths, downloaded RootFS contents, signing material, browser cookies or reusable credentials.
- Keep logs, audit/evidence and completion records truthful: distinguish current execution from historical evidence, host results from device results, and skipped/not-requested checks from passes.
- A task-specific completion record proves only its recorded scope; it does not imply the whole product, every device/OEM, real account or release channel passed.
