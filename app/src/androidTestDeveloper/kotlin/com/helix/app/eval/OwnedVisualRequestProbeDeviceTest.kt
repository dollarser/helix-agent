package com.helix.app.eval

import android.graphics.Bitmap
import android.util.Base64
import com.helix.app.provider.BuiltInModel
import com.helix.provider.api.wire.WireRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Offline instrumentation: no account, transport, helper, or model calls. */
class OwnedVisualRequestProbeDeviceTest {
    @Test fun decodableImageCountsButSemanticTextDoesNot() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
        } finally {
            bitmap.recycle()
        }
        val encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        assertEquals(1, ownedVisualImageCount(messages("data:image/png;base64,$encoded")))
        val text = buildJsonObject { put("content", "image tokens=512; pixelsAttached=true; PURPLE") }
        assertEquals(0, ownedVisualImageCount(JsonArray(listOf(text))))
    }

    @Test fun pathsAndInvalidImageBytesCannotBeTransportImages() {
        listOf("/private/screenshot.png", "https://example.com/image.png", "data:image/png;base64,bm90LWltYWdl")
            .forEach { assertTrue(runCatching { ownedVisualImageCount(messages(it)) }.isFailure) }
    }

    @Test fun acceptanceRequiresOwnedDelegatedImageAndSuccessfulResponseTogether() {
        fun row(
            images: Int,
            status: Int?,
            owned: Boolean = true,
            delegated: Boolean = true,
        ) = buildJsonObject {
            put("ownedPromptPresent", owned)
            put("originalRequestDelegated", delegated)
            put("encodedDecodableImages", images)
            put("responseStatus", status)
        }
        assertTrue(acceptedVisualRequest(listOf(row(1, 200))))
        assertFalse(acceptedVisualRequest(emptyList()))
        assertFalse(acceptedVisualRequest(listOf(row(0, 200))))
        assertFalse(acceptedVisualRequest(listOf(row(1, null))))
        assertFalse(acceptedVisualRequest(listOf(row(1, 500))))
        assertFalse(acceptedVisualRequest(listOf(row(1, 200, owned = false))))
        assertFalse(acceptedVisualRequest(listOf(row(1, 200, delegated = false))))
        assertFalse(acceptedVisualRequest(listOf(row(0, 200), row(1, 500))))
    }

    @Test fun ownershipRequiresThisNonceInUserInputAndTheConfiguredModel() {
        fun body(
            marker: String,
            role: String,
            model: String,
        ) = buildJsonObject {
            put("model", model)
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", role)
                            put("content", "owned request $marker")
                        },
                    ),
                ),
            )
        }
        val model = BuiltInModel.MODEL_ID
        assertTrue(isOwnedVisualRequest(body("nonce-a", "user", model), "nonce-a"))
        assertFalse(isOwnedVisualRequest(body("nonce-b", "user", model), "nonce-a"))
        assertFalse(isOwnedVisualRequest(body("nonce-a", "tool", model), "nonce-a"))
        assertFalse(isOwnedVisualRequest(body("nonce-a", "user", "other-model"), "nonce-a"))
    }

    @Test fun closedGateStillPassesOtherRequestsThroughWithoutObservation() {
        val body =
            buildJsonObject {
                put("model", BuiltInModel.MODEL_ID)
                put(
                    "messages",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("role", "user")
                                put("content", "owned nonce-a")
                            },
                        ),
                    ),
                )
            }.toString().toByteArray()
        val request =
            WireRequest(
                "POST",
                BuiltInModel.GENERATION_URL,
                emptyMap(),
                body,
            )
        assertEquals(null, ownedVisualRequestBody(request, "nonce-b", closed = false))
        assertEquals(null, ownedVisualRequestBody(request, "nonce-b", closed = true))
        assertTrue(ownedVisualRequestBody(request, "nonce-a", closed = false) != null)
        assertTrue(runCatching { ownedVisualRequestBody(request, "nonce-a", closed = true) }.isFailure)
        assertTrue(body.contentEquals(request.body))
        val malformed = WireRequest("POST", request.url, emptyMap(), "not-json".toByteArray())
        assertEquals(null, ownedVisualRequestBody(malformed, "nonce-a", closed = true))
        val catalog = WireRequest("GET", BuiltInModel.CATALOG_URL, emptyMap(), null)
        assertEquals(null, ownedVisualRequestBody(catalog, "nonce-a", closed = true))
    }

    private fun messages(url: String): JsonArray {
        val image =
            buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", url) })
            }
        return JsonArray(listOf(buildJsonObject { put("content", JsonArray(listOf(image))) }))
    }
}
