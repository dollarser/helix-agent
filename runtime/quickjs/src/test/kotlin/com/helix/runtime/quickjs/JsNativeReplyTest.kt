package com.helix.runtime.quickjs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsNativeReplyTest {
    @Test fun ordinaryApiFailureUsesErrorEnvelope() {
        val response = JsNativeHost.nativeReply { throw SecurityException("denied") }
        assertEquals(
            "SecurityException: denied",
            Json
                .parseToJsonElement(response)
                .jsonObject["error"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test fun reflectionCauseIsBounded() {
        val response =
            JsNativeHost.nativeReply {
                throw java.lang.reflect.InvocationTargetException(IllegalArgumentException("x".repeat(2000)))
            }
        val error = requireNotNull(Json.parseToJsonElement(response).jsonObject["error"]).jsonPrimitive.content
        assertTrue(error.startsWith("IllegalArgumentException:"))
        assertTrue(error.length < 600)
    }

    @Test fun fatalErrorsAreNotSwallowed() {
        assertThrows(AssertionError::class.java) { JsNativeHost.nativeReply { throw AssertionError("fatal") } }
        assertThrows(AssertionError::class.java) {
            JsNativeHost.nativeReply { throw java.lang.reflect.InvocationTargetException(AssertionError("fatal")) }
        }
    }

    @Test fun valueContainingErrorIsStillSuccessfulData() {
        val response = JsNativeHost.nativeReply { """{"error":"user data"}""" }
        assertEquals(
            "user data",
            Json
                .parseToJsonElement(response)
                .jsonObject
                .getValue("value")
                .jsonObject
                .getValue("error")
                .jsonPrimitive.content,
        )
    }
}
