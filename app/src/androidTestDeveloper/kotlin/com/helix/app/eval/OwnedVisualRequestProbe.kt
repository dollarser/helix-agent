package com.helix.app.eval

import android.graphics.BitmapFactory
import android.util.Base64
import com.helix.app.AppContainer
import com.helix.app.account.BuiltInAccountWire
import com.helix.app.provider.BuiltInModel
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Test-only observation of unchanged encoded requests delegated to the existing account transport.
 * Records identifiers/counts/status only, never headers, prompts, image bytes, URLs or credentials.
 * A 2xx response proves transport acceptance, not server-side perception or semantic understanding.
 */
internal class OwnedVisualRequestProbe(
    private val c: AppContainer,
    private val session: String,
    private val nonce: String,
) {
    private val factory =
        c.providerService.javaClass
            .getDeclaredField("factory")
            .apply {
                isAccessible = true
            }.get(c.providerService)
    private val field = factory.javaClass.getDeclaredField("builtInWire").apply { isAccessible = true }
    private val original = field.get(factory) as BuiltInAccountWire
    private val rows = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
    private val closed =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    private val jobs = java.util.concurrent.CopyOnWriteArrayList<kotlinx.coroutines.Job>()
    private val wire =
        object : WireClient {
            override suspend fun open(request: WireRequest): WireResponse {
                val delegate = original.newInvocation()
                val body = ownedVisualRequestBody(request, nonce, closed.get()) ?: return delegate.open(request)
                val messages = body.getValue("messages") as JsonArray
                val turn =
                    c.storage.turns
                        .listBySession(session)
                        .last()
                val modelCall =
                    c.storage.modelCalls
                        .listByTurn(turn.id)
                        .last()
                val live = requireNotNull(c.turnEngine?.liveExecution?.byTurn(turn.id))
                jobs.addIfAbsent(live.job)
                check(modelCall.settledAt == null) { "Model call was already settled" }
                val images = ownedVisualImageCount(messages)
                var status: Int? = null
                try {
                    // Pass the exact same WireRequest/body to the real delegate; no scripted responses.
                    return delegate.open(request).also { status = it.status }
                } finally {
                    rows +=
                        buildJsonObject {
                            put("turnId", turn.id)
                            put("modelCallId", modelCall.id)
                            put("ownedPromptPresent", true)
                            put("encodedDecodableImages", images)
                            put("responseStatus", status)
                            put("originalRequestDelegated", true)
                        }
                }
            }
        }

    init {
        check(
            c.storage.turns
                .listActive()
                .isEmpty(),
        )
        field.set(factory, wire)
    }

    fun driverJobs(): List<kotlinx.coroutines.Job> = jobs.toList()

    fun hasAcceptedImageRequest(): Boolean = acceptedVisualRequest(rows)

    fun evidence(): JsonArray = JsonArray(rows.toList())

    fun close() {
        closed.set(true)
    }

    fun restore() {
        check(field.get(factory) === wire) { "Visual transport changed concurrently; do not overwrite it" }
        field.set(factory, original)
    }
}

/** Null means passthrough: never gate, record, or rewrite another session's request. */
internal fun ownedVisualRequestBody(
    request: WireRequest,
    nonce: String,
    closed: Boolean,
): JsonObject? {
    if (request.method != "POST" || request.url != BuiltInModel.GENERATION_URL) return null
    val body =
        runCatching {
            Json
                .parseToJsonElement(requireNotNull(request.body).toString(Charsets.UTF_8))
                .jsonObject
                .takeIf { isOwnedVisualRequest(it, nonce) }
        }.getOrNull()
    if (body != null) check(!closed) { "Owned visual request gate closed" }
    return body
}

internal fun isOwnedVisualRequest(
    body: JsonObject,
    nonce: String,
): Boolean =
    body["model"]?.jsonPrimitive?.content == BuiltInModel.MODEL_ID &&
        (body["messages"] as? JsonArray).orEmpty().any {
            it.jsonObject["role"]?.jsonPrimitive?.content == "user" && nonce in it.jsonObject["content"].toString()
        }

internal fun acceptedVisualRequest(rows: List<JsonObject>): Boolean =
    rows.any {
        it.getValue("ownedPromptPresent").jsonPrimitive.content == "true" &&
            it.getValue("originalRequestDelegated").jsonPrimitive.content == "true" &&
            it
                .getValue("encodedDecodableImages")
                .jsonPrimitive.content
                .toInt() > 0 &&
            (it["responseStatus"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1) in 200..299
    }

/** Join the captured original Job, not just its cancellation signal or durable terminal state. */
internal suspend fun stopAndJoinOwnedVisualCase(
    c: AppContainer,
    session: String,
    knownJobs: List<kotlinx.coroutines.Job> = emptyList(),
) {
    val engine = requireNotNull(c.turnEngine)
    val turns = c.storage.turns.listBySession(session)
    val jobs = (knownJobs + turns.mapNotNull { engine.liveExecution.byTurn(it.id)?.job }).distinct()
    turns.asReversed().forEach { c.chatService.stopTurn(it.id) }
    kotlinx.coroutines.withTimeout(30_000) { jobs.forEach { it.join() } }
    check(!engine.liveExecution.hasActive(session)) { "Owned driver remains live" }
}

internal fun ownedVisualImageCount(messages: JsonArray): Int =
    messages.sumOf { message ->
        val parts = message.jsonObject["content"] as? JsonArray
        parts.orEmpty().count { part ->
            val item = part.jsonObject
            if (item["type"]?.jsonPrimitive?.content != "image_url") {
                false
            } else {
                val url =
                    item
                        .getValue("image_url")
                        .jsonObject
                        .getValue("url")
                        .jsonPrimitive.content
                val comma = url.indexOf(',')
                check(comma > 0 && url.substring(0, comma) in setOf("data:image/png;base64", "data:image/jpeg;base64"))
                val bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                check(options.outWidth > 0 && options.outHeight > 0) { "Encoded image has no valid dimensions" }
                true
            }
        }
    }
