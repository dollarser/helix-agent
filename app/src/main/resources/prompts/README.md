# Harness prompt catalog

- `base.md`: always loaded.
- The base discovery guidance explains combined queries, replacement windows and current-request schemas; it does not change tool availability, search ranking or authorization. Task-specific search terms belong only in explicit historical eval fixtures.
- `files.md`: stable guidance, only when local file tools are exposed.
- `directory.md`: request-fresh working directory from session facts, after stable built-in guidance.
- Plugin guidance is owned by each plugin: `required.md` supplies concise always-needed rules for the selected plugin, its bundled `SKILL.md` supplies the workflow, and `references/` holds detailed on-demand guidance. Mobile Use owns `android-ui-task`; Browser owns `browser`; Documents owns `document-reading`; Chat Reply owns `chat-reply`. Short workflows can remain self-contained: Documents keeps format-specific reading steps in its skill, and guidance-only Chat Reply has no duplicated required layer. Plugin selection makes its skills available together with its tools; standalone skill selection does not enable plugin capabilities. Skill bodies and references use the normal version-bound skill read/activation path, rather than being unconditionally injected when tool names appear. None of this grants authorization.
- `plan.md`: only in Plan mode.
- `goal-identity.md`, `goal-protocol.md`, `goal-safety.md`: active durable Goal binding only.
- `tool-presentation.md`: model-facing tool intent guidance.
- `loop-warning.md`, `loop-exhausted.md`: trusted no-progress controls selected by the Harness. `loop-reassess.md` is a non-stopping phone-workflow cadence reminder; it does not assert unchanged content.
- `ask-user.md`: structured question tool guidance; answers never grant tool permissions.
- `compaction-continuity.md`: historical fact attribution and trust boundaries when replaying a summary.
- `compaction.md`: continuity-summary instructions; compression budgets and history selection remain in code.

These are packaged application resources, not workspace instructions. Workspace files and MCP prompts cannot replace them. Selection uses the allowlist in `PromptEnvironmentSections`, assembled by `SystemPromptContext`; register a trigger there when adding a template. Templates are included in context token estimation. No third-party prompt text is copied.

This directory is only part of the assembled prompt: Goal reporting, tool presentation, session expert, authorized Workspace instructions and Memory have their own sections. Check the assembled mode-specific prompt when changing guidance. `files.md` also appears in read-only modes; it must not imply write permission. Base guidance must preserve required Goal lifecycle reports.

Use `PromptEnvironmentSectionsTest` for selection/trust/substitution checks and the relevant fixed device evals for behavior. Preserve prompt/fixture identities and historical failures; cross-fixture pass rates are not prompt-only A/B evidence.

`automatic-recovery.md` supplies the bounded read-only recovery inspection instruction. It grants no capability, does not reset Goal accounting, and cannot settle original executor facts.

Shared behavior belongs in `base.md`: task completion, current-schema discovery, dependency ordering, clarification versus approval, and truthful reporting. Mode, Goal, recovery and compaction templates add only their applicable constraints. Plugin workflows remain plugin-owned; do not copy Mobile Use/Browser instructions into the base prompt.

The compaction request uses a SYSTEM instruction followed by USER-role historical data. Its returned summary remains untrusted history. A summary retains user constraints and attribution, but cannot mint authorization or turn an assistant claim into executor evidence. Tool-exhausted finalization must not demand an unavailable Goal-report call.

`files.md` follows the implemented read/edit contract: `read(includeSha256=true)` scans a bounded stream once and returns the full-file hash with its window; ordinary reads avoid that scan. Prompt text must describe actual inputs and outcomes, never compensate for missing execution behavior. The 2026-10-16 consistency review and host evidence are recorded in `docs/evidence/development/prompt-alignment-2026-10-16.md`.
