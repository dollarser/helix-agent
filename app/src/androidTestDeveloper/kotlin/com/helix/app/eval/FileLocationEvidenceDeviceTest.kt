package com.helix.app.eval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Offline checks of acceptance semantics; these are not real-model task evidence. */
class FileLocationEvidenceDeviceTest {
    @Test fun autonomousReadDoesNotRequireSearchToolCoverage() =
        fixture { directory, result, requests ->
            save(directory, result, requests)
            assertTrue(verifyFileLocationEvidence(directory, FileLocationAcceptance.AUTONOMOUS))
            assertFalse(verifyFileLocationEvidence(directory, FileLocationAcceptance.SEARCH_TOOLS))
        }

    @Test fun searchSpecialistRequiresBothToolsAndTheirActualConsumption() =
        fixture { directory, result, requests ->
            addSearch(result, requests, "glob")
            save(directory, result, requests)
            assertFalse(verifyFileLocationEvidence(directory, FileLocationAcceptance.SEARCH_TOOLS))
            addSearch(result, requests, "grep")
            save(directory, result, requests)
            assertTrue(verifyFileLocationEvidence(directory, FileLocationAcceptance.SEARCH_TOOLS))
        }

    @Test fun searchExecutionWithoutDeliveryIsNotCoverage() =
        fixture { directory, result, requests ->
            addSearch(result, requests, "glob")
            addSearch(result, requests, "grep", deliver = false)
            save(directory, result, requests)
            assertFalse(verifyFileLocationEvidence(directory, FileLocationAcceptance.SEARCH_TOOLS))
        }

    @Test fun unrelatedDeliveryCannotGroundAnswer() =
        fixture { directory, result, requests ->
            requests.last().getJSONArray("messages").getJSONObject(0).put(
                "content",
                "[SUCCEEDED] $TOKEN\nDelivery facts:" + JSONObject().put("callRef", "different/read"),
            )
            save(directory, result, requests)
            assertFalse(verifyFileLocationEvidence(directory, FileLocationAcceptance.AUTONOMOUS))
        }

    @Test fun seededAnswerOrIncompleteTurnCannotPass() =
        fixture { directory, result, requests ->
            requests.first().put("prompt", TOKEN)
            save(directory, result, requests)
            assertFalse(verifyFileLocationEvidence(directory, FileLocationAcceptance.AUTONOMOUS))
            requests.first().remove("prompt")
            result.getJSONArray("turns").getJSONObject(0).put("state", "FAILED")
            save(directory, result, requests)
            assertFalse(verifyFileLocationEvidence(directory, FileLocationAcceptance.AUTONOMOUS))
        }

    @Test fun missingAnswerEvidenceCannotPass() =
        fixture { directory, result, requests ->
            result.put("answer", "$PATH $TOKEN")
            save(directory, result, requests)
            assertFalse(verifyFileLocationEvidence(directory, FileLocationAcceptance.AUTONOMOUS))
        }

    private fun fixture(assertions: (File, JSONObject, List<JSONObject>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "file-evidence-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val result =
                JSONObject()
                    .put("expectedToken", TOKEN)
                    .put("expectedPath", PATH)
                    .put("answer", "$PATH $TOKEN restore synthetic checkpoint SEED-case")
                    .put("turns", JSONArray().put(JSONObject().put("state", "COMPLETED")))
                    .put("tools", JSONArray())
            val requests = listOf(JSONObject().put("messages", JSONArray()), JSONObject().put("messages", JSONArray()))
            addCall(result, requests, "read", JSONObject().put("text", TOKEN).toString(), true)
            assertions(directory, result, requests)
        } finally {
            check(directory.deleteRecursively())
        }
    }

    private fun addSearch(
        result: JSONObject,
        requests: List<JSONObject>,
        name: String,
        deliver: Boolean = true,
    ) {
        val payload = JSONObject().put("path", PATH).put("complete", true).put("truncated", false)
        addCall(result, requests, name, payload.toString(), deliver)
    }

    private fun addCall(
        result: JSONObject,
        requests: List<JSONObject>,
        name: String,
        content: String,
        deliver: Boolean,
    ) {
        result.getJSONArray("tools").put(
            JSONObject()
                .put("id", name)
                .put("turnId", "turn")
                .put("name", name)
                .put("state", "COMPLETED")
                .put("resultStatus", "SUCCEEDED")
                .put("result", content),
        )
        if (deliver) {
            requests.last().getJSONArray("messages").put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", "wire-$name")
                    .put(
                        "content",
                        "[SUCCEEDED] $content\nDelivery facts:" + JSONObject().put("callRef", "turn/$name"),
                    ),
            )
        }
    }

    private fun save(
        directory: File,
        result: JSONObject,
        requests: List<JSONObject>,
    ) {
        File(directory, "result.json").writeText(result.toString())
        requests.forEachIndexed { index, request ->
            File(directory, "request-$index.json").writeText(JSONObject().put("sent", request).toString())
        }
    }

    private companion object {
        const val TOKEN = "RANDOM_EVIDENCE"
        const val PATH = "teams/release-case.md"
    }
}
