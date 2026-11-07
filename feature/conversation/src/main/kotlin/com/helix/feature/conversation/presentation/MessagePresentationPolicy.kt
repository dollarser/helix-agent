package com.helix.feature.conversation.presentation

import com.helix.core.model.TurnState

enum class PresentationMessageKind { TEXT, TOOL_PROGRESS, TOOL_CALLS, USER_QUESTION, OTHER }

data class MessagePresentationFact(
    val id: String,
    val turnId: String?,
    val sequence: Long,
    val role: String,
    val kind: PresentationMessageKind,
    val superseded: Boolean,
)

/** Display-only facts from the coordinator's atomic text + tool-step commit. */
object MessagePresentationPolicy {
    fun apply(
        history: List<MessagePresentationFact>,
        visible: List<MessageUi>,
        turnStates: Map<String, String>,
    ): List<MessageUi> {
        val questionTurns =
            history
                .filter { it.kind == PresentationMessageKind.USER_QUESTION }
                .mapNotNull { it.turnId }
                .toSet()
        val progressIds =
            history
                .sortedBy { it.sequence }
                .zipWithNext()
                .mapNotNull { (text, next) ->
                    text.id.takeIf {
                        isToolPreface(text, next, questionTurns)
                    }
                }.toSet()
        val answers =
            visible
                .filter { it.role == "assistant" && it.id !in progressIds }
                .groupBy { it.turnId }
                .mapValues { (_, messages) -> messages.mapNotNull { it.sequence }.maxOrNull() }
        return visible.map { message ->
            val state = turnStates[message.turnId]?.let { name -> TurnState.entries.firstOrNull { it.name == name } }
            val presentation = presentation(message, state, message.id in progressIds, answers[message.turnId])
            message.copy(presentation = presentation)
        }
    }

    private fun isToolPreface(
        text: MessagePresentationFact,
        next: MessagePresentationFact,
        questionTurns: Set<String>,
    ): Boolean =
        text.turnId != null && text.turnId == next.turnId && text.turnId !in questionTurns &&
            text.role == "ASSISTANT" &&
            text.kind in setOf(PresentationMessageKind.TEXT, PresentationMessageKind.TOOL_PROGRESS) &&
            next.role == "ASSISTANT" && next.kind == PresentationMessageKind.TOOL_CALLS &&
            !text.superseded && !next.superseded

    private fun presentation(
        message: MessageUi,
        state: TurnState?,
        progress: Boolean,
        answerSequence: Long?,
    ): MessagePresentation =
        when {
            message.role != "assistant" || !progress || !canMute(state) -> MessagePresentation.NORMAL

            state == TurnState.COMPLETED && message.sequence != null &&
                (answerSequence ?: Long.MIN_VALUE) > message.sequence -> MessagePresentation.COMPLETED_PROGRESS

            else -> MessagePresentation.PROGRESS
        }

    private fun canMute(state: TurnState?): Boolean =
        state != null &&
            (
                state == TurnState.COMPLETED ||
                    (!state.isTerminal && state !in setOf(TurnState.CANCELLING, TurnState.NEEDS_REVIEW))
            )
}
