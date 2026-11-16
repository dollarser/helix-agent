package com.helix.app.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Common result fields across current, authorized-target and frozen-reference read paths. */
internal object SessionToolOutputSchema {
    private const val STRING = """{"type":"string"}"""
    private const val BOOLEAN = """{"type":"boolean"}"""
    private const val INTEGER = """{"type":"integer","minimum":0}"""

    fun forTool(name: String): JsonObject {
        val (properties, required) =
            when (name) {
                "session_status" -> {
                    """"sessionId":$STRING,"title":$STRING,"archived":$BOOLEAN,
                    "executionStatus":{"type":"string","enum":["NONE","ACTIVE","BLOCKED"]},
                    "activeTurnId":{"description":"Current active Turn ID string, or null when unavailable."},
                    "turnState":{"description":"Current Turn state string, or null when no Turn exists."}""" to
                        "\"sessionId\",\"title\",\"archived\""
                }

                "session_history", "session_search", "session_list" -> {
                    """"entries":{"type":"array","items":{"type":"object"}},"hasMore":$BOOLEAN,
                    "cursor":{"type":"string","description":"Next authorized-target/list cursor, when hasMore."},
                    "nextBeforeSequence":{"type":"integer","minimum":0,
                    "description":"Next current/shared-history beforeSequence, when hasMore."},
                    "scannedMessages":$INTEGER,"unavailableContents":$INTEGER""" to "\"entries\",\"hasMore\""
                }

                "session_read" -> {
                    """"messageId":$STRING,"contentHash":$STRING,"content":$STRING,
                    "offset":$INTEGER,"totalCharacters":$INTEGER,"truncated":$BOOLEAN,
                    "nextOffset":{"type":"integer","minimum":0,
                    "description":"Continue at this Unicode code-point offset when truncated."},"citation":$STRING""" to
                        "\"messageId\",\"contentHash\",\"content\",\"offset\",\"totalCharacters\",\"truncated\""
                }

                "session_rename", "session_archive", "session_restore" -> {
                    """"sessionId":$STRING,"applied":$BOOLEAN,"title":$STRING""" to "\"sessionId\",\"applied\""
                }

                else -> {
                    error("Unknown session tool")
                }
            }
        // Paths add provenance and access metadata; common result fields do not forbid those extensions.
        return Json
            .parseToJsonElement(
                """{"type":"object","properties":{$properties},"required":[$required]}""",
            ).jsonObject
    }
}
