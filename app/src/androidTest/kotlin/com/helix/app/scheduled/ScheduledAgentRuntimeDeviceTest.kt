package com.helix.app.scheduled

import com.helix.app.chat.ScriptedSseWire
import com.helix.app.chat.sseResponse
import com.helix.app.chat.textAnswerStream
import com.helix.app.chat.toolCallStream
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.ToolOperationClass
import com.helix.core.policy.SessionPermissionConfig
import com.helix.tools.framework.TimeNowTool
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledAgentRuntimeDeviceTest {
    @Test fun runtimeUsesPlanAndOriginalSessionWithAuditedToolBackfill(): Unit =
        runBlocking {
            val wire =
                ScriptedSseWire().apply {
                    script(
                        sseResponse(toolCallStream("safe-time", "\"time.now\"", "{}")),
                        sseResponse(textAnswerStream("Synthetic clock reviewed.")),
                    )
                }
            ScheduledRuntimeFixture(wire.wireClient).use { f ->
                val attempt = f.execute("Use time.now, then report the synthetic test clock.")
                assertEquals("COMPLETED", attempt.state)
                assertEquals(
                    "PLAN",
                    f.storage.turnRuntimeRecords
                        .find(attempt.turnId)
                        ?.mode,
                )
                assertEquals(
                    "bound",
                    f.storage.turns
                        .resolve(attempt.turnId)
                        .sessionId,
                )
                assertEquals("foreground", f.chat.screen.value.openSessionId)
                assertTrue(
                    f.storage.turns
                        .listBySession("foreground")
                        .isEmpty(),
                )
                assertEquals(2, wire.callCount)
                assertTrue(wire.lastRequestBody.contains("tool_call_id"))
                assertEquals(
                    "time.now",
                    f.storage.toolCalls
                        .listByTurn(attempt.turnId)
                        .single()
                        .name,
                )
                assertTrue(
                    f.storage.auditEvents
                        .recent(20)
                        .any { it.type == "tool_dispatch" },
                )
            }
        }

    @Test fun mutationRequestNeverReachesExecutor(): Unit =
        runBlocking {
            val wire =
                ScriptedSseWire().apply {
                    script(
                        sseResponse(toolCallStream("unsafe", "\"synthetic.write\"", "{}")),
                        sseResponse(textAnswerStream("Cannot perform the write.")),
                    )
                }
            ScheduledRuntimeFixture(wire.wireClient).use { f ->
                var executed = false
                val descriptor =
                    TimeNowTool.descriptor().copy(
                        name =
                            com.helix.core.model
                                .ToolName("synthetic.write"),
                        operationClass = ToolOperationClass.LOCAL_MUTATION,
                    )
                f.pipeline.registry.register(
                    descriptor,
                    object : com.helix.tools.framework.ToolExecutor {
                        override fun execute(
                            call: com.helix.tools.framework.ExecutableToolCall,
                        ): com.helix.tools.framework.ToolExecutorResult {
                            executed = true
                            error("Must not execute")
                        }
                    },
                )
                f.execute("Try synthetic.write only; it must be denied by read-only policy.")
                assertFalse(executed)
                assertTrue(
                    f.storage.toolCalls
                        .listByTurn(
                            f.ledger.dao
                                .observeAttempts()
                                .first()
                                .single()
                                .turnId,
                        ).none { it.state == "SUCCEEDED" },
                )
            }
        }

    @Test fun eachModelRequestRejectsChangedTargetWorkspaceOrPrivilegeScope(): Unit =
        runBlocking {
            val wire = ScriptedSseWire().apply { script(sseResponse(textAnswerStream("Synthetic answer"))) }
            ScheduledRuntimeFixture(wire.wireClient).use { f ->
                val attempt = f.execute("Summarize synthetic facts.")
                assertEquals("COMPLETED", attempt.state)
                f.ledger.dao.save(attempt.copy(state = "RUNNING"))
                val call =
                    f.storage.modelCalls
                        .listByTurn(attempt.turnId)
                        .single()
                val config = f.providers.storedConfig(com.helix.app.provider.BuiltInModel.PROVIDER_ID)
                val descriptor = f.providers.modelProviderFor(config.id, config.model).descriptor
                val request =
                    ModelRequest(
                        descriptor.model,
                        listOf(ModelMessage(ModelRole.USER, "Synthetic")),
                        maxOutputTokens = 2048,
                    )
                f.execution.verifyModelRequest(attempt.turnId, call.id, descriptor, request)
                rejects {
                    f.execution.verifyModelRequest(
                        attempt.turnId,
                        call.id,
                        descriptor,
                        request.copy(model = "other"),
                    )
                }
                rejects { f.execution.verifyModelRequest(attempt.turnId, "missing-call", descriptor, request) }
                rejects {
                    f.execution.verifyModelRequest(
                        attempt.turnId,
                        call.id,
                        descriptor.copy(id = "other"),
                        request,
                    )
                }
                rejects {
                    f.execution.verifyModelRequest(
                        attempt.turnId,
                        call.id,
                        descriptor,
                        request.copy(maxOutputTokens = 2049),
                    )
                }
                val binding = requireNotNull(f.storage.workspaces.binding("bound"))
                f.storage.workspaces.bind(
                    "bound",
                    com.helix.core.workspace
                        .FileScopePath(binding.workspaceId, "changed")
                        .toModelReference(),
                )
                rejects { f.execution.verifyModelRequest(attempt.turnId, call.id, descriptor, request) }
                assertEquals(1, wire.callCount)
            }
        }

    @Test fun persistentToolIdentityExcludesOnlyProcessIncarnation() {
        val registry =
            com.helix.tools.framework
                .ToolRegistry()
        registry.register(
            TimeNowTool.descriptor(),
            TimeNowTool.executor(
                com.helix.core.model
                    .SystemClock(),
            ),
        )
        val ref = registry.snapshot().single().ref
        assertEquals(
            RuntimeScheduledAgentExecution.bindingIdentity(ref),
            RuntimeScheduledAgentExecution.bindingIdentity(ref.copy(incarnation = "new-process")),
        )
        assertTrue(
            RuntimeScheduledAgentExecution.bindingIdentity(ref) !=
                RuntimeScheduledAgentExecution.bindingIdentity(ref.copy(implementationRevision = "different")),
        )
    }

    @Test fun missingPreviouslyRecordedTurnIsUnknownRatherThanNeverExecuted(): Unit =
        runBlocking {
            ScheduledRuntimeFixture(ScriptedSseWire().wireClient).use { f ->
                val attempt =
                    com.helix.core.storage.scheduled.ScheduledAttempt(
                        "lost",
                        "rule",
                        1,
                        1,
                        "bound",
                        "UNKNOWN",
                        System.currentTimeMillis(),
                        turnId = "previously-recorded",
                    )
                val fact = requireNotNull(f.execution.lookup(attempt))
                assertEquals("NEEDS_REVIEW", fact.state)
                assertTrue(fact.unresolvedEffects)
            }
        }

    @Test fun changedRealSessionPermissionsRejectNextModelRequestBeforeWire(): Unit =
        runBlocking {
            rejectNextRequestAfterRealChange { fixture ->
                fixture.storage.sessionPermissionConfigs.setForSession(
                    "bound",
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                assertEquals(
                    SessionPermissionMode.FULL_ACCESS,
                    fixture.storage.sessionPermissionConfigs
                        .forSession("bound")
                        ?.mode,
                )
            }
        }

    @Test fun changedRealToolEligibilityRejectsNextModelRequestBeforeWire(): Unit =
        runBlocking {
            rejectNextRequestAfterRealChange { fixture ->
                fixture.storage.toolAvailability.set(
                    TimeNowTool.descriptor().origin.canonicalOf(),
                    "time.now",
                    ToolAvailabilityScope.SESSION,
                    "bound",
                    ToolAvailabilityState.DISABLED,
                    System.currentTimeMillis(),
                )
                assertFalse(requireNotNull(fixture.pipeline.disabledToolFilter)("bound", TimeNowTool.descriptor()))
            }
        }

    private suspend fun rejectNextRequestAfterRealChange(change: (ScheduledRuntimeFixture) -> Unit) {
        val wire =
            ScriptedSseWire().apply {
                script(
                    sseResponse(toolCallStream("actual-time", "\"time.now\"", "{}")),
                    sseResponse(textAnswerStream("Must never be requested after authorization changes")),
                )
            }
        ScheduledRuntimeFixture(wire.wireClient).use { fixture ->
            var changed = false
            fixture.afterTool = {
                change(fixture)
                changed = true
            }
            val attempt = fixture.execute("Read time.now and then summarize the result.")
            assertTrue(changed)
            assertEquals("FAILED", attempt.state)
            assertEquals(1, wire.callCount)
            // A second model-call admission exists, but its pre-stream scope guard rejects the wire call.
            assertEquals(
                2,
                fixture.storage.modelCalls
                    .listByTurn(attempt.turnId)
                    .size,
            )
            val call =
                fixture.storage.toolCalls
                    .listByTurn(attempt.turnId)
                    .single()
            assertTrue(fixture.storage.toolResults.byToolCall(call.id) != null)
            val rule =
                ScheduledRuleSnapshot.decode(
                    fixture.ledger.dao
                        .observeOccurrences()
                        .first()
                        .single()
                        .ruleJson,
                )
            assertTrue(rule.scopeHash != fixture.execution.scope("bound", rule.runtimeBudgetMillis).hash)
        }
    }

    @Test fun confirmedRuleBudgetBeyondTwoMinutesRemainsValidUntilItsOwnDeadline(): Unit =
        runBlocking {
            val wire = ScriptedSseWire().apply { script(sseResponse(textAnswerStream("Synthetic answer"))) }
            ScheduledRuntimeFixture(wire.wireClient).use { fixture ->
                val attempt = fixture.execute("Summarize synthetic facts.", 300_000)
                assertEquals("COMPLETED", attempt.state)
                val call =
                    fixture.storage.modelCalls
                        .listByTurn(attempt.turnId)
                        .single()
                val config = fixture.providers.storedConfig(com.helix.app.provider.BuiltInModel.PROVIDER_ID)
                val provider = fixture.providers.modelProviderFor(config.id, config.model).descriptor
                val request =
                    ModelRequest(
                        provider.model,
                        listOf(ModelMessage(ModelRole.USER, "Synthetic")),
                        maxOutputTokens = 2048,
                    )
                fixture.ledger.dao.save(
                    attempt.copy(state = "RUNNING", createdAt = System.currentTimeMillis() - 180_000),
                )
                fixture.execution.verifyModelRequest(attempt.turnId, call.id, provider, request)
                fixture.ledger.dao.save(
                    attempt.copy(state = "RUNNING", createdAt = System.currentTimeMillis() - 300_001),
                )
                rejects { fixture.execution.verifyModelRequest(attempt.turnId, call.id, provider, request) }
                assertEquals(1, wire.callCount)
            }
        }

    private suspend fun rejects(block: suspend () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (
            _: IllegalStateException,
        ) {
            rejected = true
        } catch (_: IllegalArgumentException) {
            rejected =
                true
        }
        assertTrue("Expected original-scope rejection before sending", rejected)
    }
}
