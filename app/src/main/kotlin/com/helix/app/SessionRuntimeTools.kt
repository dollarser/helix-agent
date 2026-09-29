package com.helix.app

import com.helix.app.chat.UserQuestionService
import com.helix.app.chat.UserQuestionTool
import com.helix.runtime.quickjs.JsExecutionClient
import com.helix.runtime.quickjs.tool.CodeJavascriptRunTool
import com.helix.tools.framework.ToolRegistry

internal fun registerSessionRuntimeTools(
    registry: ToolRegistry,
    javascript: JsExecutionClient,
    questions: UserQuestionService,
) {
    CodeJavascriptRunTool.register(registry) { params, cancel -> javascript.execute(params, cancel) }
    UserQuestionTool.register(registry, questions)
}
