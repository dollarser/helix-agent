package com.helix.tools.framework

/**
 * Idempotency class of a tool (architecture doc section 7 ToolDescriptor).
 *
 * This is a STATIC property of the tool's declared effect. It DESCRIBES the
 * tool for capability/policy reasoning and documents the retry precondition
 * (doc 02 section 7.2 / doc 11: a retry is only sound for a tool whose repeat
 * has no additional effect); the dispatcher's bounded technical-retry gate
 * additionally requires the PER-ATTEMPT confirmed-zero-side-effect outcome
 * (the executor's [ToolExecutorResult.Failed] flag) — both conditions, never
 * the static class alone, permit a retry. [NON_IDEMPOTENT] tools get no
 * retry in practice — a failed call is a failed call and the user decides.
 *
 * When in doubt (including every MCP tool whose server does not prove
 * idempotency), declare [NON_IDEMPOTENT]: a false [IDEMPOTENT] claim is a
 * silent double-effect hazard, a false [NON_IDEMPOTENT] claim only costs a
 * manual retry.
 */
enum class Idempotency {
    /** Repeating the call with the same arguments has no additional effect. */
    IDEMPOTENT,

    /** The call has side effects; a repeat is a new effect and needs a new ToolCall + approval. */
    NON_IDEMPOTENT,
}
