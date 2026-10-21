package com.helix.tools.framework

/**
 * Descriptive effect classification, never an authorization or automatic tool replay switch.
 * Dispatcher executes a business ToolCall once. A repeated business action is a new call
 * through normal policy and authorization. Network adapters may recover only their own
 * proven-safe transport or connection phase; remote annotations alone prove nothing.
 */
enum class Idempotency {
    /** Repeating the call with the same arguments has no additional effect. */
    IDEMPOTENT,

    /** The call has side effects; a repeat is a new effect and needs a new ToolCall + approval. */
    NON_IDEMPOTENT,
}
