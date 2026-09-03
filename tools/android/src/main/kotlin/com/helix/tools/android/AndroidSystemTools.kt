@file:Suppress("TooManyFunctions") // the 4 android.*/clipboard.* tools share the private schema/arg helpers

package com.helix.tools.android

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

// Model-facing input bounds (defensive re-reads; the input schema already enforces these).
private const val MAX_URL: Int = 2048
private const val MAX_SHARE_TEXT: Int = 10_000
private const val MAX_SHARE_SUBJECT: Int = 512
private const val MAX_CLIPBOARD_WRITE: Int = 4_000

// Model-visible error detail is bounded well below any port message length.
private const val MAX_DETAIL: Int = 512

// Stable outcome status strings; they appear in BOTH the output schema enum and the emitted output,
// so a one-sided drift is impossible.
private const val ST_OPENED: String = "opened"
private const val ST_REFUSED: String = "refused"
private const val ST_NO_HANDLER: String = "no-handler"
private const val ST_READ: String = "read"
private const val ST_WRITTEN: String = "written"
private const val ST_SHARED: String = "shared"

// ---------------------------------------------------------------------------
// Small schema + argument helpers (file-private), mirroring the browser tools' conventions.
// ---------------------------------------------------------------------------
private fun stringSchema(
    maxLength: Int,
    description: String,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("maxLength", JsonPrimitive(maxLength))
        put("description", JsonPrimitive(description))
    }

private fun enumSchema(
    values: List<String>,
    description: String,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        put("description", JsonPrimitive(description))
    }

private fun booleanSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("description", JsonPrimitive(description))
    }

private fun integerSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("description", JsonPrimitive(description))
    }

private fun objectSchema(
    properties: JsonObject,
    required: List<String>,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", properties)
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", JsonPrimitive(false))
    }

/**
 * Reads a string argument that is present, a string, non-blank and within [maxLength]. Arguments are
 * already input-schema-validated before the executor runs; this is a defensive re-read (a wrong shape
 * or a blank value is a stable `invalid arguments` failure, never a crash).
 */
private fun strArg(
    args: JsonObject,
    key: String,
    maxLength: Int,
): String? =
    (args[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() && it.length <= maxLength }

/** Bounds a port error message for the model-visible `detail` (never the audit, which keeps it raw). */
private fun bounded(detail: String): String {
    val t = detail.trim()
    return if (t.length <= MAX_DETAIL) t else t.take(MAX_DETAIL) + "…"
}

// ===========================================================================
// android.open_uri — open an http/https URL in the system handler (只打开)
// ===========================================================================
object AndroidOpenUriTool {
    const val NAME: String = "android.open_uri"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Open an http/https URL in the device's system handler (the OS picks the app). This " +
                    "tool only opens the link: it never navigates inside the handler or follows it into " +
                    "another app. Non-http(s) URLs are refused.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("url", stringSchema(MAX_URL, "The http/https URL to open."))
                        },
                    required = listOf("url"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(
                                    listOf(ST_OPENED, ST_REFUSED, ST_NO_HANDLER),
                                    "opened, refused (non-http/https scheme) or no-handler (no app can open it).",
                                ),
                            )
                            put("url", stringSchema(MAX_URL, "The URL that was opened (or requested)."))
                            put("reason", stringSchema(128, "Refusal category or stable note; empty on success."))
                        },
                    required = listOf("status", "url", "reason"),
                ),
            operationClass = ToolOperationClass.EXTERNAL_ACTION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: AndroidSystemBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val url =
                    strArg(call.args, "url", MAX_URL)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'android.open_uri' arguments: 'url' must be a non-empty string",
                        )
                val out = bridge.openUri(url)
                val status =
                    when (out.status) {
                        OpenUriStatus.OPENED -> ST_OPENED
                        OpenUriStatus.REFUSED -> ST_REFUSED
                        OpenUriStatus.NO_HANDLER -> ST_NO_HANDLER
                        OpenUriStatus.ERROR -> return ToolExecutorResult.Failed(bounded(out.reason))
                    }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("status", JsonPrimitive(status))
                        put("url", JsonPrimitive(out.url))
                        put("reason", JsonPrimitive(out.reason))
                    },
                )
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: AndroidSystemBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// clipboard.read — read the system clipboard (gated by visible-foreground)
// ===========================================================================
object ClipboardReadTool {
    const val NAME: String = "clipboard.read"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Read the current text of the device's system clipboard. Refused unless Helix is the " +
                    "visible-foreground app. An empty clipboard is a successful read of empty text.",
            inputSchema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("additionalProperties", JsonPrimitive(false))
                },
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(
                                    listOf(ST_READ, ST_REFUSED),
                                    "read, or refused (not the visible-foreground app).",
                                ),
                            )
                            put(
                                "text",
                                stringSchema(
                                    MAX_CLIPBOARD_READ,
                                    "The clipboard text, bounded; empty for an empty clipboard or a refusal.",
                                ),
                            )
                            put("length", integerSchema("The original clipboard char count (before bounding)."))
                            put("truncated", booleanSchema("True when text was cut to the bound."))
                            put(
                                "reason",
                                stringSchema(128, "not-foreground on a refusal or an error note; empty on a read."),
                            )
                        },
                    required = listOf("status", "text", "length", "truncated", "reason"),
                ),
            operationClass = ToolOperationClass.EXTERNAL_ACTION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 16_384,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: AndroidSystemBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val out = bridge.clipboardRead()
                val status =
                    when (out.status) {
                        ClipboardReadStatus.READ -> ST_READ
                        ClipboardReadStatus.REFUSED -> ST_REFUSED
                        ClipboardReadStatus.ERROR -> return ToolExecutorResult.Failed(bounded(out.reason))
                    }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("status", JsonPrimitive(status))
                        put("text", JsonPrimitive(out.text))
                        put("length", JsonPrimitive(out.length))
                        put("truncated", JsonPrimitive(out.truncated))
                        put("reason", JsonPrimitive(out.reason))
                    },
                )
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: AndroidSystemBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// clipboard.write — write text to the system clipboard (gated by visible-foreground)
// ===========================================================================
object ClipboardWriteTool {
    const val NAME: String = "clipboard.write"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Write [text] to the device's system clipboard. Refused unless Helix is the " +
                    "visible-foreground app.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("text", stringSchema(MAX_CLIPBOARD_WRITE, "The text to place on the clipboard."))
                        },
                    required = listOf("text"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(
                                    listOf(ST_WRITTEN, ST_REFUSED),
                                    "written, or refused (not the visible-foreground app).",
                                ),
                            )
                            put("length", integerSchema("The char count written (0 when refused)."))
                            put(
                                "reason",
                                stringSchema(128, "not-foreground on a refusal or an error note; empty on a write."),
                            )
                        },
                    required = listOf("status", "length", "reason"),
                ),
            operationClass = ToolOperationClass.EXTERNAL_ACTION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: AndroidSystemBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val text =
                    strArg(call.args, "text", MAX_CLIPBOARD_WRITE)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'clipboard.write' arguments: 'text' must be a non-empty string",
                        )
                val out = bridge.clipboardWrite(text)
                val status =
                    when (out.status) {
                        ClipboardWriteStatus.WRITTEN -> ST_WRITTEN
                        ClipboardWriteStatus.REFUSED -> ST_REFUSED
                        ClipboardWriteStatus.ERROR -> return ToolExecutorResult.Failed(bounded(out.reason))
                    }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("status", JsonPrimitive(status))
                        put("length", JsonPrimitive(out.length))
                        put("reason", JsonPrimitive(out.reason))
                    },
                )
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: AndroidSystemBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// android.share — share text via the system chooser (分享输入先预览)
// ===========================================================================
object AndroidShareTool {
    const val NAME: String = "android.share"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Share [text] (optional [subject]) through the device's system share chooser. The text " +
                    "is previewed in the approval card before the user approves. Helix never picks a " +
                    "target app itself — the user always chooses.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("text", stringSchema(MAX_SHARE_TEXT, "The text to share."))
                            put("subject", stringSchema(MAX_SHARE_SUBJECT, "Optional share subject."))
                        },
                    required = listOf("text"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(
                                    listOf(ST_SHARED, ST_NO_HANDLER),
                                    "shared, or no-handler (no app to share to).",
                                ),
                            )
                            put("reason", stringSchema(128, "Stable note or error; empty on a launched share."))
                        },
                    required = listOf("status", "reason"),
                ),
            operationClass = ToolOperationClass.EXTERNAL_ACTION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: AndroidSystemBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val text =
                    strArg(call.args, "text", MAX_SHARE_TEXT)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'android.share' arguments: 'text' must be a non-empty string",
                        )
                val subject = (call.args["subject"] as? JsonPrimitive)?.content.orEmpty()
                val out = bridge.share(text, subject)
                val status =
                    when (out.status) {
                        ShareStatus.SHARED -> ST_SHARED
                        ShareStatus.NO_HANDLER -> ST_NO_HANDLER
                        ShareStatus.ERROR -> return ToolExecutorResult.Failed(bounded(out.reason))
                    }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("status", JsonPrimitive(status))
                        put("reason", JsonPrimitive(out.reason))
                    },
                )
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: AndroidSystemBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// Registration
// ===========================================================================
object AndroidSystemTools {
    /**
     * Registers the four `android.*` / `clipboard.*` contracts and implementations against the shared
     * [bridge]. Called once from the app container (which owns the production
     * [AndroidSystemBridgeImpl]); tests build a [ToolRegistry] / [ToolImplementationRegistry] pair and
     * a fake bridge.
     */
    fun registerAll(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: AndroidSystemBridge,
    ) {
        AndroidOpenUriTool.register(registry, implementations, bridge)
        ClipboardReadTool.register(registry, implementations, bridge)
        ClipboardWriteTool.register(registry, implementations, bridge)
        AndroidShareTool.register(registry, implementations, bridge)
    }
}
