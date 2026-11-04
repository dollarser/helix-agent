package com.helix.app.eval

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.approval.StorageAuditSink
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-only timing inspection of an explicitly selected existing run; no prompts, tools or network. */
class MobileUseTimingDeviceTest {
    @Test
    fun inspectSelectedCompletedRun() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val session = requireNotNull(InstrumentationRegistry.getArguments().getString("helixTimingSession"))
        val storage = (instrumentation.targetContext.applicationContext as HelixApplication).appContainer.storage
        val turns = storage.turns.listBySession(session)
        require(turns.isNotEmpty() && turns.all { it.endedAt != null }) { "Select a completed existing run" }
        val models = turns.flatMap { storage.modelCalls.listByTurn(it.id) }
        val calls = turns.flatMap { storage.toolCalls.listByTurn(it.id) }
        val timeline = JSONArray()
        for (call in calls) {
            val audits = storage.auditEvents.listByCorrelation(call.callId).filter { it.type == StorageAuditSink.TYPE }
            for (audit in audits) {
                val payload = JSONObject(audit.redactedPayload)
                timeline.put(
                    JSONObject().apply {
                        put("tool", call.name)
                        put("ordinal", call.ordinal)
                        put("startedAt", payload.getLong("startedAt"))
                        put("finishedAt", payload.getLong("finishedAt"))
                        put("executionStartedAt", payload.opt("executionStartedAt"))
                        put("code", payload.getString("code"))
                    },
                )
            }
        }
        assertTrue("No model timing was captured", models.any { it.createdAt > 0 && it.settledAt != null })
        assertTrue("No dispatch timing was captured", timeline.length() > 0)
        val report =
            JSONObject().apply {
                put("historicalRun", true)
                put("session", session)
                put(
                    "turns",
                    JSONArray(turns.map { JSONObject().put("startedAt", it.startedAt).put("endedAt", it.endedAt) }),
                )
                put(
                    "models",
                    JSONArray(
                        models.map {
                            JSONObject()
                                .put("ordinal", it.ordinal)
                                .put("createdAt", it.createdAt)
                                .put("settledAt", it.settledAt)
                                .put("state", it.state)
                        },
                    ),
                )
                put("dispatches", timeline)
            }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "HELIX_TIMING=$report\n") })
    }
}
