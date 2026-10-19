package com.helix.app.scheduled

import com.helix.core.storage.scheduled.ScheduledAgentRule
import com.helix.core.storage.scheduled.ScheduledAttempt
import com.helix.core.storage.scheduled.ScheduledOccurrence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledAgentLifecycleDeviceTest {
    @Test fun previewAndDisabledSaveCannotExecute() =
        runBlocking {
            ScheduledFixture().use { f ->
                val preview = f.service.prepare(f.draft(enabled = false))
                assertTrue(
                    f.store.dao
                        .rules()
                        .isEmpty(),
                )
                assertTrue(f.wake.scheduled.isEmpty())
                f.service.save(preview)
                val rule =
                    f.store.dao
                        .rules()
                        .single()
                assertFalse(rule.enabled)
                f.service.fire(rule.id, rule.revision, f.now.get())
                assertTrue(f.execution.submitted.isEmpty())
            }
        }

    @Test fun duplicateConcurrentAndStaleClocksAdmitOnce() =
        runBlocking {
            ScheduledFixture().use { f ->
                val rule = f.save()
                f.now.set(requireNotNull(rule.nextAt))
                coroutineScope {
                    (1..5)
                        .map {
                            async {
                                f.service.fire(
                                    rule.id,
                                    rule.revision,
                                    requireNotNull(rule.nextAt),
                                )
                            }
                        }.awaitAll()
                }
                f.service.fire(rule.id, rule.revision - 1, requireNotNull(rule.nextAt))
                assertEquals(1, f.execution.submitted.size)
                assertEquals(
                    1,
                    f.store.dao
                        .observeOccurrences()
                        .first()
                        .size,
                )
                assertEquals(
                    "COMPLETED",
                    f.store.dao
                        .observeAttempts()
                        .first()
                        .single()
                        .state,
                )
            }
        }

    @Test fun earlyWakeOnlyProjectsAfterCurrentWorker() =
        runBlocking {
            ScheduledFixture().use { f ->
                val rule = f.save()
                f.service.fire(rule.id, rule.revision, requireNotNull(rule.nextAt))
                assertTrue(
                    f.wake.scheduled
                        .last()
                        .second,
                )
                assertTrue(f.execution.submitted.isEmpty())
                assertTrue(
                    f.store.dao
                        .observeOccurrences()
                        .first()
                        .isEmpty(),
                )
            }
        }

    @Test fun missedRecurrenceSkipsMergedIntervalsWithoutCatchup() =
        runBlocking {
            ScheduledFixture().use { f ->
                val rule = f.save(f.draft(repeat = "DAILY"))
                f.now.set(requireNotNull(rule.nextAt) + 10 * 24 * 60 * 60 * 1000L)
                f.service.fire(rule.id, rule.revision, requireNotNull(rule.nextAt))
                f.service.reconcile()
                assertTrue(f.execution.submitted.isEmpty())
                assertEquals(
                    "MISSED",
                    f.store.dao
                        .observeOccurrences()
                        .first()
                        .single()
                        .outcome,
                )
                assertTrue(
                    requireNotNull(
                        f.store.dao
                            .rule(rule.id)
                            ?.nextAt,
                    ) > f.now.get(),
                )
            }
        }

    @Test fun busyBoundSessionSkipsSecondRule() =
        runBlocking {
            ScheduledFixture().use { f ->
                f.execution.hold = CompletableDeferred()
                val first = f.save()
                val second = f.save()
                val producer = async { f.fire(first) }
                f.execution.started.await()
                f.service.fire(second.id, second.revision, requireNotNull(second.nextAt))
                assertEquals(
                    "BUSY",
                    f.service.occurrenceOutcome(second.id, second.revision, requireNotNull(second.nextAt)),
                )
                f.execution.hold?.complete(Unit)
                producer.await()
                assertEquals(1, f.execution.submitted.size)
                f.execution.externalBusy = true
                val normalBusy = f.save()
                f.fire(normalBusy)
                assertEquals(
                    "BUSY",
                    f.service.occurrenceOutcome(
                        normalBusy.id,
                        normalBusy.revision,
                        requireNotNull(normalBusy.nextAt),
                    ),
                )
                assertEquals(1, f.execution.submitted.size)
            }
        }

    @Test fun pauseAndEditDoNotStopOrRetargetOriginalAttempt() =
        runBlocking {
            ScheduledFixture().use { f ->
                f.execution.hold = CompletableDeferred()
                val rule = f.save(f.draft(repeat = "DAILY"))
                val producer = async { f.fire(rule) }
                f.execution.started.await()
                f.service.pause(rule.id, rule.revision)
                val paused = requireNotNull(f.store.dao.rule(rule.id))
                f.service.save(
                    f.service.prepare(paused.copy(prompt = "New future prompt", sessionId = "other", enabled = false)),
                )
                assertFalse(f.execution.stopRequested.isCompleted)
                val (snapshot, attempt) = f.execution.submitted.single()
                assertEquals("bound", attempt.sessionId)
                assertEquals(rule.prompt, snapshot.prompt)
                f.execution.hold?.complete(Unit)
                producer.await()
                assertEquals(
                    "COMPLETED",
                    f.store.dao
                        .observeAttempts()
                        .first()
                        .single()
                        .state,
                )
                assertEquals(
                    "bound",
                    ScheduledRuleSnapshot
                        .decode(
                            f.store.dao
                                .observeOccurrences()
                                .first()
                                .single()
                                .ruleJson,
                        ).sessionId,
                )
            }
        }

    @Test fun changedScopeRequiresReconfirmationAndPausesFuture() =
        runBlocking {
            ScheduledFixture().use { f ->
                val preview = f.service.prepare(f.draft())
                f.execution.hash = "scope-b"
                rejects { f.service.save(preview) }
                assertTrue(
                    f.store.dao
                        .rules()
                        .isEmpty(),
                )
                f.execution.hash = "scope-a"
                val rule = f.save(f.draft(repeat = "DAILY"))
                f.execution.hash = "scope-b"
                f.fire(rule)
                assertEquals(
                    "FAILED",
                    f.store.dao
                        .observeAttempts()
                        .first()
                        .single()
                        .state,
                )
                assertEquals(
                    "SCOPE_CHANGED",
                    f.store.dao
                        .rule(rule.id)
                        ?.status,
                )
                assertFalse(requireNotNull(f.store.dao.rule(rule.id)).enabled)
                assertTrue(f.execution.submitted.isEmpty())
                f.execution.hash = "scope-a"
                val paused = requireNotNull(f.store.dao.rule(rule.id))
                val reenabled = f.save(paused.copy(enabled = true))
                f.execution.invalidScope = true
                f.fire(reenabled)
                assertEquals(
                    "SCOPE_CHANGED",
                    f.store.dao
                        .rule(rule.id)
                        ?.status,
                )
                assertTrue(f.execution.submitted.isEmpty())
            }
        }

    @Test fun stoppingWaitsForPhysicalExitAndKeepsOriginalIdentity(): Unit =
        runBlocking {
            ScheduledFixture().use { f ->
                f.execution.hold = CompletableDeferred()
                f.execution.physicalExit = CompletableDeferred()
                val rule = f.save()
                val producer = async { f.fire(rule) }
                f.execution.started.await()
                val attempt =
                    f.store.dao
                        .observeAttempts()
                        .first { it.isNotEmpty() }
                        .single()
                val stopping = async { f.service.stop(attempt.id) }
                f.execution.stopRequested.await()
                // A cancelled producer still owns cleanup until its actual executor exits.
                f.service.refreshAttempts()
                delay(40)
                assertFalse(stopping.isCompleted)
                assertEquals(
                    "STOP_REQUESTED",
                    f.store.dao
                        .attempt(attempt.id)
                        ?.state,
                )
                f.execution.physicalExit?.complete(Unit)
                stopping.await()
                producer.join()
                assertEquals(
                    "CANCELLED",
                    f.store.dao
                        .attempt(attempt.id)
                        ?.state,
                )
                assertEquals(
                    "bound",
                    f.execution.submitted
                        .single()
                        .second.sessionId,
                )
            }
        }

    @Test fun timeoutStopsOriginalTurnAndRecordsInterrupted(): Unit =
        runBlocking {
            ScheduledFixture(limit = 100).use { f ->
                f.execution.hold = CompletableDeferred()
                f.fire(f.save())
                assertTrue(f.execution.stopRequested.isCompleted)
                assertEquals(
                    "INTERRUPTED",
                    f.store.dao
                        .observeAttempts()
                        .first()
                        .single()
                        .state,
                )
                assertEquals(1, f.execution.submitted.size)
            }
        }

    @Test fun lostAckAdoptsExistingCompletedTurnWithoutResubmit() =
        runBlocking {
            ScheduledFixture().use { f ->
                f.execution.missingAck = true
                val rule = f.save()
                f.fire(rule)
                f.service.reconcile()
                f.service.fire(rule.id, rule.revision, requireNotNull(rule.nextAt))
                val attempt =
                    f.store.dao
                        .observeAttempts()
                        .first()
                        .single()
                assertEquals("COMPLETED", attempt.state)
                assertEquals("turn-${attempt.id}", attempt.turnId)
                assertEquals(1, f.execution.submitted.size)
            }
        }

    @Test fun recoveryObservesExistingFactsWithoutRestartingAttempts() =
        runBlocking {
            ScheduledFixture().use { f ->
                val a = seed(f, f.save(), "lost")
                val b = seed(f, f.save(f.draft().copy(sessionId = "other")), "known")
                f.execution.facts[b.id] = ScheduledTurnFact("old-turn", "COMPLETED")
                f.service.reconcile()
                assertEquals(
                    "INTERRUPTED",
                    f.store.dao
                        .attempt(a.id)
                        ?.state,
                )
                assertEquals(
                    "COMPLETED",
                    f.store.dao
                        .attempt(b.id)
                        ?.state,
                )
                assertTrue(f.execution.submitted.isEmpty())
            }
        }

    @Test fun unresolvedEffectsBlockRetryUntilResolvedThenUseNewAttempt() =
        runBlocking {
            ScheduledFixture().use { f ->
                f.execution.completion = "NEEDS_REVIEW"
                f.execution.unresolved = true
                val rule = f.save()
                f.fire(rule)
                val old =
                    f.store.dao
                        .observeAttempts()
                        .first()
                        .single()
                assertEquals("UNKNOWN", old.state)
                rejects { f.service.retry(rule.id, rule.revision, old.dueAt, old.id) }
                assertEquals(1, f.execution.submitted.size)
                f.execution.facts[old.id] = ScheduledTurnFact(old.turnId, "FAILED")
                f.execution.completion = "COMPLETED"
                f.execution.unresolved = false
                f.service.retry(rule.id, rule.revision, old.dueAt, old.id)
                val attempts =
                    f.store.dao
                        .observeAttempts()
                        .first()
                assertEquals(2, attempts.size)
                assertNotEquals(old.id, attempts.first { it.id != old.id }.id)
                assertTrue(attempts.all { it.dueAt == old.dueAt && it.sessionId == old.sessionId })
                rejects { f.service.retry(rule.id, rule.revision, old.dueAt, old.id) }
            }
        }

    @Test fun projectionFailureKeepsDurableRuleForRecovery() =
        runBlocking {
            ScheduledFixture().use { f ->
                f.wake.fail = true
                rejects { f.save() }
                assertEquals(
                    "PENDING",
                    f.store.dao
                        .rules()
                        .single()
                        .status,
                )
                assertTrue(f.execution.submitted.isEmpty())
                f.wake.fail = false
                f.service.reconcile()
                assertEquals(
                    "ENQUEUED",
                    f.store.dao
                        .rules()
                        .single()
                        .status,
                )
                assertEquals(1, f.wake.scheduled.size)
            }
        }

    @Test fun finishedOnceFreesEnabledCapacityButFutureRulesRemainBounded() =
        runBlocking {
            ScheduledFixture().use { f ->
                val completed = f.save()
                f.fire(completed)
                assertFalse(requireNotNull(f.store.dao.rule(completed.id)).enabled)
                repeat(ScheduledAgentService.MAX_RULES) { f.save() }
                rejects { f.save() }
                assertEquals(
                    ScheduledAgentService.MAX_RULES,
                    f.store.dao
                        .rules()
                        .count { it.enabled },
                )
            }
        }

    @Test fun runtimeBudgetIsPersistedAndBoundWithoutAProductWideCeiling(): Unit =
        runBlocking {
            ScheduledFixture().use { fixture ->
                val draft = fixture.draft()
                val prepared = fixture.service.prepare(draft, 300_000)
                assertEquals(300_000L, prepared.scope.maxRuntimeMillis)
                assertTrue(
                    fixture.store.dao
                        .rules()
                        .isEmpty(),
                )
                val tamperedScope = prepared.scope.copy(maxRuntimeMillis = 900_000)
                rejects {
                    fixture.service.save(
                        PreparedScheduledRule(prepared.rule.copy(scopeJson = tamperedScope.encode()), tamperedScope),
                    )
                }
                rejects { fixture.service.prepare(draft, 0) }
                rejects { fixture.service.prepare(draft, -1) }
                fixture.service.save(prepared)
                val saved = requireNotNull(fixture.store.dao.rule(draft.id))
                assertEquals(300_000L, saved.runtimeBudgetMillis)
                fixture.fire(saved)
                val occurrence =
                    fixture.store.dao
                        .observeOccurrences()
                        .first()
                        .single()
                assertEquals(300_000L, ScheduledRuleSnapshot.decode(occurrence.ruleJson).runtimeBudgetMillis)
                // Existing fixed-default confirmations retain that exact budget without deleting their data.
                val originalDefault = fixture.service.prepare(fixture.draft(), 120_000)
                val existing = org.json.JSONObject(originalDefault.scope.encode()).apply { remove("maxRuntimeMillis") }
                val existingRule = originalDefault.rule.copy(scopeJson = existing.toString())
                assertEquals(120_000L, existingRule.runtimeBudgetMillis)
                fixture.service.save(
                    PreparedScheduledRule(existingRule, ScheduledAgentScope.decode(existing.toString())),
                )
                fixture.fire(requireNotNull(fixture.store.dao.rule(existingRule.id)))
                assertEquals(
                    2,
                    fixture.store.dao
                        .observeAttempts()
                        .first()
                        .size,
                )
                val invalid = org.json.JSONObject(prepared.scope.encode()).put("maxRuntimeMillis", "300000")
                rejects { ScheduledAgentScope.decode(invalid.toString()) }
            }
        }

    @Test fun editingRuleBudgetAffectsOnlyFutureOccurrences(): Unit =
        runBlocking {
            ScheduledFixture().use { fixture ->
                fixture.execution.hold = CompletableDeferred()
                val draft = fixture.draft(repeat = "DAILY")
                fixture.service.save(fixture.service.prepare(draft, 300_000))
                val original = requireNotNull(fixture.store.dao.rule(draft.id))
                val producer = async { fixture.fire(original) }
                fixture.execution.started.await()
                val advanced = requireNotNull(fixture.store.dao.rule(draft.id))
                fixture.service.save(fixture.service.prepare(advanced, 900_000))
                val edited = requireNotNull(fixture.store.dao.rule(draft.id))
                assertEquals(900_000L, edited.runtimeBudgetMillis)
                assertTrue(edited.scopeHash != original.scopeHash)
                assertEquals(original.revision + 1, edited.revision)
                assertEquals(
                    300_000L,
                    fixture.execution.submitted
                        .single()
                        .first.runtimeBudgetMillis,
                )
                val occurrence =
                    fixture.store.dao
                        .observeOccurrences()
                        .first()
                        .single()
                assertEquals(300_000L, ScheduledRuleSnapshot.decode(occurrence.ruleJson).runtimeBudgetMillis)
                fixture.execution.hold?.complete(Unit)
                producer.await()
                assertEquals(
                    "COMPLETED",
                    fixture.store.dao
                        .observeAttempts()
                        .first()
                        .single()
                        .state,
                )
            }
        }

    private suspend fun seed(
        f: ScheduledFixture,
        rule: ScheduledAgentRule,
        id: String,
    ): ScheduledAttempt {
        val due = requireNotNull(rule.nextAt)
        val attempt = ScheduledAttempt(id, rule.id, rule.revision, due, rule.sessionId, "ADMITTING", f.now.get())
        f.store.transaction {
            f.store.dao.save(
                ScheduledOccurrence(
                    rule.id,
                    rule.revision,
                    due,
                    ScheduledRuleSnapshot.encode(rule),
                    "ADMITTING",
                    f.now.get(),
                    id,
                ),
            )
            f.store.dao.save(attempt)
            f.store.dao.save(rule.copy(nextAt = null))
        }
        return attempt
    }

    private suspend fun rejects(block: suspend () -> Unit) {
        var failed = false
        try {
            withTimeout(5000) { block() }
        } catch (_: IllegalStateException) {
            failed = true
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Expected fail-closed rejection", failed)
    }
}
