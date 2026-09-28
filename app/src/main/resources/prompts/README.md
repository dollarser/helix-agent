# Harness prompt catalog

- `base.md`: always loaded.
- `files.md`: only when local file tools are exposed; working directory comes from session facts.
- `plan.md`: only in Plan mode.

These are packaged application resources, not workspace instructions. Workspace files and MCP prompts cannot replace them. Selection uses the allowlist in `PromptEnvironmentSections`, assembled by `SystemPromptContext`; register a trigger there when adding a template. Templates are included in context token estimation. No third-party prompt text is copied.

This directory is only part of the assembled prompt: Goal reporting, tool presentation, session expert, authorized Workspace instructions and Memory have their own sections. Check the assembled mode-specific prompt when changing guidance. `files.md` also appears in read-only modes; it must not imply write permission. Base guidance must preserve required Goal lifecycle reports.

Use `PromptEnvironmentSectionsTest` for selection/trust/substitution checks and the relevant fixed device evals for behavior. Preserve prompt/fixture identities and historical failures; cross-fixture pass rates are not prompt-only A/B evidence.
