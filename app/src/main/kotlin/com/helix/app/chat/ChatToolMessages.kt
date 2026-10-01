package com.helix.app.chat

import com.helix.app.agent.ToolLoopPrompts
import com.helix.app.tool.ToolPipeline
import com.helix.core.agent.LocalToolCallBatch
import com.helix.core.agent.SettledCall
import com.helix.core.agent.ToolLoopProgress
import com.helix.core.agent.ToolMessageMaterializer
import com.helix.core.agent.TurnMessageDraft
import com.helix.core.model.ModelRole
import com.helix.core.storage.HelixStorage

/** Host-side history representation and current result-reader visibility, never a dispatcher. */
internal class ChatToolMessages(
    private val storage: HelixStorage,
    private val pipeline: ToolPipeline,
    strings: (Int, Array<out Any>) -> String,
) : ToolMessageMaterializer {
    private val encoder = ChatToolMessageEncoder(strings)

    override fun assistantToolStepJson(batch: LocalToolCallBatch): String = encoder.assistantToolStepJson(batch)

    override fun toolResultDraft(settled: SettledCall): TurnMessageDraft {
        val reader = pipeline.registry.all().firstOrNull { it.name.value == ToolResultReadTool.NAME }
        val session = settled.resultReference?.substringBefore('/')?.let { storage.turns.resolve(it).sessionId }
        val enabled =
            reader != null && session != null && (pipeline.disabledToolFilter?.invoke(session, reader) ?: true)
        return encoder.toolResultDraft(settled, enabled)
    }

    override fun progressDraft(decision: ToolLoopProgress.Decision): TurnMessageDraft? =
        when (decision) {
            ToolLoopProgress.Decision.CONTINUE -> {
                null
            }

            ToolLoopProgress.Decision.WARN -> {
                TurnMessageDraft(
                    ModelRole.SYSTEM,
                    "loop_warning",
                    ToolLoopPrompts.WARNING,
                )
            }

            ToolLoopProgress.Decision.STOP -> {
                TurnMessageDraft(
                    ModelRole.SYSTEM,
                    "loop_exhausted",
                    ToolLoopPrompts.EXHAUSTED,
                )
            }
        }
}
