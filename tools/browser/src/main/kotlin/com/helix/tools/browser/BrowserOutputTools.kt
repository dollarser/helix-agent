package com.helix.tools.browser

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

object BrowserScreenshotTool {
    const val NAME: String = "browser.screenshot"
    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Capture the current page of a browser tab as a PNG and save it to the Workspace. " +
                    "Returns the model-safe Workspace reference plus the file size and SHA-256 (never a " +
                    "raw filesystem path).",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The tab to capture."))
                        },
                    required = listOf("tabId"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("status", enumSchema(listOf(ST_SAVED), "saved."))
                            put(
                                "reference",
                                stringSchema(MAX_URL, "Model-safe Workspace reference (scope:<id>:<path>)."),
                            )
                            put("sizeBytes", integerSchema("The PNG size in bytes.", 0, null))
                            put("sha256", stringSchema(64, "The lowercase hex SHA-256 of the saved PNG."))
                            put("reason", stringSchema(MAX_DETAIL_CHARS, "An explanatory note; empty on success."))
                        },
                    required = listOf("status", "reference", "sizeBytes", "sha256", "reason"),
                ),
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L1,
            timeout = 45.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val tabId =
                    strArg(call.args, "tabId", MAX_TAB_ID)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.screenshot' arguments: 'tabId' must be a string",
                        )
                val out = bridge.screenshot(tabId)
                return when (out.status) {
                    ScreenshotStatus.SAVED -> {
                        ToolExecutorResult.Completed(
                            output =
                                buildJsonObject {
                                    put("status", JsonPrimitive(ST_SAVED))
                                    put("reference", JsonPrimitive(out.reference))
                                    put("sizeBytes", JsonPrimitive(out.sizeBytes))
                                    put("sha256", JsonPrimitive(out.sha256))
                                    put("reason", JsonPrimitive(bounded(out.reason)))
                                },
                            auditDetail =
                                buildJsonObject {
                                    put("status", JsonPrimitive(ST_SAVED))
                                    put("reference", JsonPrimitive(out.reference))
                                    put("sizeBytes", JsonPrimitive(out.sizeBytes))
                                    put("sha256", JsonPrimitive(out.sha256))
                                },
                        )
                    }

                    ScreenshotStatus.NO_PAGE,
                    ScreenshotStatus.NO_TAB,
                    -> {
                        ToolExecutorResult.Failed(
                            bounded(out.reason).ifEmpty { "no captured page in that tab" },
                            sideEffectFree = true,
                        )
                    }

                    ScreenshotStatus.TIMED_OUT -> {
                        ToolExecutorResult.TimedOut
                    }

                    ScreenshotStatus.ERROR -> {
                        ToolExecutorResult.Failed(bounded(out.reason))
                    }
                }
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// browser.download
// ===========================================================================
object BrowserDownloadTool {
    const val NAME: String = "browser.download"
    const val VERSION: Int = 1

    @Suppress("LongMethod") // 8-field output schema: the download surface is wider than the sibling browser tools
    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Download an http/https resource into the Workspace and return its model-safe " +
                    "reference, size and SHA-256. Redirects are followed only to absolute http(s) " +
                    "URLs; executable / installable types (APK/DEX/JAR/SO) and files over the size " +
                    "limit are refused. Nothing is ever opened or executed.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "url",
                                stringSchema(MAX_URL, "The absolute http/https URL to download."),
                            )
                            put(
                                "suggestedName",
                                stringSchema(
                                    MAX_DL_SUGGESTED_NAME,
                                    "Optional file-name hint; a server name or the URL path take precedence.",
                                ),
                            )
                        },
                    required = listOf("url"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(listOf(ST_DL_SAVED, ST_DL_REFUSED), "saved, or refused (policy)."),
                            )
                            put("fileName", stringSchema(MAX_DL_FILE_NAME, "The saved file name; empty when refused."))
                            put(
                                "finalUrl",
                                stringSchema(
                                    MAX_URL,
                                    "The URL actually fetched after redirects; empty when refused before download.",
                                ),
                            )
                            put(
                                "reference",
                                stringSchema(MAX_URL, "Model-safe Workspace reference (scope:<id>:<path>)."),
                            )
                            put("sizeBytes", integerSchema("The saved size in bytes.", 0, null))
                            put("sha256", stringSchema(64, "The lowercase hex SHA-256 of the saved file."))
                            put(
                                "contentType",
                                stringSchema(MAX_DL_MIME, "The server-declared MIME type; empty when absent."),
                            )
                            put(
                                "reason",
                                stringSchema(MAX_DETAIL_CHARS, "Refusal category or error note; empty on success."),
                            )
                        },
                    required =
                        listOf(
                            "status",
                            "fileName",
                            "finalUrl",
                            "reference",
                            "sizeBytes",
                            "sha256",
                            "contentType",
                            "reason",
                        ),
                ),
            operationClass = ToolOperationClass.NETWORK,
            baseRisk = RiskLevel.L2,
            timeout = 120.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount", "LongMethod") // 4 outcome branches over the 8-field download output
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val url =
                    strArg(call.args, "url", MAX_URL)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.download' arguments: 'url' must be a string",
                        )
                val suggestedName = strArg(call.args, "suggestedName", MAX_DL_SUGGESTED_NAME) ?: ""
                val out = bridge.download(url, suggestedName)
                return when (out.status) {
                    DownloadToolStatus.SAVED -> {
                        ToolExecutorResult.Completed(
                            output =
                                buildJsonObject {
                                    put("status", JsonPrimitive(ST_DL_SAVED))
                                    put("fileName", JsonPrimitive(out.fileName))
                                    put("finalUrl", JsonPrimitive(out.finalUrl))
                                    put("reference", JsonPrimitive(out.reference))
                                    put("sizeBytes", JsonPrimitive(out.sizeBytes))
                                    put("sha256", JsonPrimitive(out.sha256))
                                    put("contentType", JsonPrimitive(out.contentType))
                                    put("reason", JsonPrimitive(bounded(out.reason)))
                                },
                            auditDetail =
                                buildJsonObject {
                                    put("status", JsonPrimitive(ST_DL_SAVED))
                                    put("reference", JsonPrimitive(out.reference))
                                    put("sizeBytes", JsonPrimitive(out.sizeBytes))
                                    put("sha256", JsonPrimitive(out.sha256))
                                    put("finalUrl", JsonPrimitive(out.finalUrl))
                                },
                        )
                    }

                    DownloadToolStatus.REFUSED -> {
                        ToolExecutorResult.Completed(
                            output =
                                buildJsonObject {
                                    put("status", JsonPrimitive(ST_DL_REFUSED))
                                    put("fileName", JsonPrimitive(""))
                                    put("finalUrl", JsonPrimitive(out.finalUrl))
                                    put("reference", JsonPrimitive(""))
                                    put("sizeBytes", JsonPrimitive(0))
                                    put("sha256", JsonPrimitive(""))
                                    put("contentType", JsonPrimitive(""))
                                    put("reason", JsonPrimitive(bounded(out.reason)))
                                },
                            auditDetail =
                                buildJsonObject {
                                    put("status", JsonPrimitive(ST_DL_REFUSED))
                                    put("reason", JsonPrimitive(out.reason))
                                    put("url", JsonPrimitive(url))
                                },
                        )
                    }

                    DownloadToolStatus.TIMED_OUT -> {
                        ToolExecutorResult.TimedOut
                    }

                    DownloadToolStatus.ERROR -> {
                        ToolExecutorResult.Failed(bounded(out.reason).ifEmpty { "download failed" })
                    }
                }
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}
