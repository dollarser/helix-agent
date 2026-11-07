package com.helix.app.infrastructure.conversation

import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.chat.UserQuestionService
import com.helix.core.storage.entity.MessageEntity
import com.helix.feature.conversation.presentation.MessagePresentationFact
import com.helix.feature.conversation.presentation.PresentationMessageKind

/** Interpret durable protocol rows once; presentation receives no Room entity or content handle. */
internal fun messagePresentationFact(row: MessageEntity): MessagePresentationFact =
    MessagePresentationFact(
        row.id,
        row.turnId,
        row.sequence,
        row.role,
        when (row.kind) {
            ChatHistoryBuilder.KIND_TEXT -> PresentationMessageKind.TEXT
            ChatHistoryBuilder.KIND_TOOL_PROGRESS -> PresentationMessageKind.TOOL_PROGRESS
            ChatHistoryBuilder.KIND_TOOL_CALLS -> PresentationMessageKind.TOOL_CALLS
            UserQuestionService.KIND -> PresentationMessageKind.USER_QUESTION
            else -> PresentationMessageKind.OTHER
        },
        row.supersededBy != null,
    )
