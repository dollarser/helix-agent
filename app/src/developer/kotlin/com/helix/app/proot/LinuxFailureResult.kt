package com.helix.app.proot

import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Delivery failures never erase the original execution's known terminal facts. */
internal fun terminalFailure(
    record: ProotJobRecord,
    detail: String,
    code: String,
    sideEffectFree: Boolean = false,
    requiresReview: Boolean = true,
): ToolExecutorResult.Failed {
    val fields = runCatching { Json.parseToJsonElement(detail) as? JsonObject }.getOrNull()
    val content =
        buildJsonObject {
            if (fields == null) put("error", detail) else fields.forEach { (key, value) -> put(key, value) }
            put("state", record.state.wire)
            put("exitCode", record.exitCode)
            put("jobId", record.jobId)
            put("executionId", record.executionId)
            put("logTruncated", record.truncated)
            put("deliveryErrorCode", code)
        }
    return LinuxRunTool.failed(content.toString(), code, sideEffectFree, requiresReview)
}
