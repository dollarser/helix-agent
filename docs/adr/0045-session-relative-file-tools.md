# ADR-0045: Session-relative file tool inputs

Status: accepted
Date: 2026-09-10
HXA: HXA-190, HXA-191
Deciders: Project owner explicitly requested relative workspace file operations and unified tool/context contracts.
Supersedes: none
Superseded by: none

## Context

A real file task repeatedly guessed scope IDs and failed despite having a default workspace. Session directory binding existed but was not used by file calls; mandatory internal references and opaque errors obstructed simple tasks. The owner authorized relative-path support, root-file use and reusable harness templates.

## Decision

Local built-in file tools accept session-relative paths at the model-facing application boundary. The application resolves them against the saved session directory, or the default workspace root, before canonical argument hashing, scope/policy, approval, scheduling and execution. Explicit `scope:` references remain anchored to their named scope. Invalid selected directories never silently fall back to another scope. MCP tools are not rewritten.

The workspace root accepts ordinary user files and directories, preserving quota, symlink, atomic write and hash checks. `input/`, `work/`, `output/` remain conventional directories; `.helix/` and operations on the entire scope root remain protected. Archive/extract retain their existing work-only destinations. This replaces the earlier layout-only mutation constraint for normal file operations, not the scope or authorization boundary. Changed mutation contracts receive new versions.

Common harness instructions live in packaged Markdown resources selected through a fixed code allowlist. Per-request mode and tool exposure determine inclusion; the current directory is substituted from app state. Workspace/MCP content cannot replace these system instructions. No new tool, IPC format or authority is introduced.

## References

- [MCP tools](https://modelcontextprotocol.io/specification/2025-06-18/server/tools): tool description, input schema and error feedback; no requirement that local tools become an MCP server.
- [Anthropic context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents): focused context and progressive disclosure. This implementation uses original templates, not copied competitor prompts.

## Alternatives considered

Keep adding examples but retain mandatory scope syntax: rejected because the application already knows the directory. Add an MCP server: unnecessary for local execution. Change the low-level scope parser to accept unbound relative paths: rejected because authorization and audit need explicit scope identity. Store mutable system prompts in the user workspace: rejected because workspace content is untrusted input.

## Consequences

Root files join workspace quota accounting and normal mutation contracts are versioned. Existing explicit references remain valid. The application tool boundary normalizes relative input; direct low-level executors still require canonical references. Packaged instructions are version-controlled and included in token estimates; adding a template requires a declared selection rule.

## Verification

Verify default/selected directory, explicit reference, traversal rejection, content preservation, scope binding before approval, root-file write/read/edit/list, protected internals, packaged prompt presence and conditional selection. Real model completion remains separate from host regression evidence.

## Reconsider when

Multiple working directories per turn, model-selected prompt retrieval, new storage scope types or writable runtime prompt customization are introduced. Each needs explicit identity, provenance and authorization rules.
