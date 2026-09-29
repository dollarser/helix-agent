package com.helix.runtime.quickjs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsNativeObjectsTest {
    @Test fun publicMethodsUseExactTypesAndOpaqueHandles() {
        val host = JsNativeObjects("context")
        val handle =
            host.invoke(
                Json.parseToJsonElement("""{"op":"create","type":"java.lang.StringBuilder"}""").jsonObject,
            )
        host.invoke(
            Json
                .parseToJsonElement(
                    """{"op":"call","target":$handle,"method":"append",
                        "types":["java.lang.String"],"args":["hello"]}""",
                ).jsonObject,
        )
        assertEquals(
            "\"hello\"",
            host
                .invoke(
                    Json.parseToJsonElement("""{"op":"call","target":$handle,"method":"toString"}""").jsonObject,
                ).toString(),
        )
        host.invoke(Json.parseToJsonElement("""{"op":"release","target":$handle}""").jsonObject)
        assertThrows(IllegalStateException::class.java) {
            host.invoke(Json.parseToJsonElement("""{"op":"call","target":$handle,"method":"toString"}""").jsonObject)
        }
    }

    @Test fun numericArgumentsDoNotSelectAnUnrelatedOverload() {
        val host = JsNativeObjects("context")
        assertEquals(
            "7",
            host
                .invoke(
                    Json
                        .parseToJsonElement(
                            """{"op":"staticCall","type":"java.lang.Math","method":"max",
                                "types":["int","int"],"args":[2,7]}""",
                        ).jsonObject,
                ).toString(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            host.invoke(
                Json
                    .parseToJsonElement(
                        """{"op":"staticCall","type":"java.lang.Math","method":"max","types":["int"],"args":[]}""",
                    ).jsonObject,
            )
        }
    }
}
