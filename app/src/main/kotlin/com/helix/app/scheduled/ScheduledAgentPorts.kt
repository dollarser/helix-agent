package com.helix.app.scheduled

import com.helix.core.storage.scheduled.ScheduledAgentRule
import com.helix.core.storage.scheduled.ScheduledAttempt
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/** Public preview, never credentials. The hash also binds private provider/configuration facts. */
data class ScheduledAgentScope(
    val sessionId: String,
    val sessionTitle: String,
    val providerId: String,
    val providerName: String,
    val model: String,
    val origin: String,
    val residence: String,
    val workspace: String,
    val toolCount: Int,
    val hash: String,
    val connectionHash: String,
    val workspaceBinding: String,
    val bindings: String,
) {
    fun encode(): String =
        JSONObject()
            .put("sessionId", sessionId)
            .put("sessionTitle", sessionTitle)
            .put("providerId", providerId)
            .put("providerName", providerName)
            .put("model", model)
            .put("origin", origin)
            .put("residence", residence)
            .put("workspace", workspace)
            .put("toolCount", toolCount)
            .put("hash", hash)
            .put("connectionHash", connectionHash)
            .put("workspaceBinding", workspaceBinding)
            .put("bindings", bindings)
            .toString()

    companion object {
        fun decode(text: String): ScheduledAgentScope {
            val value = JSONObject(text)
            return ScheduledAgentScope(
                value.getString("sessionId"),
                value.getString("sessionTitle"),
                value.getString("providerId"),
                value.getString("providerName"),
                value.getString("model"),
                value.getString("origin"),
                value.getString("residence"),
                value.getString("workspace"),
                value.getInt("toolCount"),
                value.getString("hash"),
                value.getString("connectionHash"),
                value.getString("workspaceBinding"),
                value.getString("bindings"),
            )
        }
    }
}

data class ScheduledTurnFact(
    val turnId: String,
    val state: String,
    val live: Boolean = false,
    val waitingApproval: Boolean = false,
    val unresolvedEffects: Boolean = false,
)

data class ScheduledSessionChoice(
    val id: String,
    val title: String,
)

/** The production adapter uses the existing AgentRuntime/Dispatcher, not an alternate model loop. */
interface ScheduledAgentExecution {
    suspend fun sessions(): List<ScheduledSessionChoice>

    suspend fun scope(sessionId: String): ScheduledAgentScope

    suspend fun isBusy(sessionId: String): Boolean = false

    suspend fun submit(
        rule: ScheduledAgentRule,
        attempt: ScheduledAttempt,
    ): String

    suspend fun lookup(attempt: ScheduledAttempt): ScheduledTurnFact?

    fun observe(turnId: String): Flow<ScheduledTurnFact>

    suspend fun stopAndAwait(
        attempt: ScheduledAttempt,
        reason: String,
    ): ScheduledTurnFact?
}

/** A replaceable projection; input data only identifies a rule/revision/instant. */
interface ScheduledAgentWake {
    suspend fun schedule(
        rule: ScheduledAgentRule,
        afterCurrent: Boolean = false,
    )

    suspend fun cancelFuture(rule: ScheduledAgentRule)
}

class PreparedScheduledRule internal constructor(
    val rule: ScheduledAgentRule,
    val scope: ScheduledAgentScope,
)

internal object ScheduledRuleSnapshot {
    fun encode(rule: ScheduledAgentRule): String =
        JSONObject()
            .put("id", rule.id)
            .put("title", rule.title)
            .put("prompt", rule.prompt)
            .put("scope", rule.scopeJson)
            .put("scopeHash", rule.scopeHash)
            .put("sessionId", rule.sessionId)
            .put("localTime", rule.localTime)
            .put("zone", rule.zone)
            .put("repeat", rule.repeat)
            .put("revision", rule.revision)
            .put("creator", rule.creator)
            .toString()

    fun decode(value: String): ScheduledAgentRule {
        val rule = JSONObject(value)
        return ScheduledAgentRule(
            rule.getString("id"),
            rule.getString("title"),
            rule.getString("prompt"),
            rule.getString("scope"),
            rule.getString("scopeHash"),
            rule.getString("sessionId"),
            rule.getString("localTime"),
            rule.getString("zone"),
            rule.getString("repeat"),
            revision = rule.getLong("revision"),
            creator = rule.getString("creator"),
        )
    }
}
