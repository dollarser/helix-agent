package com.helix.app.provider

import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireRequestRejectedException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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

    @Test fun provenImageFormatsPassBothProtocolShapesWithoutChangingContent() {
        for (mime in listOf("png", "jpeg", "webp")) {
            val url = "data:image/$mime;base64,aW1hZ2U="
            val chat = """{"model":"gpt-6-luna","messages":[{"role":"user","content":[
                {"type":"text","text":"inspect"},{"type":"image_url","image_url":{"url":"$url"}}]}]}"""
            val normalized = normalize(chat)
            assertEquals(Json.parseToJsonElement(chat).jsonObject["messages"], normalized["messages"])
            val response = """{"model":"gpt-6-luna","input":[{"role":"user","content":[
                {"type":"input_text","text":"inspect"},{"type":"input_image","image_url":"$url"}]}]}"""
            val encoded = HilexLunaRequestContract.normalize(request(response, BuiltInModel.RESPONSES_URL))
            assertEquals(
                Json.parseToJsonElement(response).jsonObject["input"],
                Json.parseToJsonElement(encoded.body!!.toString(Charsets.UTF_8)).jsonObject["input"],
            )
        }
    }

    @Test fun unsupportedMediaReferencesAndMalformedImagesRemainRejected() {
        val parts =
            listOf(
                """{"type":"input_audio"}""",
                """{"type":"file"}""",
                """{"type":"image_url"}""",
                """{"type":"image_url","image_url":{"url":"https://example.com/a.png"}}""",
                """{"type":"image_url","image_url":{"url":"data:image/svg+xml;base64,YQ=="}}""",
                """{"type":"image_url","image_url":{"url":"data:image/png;base64,"}}""",
                """{"type":"input_image","image_url":"data:image/png;base64,YQ=="}""",
            )
        for (part in parts) {
            val error =
                assertThrows(WireRequestRejectedException::class.java) {
                    normalize("""{"model":"gpt-6-luna","messages":[{"content":[$part]}]}""")
                }
            assertEquals(WireRequestRejectedException.Reason.UNSUPPORTED_MEDIA, error.reason)
        }
        assertThrows(WireRequestRejectedException::class.java) {
            HilexLunaRequestContract.normalize(
                request(
                    """{"model":"gpt-6-luna","input":[{"content":[{"type":"input_file","file_id":"file-x"}]}]}""",
                    BuiltInModel.RESPONSES_URL,
                ),
            )
        }
    }

    @Test fun imageBytesStillCountTowardConservativeRequestBudget() {
        val data = "A".repeat(250000)
        val error =
            assertThrows(WireRequestRejectedException::class.java) {
                normalize(
                    """{"model":"gpt-6-luna","messages":[{"content":[
                {"type":"image_url","image_url":{"url":"data:image/png;base64,$data"}}]}]}""",
                )
            }
        assertEquals(WireRequestRejectedException.Reason.INPUT_TOO_LARGE, error.reason)
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
