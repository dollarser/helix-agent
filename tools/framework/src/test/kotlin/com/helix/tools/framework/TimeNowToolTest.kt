package com.helix.tools.framework

import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolOperationClass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * HXA-035: the first real tool's contract — `time.now` must be the canonical no-approval
 * path (doc 01 P0: L0, READ_ONLY, idempotent, in-process) and its output must satisfy its
 * own registered output schema.
 */
class TimeNowToolTest {
    private val clock =
        object : Clock {
            var instant: Instant = Instant.parse("2026-01-01T00:00:00Z")

            override fun now(): Instant = instant

            fun advance(seconds: Long) {
                instant = instant.plusMillis(seconds * 1000L)
            }
        }

    @Test
    fun theContractIsTheCanonicalNoApprovalShape() {
        val d = TimeNowTool.descriptor()
        assertEquals("time.now", d.name.value)
        assertEquals(1, d.version.value)
        assertEquals(RiskLevel.L0, d.baseRisk)
        assertEquals(ToolOperationClass.READ_ONLY, d.operationClass)
        assertEquals(Idempotency.IDEMPOTENT, d.idempotency)
        assertEquals(ExecutionTargetType.LOCAL_ANDROID, d.executionTarget)
        assertTrue(d.requiredCapabilities.isEmpty())
        assertTrue(d.origin is ToolOrigin.BuiltInOrigin)
    }

    @Test
    fun theOutputSatisfiesItsOwnOutputSchemaAndIsClockDeterministic() {
        val executor = TimeNowTool.executor(clock)
        val call =
            ExecutableToolCall(
                toolCallId = "call-tn-1",
                toolName = "time.now",
                toolVersion = "1",
                args = Json.parseToJsonElement("{}").jsonObject,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                deadline = clock.instant.plusMillis(30_000),
                cancel = NoCancellation,
            )
        val first = executor.execute(call)
        val second = executor.execute(call)
        assertEquals("same clock, same output — deterministic", first, second)
        val completed = first as ToolExecutorResult.Completed
        val output = completed.output
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(TimeNowTool.descriptor().outputSchema, output),
        )
        assertEquals(
            clock.instant.toString(),
            (output.jsonObject["utcIso"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
        )
    }

    @Test
    fun duplicateRegistrationFailsOnBothRegistries() {
        val registry = ToolRegistry()
        val impls = ToolImplementationRegistry()
        TimeNowTool.register(registry, impls, clock)
        try {
            TimeNowTool.register(registry, impls, clock)
            throw AssertionError("duplicate time.now registration must fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("duplicate") ?: false)
        }
    }
}
