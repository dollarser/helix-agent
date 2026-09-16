package com.helix.core.policy

import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SessionPermissionMode

/**
 * The single compiled session permission configuration (ADR-PERMISSIONS-001 section 2 step
 * 4): the three presets and CUSTOM all compile to this ONE shape, and ONE resolver decides
 * from it — there are no four execution chains. A preset is simply its fixed rule table;
 * CUSTOM is the user's explicit snapshot copied from a preset and then edited.
 *
 * Copying a preset materializes its rules into a FIXED snapshot: later changes to a preset
 * definition never flow back into an existing CUSTOM configuration (ADR section 1.2).
 * [configVersion] is the version of the rule set as saved; audit and the linearization of
 * config changes against execution starts use it (ADR sections 4 and 5).
 *
 * Risk levels are deliberately absent here: they inform display, audit and anomaly analysis
 * but never re-gate an operation the session mode authorized (ADR section 2).
 */
data class SessionPermissionConfig(
    val mode: SessionPermissionMode,
    val rules: Map<OperationEffect, OperationRule>,
    val configVersion: Int = CURRENT_CONFIG_VERSION,
) {
    init {
        require(configVersion >= 1) { "configVersion must be >= 1" }
    }

    /**
     * The effective rule for [effect]. A key absent from a CUSTOM snapshot is an UNDEFINED
     * category and defaults to ASK, never ALLOW (ADR section 1.2).
     */
    fun ruleFor(effect: OperationEffect): OperationRule = rules[effect] ?: OperationRule.ASK

    companion object {
        /** Version of the rule-set contract this build speaks; bump only on an explicit contract change. */
        const val CURRENT_CONFIG_VERSION = 1

        /** Compiles a preset mode into the single config shape. CUSTOM has no implicit rules. */
        fun of(mode: SessionPermissionMode): SessionPermissionConfig {
            require(mode != SessionPermissionMode.CUSTOM) {
                "CUSTOM requires an explicit copied rule snapshot; use custom()"
            }
            return SessionPermissionConfig(mode, presetRules(mode))
        }

        /** A CUSTOM config from the user's explicit (copied-then-edited) rule snapshot. */
        fun custom(rules: Map<OperationEffect, OperationRule>): SessionPermissionConfig =
            SessionPermissionConfig(SessionPermissionMode.CUSTOM, rules)

        /** The fixed rule table a preset compiles to (ADR section 1 table). */
        fun presetRules(mode: SessionPermissionMode): Map<OperationEffect, OperationRule> =
            when (mode) {
                SessionPermissionMode.FULL_ACCESS -> {
                    OperationEffect.values().associateWith { OperationRule.ALLOW }
                }

                SessionPermissionMode.WORKSPACE -> {
                    mapOf(
                        OperationEffect.FILE_READ_WORKSPACE to OperationRule.ALLOW,
                        OperationEffect.FILE_MUTATION_WORKSPACE to OperationRule.ALLOW,
                        OperationEffect.REMOTE_BUSINESS_MUTATION to OperationRule.ALLOW,
                        OperationEffect.FILE_READ_EXTERNAL to OperationRule.ASK,
                        OperationEffect.FILE_MUTATION_EXTERNAL to OperationRule.ASK,
                        OperationEffect.DEVICE_SYSTEM_MUTATION to OperationRule.ASK,
                        OperationEffect.COMMAND_EXECUTION to OperationRule.ASK,
                    )
                }

                SessionPermissionMode.READ_ONLY -> {
                    mapOf(
                        OperationEffect.FILE_READ_WORKSPACE to OperationRule.ALLOW,
                        OperationEffect.FILE_READ_EXTERNAL to OperationRule.ASK,
                        OperationEffect.FILE_MUTATION_WORKSPACE to OperationRule.ASK,
                        OperationEffect.FILE_MUTATION_EXTERNAL to OperationRule.ASK,
                        OperationEffect.REMOTE_BUSINESS_MUTATION to OperationRule.ASK,
                        OperationEffect.DEVICE_SYSTEM_MUTATION to OperationRule.ASK,
                        OperationEffect.COMMAND_EXECUTION to OperationRule.ASK,
                    )
                }

                // CUSTOM carries no implicit table; its snapshot is explicit (see custom()).
                SessionPermissionMode.CUSTOM -> {
                    emptyMap()
                }
            }

        /**
         * Copies a preset into a fresh CUSTOM starting snapshot: the preset's rules
         * materialized as explicit entries (ADR section 1.2: a fixed copy, no dynamic
         * inheritance from later preset changes).
         */
        fun copyPreset(mode: SessionPermissionMode): Map<OperationEffect, OperationRule> = presetRules(mode).toMap()
    }
}
