package com.helix.tools.framework

import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class JobObservationFixture : JobObservationPort {
    val calls = AtomicInteger()
    val resolutions = AtomicInteger()
    val values = ConcurrentHashMap<String, JobObservation>()

    @Volatile var current = true

    @Volatile var queryAction: ((JobObservationBinding) -> JobObservation?)? = null
    val clock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-01T00:00:00Z")
        }

    fun binding(id: String) =
        JobObservationBinding("s", "source-turn", id, "fixture", "exec-$id", "job-$id", "a".repeat(64))

    fun value(
        id: String,
        terminal: Boolean = false,
        review: Boolean = false,
    ): JobObservation =
        JobObservation(
            binding(id),
            if (terminal) "SUCCEEDED" else "RUNNING",
            terminal,
            review,
            true,
            if (terminal) "terminal-$id" else "running-$id",
            1_000,
            if (terminal) 0 else null,
        )

    override fun resolve(
        sessionId: String,
        handle: String,
    ): JobObservationBinding {
        resolutions.incrementAndGet()
        require(sessionId == "s" && values.containsKey(handle)) { "fixture invalid handle" }
        return binding(handle)
    }

    override fun isCurrent(binding: JobObservationBinding): Boolean = current && binding == binding(binding.handle)

    override fun query(binding: JobObservationBinding): JobObservation? {
        check(!Thread.currentThread().name.contains("timer")) { "Query ran on timer" }
        calls.incrementAndGet()
        return queryAction?.invoke(binding) ?: values[binding.handle]
    }

    fun call(
        id: String = "observer",
        handles: List<String> = listOf("a"),
        condition: String = "ANY",
        outerMillis: Long = 5_000,
    ) = ExecutableToolCall(
        id,
        "jobs.await",
        "1",
        buildJsonObject {
            put("handles", JsonArray(handles.map(::JsonPrimitive)))
            put("condition", condition)
        },
        ExecutionTargetType.LOCAL_ANDROID,
        clock.now().plusMillis(outerMillis),
        NoCancellation,
        "s",
        "observer-turn",
    )

    fun service(
        limits: JobObservationLimits = JobObservationLimits(maxWaitMillis = 1_000, pollMillis = 10, tickMillis = 5),
    ) = JobObservationService(this, clock, limits)
}

internal fun JobObservationService.submit(
    call: ExecutableToolCall,
    mayRead: () -> Boolean = { true },
) = (awaitExecutor() as JobObservationExecutor).start(call, mayRead)

internal fun CountDownLatch.awaitChecked() {
    check(await(5, TimeUnit.SECONDS)) { "fixture latch timed out" }
}
