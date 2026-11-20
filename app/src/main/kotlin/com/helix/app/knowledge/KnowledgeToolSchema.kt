package com.helix.app.knowledge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Describes selection and pagination without changing the backend's authorization or revision checks. */
internal object KnowledgeToolSchema {
    private val parameterHelp =
        mapOf(
            "query" to "Text to find. Selected revisions use literal case-insensitive matching; " +
                "library catalog search may rank terms. Keep it unchanged while paging.",
            "cursor" to "Only for selected revisions. Omit initially, then copy nextCursor exactly, " +
                "even after an empty page. Keep the same query and reference pair.",
            "kind" to "Copy source or note from a search result or selected reference; PDF pages require source.",
            "id" to "Exact knowledge item ID from a search result or selected reference, not a file path or URL.",
            "fileIndex" to "Zero-based original PDF file index from originals/extraction. Supply together with page.",
            "page" to "One-based PDF page number from originals/extraction. Supply together with fileIndex.",
            "offset" to "Zero-based Unicode code-point offset. Omit for 0; continue with nextOffset " +
                "and the same contentHash. For PDF text, the offset is within that page.",
            "limit" to "Maximum Unicode code points to return, default 2048, maximum 4096. Not bytes or UTF-16 units.",
            "contentHash" to "Exact hash returned by the previous read or selected search hit. " +
                "Required after offset 0. PDF reads use the returned sourceRevision as contentHash; " +
                "never replace it with a newer revision to continue an old page.",
            "referenceInputId" to "User reference manifest ID. Supply with referenceHash to read only its " +
                "selected revisions; recover older manifests through session_history/read.",
            "referenceHash" to "Exact manifest hash paired with referenceInputId, not the document contentHash. " +
                "Omit both reference fields only when whole-library access is enabled.",
        )

    fun describeInput(schema: JsonObject): JsonObject =
        JsonObject(
            schema +
                (
                    "properties" to
                        JsonObject(
                            schema.getValue("properties").jsonObject.mapValues { (name, property) ->
                                JsonObject(
                                    property.jsonObject + (
                                        "description" to
                                            JsonPrimitive(
                                                parameterHelp.getValue(name),
                                            )
                                    ),
                                )
                            },
                        )
                ),
        )

    fun output(search: Boolean): JsonObject {
        val fields =
            if (search) {
                """"items":{"type":"array","items":{"type":"object"}},
                "related":{"type":"array","items":{"type":"object"},
                "description":"Labelled hybrid candidates, not full documents."},
                "hasMore":{"type":"boolean"},
                "nextCursor":{"type":"string","description":"Present for more selected-revision search results."}"""
            } else {
                """"text":{"type":"string"},"title":{"type":"string"},
                "contentHash":{"type":"string"},"citation":{"type":"string"},
                "offset":{"type":"integer","minimum":0},"endOffset":{"type":"integer","minimum":0},
                "truncated":{"type":"boolean"},
                "nextOffset":{"type":"integer","minimum":0,"description":"Next Unicode code-point offset."}"""
            }
        val required = if (search) "\"items\",\"related\"" else "\"text\",\"contentHash\",\"citation\""
        // Original PDF pages and library items retain their different provenance fields.
        return Json
            .parseToJsonElement(
                """{"type":"object","properties":{$fields},"required":[$required]}""",
            ).jsonObject
    }
}
