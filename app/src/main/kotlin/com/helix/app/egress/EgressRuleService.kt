package com.helix.app.egress

import com.helix.app.APP_SCOPE_ID
import com.helix.core.model.McpServerId
import com.helix.core.model.ProviderId
import com.helix.core.policy.EgressTarget
import com.helix.core.policy.HighSensitivityRule
import com.helix.core.policy.RuleDuration
import com.helix.core.policy.WorkspaceScope
import com.helix.core.storage.repository.HighSensitivityRuleRepository
import com.helix.core.storage.repository.StoredEgressRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

data class EgressRuleDraft(
    val provider: Boolean,
    val targetId: String,
    val origin: String,
    val duration: RuleDuration,
)

/** Display projection only; never an authorization source. */
data class EgressRuleRow(
    val id: String,
    val target: String,
    val origin: String,
    val category: String,
    val scope: String,
    val expiresAt: Instant,
)

/** User-action application boundary. Policy still reads the original durable repository. */
class EgressRuleService internal constructor(
    private val read: () -> List<StoredEgressRule>,
    private val save: (HighSensitivityRule) -> StoredEgressRule,
    private val remove: (String) -> Unit,
    private val now: () -> Instant = Instant::now,
) {
    constructor(repository: HighSensitivityRuleRepository) : this(repository::all, repository::save, repository::revoke)

    suspend fun load(): Result<List<EgressRuleRow>> =
        attempt {
            read().map { stored ->
                val rule = stored.rule
                val target =
                    when (val value = rule.target) {
                        is EgressTarget.Provider -> "provider:${value.id.value}"
                        is EgressTarget.Mcp -> "mcp:${value.id.value}"
                        is EgressTarget.A2a -> "a2a:${value.id.value}"
                    }
                EgressRuleRow(
                    stored.id,
                    target,
                    rule.origin.origin,
                    rule.dataCategory.name,
                    rule.scope.toScopeRef(),
                    rule.expiresAt,
                )
            }
        }

    suspend fun create(draft: EgressRuleDraft): Result<Unit> =
        attempt {
            val target =
                if (draft.provider) {
                    EgressTarget.Provider(
                        ProviderId(draft.targetId),
                    )
                } else {
                    EgressTarget.Mcp(McpServerId(draft.targetId))
                }
            save(
                HighSensitivityRule.withDuration(
                    target,
                    draft.origin,
                    WorkspaceScope(APP_SCOPE_ID),
                    draft.duration,
                    now(),
                ),
            )
            Unit
        }

    suspend fun revoke(id: String): Result<Unit> = attempt { remove(id) }

    private suspend fun <T> attempt(action: () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching(action).onFailure { failure ->
                if (failure is CancellationException || failure !is Exception) throw failure
            }
        }
}
