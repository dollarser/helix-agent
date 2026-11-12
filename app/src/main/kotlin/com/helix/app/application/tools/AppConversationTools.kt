package com.helix.app.application.tools

import com.helix.app.tool.ToolPipeline
import com.helix.core.model.input.InputSkillActivation
import com.helix.feature.conversation.application.ConversationTools

/** Reads actual registered/disabled bindings and revalidates skill snapshots at admission. */
internal class AppConversationTools(
    private val toolPipeline: ToolPipeline,
    private val gateway: ApplicationToolGateway,
) : ConversationTools {
    override fun referenceToolsAvailable(
        sessionId: String,
        conversation: Boolean,
        knowledge: Boolean,
    ): Boolean {
        val names =
            buildList {
                if (conversation) addAll(listOf("session.history", "session.search", "session.read"))
                if (knowledge) addAll(listOf("knowledge.search", "knowledge.read"))
            }
        val bindings = toolPipeline.registry.snapshot()
        return names.all { name ->
            bindings.any { binding ->
                binding.descriptor.name.value == name &&
                    toolPipeline.disabledToolFilter?.invoke(sessionId, binding.descriptor) != false
            }
        }
    }

    override fun validSkillActivations(
        sessionId: String,
        activations: List<InputSkillActivation>,
    ): Boolean =
        activations.all { activation ->
            val repository = toolPipeline.skills ?: return@all false
            try {
                repository.prepareUserActivation(
                    com.helix.extensions.skills.SkillKey(
                        com.helix.extensions.skills.SkillSource
                            .valueOf(activation.source),
                        activation.name,
                        activation.snapshotHash,
                        activation.ownerId,
                    ),
                    sessionId,
                    activation.arguments,
                )
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }

    override fun endTurn(turnId: String) = toolPipeline.endTurn(turnId)

    override fun finishTurn(turnId: String) = gateway.finishTurn(turnId)

    override fun reviewNativeApproval(
        sessionId: String,
        turnId: String,
        approvalId: String,
        approve: Boolean,
    ) = gateway.reviewNativeApproval(sessionId, turnId, approvalId, approve)

    override fun approveApproval(approvalId: String) = gateway.approveApproval(approvalId)

    override fun denyApproval(approvalId: String) = gateway.denyApproval(approvalId)
}
