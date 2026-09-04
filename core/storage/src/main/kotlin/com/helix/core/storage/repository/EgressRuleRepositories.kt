package com.helix.core.storage.repository

import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import com.helix.core.policy.DataSensitivity
import com.helix.core.policy.EgressTarget
import com.helix.core.policy.HighSensitivityRule
import com.helix.core.policy.UserScopeCodec
import com.helix.core.storage.dao.HighSensitivityRuleDao
import com.helix.core.storage.entity.HighSensitivityRuleEntity
import java.time.Instant
import java.util.UUID

/**
 * A stored rule with its storage id — the id is what the UI hands back to
 * [HighSensitivityRuleRepository.revoke]; the [rule] is what the Policy Engine consumes.
 */
data class StoredEgressRule(
    val id: String,
    val rule: HighSensitivityRule,
)

/**
 * ADR-0005 high-sensitivity egress rules (HXA-068). The Policy Engine reads rules through
 * [all]; the developer/Advanced UI creates them through [save] and revokes them through
 * [revoke] (immediate, no sliding renewal — a re-approval is a brand-new rule).
 *
 * Failure is closed end to end: [save] only ever stores an already-valid [HighSensitivityRule]
 * (whose constructor enforces SENSITIVE + the four fixed TTLs + a mandatory scope), and [all]/
 * [byId] REHYDRATE every row through the lossless [UserScopeCodec] and the fail-closed
 * [NormalizedEndpoint.parse] and then rebuild the rule — a corrupt or unknown row throws rather
 * than being guessed, so a bad row can never match (the caller blanks the rule set, and the
 * engine then confirms per call).
 */
class HighSensitivityRuleRepository(
    private val dao: HighSensitivityRuleDao,
) {
    /** Persists a valid rule under a fresh id and returns it with that id. */
    fun save(rule: HighSensitivityRule): StoredEgressRule {
        val id = UUID.randomUUID().toString()
        dao.insert(rule.toEntity(id))
        return StoredEgressRule(id, rule)
    }

    /** Immediately revokes the rule by storage id; throws when the id does not exist. */
    fun revoke(id: String) {
        require(dao.delete(id) == 1) { "egress rule not found: $id" }
    }

    /**
     * Every stored rule, rehydrated to its live [HighSensitivityRule]. Throws on the first
     * corrupt/undecodable row (fail closed: a corrupted store must never yield a partial,
     * silently-wrong rule set).
     */
    fun all(): List<StoredEgressRule> = dao.list().map { it.toStoredRule() }

    /** Rehydrates one stored rule by id; throws when missing or corrupt. */
    fun byId(id: String): StoredEgressRule {
        val entity = dao.byId(id)

        return entity?.toStoredRule() ?: error("egress rule not found: $id")
    }

    private fun HighSensitivityRuleEntity.toStoredRule(): StoredEgressRule = StoredEgressRule(id, toRule())

    private fun HighSensitivityRule.toEntity(id: String): HighSensitivityRuleEntity =
        HighSensitivityRuleEntity(
            id = id,
            targetKind = target.kind(),
            targetId = target.id(),
            originFull = origin.full,
            scopeEncoded = UserScopeCodec.encode(scope),
            createdAtEpoch = createdAt.epochSecond,
            expiresAtEpoch = expiresAt.epochSecond,
        )

    private fun HighSensitivityRuleEntity.toRule(): HighSensitivityRule {
        val target =
            when (targetKind) {
                TARGET_KIND_PROVIDER -> EgressTarget.Provider(ProviderId(targetId))
                TARGET_KIND_MCP -> EgressTarget.Mcp(McpServerId(targetId))
                else -> error("corrupt egress rule $id: unknown target kind '$targetKind'")
            }
        // parse is fail-closed (throws on any non-canonical / wrong-scheme / control-corrupt string).
        val origin = NormalizedEndpoint.parse(originFull)
        // decode returns null (not an exception) on malformed storage — a rule that cannot be
        // decoded must never match, so surface it as a hard failure here.
        val scope =
            UserScopeCodec.decode(scopeEncoded)
                ?: error("corrupt egress rule $id: undecodable scope")
        // The rebuild re-runs the HighSensitivityRule invariants (SENSITIVE + the four TTLs); a
        // corrupt row fails closed here too. createdAt/expiresAt share a fractional part, so
        // whole-epochSecond storage preserves the exact fixed TTL.
        return HighSensitivityRule(
            target = target,
            origin = origin,
            dataCategory = DataSensitivity.SENSITIVE,
            scope = scope,
            createdAt = Instant.ofEpochSecond(createdAtEpoch),
            expiresAt = Instant.ofEpochSecond(expiresAtEpoch),
        )
    }

    private companion object {
        const val TARGET_KIND_PROVIDER = "provider"
        const val TARGET_KIND_MCP = "mcp"
    }
}

/** The closed discriminator of an [EgressTarget] for storage. */
private fun EgressTarget.kind(): String =
    when (this) {
        is EgressTarget.Provider -> "provider"
        is EgressTarget.Mcp -> "mcp"
    }

/** The stable id of an [EgressTarget] for storage. */
private fun EgressTarget.id(): String =
    when (this) {
        is EgressTarget.Provider -> id.value
        is EgressTarget.Mcp -> id.value
    }
