package com.helix.app.eval

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Join durable execution identity to successful tool-role delivery, not merely matching prompt text. */
@Suppress("CyclomaticComplexMethod", "LongMethod") // Independent execution, delivery and answer acceptance facts.
internal fun verifyFileLocationEvidence(directory: File): Boolean {
    val result = JSONObject(File(directory, "result.json").readText())
    val token = result.getString("expectedToken")
    val path = result.getString("expectedPath")
    val requests =
        directory
            .listFiles()
            .orEmpty()
            .filter { Regex("request-\\d+\\.json").matches(it.name) }
            .sortedBy {
                it.name
                    .removePrefix("request-")
                    .removeSuffix(".json")
                    .toInt()
            }.map { JSONObject(it.readText()).getJSONObject("sent") }
    val calls = result.getJSONArray("tools").fileObjects()
    val deliveries =
        requests
            .flatMap { it.getJSONArray("messages").fileObjects() }
            .filter { it.optString("role") == "tool" && it.optString("tool_call_id").isNotEmpty() }
    val consumed =
        listOf("glob", "grep", "read").associateWith { name ->
            calls
                .filter {
                    it.optString("name") == name && it.optString("state") == "COMPLETED" &&
                        it.optString("resultStatus") == "SUCCEEDED"
                }.any { call ->
                    val content = call.optString("result").replace("\\/", "/")
                    val payload = JSONObject(content)
                    val supported =
                        if (name == "read") {
                            token in content
                        } else {
                            path in content && payload.optBoolean("complete") && !payload.optBoolean("truncated") &&
                                payload.optInt("unreadableEntries") == 0 && payload.optInt("skippedContentFiles") == 0
                        }
                    supported &&
                        deliveries.any { message ->
                            val body = message.optString("content")
                            val marker = "\nDelivery facts:"
                            val reference =
                                if (marker in
                                    body
                                ) {
                                    JSONObject(body.substringAfterLast(marker)).optString("callRef")
                                } else {
                                    ""
                                }
                            val delivered = body.substringBefore(marker).replace("\\/", "/")
                            reference == call.getString("turnId") + "/" + call.getString("id") &&
                                delivered.startsWith("[SUCCEEDED] ") &&
                                (if (name == "read") token in delivered else path in delivered)
                        }
                }
        }
    val absentInitially = requests.firstOrNull()?.toString()?.contains(token) == false
    val answer = result.optString("answer")
    val checkpoint = path.substringAfterLast("release-").removeSuffix(".md")
    val supportedAnswer = token in answer && path in answer && "restore synthetic checkpoint SEED-$checkpoint" in answer
    val audit =
        JSONObject()
            .put("consumed", JSONObject(consumed))
            .put("tokenAbsentInitially", absentInitially)
            .put("answerSupported", supportedAnswer)
    File(directory, "source-audit.json").writeText(audit.toString(2))
    return consumed.values.all { it } && absentInitially && supportedAnswer
}

private fun JSONArray.fileObjects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
