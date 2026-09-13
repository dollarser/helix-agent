# Harness prompt catalog

- `base.md`: always loaded.
- `files.md`: only when local file tools are exposed; working directory comes from session facts.
- `plan.md`: only in Plan mode.

These are packaged application resources, not workspace instructions. Workspace files and MCP prompts cannot replace them. Selection uses the allowlist in `ChatEnvironmentContext`; register a trigger there when adding a template. Templates are included in context token estimation. No third-party prompt text is copied.
