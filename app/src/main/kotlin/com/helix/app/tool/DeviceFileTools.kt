package com.helix.app.tool

import com.helix.core.model.CancelSignal
import com.helix.core.model.ToolName
import com.helix.core.workspace.FileScopePath
import com.helix.tools.android.AndroidShareTool
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Scoped file handoff; URL download and arbitrary Android URI access are separate operations. */
internal object DeviceFileTools {
    const val SHARE = "device.share_files"
    const val OPEN = "device.open_file"
    const val MAX_FILES = 8

    fun descriptor(open: Boolean) =
        AndroidShareTool.descriptor().copy(
            name = ToolName(if (open) OPEN else SHARE),
            description =
                if (open) {
                    "Open one authorized workspace file in a system app chooser. Use [paths] with exactly one " +
                        "scope:<scopeId>:<relativePath> reference. The user chooses the app; chooser-opened " +
                        "does not prove the file was opened. No raw paths, file://, arbitrary content URI or URL."
                } else {
                    "Share up to eight authorized workspace files, including images, through the system chooser. " +
                        "Use [paths] with scope:<scopeId>:<relativePath> references. The user chooses the target. " +
                        "chooser-opened is not delivery or saving. For direct IMA/Tencent Docs saving use its " +
                        "connector save tool. No raw paths, file://, arbitrary content URI or URL."
                },
            inputSchema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "paths",
                                buildJsonObject {
                                    put("type", JsonPrimitive("array"))
                                    put("minItems", JsonPrimitive(1))
                                    put("maxItems", JsonPrimitive(if (open) 1 else MAX_FILES))
                                    put(
                                        "items",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("string"))
                                            put("maxLength", JsonPrimitive(512))
                                        },
                                    )
                                },
                            )
                        },
                    )
                    put("required", JsonArray(listOf(JsonPrimitive("paths"))))
                    put("additionalProperties", JsonPrimitive(false))
                },
        )

    fun executor(
        open: Boolean,
        handoff: Handoff,
    ) = object : ToolExecutor {
        override fun execute(call: com.helix.tools.framework.ExecutableToolCall): ToolExecutorResult =
            if (call.cancel.isCancelled()) {
                ToolExecutorResult.Cancelled
            } else {
                val paths =
                    (call.args["paths"] as? JsonArray)?.mapNotNull {
                        (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.let { reference ->
                            runCatching { FileScopePath.fromModelReference(reference) }.getOrNull()
                        }
                    }
                val count = (call.args["paths"] as? JsonArray)?.size
                if (paths.isNullOrEmpty() || paths.size != count || paths.size > if (open) 1 else MAX_FILES) {
                    ToolExecutorResult.Failed("Expected authorized workspace file references")
                } else {
                    handoff.launch(paths, open, call.cancel)
                }
            }
    }

    fun register(
        registry: ToolRegistry,
        handoff: Handoff,
    ) {
        listOf(false, true).forEach { open -> registry.register(descriptor(open), executor(open, handoff)) }
    }

    fun interface Handoff {
        fun launch(
            paths: List<FileScopePath>,
            open: Boolean,
            cancel: CancelSignal,
        ): ToolExecutorResult
    }
}
