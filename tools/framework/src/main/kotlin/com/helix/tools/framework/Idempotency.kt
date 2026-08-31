package com.helix.tools.framework

/**
 * Idempotency class of a tool (architecture doc section 7 ToolDescriptor).
 *
 * This is a STATIC property of the tool's declared effect, consumed by the
 * retry rules (doc 02 section 7.2 / doc 11): only [IDEMPOTENT] tools may be
 * retried at all, and only under the bounded technical-retry conditions
 * (same envelope, confirmed zero side effects, same or stronger isolation).
 * [NON_IDEMPOTENT] tools get no retry — a failed call is a failed call and
 * the user decides.
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
