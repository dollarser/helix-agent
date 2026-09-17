package com.helix.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.profile.AdvancedProfileAvailability
import com.helix.app.tool.SessionToolEffectClassifier
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.SessionPermissionConfig
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-209 D8 device acceptance — the execution-effect BOUNDARY at the REAL execution entry. The
 * one [com.helix.app.tool.SessionToolEffectClassifier] classifies the developer-only
 * `code.linux.run` shell (COMMAND_EXECUTION is a determined effect; the six file/remote/device
 * effects are undetermined — a command runs with the app's full reach), and the ONE
 * [com.helix.core.policy.SessionPermissionResolver] refuses a shell write BEFORE the real backend
 * runs when the session config DENYs the effect. The boundary is PER-EFFECT, not a blanket
 * refusal: a determined READ_ONLY tool (empty footprint) still proceeds under the SAME config.
 *
 * The consumer flavor lacks the developer PRoot Runtime, so the shell is a VARIANT BOUNDARY
 * there: the tool is absent and a request for it is refused at the registry (`UNKNOWN_TOOL`), not
 * executed and not skipped. Every test asserts BOTH the side effect (did the write target
 * actually appear? did the tool actually execute?) and the variant/availability facts — never
 * the outcome code alone.
 */
@RunWith(AndroidJUnit4::class)
class ExecutionEffectBoundaryDeviceTest {
    private lateinit var container: AppContainer
    private lateinit var appContext: android.app.Application
    private val run = System.nanoTime()
    private val sessionId = "be-session-$run"
    private val shellName = SessionToolEffectClassifier.LINUX_RUN
    private val readOnlyName = ToolName(READ_ONLY_TOOL_NAME)

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        appContext = app
        container = (app as HelixApplication).appContainer
        val now = System.currentTimeMillis()
        if (container.storage.sessions
                .list()
                .none { it.id == sessionId }
        ) {
            container.storage.sessions.create(sessionId, "effect boundary fixture", null, null, now)
        }
        container.chatService.openSession(sessionId)
    }

    @After
    fun restoreDefaultProfile() {
        // switchTo(ADVANCED) persists to the store: restore STANDARD so later classes in the same
        // process start from the same state (the consumer build never holds ADVANCED).
        if (AdvancedProfileAvailability.ADVANCED_AVAILABLE) {
            container.profileStore.switchTo(SafetyProfile.STANDARD)
        }
    }

    @Test
    fun theShellToolIsPresentOnlyOnTheDeveloperFlavor() {
        // The VARIANT BOUNDARY as a hard assertion (not a branch): the developer-only PRoot
        // shell is registered IFF this build is the developer flavor. A developer build missing
        // the tool (a regression) fails here rather than silently taking the consumer path.
        val linux = container.toolPipeline.registry.resolveLatest(ToolName(shellName))
        assertEquals(
            "the developer-only $shellName tool must be registered IFF the developer flavor",
            AdvancedProfileAvailability.ADVANCED_AVAILABLE,
            linux != null,
        )
    }

    @Test
    fun aRefusedShellWriteNeverReachesTheRealBackendAndReadsStillProceed() {
        val sentinel = appContext.noBackupFilesDir.resolve("be-write-sentinel-$run")
        sentinel.delete()
        val writeScript = "echo boundary > ${sentinel.absolutePath}"
        if (!AdvancedProfileAvailability.ADVANCED_AVAILABLE) {
            // CONSUMER: the developer PRoot Runtime (and the shell) is absent — a request for the
            // shell is refused at the registry (not executed, not a skip). The variant boundary.
            val outcome = dispatch("be-shell-consumer-$run", "be-turn-consumer-$run", shellName, shellArgs(writeScript))
            val denied =
                outcome as? ToolDispatchOutcome.Denied
                    ?: error("the consumer must refuse the absent $shellName tool, got: $outcome")
            assertEquals(DispatchOutcomeCode.UNKNOWN_TOOL, denied.code)
            assertFalse("the absent shell must not create the write target", sentinel.exists())
            return
        }
        // DEVELOPER: the real shell lane is present. ADVANCED lets the profile gate pass so the ONE
        // resolver is what decides; a COMMAND_EXECUTION DENY refuses the write before the backend.
        container.profileStore.switchTo(SafetyProfile.ADVANCED)
        val rules =
            SessionPermissionConfig.copyPreset(SessionPermissionMode.FULL_ACCESS).toMutableMap().apply {
                put(OperationEffect.COMMAND_EXECUTION, OperationRule.DENY)
            }
        container.sessionPermissionEdit.saveSessionConfig(
            sessionId,
            SessionPermissionConfig.custom(rules),
            System.currentTimeMillis(),
        )
        val shellCallId = "be-shell-dev-$run"
        val shell =
            dispatch(shellCallId, "be-turn-dev-$run", shellName, shellArgs(writeScript)) as ToolDispatchOutcome.Denied
        assertEquals(
            "the SESSION resolver (not the profile gate) must refuse the shell write",
            DispatchOutcomeCode.OPERATION_DENIED,
            shell.code,
        )
        assertFalse("the refused shell write must not reach the real backend", sentinel.exists())
        assertEquals(
            ToolCallState.DENIED.name,
            container.storage.toolCalls
                .resolve(shellCallId)
                .state,
        )
        // The boundary is PER-EFFECT: a determined READ_ONLY tool (empty footprint) proceeds
        // under the SAME command-DENY config — a read is not gated by a command refusal.
        val executions = AtomicInteger()
        registerReadOnlyTool(executions)
        val read = dispatch("be-read-dev-$run", "be-turn-dev-$run", READ_ONLY_TOOL_NAME, "{}")
        assertTrue(
            "a determined read-only tool must proceed under the same command-DENY config, got: $read",
            read is ToolDispatchOutcome.Succeeded,
        )
        assertEquals("the read-only tool must actually execute", 1, executions.get())
    }

    /** `code.linux.run` takes exactly one of `argv` / `script`; this is a shell script. */
    private fun shellArgs(script: String): String = buildJsonObject { put("script", script) }.toString()

    /**
     * Registers a FRESH version of a determined READ_ONLY, LOCAL_ANDROID, L1 built-in tool: the
     * classifier gives it an EMPTY footprint (no gated effect), so under any config the resolver
     * AutoProceeds it card-free. The per-test counter proves it actually executed.
     */
    private fun registerReadOnlyTool(executions: AtomicInteger) {
        val nextVersion =
            (
                container.toolPipeline.registry
                    .resolveLatest(readOnlyName)
                    ?.version
                    ?.value ?: 0
            ) + 1
        val descriptor =
            ToolDescriptor(
                name = readOnlyName,
                version = ToolVersion(nextVersion),
                description = "effect boundary read-only fixture",
                inputSchema = Json.parseToJsonElement("""{"type":"object"}""").let { it as JsonObject },
                outputSchema = Json.parseToJsonElement("""{"type":"object"}""").let { it as JsonObject },
                operationClass = ToolOperationClass.READ_ONLY,
                baseRisk = RiskLevel.L1,
                timeout = 30.seconds,
                maxOutputBytes = 4096L,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.BuiltInOrigin,
            )
        container.toolPipeline.registry.register(descriptor)
        container.toolPipeline.implementations.register(
            descriptor,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    executions.incrementAndGet()
                    return ToolExecutorResult.Completed(buildJsonObject { put("ok", true) })
                }
            },
        )
    }

    /** Seeds the session + turn rows the tool_calls foreign keys require (idempotent). */
    private fun ensureTurn(turnId: String) {
        val now = System.currentTimeMillis()
        if (container.storage.sessions
                .list()
                .none { it.id == sessionId }
        ) {
            container.storage.sessions.create(sessionId, "effect boundary fixture", null, null, now)
        }
        if (container.storage.turns
                .listBySession(sessionId)
                .none { it.id == turnId }
        ) {
            container.storage.turns.start(turnId, sessionId, now)
        }
    }

    /**
     * Runs one dispatch through the production per-call pipeline on a worker thread (the broker
     * blocks on a pending decision, so a dispatch must never run on the main instrumentation
     * thread even when this one resolves card-free).
     */
    private fun dispatch(
        toolCallId: String,
        turnId: String,
        toolNameRaw: String,
        argsJson: String,
    ): ToolDispatchOutcome {
        ensureTurn(turnId)
        val latch = CountDownLatch(1)
        val outcome = arrayOf<ToolDispatchOutcome?>(null)
        val error = arrayOf<Throwable?>(null)
        val t =
            Thread {
                try {
                    outcome[0] =
                        container.chatService.dispatchToolCall(
                            toolCallId,
                            turnId,
                            toolNameRaw,
                            argsJson,
                        )
                } catch (e: Throwable) {
                    error[0] = e
                } finally {
                    latch.countDown()
                }
            }
        t.isDaemon = true
        t.start()
        assertTrue("dispatch must finish", latch.await(30, TimeUnit.SECONDS))
        error[0]?.let { throw it }
        return outcome[0] ?: error("no outcome")
    }

    companion object {
        const val READ_ONLY_TOOL_NAME = "hxadev.beffect.readonly"
    }
}
