package com.helix.app

import com.helix.app.chat.NativeJavascriptOwnership
import com.helix.app.chat.UserQuestionService
import com.helix.app.chat.UserQuestionTool
import com.helix.runtime.quickjs.JsExecutionClient
import com.helix.runtime.quickjs.tool.CodeJavascriptRunTool
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ExecutionOwnership
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolRegistry

internal fun registerSessionRuntimeTools(
    registry: ToolRegistry,
    javascript: JsExecutionClient,
    questions: UserQuestionService,
    ownership: ExecutionOwnership,
    afterNative: () -> Unit = {},
) {
    val nativeOwner = NativeJavascriptOwnership(ownership)
    registry.register(
        CodeJavascriptRunTool.descriptor(),
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall) =
                CodeJavascriptRunTool
                    .executor { params, cancel ->
                        val invokeRuntime = { javascript.execute(params, cancel) }
                        if (params.nativeAccess) {
                            try {
                                nativeOwner.execute(call.toolCallId, params.executionId, invokeRuntime)
                            } finally {
                                afterNative()
                            }
                        } else {
                            javascript.execute(params, cancel)
                        }
                    }.execute(call)
        },
    )
    UserQuestionTool.register(registry, questions, ownership::metadataExecutor)
}
