package com.helix.app.provider

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class ScriptedTaskModelServerTest {
    @Test fun missingToolDiscoversBeforeStepAndUsesEachRequestsCurrentDeclaration() {
        ScriptedTaskModelServer { name ->
            when (name) {
                "tools_search" -> "Search fixture tools"
                "time_now" -> "Read fixture clock"
                else -> error("Unknown fixture intention")
            }
        }.use { server ->
            server.start()
            server.arm(listOf(ScriptedTaskModelServer.Step("time_now") { "{}" }), "Finished")
            val discovery = call(exchange(server, request("first_search", null)))
            assertEquals("first_search", discovery.getJSONObject("function").getString("name"))
            assertEquals(
                "time_now",
                JSONObject(discovery.getJSONObject("function").getString("arguments")).getString("query"),
            )
            val discovered = request("new_search", "current_clock", discovery.getString("id"))
            val clock = call(exchange(server, discovered))
            assertEquals("current_clock", clock.getJSONObject("function").getString("name"))
            val finished = request("third_search", "third_clock", discovery.getString("id"), clock.getString("id"))
            val final = exchange(server, finished)
            assertTrue(final.contains("Finished"))
            assertTrue(!final.contains("tool_calls"))
        }
    }

    private fun request(
        searchName: String,
        clockName: String?,
        vararg results: String,
    ): JSONObject {
        val tools = JSONArray().put(tool(searchName, "Search fixture tools"))
        if (clockName != null) tools.put(tool(clockName, "Read fixture clock"))
        val messages = JSONArray()
        results.forEach { messages.put(JSONObject().put("role", "tool").put("tool_call_id", it).put("content", "ok")) }
        return JSONObject().put("tools", tools).put("messages", messages)
    }

    private fun tool(
        name: String,
        description: String,
    ) = JSONObject().put("function", JSONObject().put("name", name).put("description", description))

    private fun exchange(
        server: ScriptedTaskModelServer,
        request: JSONObject,
    ): String =
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 5_000
            val bytes = request.toString().toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("POST /v1/chat/completions HTTP/1.1\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
            socket
                .getInputStream()
                .bufferedReader()
                .readText()
                .substringAfter("\r\n\r\n")
        }

    private fun call(stream: String): JSONObject =
        JSONObject(stream.lineSequence().first { it.startsWith("data: ") }.removePrefix("data: "))
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("delta")
            .getJSONArray("tool_calls")
            .getJSONObject(0)
}
