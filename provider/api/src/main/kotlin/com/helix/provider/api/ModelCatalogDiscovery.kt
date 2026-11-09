package com.helix.provider.api

import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.mapHttpStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder

/** Vendor query parameters are sent only to exact official origins. */
object ModelCatalogDiscovery {
    @Suppress("ReturnCount") // Abort immediately on any incomplete or inconsistent task directory.
    suspend fun fetch(
        wire: WireClient,
        url: String,
        headers: Map<String, String>,
        outputModalitiesOnly: Boolean = false,
    ): ModelCatalogResult {
        val base = read(wire, url, headers, outputModalitiesOnly)
        if (base !is ModelCatalogResult.Listed || !siliconFlow(url)) return base
        val purposes = base.purposes.toMutableMap()
        for ((filter, purpose) in listOf(
            "chat" to ModelPurpose.CHAT,
            "embedding" to ModelPurpose.EMBEDDING,
            "reranker" to ModelPurpose.RERANK,
        )) {
            val result = read(wire, "$url?sub_type=$filter", headers)
            if (result !is ModelCatalogResult.Listed) return result
            if (result.models.any {
                    it !in base.models
                }
            ) {
                return ModelCatalogResult.Failed(
                    ModelErrorCode.PROTOCOL,
                    "Model directory changed during task discovery; refresh",
                    false,
                )
            }
            for (id in result.models) {
                if (base.purposes[id].isNullOrEmpty()) purposes[id] = purposes[id].orEmpty() + purpose
            }
        }
        return base.copy(
            purposes = base.models.associateWith { purposes[it].orEmpty().ifEmpty { setOf(ModelPurpose.OTHER) } },
        )
    }

    private fun siliconFlow(url: String): Boolean =
        url == "https://api.siliconflow.cn/v1/models" || url == "https://api.siliconflow.com/v1/models"

    @Suppress("ReturnCount") // Fail closed immediately on incomplete pages, loops and directory bounds.
    private suspend fun read(
        wire: WireClient,
        url: String,
        headers: Map<String, String>,
        outputModalitiesOnly: Boolean = false,
    ): ModelCatalogResult {
        val ids = linkedSetOf<String>()
        val purposes = linkedMapOf<String, Set<ModelPurpose>>()
        val cursors = mutableSetOf<String>()
        var pageUrl = url
        repeat(32) {
            val page = readPage(wire, pageUrl, headers, outputModalitiesOnly)
            if (page.result !is ModelCatalogResult.Listed) return page.result
            ids += page.result.models
            page.result.purposes.forEach { (id, values) -> purposes[id] = purposes[id].orEmpty() + values }
            if (ids.size > ModelCatalogResult.Listed.MAX_MODELS) return incomplete("Model directory exceeds item limit")
            val cursor = page.cursor ?: return ModelCatalogResult.Listed(ids.toList(), purposes)
            if (!cursors.add(cursor)) return incomplete("Model directory repeated a pagination cursor")
            // Only a cursor value is accepted. Never follow server-provided URLs with credentials.
            pageUrl = url + (if ('?' in url) "&" else "?") + "after_id=" +
                URLEncoder.encode(cursor, Charsets.UTF_8.name())
        }
        return incomplete("Model directory exceeds page limit")
    }

    private data class Page(
        val result: ModelCatalogResult,
        val cursor: String? = null,
    )

    private fun incomplete(detail: String) = ModelCatalogResult.Failed(ModelErrorCode.PROTOCOL, detail, false)

    @Suppress("ReturnCount") // Malformed pagination must not be mistaken for a complete first page.
    private fun page(body: String, outputModalitiesOnly: Boolean): Page {
        val root =
            runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: return Page(WireModelCatalogParser.parseModelIds(body, outputModalitiesOnly))
        val more = root["has_more"] as? JsonPrimitive
        if (more != null && more.content !in setOf("true", "false")) return Page(incomplete("Invalid pagination flag"))
        val cursor = (root["last_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (more?.content == "true" && (cursor.isNullOrBlank() || cursor.length > 256)) {
            return Page(incomplete("Model directory is missing a valid last_id"))
        }
        // Other pagination protocols remain explicit failures rather than partial success.
        val normalized = JsonObject(root.filterKeys { it != "has_more" })
        return Page(
            WireModelCatalogParser.parseModelIds(normalized.toString(), outputModalitiesOnly),
            cursor.takeIf { more?.content == "true" },
        )
    }

    private suspend fun readPage(
        wire: WireClient,
        url: String,
        headers: Map<String, String>,
        outputModalitiesOnly: Boolean,
    ): Page =
        try {
            val response = wire.open(WireRequest("GET", url, headers, null))
            try {
                if (response.status in
                    200..299
                ) {
                    page(response.body.bytes().decodeToString(), outputModalitiesOnly)
                } else {
                    val (code, retryable) = mapHttpStatus(response.status)
                    Page(ModelCatalogResult.Failed(code, "HTTP ${response.status} on /models", retryable))
                }
            } finally {
                response.body.close()
            }
        } catch (_: SocketTimeoutException) {
            Page(ModelCatalogResult.Failed(ModelErrorCode.TIMEOUT, "Model directory timed out", true))
        } catch (error: IOException) {
            // Export only a fixed enum, never exception text, URLs, headers, or credentials.
            val reason = WireFailureEvidence.reason(error).name
            Page(ModelCatalogResult.Failed(ModelErrorCode.TRANSPORT, "Model directory transport: $reason", true))
        }
}
