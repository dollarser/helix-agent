package com.helix.app.application.tools

import com.helix.core.model.JsonInputLimits
import com.helix.core.workspace.FileScopePath
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolJsonBudget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Pre-dispatch diagnostics; schema validation and authorization still belong to Dispatcher. */
internal object ToolArgumentPreparation {
    sealed interface Result

    data class Ready(
        val args: JsonObject,
    ) : Result

    enum class Rejected(
        val detail: String,
    ) : Result {
        INVALID_JSON_OBJECT("Tool arguments must be a valid JSON object."),
        JSON_LIMIT("Tool arguments exceed the nesting or node limits. Reduce their structure or split the request."),
        INVALID_FILE_PATH(
            "Tool path arguments are invalid. Use paths relative to the current working directory or " +
                "scope:<id>:<relativePath>; do not use absolute paths or ../ escapes. " +
                "Check scoped reference syntax and path lengths.",
        ),
    }

    @Suppress("ReturnCount") // Distinct pre-execution failures must keep their actionable diagnosis.
    fun prepare(
        raw: String,
        descriptor: ToolDescriptor?,
        directory: () -> FileScopePath,
    ): Result {
        // Some providers omit fragments for no-argument calls. Required fields are checked later.
        val normalized = raw.ifBlank { "{}" }
        try {
            JsonInputLimits.requireSafeNesting(normalized)
        } catch (_: IllegalArgumentException) {
            return Rejected.JSON_LIMIT
        }
        val parsed =
            try {
                Json.parseToJsonElement(normalized)
            } catch (_: IllegalArgumentException) {
                return Rejected.INVALID_JSON_OBJECT
            }
        if (ToolJsonBudget.violation(parsed, checkNumbers = false) != null) return Rejected.JSON_LIMIT
        if (parsed !is JsonObject) return Rejected.INVALID_JSON_OBJECT
        if (!FileToolArguments.handles(descriptor)) return Ready(parsed)

        // A broken trusted directory lookup is an infrastructure failure, not malformed model input.
        val boundDirectory = directory()
        return try {
            Ready(FileToolArguments.normalize(parsed, boundDirectory, descriptor?.name?.value))
        } catch (_: IllegalArgumentException) {
            // Never expose raw values/exception text or repair a path to make a rejected call execute.
            Rejected.INVALID_FILE_PATH
        }
    }
}
