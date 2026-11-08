package com.helix.app.eval

import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.app.network.LanScopeStore
import com.helix.app.proot.LogComparisonWire
import com.helix.core.policy.NetworkOriginScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal fun liveResourceAnswer(
    c: AppContainer,
    session: String,
    turn: String,
): String =
    c.storage.messages
        .listBySession(session)
        .filter { it.turnId == turn && it.role == "ASSISTANT" && it.kind == "TEXT" }
        .joinToString("\n") {
            c.storage.messages
                .readContent(it)
                .orEmpty()
        }

/** Raw optional usage, not inferred savings. One reduction preserves independent partial acceptance facts. */
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun publishLiveResourceEvidence(
    c: AppContainer,
    session: String,
    directory: File,
    wire: LogComparisonWire,
    marker: String,
    completed: Boolean,
): Boolean {
    val turns = c.storage.turns.listBySession(session)
    val calls = turns.flatMap { c.storage.toolCalls.listByTurn(it.id) }
    val results =
        c.storage.messages
            .listBySession(session)
            .filter { it.kind == "TOOL_RESULT" }
            .map {
                JSONObject(
                    c.storage.messages
                        .readContent(it)
                        .orEmpty(),
                )
            }
    val web =
        results
            .filter { it.optString("tool") == "web_fetch" && it.optString("status") == "SUCCEEDED" }
            .map { JSONObject(it.getString("summary")) }
    val resource =
        results
            .filter {
                it.optString("tool").endsWith(".read_mcp_resource") && it.optString("status") == "SUCCEEDED"
            }.map { JSONObject(it.getString("summary")) }
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
            }.map {
                Json
                    .parseToJsonElement(it.readText())
                    .jsonObject
                    .getValue("sent")
                    .jsonObject
            }
    val toolMessages =
        requests.flatMap { request ->
            (request["messages"] as? JsonArray)
                .orEmpty()
                .map { it.jsonObject }
                .filter { it["role"]?.jsonPrimitive?.content == "tool" }
                .map { it["content"]?.jsonPrimitive?.content.orEmpty() }
        }
    val webVerified =
        web.any {
            it.optString("text").contains("This domain") && it.optString("status") == "read" &&
                it.optInt("httpStatus") == 200
        }
    val resourceVerified =
        resource.any { verifiedLiveResourcePayload(it, marker) }
    val consumed =
        toolMessages.any { it.contains("This domain") && it.contains("https://example.com/") } &&
            toolMessages.any { it.contains(marker) && it.contains("memo://live/source") }
    val sourceOnly = requests.firstOrNull()?.toString()?.contains(marker) == false
    val answerVerified =
        turns.size == 4 && turns.all { marker in liveResourceAnswer(c, session, it.id) } &&
            turns.firstOrNull()?.let { "Example Domain" in liveResourceAnswer(c, session, it.id) } == true
    val passed = completed && webVerified && resourceVerified && consumed && sourceOnly && answerVerified
    val responseCaptures =
        directory
            .listFiles()
            .orEmpty()
            .filter { Regex("response-\\d+\\.json").matches(it.name) }
            .sortedBy {
                it.name
                    .removePrefix("response-")
                    .removeSuffix(".json")
                    .toInt()
            }.map { Json.parseToJsonElement(it.readText()).jsonObject }
    val evidence =
        buildJsonObject {
            put("responseCaptureFiles", responseCaptures.size)
            put("responseCaptureExpected", wire.calls)
            put(
                "responseCaptureSummaries",
                JsonArray(
                    responseCaptures.map { capture ->
                        kotlinx.serialization.json.JsonObject(capture.filterKeys { it != "chunks" })
                    },
                ),
            )
            put("status", if (passed) "passed" else "failed_or_incomplete")
            put("realModel", true)
            put("realWebHttp", true)
            put("mcpTransport", "production SDK to owned loopback HTTP fixture; not a third-party account")
            put("mcpRegistration", "test registers a bridge directly; not full McpAppService setup acceptance")
            put("chatService", true)
            put("wireRequests", wire.calls)
            put("httpStatuses", JsonArray(wire.statuses.map(::JsonPrimitive)))
            put("webVerified", webVerified)
            put("resourceVerified", resourceVerified)
            put("consumedInFollowingRequest", consumed)
            put("markerAbsentFromInitialRequest", sourceOnly)
            put("answerVerified", answerVerified)
            put(
                "cacheInterpretation",
                "Observed server usage only; missing remains unknown; no causal A/B or billing claim",
            )
            put(
                "turns",
                JsonArray(
                    turns.map { turn ->
                        buildJsonObject {
                            put("id", turn.id)
                            put("state", turn.state)
                            put("startedAt", turn.startedAt)
                            put("endedAt", turn.endedAt?.let(::JsonPrimitive) ?: JsonNull)
                            turn.errorCode?.let { put("errorCode", it) }
                            put("answer", liveResourceAnswer(c, session, turn.id))
                            put(
                                "calls",
                                JsonArray(
                                    c.storage.modelCalls.listByTurn(turn.id).map { call ->
                                        buildJsonObject {
                                            put("id", call.id)
                                            put("createdAt", call.createdAt)
                                            put("settledAt", call.settledAt?.let(::JsonPrimitive) ?: JsonNull)
                                            put("ordinal", call.ordinal)
                                            put("state", call.state)
                                            put("usage", call.usage?.let(Json::parseToJsonElement) ?: JsonNull)
                                        }
                                    },
                                ),
                            )
                        }
                    },
                ),
            )
            put(
                "tools",
                JsonArray(
                    calls.map {
                        buildJsonObject {
                            put("name", it.name)
                            put("state", it.state)
                        }
                    },
                ),
            )
            put(
                "requestShapes",
                JsonArray(
                    requests.map { request ->
                        buildJsonObject {
                            put("bytes", request.toString().toByteArray().size)
                            put("messages", (request["messages"] as? JsonArray)?.size ?: 0)
                            put("tools", request["tools"] ?: JsonNull)
                        }
                    },
                ),
            )
        }
    File(directory, "result.json").writeText(evidence.toString())
    return passed
}

/** Explicit emulator-test authorization only: temporary isolated-App setting, never a session scope. */
internal class LiveResourceLanScope(
    private val app: HelixApplication,
    private val c: AppContainer,
    private val directory: File,
) {
    private val previous = c.lanScopeStore.current()
    private var attempted = false

    init {
        check(app.packageName == "com.helix.agent.developer.asrvalidation")
        check(TARGET !in previous) { "Refuse to replace an existing LAN origin" }
        check(durable() == previous) { "Initial LAN memory and disk differ" }
    }

    fun grant(authorizedOrigin: String?) {
        require(authorizedOrigin == ORIGIN) { "Exact temporary isolated-App LAN grant required" }
        attempted = true
        c.lanScopeStore.add(ORIGIN)
        check(c.lanScopeStore.current() == previous + TARGET)
        check(durable() == previous + TARGET)
    }

    fun revoke() {
        try {
            if (attempted) c.lanScopeStore.remove(ORIGIN)
        } finally {
            val memoryRestored = c.lanScopeStore.current() == previous
            val diskRestored = durable() == previous
            File(directory, "lan-scope-lifecycle.json").writeText(
                buildJsonObject {
                    put("scope", "temporary isolated-App setting, not session-scoped")
                    put("origin", ORIGIN)
                    put("grantAttempted", attempted)
                    put("memoryRestored", memoryRestored)
                    put("diskRestored", diskRestored)
                    put("dailyPackageTouched", false)
                }.toString(),
            )
            check(memoryRestored && diskRestored) { "Temporary LAN origin cleanup incomplete" }
        }
    }

    private fun durable() =
        LanScopeStore(PrefsLineStore(app, "helix-lan-scopes", synchronous = true)) { true }.current()

    private companion object {
        const val ORIGIN = "http://127.0.0.1:19092"
        val TARGET = NetworkOriginScope("127.0.0.1", 19092)
    }
}

/** Decode fields before comparison: Android JSONObject serialization escapes URI slashes. */
@Suppress("ReturnCount") // Each malformed contract boundary fails closed independently.
internal fun verifiedLiveResourcePayload(payload: JSONObject, marker: String): Boolean {
    if (payload.opt("isError") != false) return false
    val structured = payload.optJSONObject("structuredContent") ?: return false
    if (structured.opt("untrusted") != true) return false
    val resource = structured.optJSONObject("resource") ?: return false
    if (resource.optString("uri") != "memo://live/source") return false
    val contents = resource.optJSONArray("contents") ?: return false
    if (contents.length() != 1) return false
    val content = contents.optJSONObject(0) ?: return false
    val text = content.optString("text")
    val expected = "Owned synthetic resource. Verification marker: $marker"
    // Production MCP resources hash a length-framed field, not raw text (SdkMcpResources/hashFields).
    val canonical = "${text.toByteArray(Charsets.UTF_8).size}:$text;"
    val hash =
        MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    return content.optString("uri") == "memo://live/source" && text == expected &&
        content.optString("contentHash") == hash
}
