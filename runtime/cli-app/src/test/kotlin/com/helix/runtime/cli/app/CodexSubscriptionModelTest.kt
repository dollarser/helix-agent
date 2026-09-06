package com.helix.runtime.cli.app

import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CodexSubscriptionModelTest {
    @Test fun subscriptionWireRequestOmitsUnsupportedOutputTokenLimit() {
        val request = ModelRequest(
            model = "gpt-test",
            messages = listOf(ModelMessage(ModelRole.USER, "hello")),
            maxOutputTokens = 8,
        )

        val body = Json.parseToJsonElement(
            CodexSubscriptionModel.encodeSubscriptionRequest(request),
        ).jsonObject

        assertEquals("gpt-test", body.getValue("model").toString().trim('"'))
        assertFalse("max_output_tokens" in body)
    }
}
