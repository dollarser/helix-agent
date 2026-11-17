package com.helix.app.eval

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.account.BuiltInAccountWire
import com.helix.app.provider.BuiltInModel
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.httpFailureDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Gateway-only pixel probe. Does not alter model capabilities or prove app image-tool integration. */
class LunaPixelCapabilityDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val rows = mutableListOf<JsonObject>()
    private val colors =
        linkedMapOf(
            "red" to 0xffff0000,
            "green" to 0xff00b400,
            "blue" to 0xff0000ff,
            "yellow" to 0xffffff00,
            "magenta" to 0xffff00ff,
            "cyan" to 0xff00ffff,
        )
    private val prompt =
        "Inspect the attached image pixels: a 3-column, 2-row grid of solid colors. " +
            "Return only a JSON array of six English color names, top row left to right then bottom row. " +
            "Use red, green, blue, yellow, magenta, cyan. If no image is attached return only NO_IMAGE. Do not guess."

    @Test fun existingAccountRecognizesIndependentPixelInputs() =
        runBlocking(Dispatchers.IO) {
            assumeTrue(InstrumentationRegistry.getArguments().getString("lunaPixels") == "true")
            check(app.packageName == "com.helix.agent.developer" && android.os.Build.HARDWARE == "ranchu")
            check(
                app.appContainer.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            val output = File(app.cacheDir, "luna-pixels/${UUID.randomUUID()}.json")
            check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
            try {
                app.account.restore()
                check(app.account.signedIn.value) { "Existing test account login required" }
                val wire = BuiltInAccountWire(app.account)
                withTimeout(180_000) {
                    catalog(wire)
                    check(runProbes(wire)) { "Pixel probe did not fully pass; see sanitized evidence" }
                }
            } catch (failure: Exception) {
                rows += buildJsonObject { put("failureType", failure.javaClass.simpleName) }
                throw AssertionError("Pixel probe incomplete; see sanitized evidence")
            } finally {
                output.writeText(JsonArray(rows).toString())
            }
        }

    private suspend fun runProbes(wire: BuiltInAccountWire): Boolean {
        val first = colors.keys.shuffled()
        if (InstrumentationRegistry.getArguments().getString("lunaPixelMatrix") == "true") {
            val results = mutableListOf<Boolean>()
            for (responses in listOf(false, true)) {
                for (format in listOf("png", "jpeg", "webp")) {
                    results += probe(wire, "$responses-$format", first, responses, format)
                }
            }
            return results.all { it }
        }
        val a = probe(wire, "a", first)
        val b = probe(wire, "b", first.drop(1) + first.first())
        val control = probe(wire, "control", null)
        return a && b && control
    }

    private suspend fun catalog(wire: BuiltInAccountWire) {
        val data = exchange(wire, "catalog", WireRequest("GET", BuiltInModel.CATALOG_URL, emptyMap(), null))
        val model =
            data
                ?.get("data")
                ?.jsonArray
                ?.firstOrNull {
                    it.jsonObject["id"]?.jsonPrimitive?.content == BuiltInModel.MODEL_ID
                }?.jsonObject
        rows +=
            buildJsonObject {
                put("stage", "catalog-model")
                put("lunaPresent", model != null)
                for (key in listOf("input_modalities", "output_modalities", "supported_endpoints")) {
                    model?.get(key)?.let { put(key, it) }
                }
            }
    }

    private suspend fun probe(
        wire: BuiltInAccountWire,
        label: String,
        expected: List<String>?,
        responses: Boolean = false,
        format: String = "png",
    ): Boolean {
        val data =
            exchange(
                wire,
                label,
                WireRequest(
                    "POST",
                    if (responses) BuiltInModel.RESPONSES_URL else BuiltInModel.GENERATION_URL,
                    mapOf("Content-Type" to "application/json"),
                    requestBody(expected, responses, format).toString().toByteArray(),
                ),
            )
        val text = responseText(data, responses)?.trim()
        val actual =
            text?.let {
                runCatching {
                    Json.parseToJsonElement(it).jsonArray.map { c ->
                        c.jsonPrimitive.content
                    }
                }.getOrNull()
            }
        val passed = if (expected == null) text == "NO_IMAGE" else actual == expected
        rows +=
            buildJsonObject {
                put("stage", "$label-pixels")
                put("passed", passed)
                put("imageAttached", expected != null)
                expected?.let { put("expected", JsonArray(it.map(::JsonPrimitive))) }
                actual?.takeIf { it.size == 6 && it.all(colors::containsKey) }?.let {
                    put("recognized", JsonArray(it.map(::JsonPrimitive)))
                }
                put("noImageResponse", text == "NO_IMAGE")
            }
        return passed
    }

    private fun responseText(
        data: JsonObject?,
        responses: Boolean,
    ): String? =
        if (responses) {
            data
                ?.get("output")
                ?.jsonArray
                .orEmpty()
                .flatMap {
                    it.jsonObject["content"]?.jsonArray.orEmpty()
                }.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                .joinToString("")
        } else {
            data
                ?.get("choices")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.content
        }

    private fun requestBody(
        expected: List<String>?,
        responses: Boolean,
        format: String,
    ): JsonObject {
        val content =
            mutableListOf<JsonObject>(
                buildJsonObject {
                    put("type", if (responses) "input_text" else "text")
                    put("text", prompt)
                },
            )
        if (expected != null) {
            val image = "data:image/$format;base64," + encodeGrid(expected, format)
            content +=
                buildJsonObject {
                    put("type", if (responses) "input_image" else "image_url")
                    put("image_url", if (responses) JsonPrimitive(image) else buildJsonObject { put("url", image) })
                }
        }
        return buildJsonObject {
            put("model", BuiltInModel.MODEL_ID)
            put(if (responses) "max_output_tokens" else "max_completion_tokens", 256)
            if (responses) {
                put("reasoning", buildJsonObject { put("effort", "none") })
            } else {
                put("reasoning_effort", "none")
            }
            put("stream", false)
            put(
                if (responses) "input" else "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("content", JsonArray(content))
                        },
                    ),
                ),
            )
        }
    }

    private suspend fun exchange(
        wire: BuiltInAccountWire,
        stage: String,
        request: WireRequest,
    ): JsonObject? {
        val started = android.os.SystemClock.elapsedRealtime()
        val response = wire.newInvocation().open(request)
        try {
            val body = response.body.bytes().toString(Charsets.UTF_8)
            val failure = httpFailureDetails(response.status, response.headers, body)
            rows +=
                buildJsonObject {
                    put("stage", stage)
                    put("status", response.status)
                    put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                    put("requestId", failure.requestId)
                    put("errorCode", failure.errorCode)
                    put("unsupportedMedia", listOf("image", "media").any { failure.detail?.contains(it, true) == true })
                }
            return if (response.status in 200..299) Json.parseToJsonElement(body).jsonObject else null
        } finally {
            response.body.close()
        }
    }

    private fun encodeGrid(
        order: List<String>,
        format: String,
    ): String {
        val bitmap = Bitmap.createBitmap(360, 240, Bitmap.Config.ARGB_8888)
        try {
            val pixels =
                IntArray(360 * 240) { index ->
                    colors.getValue(order[(index / 360 / 120) * 3 + index % 360 / 120]).toInt()
                }
            bitmap.setPixels(pixels, 0, 360, 0, 0, 360, 240)
            val bytes = ByteArrayOutputStream()
            val encoding =
                when (format) {
                    "png" -> Bitmap.CompressFormat.PNG
                    "jpeg" -> Bitmap.CompressFormat.JPEG
                    "webp" -> Bitmap.CompressFormat.WEBP_LOSSLESS
                    else -> error("Unknown fixture image format")
                }
            check(bitmap.compress(encoding, 100, bytes))
            return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }
}
