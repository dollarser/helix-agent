package com.helix.app.proot

import com.helix.core.workspace.FileScopePath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The existing input name, shared by archive creation and its informational projection. */
internal fun snapshotInputName(
    reference: String,
    index: Int,
): String = FileScopePath.fromModelReference(reference).name + if (index == 0) "" else "-${index + 1}"

/** Reports the current snapshot contract; no new backend, materialization or source writeback. */
internal fun linuxExecutionInfo(
    call: LinuxRunTool.ParsedLinuxCall,
    jobId: String,
): JsonObject {
    var bytes = 0
    val mappings =
        call.inputReferences.mapIndexedNotNull { index, reference ->
            val entry =
                buildJsonObject {
                    put("source", reference)
                    put("guestPath", "/workspace/${snapshotInputName(reference, index)}")
                }
            bytes += entry.toString().toByteArray(Charsets.UTF_8).size
            entry.takeIf { bytes <= 1536 }
        }
    return buildJsonObject {
        put("environment", "local-proot")
        put("mode", "snapshot")
        put("jobId", jobId)
        put(
            "cwd",
            "/workspace" +
                call.cwd
                    .takeIf { it.isNotEmpty() }
                    ?.let { "/$it" }
                    .orEmpty(),
        )
        put("automaticSourceWriteback", false)
        put("inputCount", call.inputReferences.size)
        put("inputMapping", JsonArray(mappings))
        put("inputMappingTruncated", mappings.size < call.inputReferences.size)
        // This is the approved import destination, not proof the import has succeeded.
        call.outputReference?.let { put("outputImportTarget", it) }
        put("outputArtifacts", "/workspace/output/")
    }
}
