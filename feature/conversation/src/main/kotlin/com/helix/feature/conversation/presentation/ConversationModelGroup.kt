package com.helix.feature.conversation.presentation

/** Each source keeps its own identity, including when names or model IDs overlap. */
internal data class ConversationModelGroup(
    val source: ConversationModelSourceUi,
    val models: List<String>,
)

internal fun conversationModelGroups(
    sources: List<ConversationModelSourceUi>,
    query: String,
): List<ConversationModelGroup> =
    sources.mapNotNull { source ->
        val models =
            source.conversationModels.filter { model ->
                listOf(
                    model,
                    source.modelLabel(model),
                    source.displayName,
                ).any { it.contains(query, ignoreCase = true) }
            }
        models.takeIf { it.isNotEmpty() }?.let { ConversationModelGroup(source, it) }
    }
