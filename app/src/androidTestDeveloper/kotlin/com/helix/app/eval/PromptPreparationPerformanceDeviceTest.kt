package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.chat.ChatRequestAssembler
import com.helix.app.infrastructure.agent.TurnRuntimeRecordCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in, no requests or tool execution: inspect existing synthetic context with production wiring. */
class PromptPreparationPerformanceDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer

    @Test fun measureExistingSyntheticContext() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("helixPromptMeasurement") == "true")
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            val session = requireNotNull(args.getString("helixSession"))
            val turn = requireNotNull(args.getString("helixTurn"))
            check(
                c.storage.turns
                    .resolve(turn)
                    .sessionId == session,
            )
            val control = TurnRuntimeRecordCodec.decode(requireNotNull(c.storage.turnRuntimeRecords.find(turn))).control
            val installed = c.pluginService.list()
            val selected = c.pluginService.catalog.effectiveSelected(session)
            assertTrue(installed.any { it.native?.pluginId == "mobile-use" && it.id in selected })
            // Test-only access to the exact production assembler, no alternate default wiring.
            val composition =
                c.javaClass
                    .getDeclaredField("conversation")
                    .apply { isAccessible = true }
                    .get(c)
            val assembler =
                composition.javaClass
                    .getDeclaredField("requestAssembler")
                    .apply { isAccessible = true }
                    .get(composition) as ChatRequestAssembler
            val rows = samples(assembler, session, turn, control)
            assertEquals(installed, c.pluginService.list())
            assertEquals(selected, c.pluginService.catalog.effectiveSelected(session))
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            File(app.cacheDir, "prompt-preparation-performance.json").writeText(rows.toString())
            println("PROMPT_MEASUREMENT_COMPLETE samples=8;network=false;toolExecution=false;contentRecorded=false")
        }

    private suspend fun samples(
        assembler: ChatRequestAssembler,
        session: String,
        turn: String,
        control: com.helix.core.agent.RunControlConfig,
    ) = buildJsonArray {
        repeat(8) { iteration ->
            val before =
                c.pluginRegistry.performance
                    .snapshot()
                    .associateBy { it.pluginId to it.operation }
            val started = System.nanoTime()
            val request =
                if (iteration % 2 == 0) {
                    assembler.buildRequest(session, turn, turn, control)
                } else {
                    assembler.buildBackfillRequest(session, turn, control, turn)
                }
            val elapsed = System.nanoTime() - started
            assertTrue(request.tools.isNotEmpty())
            add(
                buildJsonObject {
                    put("iteration", iteration)
                    put("path", if (iteration % 2 == 0) "initial_retry" else "backfill_retry")
                    put("elapsedNanos", elapsed)
                    put("promptFingerprint", request.prompt?.fingerprint.orEmpty())
                    put("toolSchemaFingerprint", schemaFingerprint(request.tools))
                    put(
                        "measurements",
                        buildJsonArray {
                            c.pluginRegistry.performance.snapshot().forEach { row ->
                                val previous = before[row.pluginId to row.operation]
                                add(
                                    buildJsonObject {
                                        put("pluginId", row.pluginId)
                                        put("operation", row.operation.name)
                                        put("calls", row.calls - (previous?.calls ?: 0))
                                        put("failures", row.failures - (previous?.failures ?: 0))
                                        put("totalNanos", row.totalNanos - (previous?.totalNanos ?: 0))
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    private fun schemaFingerprint(tools: List<com.helix.core.model.ModelToolSchema>): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(
                tools.joinToString("\n") { "${it.name.value}\n${it.description}\n${it.inputSchemaJson}" }.toByteArray(),
            ).joinToString("") { "%02x".format(it) }
}
