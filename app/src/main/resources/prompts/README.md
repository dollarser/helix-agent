# Harness prompt catalog

- `base.md`: always loaded.
- `files.md`: only when local file tools are exposed; working directory comes from session facts.
- `plan.md`: only in Plan mode.
- `goal-identity.md`, `goal-protocol.md`, `goal-safety.md`: active durable Goal binding only.
- `tool-presentation.md`: model-facing tool intent guidance.
- `loop-warning.md`, `loop-exhausted.md`: trusted no-progress controls selected by the Harness.
- `ask-user.md`: structured question tool guidance; answers never grant tool permissions.
- `compaction.md`: continuity-summary instructions; compression budgets and history selection remain in code.

These are packaged application resources, not workspace instructions. Workspace files and MCP prompts cannot replace them. Selection uses the allowlist in `PromptEnvironmentSections`, assembled by `SystemPromptContext`; register a trigger there when adding a template. Templates are included in context token estimation. No third-party prompt text is copied.

This directory is only part of the assembled prompt: Goal reporting, tool presentation, session expert, authorized Workspace instructions and Memory have their own sections. Check the assembled mode-specific prompt when changing guidance. `files.md` also appears in read-only modes; it must not imply write permission. Base guidance must preserve required Goal lifecycle reports.

Use `PromptEnvironmentSectionsTest` for selection/trust/substitution checks and the relevant fixed device evals for behavior. Preserve prompt/fixture identities and historical failures; cross-fixture pass rates are not prompt-only A/B evidence.

`automatic-recovery.md` supplies the bounded read-only recovery inspection instruction. It grants no capability, does not reset Goal accounting, and cannot settle original executor facts.
