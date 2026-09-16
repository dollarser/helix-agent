package com.helix.core.policy

import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * HXA-209 B: the single session permission config and unified resolver
 * (ADR-PERMISSIONS-001). The three presets and CUSTOM compile to ONE config shape and share
 * ONE resolver: multiple effects merge DENY > ASK > ALLOW, an undetermined effect touching a
 * DENY refuses outright (never downgraded to ASK), the rm -rf command rule adds a precise
 * one-time approval in every mode, and tool availability is two-state with outer disable
 * winning over a session enable.
 */
class SessionPermissionPolicyTest {
    private val fullAccess = SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS)
    private val workspace = SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE)
    private val readOnly = SessionPermissionConfig.of(SessionPermissionMode.READ_ONLY)

    // --- preset compilation -------------------------------------------------

    @Test
    fun `full access compiles every effect to allow`() {
        OperationEffect.values().forEach { effect ->
            assertEquals(OperationRule.ALLOW, fullAccess.ruleFor(effect))
        }
    }

    @Test
    fun `workspace preset allows inside file and remote mutation, asks the rest`() {
        assertEquals(OperationRule.ALLOW, workspace.ruleFor(OperationEffect.FILE_READ_WORKSPACE))
        assertEquals(OperationRule.ALLOW, workspace.ruleFor(OperationEffect.FILE_MUTATION_WORKSPACE))
        assertEquals(OperationRule.ALLOW, workspace.ruleFor(OperationEffect.REMOTE_BUSINESS_MUTATION))
        assertEquals(OperationRule.ASK, workspace.ruleFor(OperationEffect.FILE_READ_EXTERNAL))
        assertEquals(OperationRule.ASK, workspace.ruleFor(OperationEffect.FILE_MUTATION_EXTERNAL))
        assertEquals(OperationRule.ASK, workspace.ruleFor(OperationEffect.DEVICE_SYSTEM_MUTATION))
        assertEquals(OperationRule.ASK, workspace.ruleFor(OperationEffect.COMMAND_EXECUTION))
    }

    @Test
    fun `read only preset allows only the workspace read`() {
        assertEquals(OperationRule.ALLOW, readOnly.ruleFor(OperationEffect.FILE_READ_WORKSPACE))
        OperationEffect
            .values()
            .filter { it != OperationEffect.FILE_READ_WORKSPACE }
            .forEach { effect -> assertEquals(OperationRule.ASK, readOnly.ruleFor(effect)) }
    }

    @Test
    fun `custom has no implicit rules`() {
        try {
            SessionPermissionConfig.of(SessionPermissionMode.CUSTOM)
            fail("CUSTOM must require an explicit snapshot")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("CUSTOM"))
        }
    }

    @Test
    fun `custom missing categories default to ask never allow`() {
        val custom =
            SessionPermissionConfig.custom(
                mapOf(OperationEffect.COMMAND_EXECUTION to OperationRule.ALLOW),
            )
        assertEquals(OperationRule.ALLOW, custom.ruleFor(OperationEffect.COMMAND_EXECUTION))
        assertEquals(OperationRule.ASK, custom.ruleFor(OperationEffect.FILE_MUTATION_WORKSPACE))
        assertEquals(OperationRule.ASK, custom.ruleFor(OperationEffect.REMOTE_BUSINESS_MUTATION))
    }

    @Test
    fun `copying a preset materializes a fixed editable snapshot`() {
        val snapshot = SessionPermissionConfig.copyPreset(SessionPermissionMode.READ_ONLY).toMutableMap()
        assertEquals(readOnly.rules, snapshot)
        snapshot[OperationEffect.COMMAND_EXECUTION] = OperationRule.ALLOW
        val copied = SessionPermissionConfig.custom(snapshot)
        assertEquals(OperationRule.ALLOW, copied.ruleFor(OperationEffect.COMMAND_EXECUTION))
        // The preset definition itself is untouched: no dynamic inheritance either way.
        assertEquals(OperationRule.ASK, readOnly.ruleFor(OperationEffect.COMMAND_EXECUTION))
    }

    @Test
    fun `an unedited custom copy of a preset resolves exactly like the preset`() {
        val copied = SessionPermissionConfig.custom(SessionPermissionConfig.copyPreset(SessionPermissionMode.READ_ONLY))
        val footprints =
            listOf(
                OperationFootprint(setOf(OperationEffect.FILE_READ_WORKSPACE)),
                OperationFootprint(setOf(OperationEffect.FILE_READ_EXTERNAL)),
                OperationFootprint(setOf(OperationEffect.FILE_MUTATION_WORKSPACE)),
                OperationFootprint(setOf(OperationEffect.REMOTE_BUSINESS_MUTATION)),
                OperationFootprint(
                    setOf(OperationEffect.FILE_READ_WORKSPACE),
                    setOf(OperationEffect.FILE_MUTATION_EXTERNAL),
                ),
            )
        footprints.forEach { footprint ->
            assertEquals(
                SessionPermissionResolver.resolve(readOnly, footprint, false),
                SessionPermissionResolver.resolve(copied, footprint, false),
            )
        }
    }

    // --- effect merge: DENY > ASK > ALLOW -----------------------------------

    @Test
    fun `an all allow footprint skips the card`() {
        assertEquals(
            SessionPermissionResolution.AutoProceed,
            SessionPermissionResolver.resolve(
                fullAccess,
                OperationFootprint(setOf(OperationEffect.FILE_MUTATION_EXTERNAL, OperationEffect.COMMAND_EXECUTION)),
                false,
            ),
        )
    }

    @Test
    fun `any deny refuses the whole call not just the denied part`() {
        val custom =
            SessionPermissionConfig.custom(
                mapOf(
                    OperationEffect.FILE_READ_WORKSPACE to OperationRule.ALLOW,
                    OperationEffect.FILE_MUTATION_EXTERNAL to OperationRule.DENY,
                ),
            )
        val resolution =
            SessionPermissionResolver.resolve(
                custom,
                OperationFootprint(setOf(OperationEffect.FILE_READ_WORKSPACE, OperationEffect.FILE_MUTATION_EXTERNAL)),
                false,
            )
        assertTrue(resolution is SessionPermissionResolution.Denied)
        assertEquals(PermissionDenyCode.OPERATION_DENIED, (resolution as SessionPermissionResolution.Denied).code)
    }

    @Test
    fun `deny beats ask on the same call`() {
        val custom =
            SessionPermissionConfig.custom(
                mapOf(
                    OperationEffect.FILE_MUTATION_WORKSPACE to OperationRule.DENY,
                    OperationEffect.COMMAND_EXECUTION to OperationRule.ASK,
                ),
            )
        val resolution =
            SessionPermissionResolver.resolve(
                custom,
                OperationFootprint(setOf(OperationEffect.FILE_MUTATION_WORKSPACE, OperationEffect.COMMAND_EXECUTION)),
                false,
            )
        assertTrue(resolution is SessionPermissionResolution.Denied)
    }

    @Test
    fun `any ask yields one approval carrying the merged reasons`() {
        val resolution =
            SessionPermissionResolver.resolve(
                readOnly,
                OperationFootprint(
                    setOf(
                        OperationEffect.FILE_READ_WORKSPACE,
                        OperationEffect.FILE_MUTATION_WORKSPACE,
                        OperationEffect.REMOTE_BUSINESS_MUTATION,
                    ),
                ),
                false,
            ) as SessionPermissionResolution.RequiresApproval
        assertEquals(
            setOf(
                PermissionReason(PermissionReasonCode.OPERATION_ASK, OperationEffect.FILE_MUTATION_WORKSPACE),
                PermissionReason(PermissionReasonCode.TOOL_NETWORK, OperationEffect.REMOTE_BUSINESS_MUTATION),
            ),
            resolution.reasons,
        )
    }

    @Test
    fun `an outside scope ask is expressed as scope not operation ask`() {
        val resolution =
            SessionPermissionResolver.resolve(
                workspace,
                OperationFootprint(setOf(OperationEffect.FILE_READ_EXTERNAL)),
                false,
            ) as SessionPermissionResolution.RequiresApproval
        assertEquals(
            setOf(PermissionReason(PermissionReasonCode.SCOPE_OUTSIDE, OperationEffect.FILE_READ_EXTERNAL)),
            resolution.reasons,
        )
    }

    @Test
    fun `an empty footprint such as metadata settlement skips the card`() {
        assertEquals(
            SessionPermissionResolution.AutoProceed,
            SessionPermissionResolver.resolve(workspace, OperationFootprint(), false),
        )
    }

    // --- undetermined effects: refuse on DENY, explain on ASK ----------------

    @Test
    fun `an undetermined effect touching a deny refuses with the domain reason`() {
        val custom =
            SessionPermissionConfig.custom(
                mapOf(
                    OperationEffect.COMMAND_EXECUTION to OperationRule.ALLOW,
                    OperationEffect.FILE_MUTATION_WORKSPACE to OperationRule.DENY,
                    OperationEffect.FILE_MUTATION_EXTERNAL to OperationRule.DENY,
                ),
            )
        // A Shell/PRoot call: execution allowed, but its file effects cannot be ruled out.
        val resolution =
            SessionPermissionResolver.resolve(
                custom,
                OperationFootprint(
                    effects = setOf(OperationEffect.COMMAND_EXECUTION),
                    undeterminedEffects =
                        setOf(
                            OperationEffect.FILE_MUTATION_WORKSPACE,
                            OperationEffect.FILE_MUTATION_EXTERNAL,
                        ),
                ),
                false,
            ) as SessionPermissionResolution.Denied
        assertEquals(PermissionDenyCode.OPERATION_DENIED_DOMAIN, resolution.code)
        assertTrue(
            PermissionReason(PermissionReasonCode.UNDETERMINED_EFFECT, OperationEffect.FILE_MUTATION_WORKSPACE) in
                resolution.reasons,
        )
    }

    @Test
    fun `a deny is never downgraded to ask when the domain cannot guarantee it`() {
        val custom =
            SessionPermissionConfig.custom(
                mapOf(OperationEffect.FILE_MUTATION_WORKSPACE to OperationRule.DENY),
            )
        val resolution =
            SessionPermissionResolver.resolve(
                custom,
                OperationFootprint(undeterminedEffects = setOf(OperationEffect.FILE_MUTATION_WORKSPACE)),
                false,
            )
        assertTrue(resolution is SessionPermissionResolution.Denied)
    }

    @Test
    fun `an undetermined effect under an ask asks and explains the gap`() {
        val resolution =
            SessionPermissionResolver.resolve(
                workspace,
                OperationFootprint(
                    effects = setOf(OperationEffect.COMMAND_EXECUTION),
                    undeterminedEffects = setOf(OperationEffect.FILE_MUTATION_EXTERNAL),
                ),
                false,
            ) as SessionPermissionResolution.RequiresApproval
        assertTrue(
            PermissionReason(PermissionReasonCode.UNDETERMINED_EFFECT, OperationEffect.FILE_MUTATION_EXTERNAL) in
                resolution.reasons,
        )
    }

    @Test
    fun `a determined effect and the same undetermined effect are not both listed`() {
        try {
            OperationFootprint(
                effects = setOf(OperationEffect.FILE_MUTATION_WORKSPACE),
                undeterminedEffects = setOf(OperationEffect.FILE_MUTATION_WORKSPACE),
            )
            fail("overlapping determined and undetermined effects must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("determined"))
        }
    }

    // --- rm -rf command rule: the floor every mode shares -------------------

    @Test
    fun `an rm hit asks even in full access where every effect is allow`() {
        val resolution =
            SessionPermissionResolver.resolve(
                fullAccess,
                OperationFootprint(setOf(OperationEffect.COMMAND_EXECUTION)),
                rmCommandHit = true,
            ) as SessionPermissionResolution.RequiresApproval
        assertEquals(setOf(PermissionReason(PermissionReasonCode.RM_COMMAND_RULE)), resolution.reasons)
    }

    @Test
    fun `an rm hit adds its reason to an existing ask`() {
        val resolution =
            SessionPermissionResolver.resolve(
                workspace,
                OperationFootprint(setOf(OperationEffect.COMMAND_EXECUTION)),
                rmCommandHit = true,
            ) as SessionPermissionResolution.RequiresApproval
        assertEquals(
            setOf(
                PermissionReason(PermissionReasonCode.OPERATION_ASK, OperationEffect.COMMAND_EXECUTION),
                PermissionReason(PermissionReasonCode.RM_COMMAND_RULE),
            ),
            resolution.reasons,
        )
    }

    @Test
    fun `an rm hit never overrides a deny`() {
        val custom =
            SessionPermissionConfig.custom(
                mapOf(OperationEffect.FILE_MUTATION_WORKSPACE to OperationRule.DENY),
            )
        val resolution =
            SessionPermissionResolver.resolve(
                custom,
                OperationFootprint(undeterminedEffects = setOf(OperationEffect.FILE_MUTATION_WORKSPACE)),
                rmCommandHit = true,
            )
        assertTrue(resolution is SessionPermissionResolution.Denied)
    }

    // --- tool availability: two-state, outer disable wins --------------------

    @Test
    fun `availability defaults to enabled`() {
        assertEquals(
            ToolAvailabilityState.ENABLED,
            effectiveAvailability(global = null, workspace = null, session = null),
        )
    }

    @Test
    fun `a global disable is not overridden by a session enable`() {
        assertEquals(
            ToolAvailabilityState.DISABLED,
            effectiveAvailability(
                global = ToolAvailabilityState.DISABLED,
                workspace = null,
                session = ToolAvailabilityState.ENABLED,
            ),
        )
    }

    @Test
    fun `a workspace disable wins over a session enable`() {
        assertEquals(
            ToolAvailabilityState.DISABLED,
            effectiveAvailability(
                global = ToolAvailabilityState.ENABLED,
                workspace = ToolAvailabilityState.DISABLED,
                session = ToolAvailabilityState.ENABLED,
            ),
        )
    }

    @Test
    fun `without any disable the narrowest explicit state wins`() {
        assertEquals(
            ToolAvailabilityState.DISABLED,
            effectiveAvailability(
                global = ToolAvailabilityState.ENABLED,
                workspace = null,
                session = ToolAvailabilityState.DISABLED,
            ),
        )
        assertEquals(
            ToolAvailabilityState.ENABLED,
            effectiveAvailability(
                global = ToolAvailabilityState.ENABLED,
                workspace = ToolAvailabilityState.ENABLED,
                session = null,
            ),
        )
    }

    @Test
    fun `re-enabling only restores availability and never a remembered ask or allow`() {
        // Availability is two-state: the resolution before and after a disable/enable cycle
        // depends ONLY on the mode config, so no old ASK/ALLOW state can resurface.
        val before =
            SessionPermissionResolver.resolve(
                readOnly,
                OperationFootprint(setOf(OperationEffect.FILE_MUTATION_WORKSPACE)),
                false,
            )
        effectiveAvailability(session = ToolAvailabilityState.DISABLED)
        effectiveAvailability(session = ToolAvailabilityState.ENABLED)
        val after =
            SessionPermissionResolver.resolve(
                readOnly,
                OperationFootprint(setOf(OperationEffect.FILE_MUTATION_WORKSPACE)),
                false,
            )
        assertEquals(before, after)
    }

    // --- config version and structured reasons --------------------------------

    @Test
    fun `the config carries the contract version for audit and linearization`() {
        assertEquals(SessionPermissionConfig.CURRENT_CONFIG_VERSION, fullAccess.configVersion)
        assertEquals(
            3,
            SessionPermissionConfig(SessionPermissionMode.CUSTOM, emptyMap(), configVersion = 3).configVersion,
        )
    }

    @Test
    fun `approval reasons are a structured set never description strings`() {
        val resolution =
            SessionPermissionResolver.resolve(
                workspace,
                OperationFootprint(
                    effects = setOf(OperationEffect.FILE_READ_EXTERNAL),
                    undeterminedEffects = setOf(OperationEffect.DEVICE_SYSTEM_MUTATION),
                ),
                rmCommandHit = false,
            ) as SessionPermissionResolution.RequiresApproval
        assertEquals(
            setOf(
                PermissionReason(PermissionReasonCode.SCOPE_OUTSIDE, OperationEffect.FILE_READ_EXTERNAL),
                PermissionReason(PermissionReasonCode.UNDETERMINED_EFFECT, OperationEffect.DEVICE_SYSTEM_MUTATION),
            ),
            resolution.reasons,
        )
    }

    @Test
    fun `an approval never resolves with zero reasons`() {
        try {
            SessionPermissionResolution.RequiresApproval(emptySet())
            fail("an empty approval reason set must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("reason"))
        }
    }
}
