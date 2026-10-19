package com.helix.app.scheduled

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.scheduled.ScheduledAgentRule
import com.helix.core.storage.scheduled.ScheduledAgentStore
import com.helix.core.storage.scheduled.ScheduledAttempt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

internal class ScheduledFixture(
    private val limit: Long = ScheduledRuntimeBudget.DEFAULT_MILLIS,
) : AutoCloseable {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "scheduled-test-${UUID.randomUUID()}.db"
    val store = ScheduledAgentStore(context, name)
    val now = AtomicLong(System.currentTimeMillis())
    val execution = ScheduledFakeExecution()
    val wake = ScheduledFakeWake()
    val service = ScheduledAgentService(store, execution, wake, now::get)

    fun draft(
        id: String = UUID.randomUUID().toString(),
        repeat: String = "ONCE",
        enabled: Boolean = true,
    ) = ScheduledAgentRule(
        id,
        "Synthetic rule",
        "Summarize synthetic facts only",
        "",
        "",
        "bound",
        Instant
            .ofEpochMilli(now.get() + 60_000)
            .atOffset(ZoneOffset.UTC)
            .toLocalDateTime()
            .toString(),
        "UTC",
        repeat,
        enabled = enabled,
    )

    suspend fun save(draft: ScheduledAgentRule = draft()): ScheduledAgentRule {
        service.save(service.prepare(draft, if (draft.scopeJson.isBlank()) limit else draft.runtimeBudgetMillis))
        return requireNotNull(store.dao.rule(draft.id))
    }

    suspend fun fire(rule: ScheduledAgentRule) {
        now.set(requireNotNull(rule.nextAt))
        service.fire(rule.id, rule.revision, requireNotNull(rule.nextAt))
    }

    override fun close() {
        service.close()
        store.close()
        context.deleteDatabase(name)
    }
}

internal class ScheduledFakeExecution : ScheduledAgentExecution {
    var hash = "scope-a"
    var invalidScope = false
    var externalBusy = false

    override suspend fun isBusy(sessionId: String) = externalBusy

    var completion = "COMPLETED"
    var unresolved = false
    var missingAck = false
    var hold: CompletableDeferred<Unit>? = null
    var physicalExit: CompletableDeferred<Unit>? = null
    val started = CompletableDeferred<Unit>()
    val stopRequested = CompletableDeferred<Unit>()
    val submitted = CopyOnWriteArrayList<Pair<ScheduledAgentRule, ScheduledAttempt>>()
    val facts = ConcurrentHashMap<String, ScheduledTurnFact>()

    override suspend fun sessions() = listOf(ScheduledSessionChoice("bound", "Synthetic result session"))

    override suspend fun scope(
        sessionId: String,
        maxRuntimeMillis: Long,
    ): ScheduledAgentScope {
        check(!invalidScope) { "Synthetic deleted configuration" }
        return ScheduledAgentScope(
            sessionId,
            "Synthetic result session",
            "fixture",
            "Synthetic model",
            "fixture-model",
            "https://synthetic.invalid",
            "REMOTE",
            "synthetic/",
            0,
            "$hash:$maxRuntimeMillis",
            "connection",
            "workspace",
            "[]",
            maxRuntimeMillis,
        )
    }

    override suspend fun submit(
        rule: ScheduledAgentRule,
        attempt: ScheduledAttempt,
    ): String {
        submitted += rule to attempt
        val id = "turn-${attempt.id}"
        facts[attempt.id] =
            ScheduledTurnFact(
                id,
                if (hold == null) completion else "WAITING_MODEL",
                live = hold != null,
                unresolvedEffects = unresolved,
            )
        started.complete(Unit)
        check(!missingAck) { "Synthetic lost ACK" }
        return id
    }

    override suspend fun lookup(attempt: ScheduledAttempt) = facts[attempt.id]

    override fun observe(turnId: String): Flow<ScheduledTurnFact> =
        flow {
            val id = turnId.removePrefix("turn-")
            emit(requireNotNull(facts[id]))
            hold?.await()
            val final = requireNotNull(facts[id]).copy(state = completion, live = false, unresolvedEffects = unresolved)
            facts[id] = final
            emit(final)
        }

    override suspend fun stopAndAwait(
        attempt: ScheduledAttempt,
        reason: String,
    ): ScheduledTurnFact? {
        stopRequested.complete(Unit)
        physicalExit?.await()
        val current = facts[attempt.id] ?: return null
        val stopped = current.copy(state = "CANCELLED", live = false)
        facts[attempt.id] = stopped
        return stopped
    }
}

internal class ScheduledFakeWake : ScheduledAgentWake {
    val scheduled = CopyOnWriteArrayList<Pair<ScheduledAgentRule, Boolean>>()
    val cancelled = CopyOnWriteArrayList<ScheduledAgentRule>()
    var fail = false

    override suspend fun schedule(
        rule: ScheduledAgentRule,
        afterCurrent: Boolean,
    ) {
        check(!fail) { "Synthetic projection failure" }
        scheduled += rule to afterCurrent
    }

    override suspend fun cancelFuture(rule: ScheduledAgentRule) {
        cancelled += rule
    }
}
