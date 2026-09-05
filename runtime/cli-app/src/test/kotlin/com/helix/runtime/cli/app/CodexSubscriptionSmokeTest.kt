package com.helix.runtime.cli.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CodexSubscriptionSmokeTest {
    @Test fun fixedRequestHasNoToolsAndStrictOutputLimit() {
        val body = Json.parseToJsonElement(CodexSubscriptionSmoke.encodeRequest("gpt-test")).jsonObject

        assertEquals("gpt-test", body.getValue("model").jsonPrimitive.content)
        assertEquals(
            "Reply exactly HELIX_OK",
            body.getValue("input").jsonArray.single().jsonObject
                .getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
        )
        assertFalse("tools" in body)
        assertFalse("tool_choice" in body)
        assertFalse("max_output_tokens" in body)
        assertFalse("reasoning" in body)
        assertEquals(false, body.getValue("store").jsonPrimitive.content.toBoolean())
        assertEquals(true, body.getValue("stream").jsonPrimitive.content.toBoolean())
    }

    @Test fun endpointAndClientVersionArePinned() {
        assertEquals("https://chatgpt.com/backend-api/codex/models", CodexSubscriptionSmoke.MODELS_URL)
        assertEquals("https://chatgpt.com/backend-api/codex/responses", CodexSubscriptionSmoke.RESPONSES_URL)
        assertEquals("0.153.4", CodexSubscriptionSmoke.CLIENT_VERSION)
    }
}
