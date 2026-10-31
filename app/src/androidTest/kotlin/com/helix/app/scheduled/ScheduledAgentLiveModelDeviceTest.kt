package com.helix.app.scheduled

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.account.BuiltInAccountWire
import com.helix.app.chat.MessageRunMetricsReader
import com.helix.app.provider.BuiltInModel
import com.helix.app.provider.analysisHash
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in; fresh isolated DB/context, only synthetic facts and the safe time.now tool. */
class ScheduledAgentLiveModelDeviceTest {
    @Test fun scheduledSyntheticTaskUsesRuntimeToolAndOriginalDestination(): Unit =
        runBlocking {
            val optIn = InstrumentationRegistry.getArguments().getString("helix.scheduledLive")
            org.junit.Assume.assumeTrue("Explicit scheduled live-model opt-in absent", optIn != null)
            require(optIn == "true")
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
            require(app.packageName == "com.helix.agent.developer")
            val wire = liveWire(app)
            ScheduledRuntimeFixture(
                wire,
                accountIdentity = { app.account.identity },
                catalog =
                    app
                        .awaitAppContainer()
                        .providerService.builtInModels
                        .current(),
            ).use { f ->
                val attempt =
                    f.execute(
                        "这是合成验收任务。合成项目负责人 TEAM_SYNTH_42，预算137，增量42。" +
                            "必须先调用 time.now 一次，再用中文报告负责人、预算加增量之和，" +
                            "以及逐字复制工具返回的完整 utcIso 字符串（包括小数秒），不要调用其他工具。",
                    )
                assertEquals("COMPLETED", attempt.state)
                val calls = f.storage.toolCalls.listByTurn(attempt.turnId)
                assertEquals(1, calls.size)
                assertEquals("time.now", calls.single().name)
                val result = requireNotNull(f.storage.toolResults.byToolCall(calls.single().id))
                val resultBody = requireNotNull(f.storage.toolResults.readContent(result))
                assertTrue(result.verified)
                val utc = JSONObject(resultBody).getString("utcIso")
                val body =
                    f.storage.messages
                        .listBySession("bound")
                        .filter { it.role == "ASSISTANT" }
                        .mapNotNull { f.storage.messages.readContent(it) }
                        .joinToString("\n")
                assertTrue(body.contains("TEAM_SYNTH_42"))
                assertTrue(body.contains("179"))
                assertTrue("Actual model must consume exact tool output", body.contains(utc))
                assertDestination(f, attempt.turnId)
                recordMetrics(app, f, attempt.turnId, wire.requests)
                android.util.Log.i(
                    "HelixScheduledEvidence",
                    "scheduled-live=${BuiltInModel.MODEL_ID} " +
                        "attempt=COMPLETED plan=true originalSession=true toolVerified=true " +
                        "exactToolConsumption=true " +
                        "answerHash=${analysisHash(body)}",
                )
            }
        }

    private fun assertDestination(
        f: ScheduledRuntimeFixture,
        turnId: String,
    ) {
        assertTrue(
            f.storage.turns
                .listBySession("foreground")
                .isEmpty(),
        )
        assertEquals("foreground", f.chat.screen.value.openSessionId)
        assertEquals(
            "PLAN",
            f.storage.turnRuntimeRecords
                .find(turnId)
                ?.mode,
        )
        assertTrue(
            f.storage.modelCalls
                .listByTurn(turnId)
                .size >= 2,
        )
    }

    private suspend fun liveWire(app: HelixApplication): BoundedScheduledLiveWire {
        app.account.restore()
        check(app.account.signedIn.value) { "Existing test account requires sign-in" }
        val discovery = app.awaitAppContainer().providerService.builtInModels
        discovery.refresh(false)
        val catalog = discovery.current()
        require(catalog.failure == null && BuiltInModel.MODEL_ID in catalog.models)
        return BoundedScheduledLiveWire(BuiltInAccountWire(app.account))
    }

    private fun recordMetrics(
        app: HelixApplication,
        f: ScheduledRuntimeFixture,
        turnId: String,
        requests: Int,
    ) {
        val answer =
            f.storage.messages.byTurn(turnId).last {
                it.role == "ASSISTANT" &&
                    it.kind == "TEXT"
            }
        val metrics = MessageRunMetricsReader(f.storage).read(answer.id)
        assertEquals(requests, metrics.requests.size)
        assertTrue(metrics.requests.all { requireNotNull(it.observation).durationMs != null })
        File(app.cacheDir, "priority-live-metrics.json").writeText(
            JSONObject()
                .put("model", BuiltInModel.MODEL_ID)
                .put("realProvider", true)
                .put("generationRequests", requests)
                .put("requestsWithReportedTokens", metrics.requests.count { it.outputTokens != null })
                .put("clientTimingsPersisted", true)
                .put("toolBackfillConsumed", true)
                .put(
                    "totalRequestDurationMs",
                    metrics.requests.sumOf { requireNotNull(it.observation?.durationMs) },
                ).toString(),
        )
    }
}

private class BoundedScheduledLiveWire(
    private val delegate: WireClient,
) : WireClient {
    var requests = 0
        private set

    override suspend fun open(request: WireRequest): WireResponse {
        require(request.method == "POST" && request.url == BuiltInModel.GENERATION_URL)
        require(
            ++requests <= 6 && requireNotNull(request.body).size <= 65_536,
        ) { "Synthetic live request budget exceeded" }
        return delegate.open(request)
    }
}
