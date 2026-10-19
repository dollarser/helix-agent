package com.helix.app.scheduled

import com.helix.core.model.ReminderRepeat
import com.helix.core.model.ReminderTime
import com.helix.core.storage.scheduled.ScheduledAgentRule
import com.helix.core.storage.scheduled.ScheduledAgentStore
import com.helix.core.storage.scheduled.ScheduledAttempt
import com.helix.core.storage.scheduled.ScheduledOccurrence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Room owns admission/occurrences. Jobs and wake projections cannot authorize or replay a task. */
@Suppress("TooManyFunctions") // One durable rule/attempt lifecycle, with explicit UI and clock entry points.
class ScheduledAgentService(
    private val store: ScheduledAgentStore,
    private val execution: ScheduledAgentExecution,
    private val wake: ScheduledAgentWake,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executionMillis: Long = EXECUTION_MILLIS,
) {
    init {
        require(executionMillis in 1..EXECUTION_MILLIS)
    }

    private val producers = ConcurrentHashMap<String, Job>()
    private val userActions = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val rules = store.dao.observeRules()
    val occurrences = store.dao.observeOccurrences()
    val attempts = store.dao.observeAttempts()
    val failure = kotlinx.coroutines.flow.MutableStateFlow(false)

    suspend fun sessions() = withContext(Dispatchers.IO) { execution.sessions() }

    fun close() = userActions.cancel()

    // Failed UI actions remain visible; no execution success receipt is returned.
    @Suppress("TooGenericExceptionCaught")
    fun retryAsync(
        id: String,
        revision: Long,
        due: Long,
        expectedAttempt: String,
    ): Job =
        userActions.launch {
            try {
                failure.value = false
                retry(id, revision, due, expectedAttempt)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failure.value = true
            }
        }

    suspend fun prepare(draft: ScheduledAgentRule): PreparedScheduledRule =
        withContext(Dispatchers.IO) {
            validate(draft)
            val scope = execution.scope(draft.sessionId)
            PreparedScheduledRule(
                draft.copy(scopeJson = scope.encode(), scopeHash = scope.hash, creator = "LOCAL_USER"),
                scope,
            )
        }

    suspend fun save(prepared: PreparedScheduledRule) =
        withContext(Dispatchers.IO) {
            val draft = prepared.rule
            validate(draft)
            check(execution.scope(draft.sessionId).hash == draft.scopeHash) { "Scheduled scope changed" }
            var cancel: ScheduledAgentRule? = null
            val saved =
                store.transaction {
                    val previous = store.dao.rule(draft.id)
                    check(previous == null || previous.revision == draft.revision)
                    require(store.dao.rules().count { it.enabled && it.id != draft.id } < MAX_RULES || !draft.enabled)
                    val next = nextFor(draft, previous)
                    if (previous != null && canCancelFuture(previous)) cancel = previous
                    val rule =
                        draft.copy(
                            revision = (previous?.revision ?: 0) + 1,
                            nextAt = next,
                            status =
                                if (!draft.enabled) {
                                    "PAUSED"
                                } else if (next ==
                                    null
                                ) {
                                    "FINISHED"
                                } else {
                                    "PENDING"
                                },
                            updatedAt = clock(),
                        )
                    store.dao.save(rule)
                    rule
                }
            cancel?.let { wake.cancelFuture(it) }
            project(saved)
        }

    suspend fun pause(
        id: String,
        expectedRevision: Long,
    ) = withContext(Dispatchers.IO) {
        var cancel: ScheduledAgentRule? = null
        store.transaction {
            val rule = requireNotNull(store.dao.rule(id))
            check(rule.revision == expectedRevision)
            if (canCancelFuture(rule)) cancel = rule
            store.dao.save(
                rule.copy(
                    enabled = false,
                    nextAt = null,
                    revision = rule.revision + 1,
                    status = "PAUSED",
                    updatedAt = clock(),
                ),
            )
        }
        cancel?.let { wake.cancelFuture(it) }
    }

    /** The only automatic entry. Duplicate/stale/early/missed clocks never grant a second attempt. */
    suspend fun fire(
        id: String,
        revision: Long,
        due: Long,
    ) = withContext(Dispatchers.IO) {
        val producer = requireNotNull(currentCoroutineContext()[Job])
        refreshAttempts()
        val current = store.dao.rule(id) ?: return@withContext
        if (!current.enabled || current.revision != revision || current.nextAt != due) return@withContext
        if (due > clock()) {
            wake.schedule(current, afterCurrent = true)
            return@withContext
        }
        val attempt = store.transaction { admit(current, revision, due, producer) }
        try {
            if (attempt != null) execute(current, attempt)
        } finally {
            withContext(NonCancellable) { store.dao.rule(id)?.let { project(it) } }
        }
    }

    /** Explicit replay, still bound to the original occurrence, scope and destination. */
    suspend fun retry(
        id: String,
        revision: Long,
        due: Long,
        expectedAttempt: String,
    ) = withContext(Dispatchers.IO) {
        val producer = requireNotNull(currentCoroutineContext()[Job])
        refreshAttempts()
        val occurrence = requireNotNull(store.dao.occurrence(id, revision, due))
        val rule = ScheduledRuleSnapshot.decode(occurrence.ruleJson)
        check(execution.scope(rule.sessionId).hash == rule.scopeHash)
        if (occurrence.lastAttemptId.isNotEmpty()) {
            val previous = requireNotNull(store.dao.attempt(occurrence.lastAttemptId))
            val fact = execution.lookup(previous)
            check(fact == null || (!fact.live && !fact.unresolvedEffects && fact.state in TERMINAL - "NEEDS_REVIEW"))
        }
        val attempt =
            store.transaction {
                val latest = requireNotNull(store.dao.occurrence(id, revision, due))
                check(latest.lastAttemptId == expectedAttempt)
                check(latest.outcome in REPLAYABLE && store.dao.activeForSession(rule.sessionId) == 0)
                val attempt = newAttempt(rule, due, producer)
                store.dao.save(attempt)
                store.dao.save(latest.copy(outcome = "ADMITTING", lastAttemptId = attempt.id, recordedAt = clock()))
                attempt
            }
        execute(rule, attempt)
    }

    suspend fun stop(id: String) =
        withContext(Dispatchers.IO) {
            val attempt =
                store.transaction {
                    val current = requireNotNull(store.dao.attempt(id))
                    check(current.state in ACTIVE || current.state == "UNKNOWN")
                    current.copy(state = "STOP_REQUESTED").also { store.dao.save(it) }
                }
            val producer = producers[id]
            if (producer != null) producer.cancelAndJoin()
            val current = requireNotNull(store.dao.attempt(id))
            if (current.state in ACTIVE || current.state == "UNKNOWN") {
                val fact = execution.stopAndAwait(current, "SCHEDULED_USER_STOP")
                finish(current, stoppedOutcome(fact, true), fact?.turnId.orEmpty())
            }
        }

    /** Recovery observes existing Turn facts; it never calls submit/retry. Missed calendars skip. */
    suspend fun reconcile() =
        withContext(Dispatchers.IO) {
            refreshAttempts()
            store.dao.rules().forEach { rule ->
                val due = rule.nextAt
                if (rule.enabled && due != null) {
                    if (clock() - due > MISSED_GRACE) {
                        // Recovery is not a fresh clock execution authorization; never catch up.
                        store.transaction {
                            val current = store.dao.rule(rule.id)
                            if (current?.revision == rule.revision && current.nextAt == due) {
                                if (store.dao.occurrence(rule.id, rule.revision, due) == null) {
                                    store.dao.save(
                                        ScheduledOccurrence(
                                            rule.id,
                                            rule.revision,
                                            due,
                                            ScheduledRuleSnapshot.encode(rule),
                                            "MISSED",
                                            clock(),
                                            skippedThrough = clock(),
                                        ),
                                    )
                                }
                                advance(rule, due)
                            }
                        }
                        store.dao.rule(rule.id)?.let { project(it) }
                    } else {
                        project(rule)
                    }
                }
            }
        }

    suspend fun refreshAttempts() =
        withContext(Dispatchers.IO) {
            store.dao.unfinished().forEach { attempt ->
                if (producers[attempt.id]?.isCompleted == false) return@forEach
                val fact = execution.lookup(attempt)
                val state =
                    when {
                        fact == null -> "INTERRUPTED"
                        fact.waitingApproval -> "WAITING"
                        fact.live -> "RUNNING"
                        else -> outcome(fact)
                    }
                finish(attempt, state, fact?.turnId.orEmpty())
            }
        }

    @Suppress("TooGenericExceptionCaught") // Persist failure/unknown; never turn an exception into success.
    private suspend fun execute(rule: ScheduledAgentRule, initial: ScheduledAttempt) {
        var attempt = initial
        try {
            val admitted = requireNotNull(store.dao.attempt(attempt.id))
            if (!validateAdmitted(rule, admitted)) return
            withTimeout(executionMillis) {
                val turn = execution.submit(rule, attempt)
                attempt = attempt.copy(turnId = turn, state = "RUNNING")
                store.transaction {
                    val current = requireNotNull(store.dao.attempt(attempt.id))
                    store.dao.save(
                        current.copy(
                            turnId = turn,
                            state =
                                if (current.state ==
                                    "STOP_REQUESTED"
                                ) {
                                    current.state
                                } else {
                                    "RUNNING"
                                },
                        ),
                    )
                }
                observeAttempt(attempt, turn)
            }
        } catch (_: TimeoutCancellationException) {
            withContext(NonCancellable) {
                val fact = execution.stopAndAwait(attempt, "SCHEDULED_TIMEOUT")
                finish(attempt, stoppedOutcome(fact, false), fact?.turnId.orEmpty())
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                val userStop = store.dao.attempt(attempt.id)?.state == "STOP_REQUESTED"
                val fact =
                    execution.stopAndAwait(
                        attempt,
                        if (userStop) "SCHEDULED_USER_STOP" else "SCHEDULED_INTERRUPTED",
                    )
                finish(attempt, stoppedOutcome(fact, userStop), fact?.turnId.orEmpty())
            }
            throw cancel
        } catch (_: Exception) {
            val observed = execution.lookup(attempt)
            val fact =
                if (observed?.live ==
                    true
                ) {
                    execution.stopAndAwait(attempt, "SCHEDULED_INTERRUPTED")
                } else {
                    observed
                }
            finish(attempt, if (fact == null) "FAILED" else outcome(fact), fact?.turnId.orEmpty())
        }
    }

    private suspend fun observeAttempt(
        attempt: ScheduledAttempt,
        turn: String,
    ) {
        var last: ScheduledTurnFact? = null
        execution.observe(turn).collect { fact ->
            last = fact
            store.transaction {
                val current = requireNotNull(store.dao.attempt(attempt.id))
                if (current.state in setOf("RUNNING", "WAITING")) {
                    store.dao.save(current.copy(state = if (fact.waitingApproval) "WAITING" else "RUNNING"))
                }
            }
        }
        finish(attempt, last?.let(::outcome) ?: "UNKNOWN", turn)
    }

    // A missing/deleted configuration fails closed and pauses future execution, never a success fallback.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun scopeMatches(rule: ScheduledAgentRule): Boolean =
        try {
            execution.scope(rule.sessionId).hash == rule.scopeHash
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            false
        }

    private suspend fun validateAdmitted(
        rule: ScheduledAgentRule,
        attempt: ScheduledAttempt,
    ): Boolean {
        if (attempt.state == "STOP_REQUESTED") finish(attempt, "CANCELLED", "")
        if (attempt.state != "ADMITTING") return false
        val valid = scopeMatches(rule)
        if (!valid) {
            store.transaction {
                val current = store.dao.rule(rule.id)
                if (current?.revision == rule.revision) {
                    store.dao.save(current.copy(enabled = false, nextAt = null, status = "SCOPE_CHANGED"))
                }
                val latest = requireNotNull(store.dao.attempt(attempt.id))
                store.dao.save(latest.copy(detail = "SCOPE_CHANGED"))
            }
            finish(attempt, "FAILED", "")
        }
        return valid
    }

    private suspend fun admit(
        expected: ScheduledAgentRule,
        revision: Long,
        due: Long,
        producer: Job,
    ): ScheduledAttempt? {
        val rule = store.dao.rule(expected.id)
        if (rule == null || !matches(rule, revision, due) ||
            store.dao.occurrence(rule.id, revision, due) != null
        ) {
            return null
        }
        val outcome =
            when {
                clock() - due > MISSED_GRACE -> "MISSED"
                store.dao.activeForSession(rule.sessionId) > 0 || execution.isBusy(rule.sessionId) -> "BUSY"
                else -> "ADMITTING"
            }
        val attempt = if (outcome == "ADMITTING") newAttempt(rule, due, producer) else null
        store.dao.save(
            ScheduledOccurrence(
                rule.id,
                revision,
                due,
                ScheduledRuleSnapshot.encode(rule),
                outcome,
                clock(),
                attempt?.id.orEmpty(),
                if (outcome == "MISSED") clock() else null,
            ),
        )
        attempt?.let { store.dao.save(it) }
        advance(rule, due)
        return attempt
    }

    private suspend fun canCancelFuture(rule: ScheduledAgentRule): Boolean =
        rule.nextAt?.let { store.dao.attemptsAt(rule.id, rule.revision, it) == 0 } == true

    private fun nextFor(
        draft: ScheduledAgentRule,
        previous: ScheduledAgentRule?,
    ): Long? {
        val unchanged = previous?.enabled == true && draft.enabled && sameCalendar(previous, draft)
        val next =
            when {
                !draft.enabled -> null
                unchanged -> previous.nextAt
                else -> time(draft).next(clock())
            }
        require(!draft.enabled || unchanged || next != null)
        require(next == null || unchanged || next > clock())
        return next
    }

    private fun sameCalendar(
        left: ScheduledAgentRule,
        right: ScheduledAgentRule,
    ): Boolean = left.localTime == right.localTime && left.zone == right.zone && left.repeat == right.repeat

    private suspend fun finish(
        attempt: ScheduledAttempt,
        state: String,
        turn: String,
    ) = store.transaction {
        val current = requireNotNull(store.dao.attempt(attempt.id))
        if (current.state !in ACTIVE && current.state != "UNKNOWN") return@transaction
        store.dao.save(
            current.copy(
                state = state,
                turnId = turn.ifEmpty { current.turnId },
                completedAt = if (state in ACTIVE) null else clock(),
            ),
        )
        val occurrence = requireNotNull(store.dao.occurrence(attempt.ruleId, attempt.revision, attempt.dueAt))
        if (occurrence.lastAttemptId ==
            attempt.id
        ) {
            store.dao.save(occurrence.copy(outcome = state, recordedAt = clock()))
        }
    }

    suspend fun occurrenceOutcome(
        id: String,
        revision: Long,
        due: Long,
    ): String? = withContext(Dispatchers.IO) { store.dao.occurrence(id, revision, due)?.outcome }

    private suspend fun advance(
        rule: ScheduledAgentRule,
        due: Long,
    ) {
        val next = time(rule).next(maxOf(clock(), due))
        store.dao.save(
            rule.copy(
                enabled = next != null,
                nextAt = next,
                status =
                    if (next ==
                        null
                    ) {
                        "FINISHED"
                    } else {
                        "PENDING"
                    },
                updatedAt = clock(),
            ),
        )
    }

    private suspend fun project(rule: ScheduledAgentRule) {
        if (rule.enabled && rule.nextAt != null) {
            wake.schedule(rule)
            store.transaction {
                val current = store.dao.rule(rule.id)
                if (current?.revision == rule.revision &&
                    current.nextAt == rule.nextAt
                ) {
                    store.dao.save(current.copy(status = "ENQUEUED"))
                }
            }
        }
    }

    private fun newAttempt(
        rule: ScheduledAgentRule,
        due: Long,
        job: Job,
    ): ScheduledAttempt {
        val attempt =
            ScheduledAttempt(
                UUID.randomUUID().toString(),
                rule.id,
                rule.revision,
                due,
                rule.sessionId,
                "ADMITTING",
                clock(),
            )
        producers[attempt.id] = job
        job.invokeOnCompletion { producers.remove(attempt.id, job) }
        return attempt
    }

    private fun matches(
        rule: ScheduledAgentRule,
        revision: Long,
        due: Long,
    ): Boolean = rule.enabled && rule.revision == revision && rule.nextAt == due

    private fun time(rule: ScheduledAgentRule) =
        ReminderTime(LocalDateTime.parse(rule.localTime), rule.zone, ReminderRepeat.valueOf(rule.repeat))

    private fun validate(rule: ScheduledAgentRule) {
        require(rule.id.isNotBlank() && rule.id.length <= 200 && rule.sessionId.isNotBlank())
        require(
            rule.title.isNotBlank() && rule.title.length <= 160 && rule.prompt.isNotBlank() &&
                rule.prompt.length <= 6000,
        )
        time(rule).first()
    }

    private fun outcome(fact: ScheduledTurnFact): String =
        when {
            fact.unresolvedEffects -> {
                "UNKNOWN"
            }

            fact.waitingApproval -> {
                "WAITING"
            }

            else -> {
                when (fact.state) {
                    "COMPLETED" -> "COMPLETED"
                    "CANCELLED" -> "CANCELLED"
                    "FAILED" -> "FAILED"
                    "INTERRUPTED" -> "INTERRUPTED"
                    else -> "UNKNOWN"
                }
            }
        }

    private fun stoppedOutcome(
        fact: ScheduledTurnFact?,
        user: Boolean,
    ): String =
        when {
            fact == null -> if (user) "CANCELLED" else "INTERRUPTED"
            fact.live || fact.unresolvedEffects || fact.state == "NEEDS_REVIEW" -> "UNKNOWN"
            fact.state == "CANCELLED" -> if (user) "CANCELLED" else "INTERRUPTED"
            else -> outcome(fact)
        }

    companion object {
        const val MISSED_GRACE = 15 * 60 * 1000L
        const val EXECUTION_MILLIS = 2 * 60 * 1000L
        const val MAX_RULES = 32
        internal val ACTIVE = setOf("ADMITTING", "RUNNING", "STOP_REQUESTED", "WAITING")
        internal val TERMINAL = setOf("COMPLETED", "CANCELLED", "FAILED", "INTERRUPTED", "NEEDS_REVIEW")
        internal val REPLAYABLE = setOf("FAILED", "INTERRUPTED", "UNKNOWN", "MISSED", "BUSY")
    }
}
