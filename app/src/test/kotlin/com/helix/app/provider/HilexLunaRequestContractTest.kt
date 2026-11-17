package com.helix.app.provider

import com.helix.provider.api.wire.WireRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HilexLunaRequestContractTest {
    private fun request(
        body: String,
        url: String = BuiltInModel.GENERATION_URL,
    ) = WireRequest("POST", url, emptyMap(), body.toByteArray())

    @Test fun defaultsAndCapsDeploymentOutput() {
        val default = normalize("""{"model":"gpt-6-luna","messages":[]}""")
        assertEquals("4096", default["max_completion_tokens"]?.jsonPrimitive?.content)
        val capped = normalize("""{"model":"gpt-6-luna","messages":[],"max_tokens":99999}""")
        assertEquals("16384", capped["max_completion_tokens"]?.jsonPrimitive?.content)
        assertEquals(null, capped["max_tokens"])
    }

    @Test fun historicalToolsAlsoDisableChatReasoningButResponsesKeepIt() {
        val chat =
            normalize(
                """{"model":"gpt-6-luna","messages":[{"role":"tool","content":"ok"}],"reasoning_effort":"medium"}""",
            )
        assertEquals("none", chat["reasoning_effort"]?.jsonPrimitive?.content)
        val body = """{"model":"gpt-6-luna","input":[],"reasoning":{"effort":"medium"}}"""
        val response = HilexLunaRequestContract.normalize(request(body, BuiltInModel.RESPONSES_URL))
        val reasoning = Json.parseToJsonElement(response.body!!.toString(Charsets.UTF_8)).jsonObject["reasoning"]
        assertEquals(
            "medium",
            reasoning
                ?.jsonObject
                ?.get("effort")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test fun otherEndpointsAndModelsAreUntouched() {
        val official = request("""{"model":"gpt-6-luna"}""", "https://api.openai.com/v1/chat/completions")
        assertSame(official, HilexLunaRequestContract.normalize(official))
        val other = request("""{"model":"other"}""")
        assertSame(other, HilexLunaRequestContract.normalize(other))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMediaInsteadOfSendingAnUnsupportedRequest() {
        normalize("""{"model":"gpt-6-luna","messages":[{"content":[{"type":"image_url"}]}]}""")
    }

    @Test fun responsesToolTextIsNotMistakenForMedia() {
        val body = """{
            "model":"gpt-6-luna",
            "input":[{"type":"function_call_output","call_id":"call","output":"image_url is a string, not media"}],
            "tools":[{"type":"function","name":"read","parameters":{"type":"object"}}]}"""
        HilexLunaRequestContract.normalize(request(body, BuiltInModel.RESPONSES_URL))
    }

    @Test(expected = IllegalArgumentException::class)
    fun responsesToolSchemaAndHistoryCountTowardByteBound() {
        val body = """{
            "model":"gpt-6-luna",
            "input":[{"type":"function_call_output","call_id":"call","output":"${"a".repeat(120000)}"}],
            "tools":[{"type":"function","name":"read","description":"${"b".repeat(130000)}"}]}"""
        HilexLunaRequestContract.normalize(request(body, BuiltInModel.RESPONSES_URL))
    }

    @Test(expected = IllegalArgumentException::class)
    fun byteBoundCountsMultibyteTextNotCharacters() {
        normalize("""{"model":"gpt-6-luna","messages":[{"content":"${"中".repeat(84000)}"}]}""")
    }

    private fun normalize(body: String) =
        Json
            .parseToJsonElement(
                HilexLunaRequestContract.normalize(request(body)).body!!.toString(Charsets.UTF_8),
            ).jsonObject
}
