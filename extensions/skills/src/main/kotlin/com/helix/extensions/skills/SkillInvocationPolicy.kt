package com.helix.extensions.skills

/** Content eligibility only. These flags never grant tool or platform permissions. */
data class SkillInvocationPolicy(
    val userInvocable: Boolean = true,
    val modelInvocable: Boolean = true,
    val unsupportedExecutionFields: Set<String> = emptySet(),
) {
    companion object {
        fun parse(fields: Map<String, Any?>): SkillInvocationPolicy {
            fun boolean(
                name: String,
                default: Boolean,
            ): Boolean {
                if (name !in fields) return default
                return fields[name] as? Boolean ?: throw InvalidSkillException("$name must be a boolean")
            }
            return SkillInvocationPolicy(
                boolean("user-invocable", true),
                !boolean("disable-model-invocation", false),
                setOf("context", "agent", "hooks").filter { it in fields }.toSet(),
            )
        }
    }
}

/** Produced by trusted accepted input lookup, never deserialized from tool arguments. */
data class UserSkillActivation(
    val key: SkillKey,
    val arguments: String,
)
