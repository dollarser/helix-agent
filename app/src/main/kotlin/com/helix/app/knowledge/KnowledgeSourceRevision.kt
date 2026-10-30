package com.helix.app.knowledge

import com.helix.core.storage.knowledge.CollectedSource

/** Bind derived text and original identities together; a path or unchanged preview is not a revision. */
internal fun knowledgeSourceRevision(source: CollectedSource): String {
    val text = source.extracted.ifBlank { source.rawText }
    return if (source.filesJson == "[]" && source.extraction.isBlank()) {
        knowledgeHash(text)
    } else {
        val files =
            kotlinx.serialization.json.Json.parseToJsonElement(
                source.filesJson,
            ) as kotlinx.serialization.json.JsonArray
        val identities =
            kotlinx.serialization.json.JsonArray(
                files.map {
                    kotlinx.serialization.json.JsonObject((it as kotlinx.serialization.json.JsonObject) - "reference")
                },
            )
        knowledgeHash(text + "\n" + identities + "\n" + source.extraction)
    }
}

internal fun knowledgeTextPrefix(
    text: String,
    limit: Int,
): String =
    text.take(limit).let {
        if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
    }

internal fun knowledgeOriginalMetadata(source: CollectedSource): kotlinx.serialization.json.JsonArray {
    val files =
        kotlinx.serialization.json.Json.parseToJsonElement(
            source.filesJson,
        ) as kotlinx.serialization.json.JsonArray
    return kotlinx.serialization.json.JsonArray(
        files.mapIndexed { index, value ->
            val fields = value as kotlinx.serialization.json.JsonObject
            kotlinx.serialization.json.JsonObject(
                fields - "reference" + (
                    "fileIndex" to kotlinx.serialization.json.JsonPrimitive(index)
                ) + (
                    "availability" to
                        kotlinx.serialization.json.JsonPrimitive(
                            if ((fields["reference"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.startsWith(
                                    "unavailable:backup:",
                                ) ==
                                true
                            ) {
                                "not_in_backup"
                            } else {
                                "check_on_read"
                            },
                        )
                ),
            )
        },
    )
}
