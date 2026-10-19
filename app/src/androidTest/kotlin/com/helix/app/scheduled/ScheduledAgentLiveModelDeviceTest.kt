package com.helix.app.scheduled

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.provider.BuiltInModel
import com.helix.app.provider.analysisHash
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit opt-in; fresh isolated DB/context, only synthetic facts and the safe time.now tool. */
class ScheduledAgentLiveModelDeviceTest {
    @Test fun scheduledSyntheticTaskUsesRuntimeToolAndOriginalDestination(): Unit =
        runBlocking {
            val optIn = InstrumentationRegistry.getArguments().getString("helix.scheduledLive")
            org.junit.Assume.assumeTrue("Explicit scheduled live-model opt-in absent", optIn != null)
            require(optIn == "true")
            ScheduledRuntimeFixture().use { f ->
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
                assertTrue(
                    f.storage.turns
                        .listBySession("foreground")
                        .isEmpty(),
                )
                assertEquals("foreground", f.chat.screen.value.openSessionId)
                assertEquals(
                    "PLAN",
                    f.storage.turnRuntimeRecords
                        .find(attempt.turnId)
                        ?.mode,
                )
                assertTrue(
                    f.storage.modelCalls
                        .listByTurn(attempt.turnId)
                        .size >= 2,
                )
                android.util.Log.i(
                    "HelixScheduledEvidence",
                    "scheduled-live=${BuiltInModel.MODEL_ID} " +
                        "attempt=COMPLETED plan=true originalSession=true toolVerified=true " +
                        "exactToolConsumption=true " +
                        "answerHash=${analysisHash(body)}",
                )
            }
        }
}
