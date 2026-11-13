package com.helix.smoke

import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic wire observations only. No production hooks, database access, accounts or model services. */
internal class ReleaseConversationScenario {
    private val id = UUID.randomUUID().toString()
    private val requests = ConcurrentHashMap<String, AtomicInteger>()
    val disconnected = AtomicInteger()
    val toolBackfills = AtomicInteger()
    val restoredHistories = AtomicInteger()

    fun prompt(phase: String) = "R8_CORE=$id:$phase Use only the synthetic read-only fixture."

    fun answer(phase: String) = "R8_VERIFIED_${id}_$phase"

    fun count(phase: String) = requests[phase]?.get() ?: 0

    private fun phase(request: JSONObject): String? {
        val user = request.optJSONArray("messages")?.objects()?.lastOrNull { it.optString("role") == "user" }
        return Regex("R8_CORE=$id:([a-z]+)").find(user?.opt("content")?.toString().orEmpty())?.groupValues?.get(1)
    }

    fun holdCancelledRequest(
        request: JSONObject,
        socket: Socket,
    ): Boolean {
        if (phase(request) != "cancel") return false
        requests.computeIfAbsent("cancel") { AtomicInteger() }.incrementAndGet()
        socket.soTimeout = 45_000
        check(socket.getInputStream().read() == -1) { "Cancelled request must close its wire connection" }
        disconnected.incrementAndGet()
        return true
    }

    fun reply(request: JSONObject): Pair<JSONObject, String>? {
        val phase = phase(request) ?: return null
        requests.computeIfAbsent(phase) { AtomicInteger() }.incrementAndGet()
        val messages = request.getJSONArray("messages").objects()
        if (phase == "tool") {
            val callId = "r8-core-search-$id"
            val result =
                messages.lastOrNull {
                    it.optString("role") == "tool" && it.optString("tool_call_id") == callId
                }
            if (result == null) {
                check(count(phase) == 1) { "Read-only tool was replayed" }
                val tool =
                    request
                        .getJSONArray("tools")
                        .objects()
                        .map { it.getJSONObject("function") }
                        .single { it.getString("description").startsWith("Search user-enabled tools") }
                val function =
                    JSONObject()
                        .put("name", tool.getString("name"))
                        .put("arguments", JSONObject().put("query", "read").put("limit", 1).toString())
                val call =
                    JSONObject()
                        .put("id", callId)
                        .put("index", 0)
                        .put("type", "function")
                        .put("function", function)
                return JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(call)) to "tool_calls"
            }
            val content = result.opt("content")?.toString().orEmpty()
            check(content.contains("\"schemaAvailability\":\"next_request\"")) {
                "Missing successful discovery result: $content"
            }
            check(content.contains("\"tools\"")) { "Missing discovered tools" }
            check(
                messages.any {
                    it.optString("role") == "assistant" &&
                        it
                            .opt("tool_calls")
                            ?.toString()
                            .orEmpty()
                            .contains(callId)
                },
            )
            toolBackfills.incrementAndGet()
        }
        if (phase == "continue" || phase == "aftercancel") {
            check(
                messages.any {
                    it.optString("role") == "assistant" &&
                        it
                            .opt("content")
                            ?.toString()
                            .orEmpty()
                            .contains(answer("plain"))
                },
            ) {
                "Completed ordinary answer was not loaded from persistent history"
            }
            check(
                messages.any {
                    it.optString("role") == "assistant" &&
                        it
                            .opt("content")
                            ?.toString()
                            .orEmpty()
                            .contains(answer("tool"))
                },
            ) {
                "Completed tool answer was not loaded from persistent history"
            }
            restoredHistories.incrementAndGet()
        }
        return JSONObject().put("role", "assistant").put("content", answer(phase)) to "stop"
    }
}

private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
