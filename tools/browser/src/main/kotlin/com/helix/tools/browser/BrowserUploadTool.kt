package com.helix.tools.browser

import com.helix.core.model.ToolName
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Source version and destination are part of the approved call, not page-supplied authority. */
data class BrowserUploadRequest(
    val tabId: String,
    val token: String,
    val pageUrl: String,
    val path: String,
    val sha256: String,
)

data class BrowserUploadOutcome(
    val action: ActionOutcome,
    val sizeBytes: Int = 0,
)

object BrowserUploadTool {
    const val NAME = "browser.upload"
    const val MAX_BYTES = 1024 * 1024

    fun descriptor(origin: ToolOrigin.PluginOrigin) =
        BrowserClickTool.descriptor(origin).copy(
            name = ToolName(NAME),
            description =
                "Attach one authorized workspace file (at most 1 MiB) " +
                    "to a file input in the exact pageUrl. " +
                    "Requires a fresh snapshot token, scope:<id>:<path> and the source SHA-256 from a prior read. " +
                    "The page receives the file and may immediately transmit it on change; " +
                    "authorize that disclosure. " +
                    "Attached does not prove server upload or business success. " +
                    "Never automatically retry uncertain effects.",
            inputSchema =
                objectSchema(
                    buildJsonObject {
                        put("tabId", stringSchema(MAX_TAB_ID, "Exact tab."))
                        put("token", stringSchema(MAX_TOKEN, "Fresh file-input snapshot token."))
                        put("pageUrl", stringSchema(MAX_URL, "Exact receiving HTTP(S) page URL."))
                        put("path", stringSchema(512, "Authorized scope reference, never a raw path or URI."))
                        put("sha256", stringSchema(64, "Expected lowercase SHA-256 of the source bytes."))
                    },
                    listOf("tabId", "token", "pageUrl", "path", "sha256"),
                ),
            outputSchema =
                objectSchema(
                    buildJsonObject {
                        put("status", enumSchema(listOf("attached"), "Field attachment, not server success."))
                        put("path", stringSchema(512, "Original authorized source reference."))
                        put("sha256", stringSchema(64, "Actual source hash matching the approved version."))
                        put("pageUrl", stringSchema(MAX_URL, "Actual receiving page URL at dispatch."))
                        put("sizeBytes", integerSchema("File bytes exposed to the page.", 0, MAX_BYTES))
                    },
                    listOf("status", "path", "sha256", "pageUrl", "sizeBytes"),
                ),
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount") // Validate identities before any file or page access.
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val values =
                    listOf(
                        "tabId" to MAX_TAB_ID,
                        "token" to MAX_TOKEN,
                        "pageUrl" to MAX_URL,
                        "path" to 512,
                        "sha256" to 64,
                    ).map { (key, bound) -> strArg(call.args, key, bound) }
                if (values.any { it.isNullOrEmpty() } || !Regex("[a-f0-9]{64}").matches(values[4].orEmpty())) {
                    return ToolExecutorResult.Failed("Invalid upload identity", sideEffectFree = true)
                }
                val request = BrowserUploadRequest(values[0]!!, values[1]!!, values[2]!!, values[3]!!, values[4]!!)
                val result =
                    bridge.upload(request) {
                        !call.cancel.isCancelled() && !Thread.currentThread().isInterrupted &&
                            System.currentTimeMillis() < call.deadline.toEpochMilli()
                    }
                return if (result.action.status == ActionStatus.PERFORMED) {
                    ToolExecutorResult.Completed(
                        buildJsonObject {
                            put("status", "attached")
                            put("path", request.path)
                            put("sha256", request.sha256)
                            put("pageUrl", request.pageUrl)
                            put("sizeBytes", result.sizeBytes)
                        },
                    )
                } else {
                    ToolExecutorResult.Failed(
                        "browser.upload: " +
                            result.action.reason.ifEmpty {
                                result.action.status.name
                                    .lowercase()
                            },
                        sideEffectFree =
                            result.action.status in
                                setOf(ActionStatus.REFUSED, ActionStatus.STALE_TOKEN, ActionStatus.NO_TAB),
                        requiresReview = result.action.status == ActionStatus.UNKNOWN,
                    )
                }
            }
        }
}
