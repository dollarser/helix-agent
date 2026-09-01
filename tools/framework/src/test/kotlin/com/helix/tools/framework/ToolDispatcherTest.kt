package com.helix.tools.framework

import com.helix.core.model.AgentMode
import com.helix.core.model.Capability
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.ApprovalBinding
import com.helix.core.policy.ApprovalProof
import com.helix.core.policy.CapabilityCenter
import com.helix.core.policy.CapabilityGrant
import com.helix.core.policy.CapabilityResolver
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.DataSensitivity
import com.helix.core.policy.EgressRequest
import com.helix.core.policy.EgressTarget
import com.helix.core.policy.GrantState
import com.helix.core.policy.HighSensitivityRule
import com.helix.core.policy.MintRejectionCode
import com.helix.core.policy.PolicyEngine
import com.helix.core.policy.UserScope
import com.helix.core.policy.WorkspaceScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-035 acceptance (verification-matrix row `:tools:framework:test`): the dispatcher's
 * full pipeline — validate → capability → policy → approval → timeout/cancel → execute →
 * bound result → verify → audit — including the same-turn denial invariant (the exact
 * denied high-risk action is not re-requested in the same turn; material parameter/scope
 * changes may generate a new approval) and the first real tool (`time.now`).
 */
class ToolDispatcherTest {
    private lateinit var clock: FakeClock
    private lateinit var registry: ToolRegistry
    private lateinit var impls: ToolImplementationRegistry
    private lateinit var broker: ScriptedBroker
    private lateinit var sink: RecordingSink
    private lateinit var dispatcher: ToolDispatcher
    private val usableCaps = mutableSetOf<Capability>()

    @Before
    fun setUp() {
        clock = FakeClock(Instant.parse("2026-01-01T00:00:00Z"))
        registry = ToolRegistry()
        impls = ToolImplementationRegistry()
        val center = CapabilityCenter(RecordingResolver(usableCaps, clock))
        broker = ScriptedBroker()
        sink = RecordingSink()
        dispatcher = ToolDispatcher(clock, registry, impls, center, PolicyEngine(clock), broker, sink)
    }

    // ------------------------------------------------------------------ validate stage

    @Test
    fun unknownToolIsRejectedStablyWithoutPolicyOrApproval() {
        val outcome = dispatcher.dispatch(request(tool("ghost"), version(1), emptyArgs()))
        val denied = outcome as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.UNKNOWN_TOOL, denied.code)
        assertTrue(denied.detail.contains("ghost"))
        assertEquals(0, broker.acquireCalls.size)
        assertEquals(1, sink.events.size)
        assertEquals(DispatchOutcomeCode.UNKNOWN_TOOL, sink.events[0].code)
        assertEquals(DecisionSource.FRAMEWORK, sink.events[0].decisionSource)
        assertEquals(null, sink.events[0].policyDecidedAt)
    }

    @Test
    fun registeredNameWithUnknownVersionIsRejectedStably() {
        registerTool(descriptor(), CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) })
        val outcome = dispatcher.dispatch(request(tool("fake"), version(2), emptyArgs()))
        val denied = outcome as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.UNKNOWN_TOOL, denied.code)
        assertEquals(1, sink.events.size)
    }

    @Test
    fun contractWithoutImplementationIsRejectedClosed() {
        val d = descriptor()
        registry.register(d)
        val outcome = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs()))
        val denied = outcome as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.NO_IMPLEMENTATION, denied.code)
        assertEquals(DecisionSource.FRAMEWORK, sink.events.single().decisionSource)
    }

    @Test
    fun invalidArgumentsReportEveryViolationAndStopBeforeCapability() {
        val d =
            descriptor(
                inputSchema =
                    json(
                        """{"type":"object","properties":{"n":{"type":"integer","minimum":0}},""" +
                            """ "required":["n"],"additionalProperties":false}""",
                    ),
            )
        registerTool(d, CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) })
        val args = json("""{"n":-1,"extra":"x"}""")
        val outcome = dispatcher.dispatch(request(tool("fake"), version(1), args))
        val denied = outcome as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.INVALID_ARGUMENTS, denied.code)
        assertTrue("all violations must be reported: ${denied.detail}", denied.detail.contains("$[\"n\"]"))
        assertTrue(
            "all violations must be reported: ${denied.detail}",
            denied.detail.contains("additional property 'extra'"),
        )
        assertEquals(0, broker.acquireCalls.size)
        assertEquals(DispatchOutcomeCode.INVALID_ARGUMENTS, sink.events.single().code)
    }

    // ------------------------------------------------- capability + policy stages

    @Test
    fun l0ReadOnlyRunsWithoutApproval() {
        registerTool(
            descriptor(operationClass = ToolOperationClass.READ_ONLY, baseRisk = RiskLevel.L0),
            CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) },
        )
        val outcome = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs()))
        assertTrue(outcome is ToolDispatchOutcome.Succeeded)
        assertEquals(0, broker.acquireCalls.size)
        assertEquals(0, broker.consumeCalls.size)
        val event = sink.events.single()
        assertEquals(DispatchOutcomeCode.SUCCESS, event.code)
        assertEquals(DecisionSource.POLICY, event.decisionSource)
        assertEquals(null, event.bindingHash)
        assertNotNull(event.outputHash)
    }

    @Test
    fun missingCapabilityIsAPolicyDenial() {
        val d = descriptor(requiredCapabilities = setOf(Capability.NOTIFICATION_READ))
        registerTool(d, CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) })
        val outcome = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs()))
        val denied = outcome as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.POLICY_DENIED, denied.code)
        assertEquals(DecisionSource.POLICY, sink.events.single().decisionSource)
        assertEquals(0, broker.acquireCalls.size)
    }

    @Test
    fun grantedCapabilityDoesNotBlockPolicy() {
        usableCaps += Capability.NOTIFICATION_READ
        registerTool(
            descriptor(
                operationClass = ToolOperationClass.READ_ONLY,
                baseRisk = RiskLevel.L0,
                requiredCapabilities = setOf(Capability.NOTIFICATION_READ),
            ),
            CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) },
        )
        val outcome = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs()))
        assertTrue(outcome is ToolDispatchOutcome.Succeeded)
    }

    @Test
    fun planModeDeniesAMutatingCallByOperationClass() {
        registerTool(
            descriptor(operationClass = ToolOperationClass.LOCAL_MUTATION, baseRisk = RiskLevel.L1),
            CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) },
        )
        val outcome = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs(), mode = AgentMode.PLAN))
        val denied = outcome as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.POLICY_DENIED, denied.code)
    }

    // ---------------------------------------------------------------- approval stage

    @Test
    fun l2CallRequiresApprovalAndTheProofIsConsumedExactlyOnceAtExecutionStart() {
        val proof = proofFor("call-1")
        broker.script(ApprovalAcquisition.Approved(proof))
        registerTool(descriptor(), CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) })
        val outcome = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs()))
        assertTrue(outcome is ToolDispatchOutcome.Succeeded)
        assertEquals(1, broker.acquireCalls.size)
        assertEquals(1, broker.consumeCalls.size)
        assertEquals(proof, broker.consumeCalls.single())
        assertEquals(
            "audit must record the exact binding that was presented to the broker",
            broker.acquireCalls
                .single()
                .binding.hash,
            sink.events.single().bindingHash,
        )
        assertEquals(DecisionSource.USER, sink.events.single().decisionSource)
    }

    @Test
    fun aLiveAdvancedEgressRuleReachesTheBrokerSoTheCardShowsABoundedRule() {
        // HXA-036 (高敏出网规则单独标为有界 Policy 规则): ADVANCED + a live exactly-bound
        // rule that releases the SENSITIVE egress. The tool's own L2 risk still gates the
        // call, so the broker is called — and must receive the covering rule for display.
        val scope = WorkspaceScope("ws-9")
        val endpoint = NormalizedEndpoint.parse("https://api.example.com/v1")
        val target = EgressTarget.Provider(ProviderId("provider-1"))
        val created = clock.instant.minusSeconds(3_600L)
        val rule =
            HighSensitivityRule(
                target,
                endpoint,
                DataSensitivity.SENSITIVE,
                scope,
                created,
                created.plusSeconds(86_400L),
            )
        val center = CapabilityCenter(RecordingResolver(usableCaps, clock))
        dispatcher =
            ToolDispatcher(
                clock,
                registry,
                impls,
                center,
                PolicyEngine(clock),
                broker,
                sink,
            ) { setOf(rule) }
        broker.script(ApprovalAcquisition.Approved(proofFor("call-1")))
        registerTool(descriptor(), CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) })
        val request =
            request(tool("fake"), version(1), emptyArgs(), profile = SafetyProfile.ADVANCED, scope = scope).copy(
                egress = EgressRequest(target, endpoint, DataSensitivity.SENSITIVE),
            )
        val outcome = dispatcher.dispatch(request)
        assertTrue(outcome is ToolDispatchOutcome.Succeeded)
        assertSame(
            "the covering bounded rule must reach the approval card",
            rule,
            broker.acquireCalls.single().boundedEgressRule,
        )
    }

    @Test
    fun userDenialBlocksTheSameActionInTheSameTurnWithoutReasking() {
        broker.script(ApprovalAcquisition.Denied)
        val executor = CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) }
        registerTool(descriptor(), executor)
        val first = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs(), toolCallId = "call-1"))
        val firstDenied = first as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, firstDenied.code)

        // The model re-issues the EXACT action (same tool/version/scope/session/target/args)
        // as a NEW call instance: it must NOT be asked again.
        val second = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs(), toolCallId = "call-2"))
        val secondDenied = second as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.SAME_TURN_DENIED, secondDenied.code)
        assertEquals("the user must not be asked twice for the same action", 1, broker.acquireCalls.size)
        assertEquals(0, broker.consumeCalls.size)
        assertEquals("nothing may execute a denied action", 0, executor.invocations)
        assertEquals(2, sink.events.size)
        assertEquals(DecisionSource.USER, sink.events[1].decisionSource)
        assertEquals(DispatchOutcomeCode.SAME_TURN_DENIED, sink.events[1].code)
    }

    @Test
    fun materiallyDifferentArgsAllowANewApprovalInTheSameTurn() {
        broker.script(ApprovalAcquisition.Denied, ApprovalAcquisition.Approved(proofFor("call-2")))
        registerTool(descriptor(), CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) })
        val first =
            dispatcher.dispatch(
                request(tool("fake"), version(1), json("""{"target":"a"}"""), toolCallId = "call-1"),
            )
        assertTrue(first is ToolDispatchOutcome.Denied)
        val second =
            dispatcher.dispatch(
                request(tool("fake"), version(1), json("""{"target":"b"}"""), toolCallId = "call-2"),
            )
        assertTrue("a material argument change may generate a new approval", second is ToolDispatchOutcome.Succeeded)
        assertEquals(2, broker.acquireCalls.size)
    }

    @Test
    fun materiallyDifferentScopeAllowsANewApprovalInTheSameTurn() {
        broker.script(ApprovalAcquisition.Denied, ApprovalAcquisition.Approved(proofFor("call-2")))
        registerTool(descriptor(), CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) })
        val deniedScope = WorkspaceScope("ws-1")
        val first =
            dispatcher.dispatch(
                request(tool("fake"), version(1), emptyArgs(), scope = deniedScope, toolCallId = "call-1"),
            )
        assertTrue(first is ToolDispatchOutcome.Denied)
        val second =
            dispatcher.dispatch(
                request(tool("fake"), version(1), emptyArgs(), scope = WorkspaceScope("ws-2"), toolCallId = "call-2"),
            )
        assertTrue(second is ToolDispatchOutcome.Succeeded)
        assertEquals(2, broker.acquireCalls.size)
    }

    @Test
    fun aDifferentTurnMayAskAgainForTheSameAction() {
        broker.script(ApprovalAcquisition.Denied, ApprovalAcquisition.Approved(proofFor("call-2")))
        registerTool(descriptor(), CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) })
        val first =
            dispatcher.dispatch(
                request(tool("fake"), version(1), emptyArgs(), turnId = "turn-1", toolCallId = "call-1"),
            )
        assertTrue(first is ToolDispatchOutcome.Denied)
        val second =
            dispatcher.dispatch(
                request(tool("fake"), version(1), emptyArgs(), turnId = "turn-2", toolCallId = "call-2"),
            )
        assertTrue(second is ToolDispatchOutcome.Succeeded)
        assertEquals(2, broker.acquireCalls.size)
    }

    @Test
    fun endTurnClearsTheDenialSet() {
        broker.script(ApprovalAcquisition.Denied, ApprovalAcquisition.Approved(proofFor("call-2")))
        registerTool(descriptor(), CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) })
        val first = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs(), toolCallId = "call-1"))
        assertTrue(first is ToolDispatchOutcome.Denied)
        dispatcher.endTurn("turn-1")
        val second = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs(), toolCallId = "call-2"))
        assertTrue("after endTurn the same action may be asked again", second is ToolDispatchOutcome.Succeeded)
        assertEquals(2, broker.acquireCalls.size)
    }

    @Test
    fun nonConsumableApprovalsAreStableDenialsAndNeverExecute() {
        registerTool(descriptor(), CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) })
        broker.script(
            ApprovalAcquisition.Rejected(MintRejectionCode.PENDING),
            ApprovalAcquisition.Rejected(MintRejectionCode.EXPIRED),
            ApprovalAcquisition.Rejected(MintRejectionCode.CONSUMED),
            ApprovalAcquisition.Rejected(MintRejectionCode.NOT_FOUND),
        )
        val expected =
            listOf(
                DispatchOutcomeCode.APPROVAL_PENDING,
                DispatchOutcomeCode.APPROVAL_EXPIRED,
                DispatchOutcomeCode.APPROVAL_CONSUMED,
                DispatchOutcomeCode.APPROVAL_NOT_FOUND,
            )
        expected.forEachIndexed { index, code ->
            val outcome =
                dispatcher.dispatch(
                    request(tool("fake"), version(1), emptyArgs(), toolCallId = "call-$index"),
                )
            val denied = outcome as ToolDispatchOutcome.Denied
            assertEquals(code, denied.code)
        }
        assertEquals(0, broker.consumeCalls.size)
        assertEquals(4, sink.events.size)
        assertEquals(
            expected.map { it to DecisionSource.FRAMEWORK },
            sink.events.map { it.code to it.decisionSource },
        )
    }

    // ---------------------------------------------------------- timeout/cancel/execute

    @Test
    fun timeoutIsAStableErrorAndTheProofIsSpentBecauseExecutionStarted() {
        val proof = proofFor("call-1")
        broker.script(ApprovalAcquisition.Approved(proof))
        val executor = CaptureExecutor { ToolExecutorResult.TimedOut }
        registerTool(descriptor(), executor)
        val outcome = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs()))
        val failed = outcome as ToolDispatchOutcome.ExecutionFailed
        assertEquals(DispatchOutcomeCode.TIMEOUT, failed.code)
        assertEquals(1, executor.calls.size)
        assertEquals(clock.instant.plusSeconds(30), executor.calls.single().deadline)
        assertEquals("the proof is spent the moment execution starts", 1, broker.consumeCalls.size)
        assertEquals(DispatchOutcomeCode.TIMEOUT, sink.events.single().code)
    }

    @Test
    fun cancelBeforeStartStopsWithZeroSideEffectsAndConsumesNothing() {
        val proof = proofFor("call-1")
        broker.script(ApprovalAcquisition.Approved(proof))
        val executor = CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) }
        registerTool(descriptor(), executor)
        val cancel = ManualCancel()
        cancel.cancelled = true
        val outcome = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs(), cancel = cancel))
        assertEquals(ToolDispatchOutcome.Cancelled, outcome)
        assertEquals(0, executor.invocations)
        assertEquals("nothing started, so the proof is never spent", 0, broker.consumeCalls.size)
        assertEquals(DispatchOutcomeCode.CANCELLED_BEFORE_START, sink.events.single().code)
    }

    @Test
    fun cancelAfterStartIsAStableErrorWithUnknownSideEffectState() {
        broker.script(ApprovalAcquisition.Approved(proofFor("call-1")))
        registerTool(descriptor(), CaptureExecutor { ToolExecutorResult.Cancelled })
        val outcome = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs()))
        val failed = outcome as ToolDispatchOutcome.ExecutionFailed
        assertEquals(DispatchOutcomeCode.CANCELLED_AFTER_START, failed.code)
    }

    @Test
    fun toolFailureSurfacesItsStableDetail() {
        broker.script(ApprovalAcquisition.Approved(proofFor("call-1")))
        registerTool(descriptor(), CaptureExecutor { ToolExecutorResult.Failed("disk full") })
        val outcome = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs()))
        val failed = outcome as ToolDispatchOutcome.ExecutionFailed
        assertEquals(DispatchOutcomeCode.TOOL_FAILED, failed.code)
        assertEquals("disk full", failed.detail)
    }

    // ----------------------------------------------------------- bound result/verify

    @Test
    fun outputViolatingTheRegisteredOutputSchemaNeverReachesTheModel() {
        broker.script(ApprovalAcquisition.Approved(proofFor("call-1")))
        val d =
            descriptor(
                outputSchema =
                    json(
                        """{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"],""" +
                            """ "additionalProperties":false}""",
                    ),
            )
        registerTool(d, CaptureExecutor { ToolExecutorResult.Completed(json("""{"unexpected":1}""")) })
        val outcome = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs()))
        val failed = outcome as ToolDispatchOutcome.ExecutionFailed
        assertEquals(DispatchOutcomeCode.INVALID_OUTPUT, failed.code)
        assertEquals(null, sink.events.single().outputHash)
    }

    @Test
    fun oversizedOutputIsTruncatedWithTheFullHashPreserved() {
        broker.script(ApprovalAcquisition.Approved(proofFor("call-1")))
        val d = descriptor(maxOutputBytes = 20L)
        val fullOutput = json("""{"data":"01234567890123456789"}""")
        registerTool(d, CaptureExecutor { ToolExecutorResult.Completed(fullOutput) })
        val outcome = dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs()))
        val succeeded = outcome as ToolDispatchOutcome.Succeeded
        val result = succeeded.result
        assertTrue(result.truncated)
        assertTrue("payload must respect the byte cap", result.payload.toByteArray(Charsets.UTF_8).size <= 20)
        val expected = sha256Hex("""{"data":"01234567890123456789"}""")
        assertEquals("the hash covers the FULL pre-truncation output", expected, result.outputHash.hex)
        assertTrue(sink.events.single().outputTruncated)
        assertEquals(expected, sink.events.single().outputHash)
    }

    @Test
    fun successfulDispatchAuditsOneEventWithMonotonicStageTimestamps() {
        val proof = proofFor("call-1")
        broker.script(ApprovalAcquisition.Approved(proof))
        registerTool(descriptor(), CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) })
        dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs()))
        val event = sink.events.single()
        assertEquals("call-1", event.correlationId)
        assertEquals("turn-1", event.turnId)
        assertEquals("session-1", event.sessionId)
        assertEquals("fake", event.toolName)
        assertEquals("1", event.toolVersion)
        assertTrue(event.startedAt <= (event.policyDecidedAt ?: 0))
        assertTrue((event.policyDecidedAt ?: 0) <= (event.approvalAcquiredAt ?: 0))
        assertTrue((event.approvalAcquiredAt ?: 0) <= (event.executionStartedAt ?: 0))
        assertTrue((event.executionStartedAt ?: 0) <= event.finishedAt)
        assertNotNull(event.bindingHash)
        assertNotNull(event.actionFingerprint)
        assertNotNull(event.outputHash)
        assertFalse(event.outputTruncated)
    }

    // ------------------------------------------------------------------- fail closed

    @Test
    fun aFailingAuditSinkPropagatesInsteadOfReportingSuccess() {
        val failing =
            object : AuditSink {
                override fun record(event: DispatchAuditEvent): Unit = error("audit storage down")
            }
        val strict =
            ToolDispatcher(
                clock,
                registry,
                impls,
                CapabilityCenter(RecordingResolver(usableCaps, clock)),
                PolicyEngine(clock),
                broker,
                failing,
            )
        registerTool(
            descriptor(operationClass = ToolOperationClass.READ_ONLY, baseRisk = RiskLevel.L0),
            CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) },
        )
        try {
            strict.dispatch(request(tool("fake"), version(1), emptyArgs()))
            fail("expected the audit failure to propagate")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message?.contains("audit storage down") ?: false)
        }
    }

    // ------------------------------------------------------------ action fingerprint

    @Test
    fun theFingerprintIsInstanceBlindAndArgSensitive() {
        val base =
            ApprovalBinding(
                toolCallId = "call-1",
                toolName = "fake",
                toolVersion = "1",
                schemaHash = "a".repeat(64),
                scopeRef = "unscoped",
                sessionId = "session-1",
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                uiToken = "ui:card:1",
                argsHash = "b".repeat(64),
            )
        val otherInstance = base.copy(toolCallId = "call-2", uiToken = "ui:card:2")
        assertEquals(dispatcher.actionFingerprint(base), dispatcher.actionFingerprint(otherInstance))
        val differentArgs = base.copy(argsHash = "c".repeat(64))
        assertTrue(dispatcher.actionFingerprint(base) != dispatcher.actionFingerprint(differentArgs))
        val differentTarget = base.copy(executionTarget = ExecutionTargetType.LOCAL_QUICKJS)
        assertTrue(dispatcher.actionFingerprint(base) != dispatcher.actionFingerprint(differentTarget))
    }

    // ------------------------------------------------------------- time.now end-to-end

    @Test
    fun timeNowRunsTheNoApprovalPathEndToEnd() {
        TimeNowTool.register(registry, impls, clock)
        val outcome = dispatcher.dispatch(request(tool(TimeNowTool.NAME), version(TimeNowTool.VERSION), emptyArgs()))
        val succeeded = outcome as ToolDispatchOutcome.Succeeded
        assertEquals(0, broker.acquireCalls.size)
        val payload = Json.parseToJsonElement(succeeded.result.payload).jsonObject
        assertEquals(
            clock.instant.toString(),
            (payload["utcIso"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
        )
        assertEquals(DispatchOutcomeCode.SUCCESS, sink.events.single().code)
        assertFalse(succeeded.result.truncated)
        // the output validated against the registered output schema at the verify stage
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(TimeNowTool.descriptor().outputSchema, payload),
        )
    }

    @Test
    fun timeNowRejectsNonEmptyArgumentsAtTheValidateStage() {
        TimeNowTool.register(registry, impls, clock)
        val outcome =
            dispatcher.dispatch(
                request(tool(TimeNowTool.NAME), version(TimeNowTool.VERSION), json("""{"tz":"utc"}""")),
            )
        val denied = outcome as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.INVALID_ARGUMENTS, denied.code)
        assertEquals(0, broker.acquireCalls.size)
    }

    // ------------------------------------------------- HXA-037 bounded technical retry

    @Test
    fun sideEffectFreeFailureIsRetriedOnceWithAttemptMetadata() {
        var attempts = 0
        val executor =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    attempts++
                    return if (attempts == 1) {
                        ToolExecutorResult.Failed("companion binder dropped", sideEffectFree = true)
                    } else {
                        ToolExecutorResult.Completed(emptyObject())
                    }
                }
            }
        registerTool(
            descriptor(operationClass = ToolOperationClass.READ_ONLY, baseRisk = RiskLevel.L0),
            executor,
        )
        val outcome =
            dispatcher.dispatch(
                request(tool("fake"), version(1), emptyArgs()).copy(maxAttempts = 2),
            )
        assertTrue(outcome is ToolDispatchOutcome.Succeeded)
        assertEquals("the retry is bounded to one extra attempt", 2, attempts)
        // Two durable audit rows: one per attempt, same correlation, attemptId 1 then 2.
        assertEquals(2, sink.events.size)
        assertEquals("call-1", sink.events[0].correlationId)
        assertEquals("call-1", sink.events[1].correlationId)
        assertEquals(1, sink.events[0].attemptId)
        assertEquals(2, sink.events[1].attemptId)
        assertEquals(DispatchOutcomeCode.TOOL_FAILED, sink.events[0].code)
        assertEquals(DispatchOutcomeCode.SUCCESS, sink.events[1].code)
    }

    @Test
    fun anUnconfirmedFailureIsTerminalEvenWithRetryBudget() {
        var attempts = 0
        registerTool(
            descriptor(operationClass = ToolOperationClass.READ_ONLY, baseRisk = RiskLevel.L0),
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    attempts++
                    return ToolExecutorResult.Failed("unknown state")
                }
            },
        )
        val outcome =
            dispatcher.dispatch(
                request(tool("fake"), version(1), emptyArgs()).copy(maxAttempts = 2),
            )
        val failed = outcome as ToolDispatchOutcome.ExecutionFailed
        assertEquals(DispatchOutcomeCode.TOOL_FAILED, failed.code)
        assertEquals("no side-effect confirmation, no retry", 1, attempts)
        assertEquals(1, sink.events.size)
        assertEquals(1, sink.events.single().attemptId)
    }

    @Test
    fun theRetryReusesTheReMintedProofWithoutRePresentingTheCard() {
        val proof = proofFor("call-1")
        val reminted = ApprovalProof("call-1", "f".repeat(64))
        broker.script(ApprovalAcquisition.Approved(proof))
        broker.reMintResult = reminted
        var attempts = 0
        registerTool(
            descriptor(),
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    attempts++
                    return if (attempts == 1) {
                        ToolExecutorResult.Failed("lane dropped", sideEffectFree = true)
                    } else {
                        ToolExecutorResult.Completed(emptyObject())
                    }
                }
            },
        )
        val outcome =
            dispatcher.dispatch(
                request(tool("fake"), version(1), emptyArgs()).copy(maxAttempts = 2),
            )
        assertTrue(outcome is ToolDispatchOutcome.Succeeded)
        assertEquals(2, attempts)
        // The card was presented EXACTLY ONCE; the retry rode the re-minted proof of the
        // SAME typed APPROVED record.
        assertEquals(1, broker.acquireCalls.size)
        assertEquals(listOf(proof, reminted), broker.consumeCalls)
        assertEquals(listOf(proof), broker.reMintCalls)
        assertEquals(2, sink.events.size)
        // Both attempts audited under the SAME presented binding (the re-mint rides the
        // same record — its proof hash is the record's binding hash, not a new binding).
        val binding =
            broker.acquireCalls
                .single()
                .binding.hash
        assertEquals(binding, sink.events[0].bindingHash)
        assertEquals(binding, sink.events[1].bindingHash)
    }

    @Test
    fun anUnMintableReMintEndsTheRetryWithTheOriginalFailure() {
        val proof = proofFor("call-1")
        broker.script(ApprovalAcquisition.Approved(proof))
        broker.reMintReturnsNull = true
        var attempts = 0
        registerTool(
            descriptor(),
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    attempts++
                    return ToolExecutorResult.Failed("dropped", sideEffectFree = true)
                }
            },
        )
        val outcome =
            dispatcher.dispatch(
                request(tool("fake"), version(1), emptyArgs()).copy(maxAttempts = 2),
            )
        val failed = outcome as ToolDispatchOutcome.ExecutionFailed
        assertEquals(DispatchOutcomeCode.TOOL_FAILED, failed.code)
        // reMint returned null (the record can no longer mint): NO second attempt ran.
        assertEquals(1, attempts)
        assertEquals(listOf(proof), broker.reMintCalls)
        assertEquals(1, sink.events.size)
        assertEquals(1, sink.events.single().attemptId)
    }

    @Test
    fun theReMintGuardIsOneTimePerBinding() {
        // First dispatch spends the fake's one-time reMint for this binding (it fails at
        // attempt 2's budget edge: attempt 1 re-mints, attempt 2 fails terminally).
        val proof = proofFor("call-1")
        broker.script(ApprovalAcquisition.Approved(proof))
        broker.reMintResult = ApprovalProof("call-1", "f".repeat(64))
        var attempts = 0
        registerTool(
            descriptor(),
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    attempts++
                    return ToolExecutorResult.Failed("dropped", sideEffectFree = true)
                }
            },
        )
        dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs()).copy(maxAttempts = 2))
        assertEquals("attempt 1 + its single retry", 2, attempts)
        assertEquals(1, broker.reMintCalls.size)
        // Second dispatch, same binding: the reMint guard is already spent → the retry
        // stops with the original attempt-1 failure (a double refund is impossible).
        broker.script(ApprovalAcquisition.Approved(proofFor("call-2")))
        val outcome2 =
            dispatcher.dispatch(
                request(tool("fake"), version(1), emptyArgs(), toolCallId = "call-2").copy(maxAttempts = 2),
            )
        val failed2 = outcome2 as ToolDispatchOutcome.ExecutionFailed
        assertEquals(DispatchOutcomeCode.TOOL_FAILED, failed2.code)
        assertEquals("the spent guard must stop the retry before attempt 2", 3, attempts)
        assertEquals(2, broker.reMintCalls.size)
        assertEquals("call-2", sink.events.last().correlationId)
        assertEquals(1, sink.events.last().attemptId)
    }

    @Test
    fun aRevokedCapabilityStopsTheLiveRetry() {
        usableCaps += Capability.NOTIFICATION_READ
        var attempts = 0
        registerTool(
            descriptor(
                operationClass = ToolOperationClass.READ_ONLY,
                baseRisk = RiskLevel.L0,
                requiredCapabilities = setOf(Capability.NOTIFICATION_READ),
            ),
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    attempts++
                    usableCaps.clear()
                    return ToolExecutorResult.Failed("flaky", sideEffectFree = true)
                }
            },
        )
        val outcome =
            dispatcher.dispatch(
                request(tool("fake"), version(1), emptyArgs()).copy(maxAttempts = 2),
            )
        // The retry re-runs the LIVE capability check: the grant was revoked during the
        // first attempt, so attempt 2 is a policy denial, not an execution.
        val denied = outcome as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.POLICY_DENIED, denied.code)
        assertEquals(1, attempts)
        assertEquals(2, sink.events.size)
        assertEquals(DispatchOutcomeCode.TOOL_FAILED, sink.events[0].code)
        assertEquals(DispatchOutcomeCode.POLICY_DENIED, sink.events[1].code)
        assertEquals(2, sink.events[1].attemptId)
    }

    @Test
    fun maxAttemptsAboveTheHardCapIsRejected() {
        registerTool(
            descriptor(operationClass = ToolOperationClass.READ_ONLY, baseRisk = RiskLevel.L0),
            CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) },
        )
        assertThrows(IllegalArgumentException::class.java) {
            ToolDispatchRequest(
                toolCallId = "call-1",
                turnId = "turn-1",
                sessionId = "session-1",
                toolName = tool("fake"),
                toolVersion = version(1),
                args = emptyArgs(),
                mode = AgentMode.ACT,
                profile = SafetyProfile.STANDARD,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                dataOrigin = DataOrigin.WORKSPACE,
                scope = null,
                uiToken = "ui:t",
                maxAttempts = 3,
            )
        }
        request(tool("fake"), version(1), emptyArgs()).copy(maxAttempts = 2)
        request(tool("fake"), version(1), emptyArgs()).copy(maxAttempts = 1)
    }

    @Test
    fun aQueuedCallKeepsItsQueuedStampInEveryAttemptAuditRow() {
        registerTool(
            descriptor(operationClass = ToolOperationClass.READ_ONLY, baseRisk = RiskLevel.L0),
            CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) },
        )
        // queuedAt is the ENQUEUE time: it must not lie in the future of the dispatch.
        val queuedAt = clock.instant.minusMillis(500).toEpochMilli()
        dispatcher.dispatch(request(tool("fake"), version(1), emptyArgs()).copy(queuedAt = queuedAt))
        val event = sink.events.single()
        assertEquals(queuedAt, event.queuedAt)
        assertTrue(event.queuedAt != null && event.startedAt >= event.queuedAt!!)
    }

    // ---------------------------------------------------------------------- helpers

    private fun tool(name: String): ToolName = ToolName(name)

    private fun version(v: Int): ToolVersion = ToolVersion(v)

    private fun emptyArgs(): JsonObject = json("{}")

    private fun emptyObject(): JsonObject = json("{}")

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun proofFor(toolCallId: String): ApprovalProof = ApprovalProof(toolCallId, "e".repeat(64))

    // Eight contract fields: the fixture mirrors the descriptor's dispatch-relevant shape.
    @Suppress("LongParameterList")
    private fun descriptor(
        name: String = "fake",
        version: Int = 1,
        operationClass: ToolOperationClass = ToolOperationClass.LOCAL_MUTATION,
        baseRisk: RiskLevel = RiskLevel.L2,
        inputSchema: JsonObject = json("""{"type":"object"}"""),
        outputSchema: JsonObject = json("""{"type":"object"}"""),
        requiredCapabilities: Set<Capability> = emptySet(),
        maxOutputBytes: Long = 1024L * 1024L,
    ): ToolDescriptor =
        ToolDescriptor(
            name = tool(name),
            version = version(version),
            description = "test tool",
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            operationClass = operationClass,
            baseRisk = baseRisk,
            timeout = 30.seconds,
            maxOutputBytes = maxOutputBytes,
            requiredCapabilities = requiredCapabilities,
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    private fun registerTool(
        d: ToolDescriptor,
        executor: ToolExecutor,
    ) {
        registry.register(d)
        impls.register(d, executor)
    }

    // One parameter per request fact the tests exercise.
    @Suppress("LongParameterList")
    private fun request(
        tool: ToolName,
        version: ToolVersion,
        args: JsonObject,
        mode: AgentMode = AgentMode.ACT,
        profile: SafetyProfile = SafetyProfile.STANDARD,
        scope: UserScope? = null,
        turnId: String = "turn-1",
        toolCallId: String = "call-1",
        cancel: CancelSignal = NoCancellation,
    ): ToolDispatchRequest =
        ToolDispatchRequest(
            toolCallId = toolCallId,
            turnId = turnId,
            sessionId = "session-1",
            toolName = tool,
            toolVersion = version,
            args = args,
            mode = mode,
            profile = profile,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            dataOrigin = DataOrigin.WORKSPACE,
            scope = scope,
            uiToken = "ui:card:1",
            cancel = cancel,
        )

    private fun sha256Hex(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    private class FakeClock(
        var instant: Instant,
    ) : Clock {
        override fun now(): Instant = instant

        fun plusSeconds(seconds: Long): Instant = instant.plusMillis(seconds * 1000L)
    }

    private class RecordingResolver(
        private val usable: MutableSet<Capability>,
        private val clock: Clock,
    ) : CapabilityResolver {
        override fun resolve(capability: Capability): CapabilityGrant =
            CapabilityGrant(
                capability = capability,
                state = if (capability in usable) GrantState.GRANTED else GrantState.UNAVAILABLE,
                grantedBySystem = true,
                userScope = null,
                checkedAt = clock.now(),
            )
    }

    private class ScriptedBroker : ApprovalBroker {
        val scripted = ArrayDeque<ApprovalAcquisition>()
        val acquireCalls = mutableListOf<ApprovalRequest>()
        val consumeCalls = mutableListOf<ApprovalProof>()
        val reMintCalls = mutableListOf<ApprovalProof>()
        var reMintResult: ApprovalProof? = null

        /** Forces the un-mintable path (the record's window elapsed in the meantime). */
        var reMintReturnsNull = false
        private val reminted = mutableSetOf<String>()

        fun script(vararg acquisitions: ApprovalAcquisition) {
            scripted.addAll(acquisitions)
        }

        override fun acquire(request: ApprovalRequest): ApprovalAcquisition {
            acquireCalls += request
            check(scripted.isNotEmpty()) { "broker scripted empty" }
            return scripted.removeFirst()
        }

        override fun consume(proof: ApprovalProof) {
            consumeCalls += proof
        }

        /** One-time per binding: a second re-mint of the same binding fails (the storage guard's twin). */
        @Suppress("ReturnCount") // each guard is a distinct terminal path of the fake's contract
        override fun reMint(proof: ApprovalProof): ApprovalProof? {
            reMintCalls += proof
            if (reMintReturnsNull) return null
            if (!reminted.add(proof.bindingHash)) return null
            return reMintResult ?: proof
        }
    }

    private class RecordingSink : AuditSink {
        val events = mutableListOf<DispatchAuditEvent>()

        override fun record(event: DispatchAuditEvent) {
            events += event
        }
    }

    private class ManualCancel : CancelSignal {
        var cancelled = false

        override fun isCancelled(): Boolean = cancelled
    }

    private class CaptureExecutor(
        private val result: () -> ToolExecutorResult,
    ) : ToolExecutor {
        val calls = mutableListOf<ExecutableToolCall>()
        var invocations = 0
            private set

        override fun execute(call: ExecutableToolCall): ToolExecutorResult {
            invocations++
            calls += call
            return result()
        }
    }
}
