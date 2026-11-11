package com.helix.tools.browser

import com.helix.core.model.ToolName
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.buildJsonObject

/** Closed action vocabulary, not page-supplied executable code. */
enum class BrowserNodeAction(
    val toolName: String,
    val parameter: String?,
    val help: String,
) {
    APPEND("browser.append", "text", "Append text to an editable text input or textarea."),
    SELECT(
        "browser.select",
        "value",
        "Select one enabled single-select option by exact value or visible label; ambiguous matches are refused.",
    ),
    PRESS("browser.press", "key", "Dispatch synthetic keydown/keyup to the node. No native default action is implied."),
    SUBMIT(
        "browser.submit",
        null,
        "Request form submission using the named enabled submit button and native validation.",
    ),
}

object BrowserNodeActionTool {
    val keys: List<String> =
        listOf("Enter", "Escape", "Tab", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Space")

    fun descriptor(
        origin: ToolOrigin.PluginOrigin,
        action: BrowserNodeAction,
    ): ToolDescriptor =
        BrowserClickTool.descriptor(origin).copy(
            name = ToolName(action.toolName),
            description =
                action.help +
                    " Requires a fresh snapshot token; sensitive fields are refused. " +
                    "Performed only means DOM dispatch, not business success. Observe the result before proceeding; " +
                    "never automatically replay an uncertain action. " +
                    "Use browser.submit for form submission, not press Enter.",
            inputSchema =
                objectSchema(
                    buildJsonObject {
                        put("tabId", stringSchema(MAX_TAB_ID, "The tab the token belongs to."))
                        put("token", stringSchema(MAX_TOKEN, "The node token from the most recent snapshot."))
                        action.parameter?.let { parameter ->
                            put(
                                parameter,
                                if (action == BrowserNodeAction.PRESS) {
                                    enumSchema(keys, "Synthetic key; does not perform native default behavior.")
                                } else {
                                    stringSchema(MAX_TYPE_TEXT, "Literal text or exact option value.")
                                },
                            )
                        }
                    },
                    listOfNotNull("tabId", "token", action.parameter),
                ),
        )

    fun executor(
        bridge: BrowserToolBridge,
        action: BrowserNodeAction,
    ): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val tab =
                    strArg(call.args, "tabId", MAX_TAB_ID)
                        ?: return ToolExecutorResult.Failed("invalid tabId", sideEffectFree = true)
                val token =
                    strArg(call.args, "token", MAX_TOKEN)
                        ?: return ToolExecutorResult.Failed("invalid token", sideEffectFree = true)
                val value = action.parameter?.let { strArg(call.args, it, MAX_TYPE_TEXT) } ?: ""
                if (action.parameter != null && strArg(call.args, action.parameter, MAX_TYPE_TEXT) == null) {
                    return ToolExecutorResult.Failed("missing action value", sideEffectFree = true)
                }
                if (action == BrowserNodeAction.PRESS && value !in keys) {
                    return ToolExecutorResult.Failed("unsupported key", sideEffectFree = true)
                }
                return actionResult(
                    bridge.nodeAction(tab, token, action, value) {
                        !call.cancel.isCancelled() && !Thread.currentThread().isInterrupted &&
                            System.currentTimeMillis() < call.deadline.toEpochMilli()
                    },
                )
            }
        }
}
