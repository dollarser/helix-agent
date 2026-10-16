package com.helix.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.conversationEntries
import com.helix.core.model.TurnState

internal data class SearchMatch(
    val targetId: String,
    val listIndex: Int,
    val snippet: String,
)

internal class ConversationSearchController {
    var query by mutableStateOf("")
        private set

    var matches by mutableStateOf<List<SearchMatch>>(emptyList())
        private set

    var currentMatchIndex by mutableIntStateOf(0)
        private set

    val currentMatch: SearchMatch?
        get() =
            if (matches.isNotEmpty() && currentMatchIndex in matches.indices) {
                matches[currentMatchIndex]
            } else {
                null
            }

    fun onQueryChange(
        newQuery: String,
        screen: ChatScreenState,
    ) {
        query = newQuery
        updateMatches(screen)
    }

    fun updateMatches(screen: ChatScreenState) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            matches = emptyList()
            currentMatchIndex = 0
            return
        }
        val found = findMatches(trimmed, screen)
        matches = found
        if (currentMatchIndex >= found.size) {
            currentMatchIndex = if (found.isNotEmpty()) found.size - 1 else 0
        }
    }

    fun nextMatch(): SearchMatch? {
        if (matches.isEmpty()) return null
        currentMatchIndex = (currentMatchIndex + 1) % matches.size
        return matches[currentMatchIndex]
    }

    fun prevMatch(): SearchMatch? {
        if (matches.isEmpty()) return null
        currentMatchIndex = if (currentMatchIndex - 1 < 0) matches.size - 1 else currentMatchIndex - 1
        return matches[currentMatchIndex]
    }

    fun selectTarget(id: String): SearchMatch? {
        val index = matches.indexOfFirst { it.targetId == id }
        if (index < 0) return null
        currentMatchIndex = index
        return currentMatch
    }

    fun clear() {
        query = ""
        matches = emptyList()
        currentMatchIndex = 0
    }
}

internal fun findMatches(
    query: String,
    screen: ChatScreenState,
): List<SearchMatch> {
    val results = mutableListOf<SearchMatch>()
    val entries = conversationEntries(screen)
    val emptyConversation =
        screen.activeTurn == null &&
            listOf(screen.messages, screen.toolTimeline, screen.subscriptionRecoveries, screen.taskLedger)
                .all { it.isEmpty() }
    if (emptyConversation) return emptyList()

    var currentIndex = (if (screen.hasOlderMessages) 1 else 0) + (if (screen.hasNewerMessages) 1 else 0)
    for (entry in entries) {
        currentIndex = scanEntryMatches(entry, screen, query, currentIndex, results)
    }
    return results
}

private fun scanEntryMatches(
    entry: com.helix.app.chat.ConversationEntry,
    screen: ChatScreenState,
    query: String,
    startIndex: Int,
    results: MutableList<SearchMatch>,
): Int {
    var currentIndex = startIndex

    for (content in entry.timeline) {
        val itemIndex = currentIndex++
        contentMatch(content, query, itemIndex)?.let(results::add)
    }
    if (entry.recoveries.isNotEmpty()) currentIndex++

    // 4. Past error
    val past = screen.turns.firstOrNull { it.id == entry.key && it.id != screen.activeTurn?.id }
    if (past?.state == TurnState.FAILED && past.errorLabel != null) {
        currentIndex++
    }

    // 5. Past recovery
    val pastPanel = if (entry.key != screen.activeTurn?.id) screen.recoveryPanels[entry.key] else null
    if (pastPanel != null) {
        currentIndex++
    }

    return currentIndex
}

private fun contentMatch(
    content: com.helix.app.chat.ConversationContent,
    query: String,
    itemIndex: Int,
): SearchMatch? =
    when (content) {
        is com.helix.app.chat.ConversationContent.Message -> {
            content.value.let { message ->
                if (message.content.contains(query, ignoreCase = true)) {
                    SearchMatch(message.id, itemIndex, message.content)
                } else {
                    null
                }
            }
        }

        is com.helix.app.chat.ConversationContent.Tool -> {
            content.value.let { tool ->
                if (tool.toolName.contains(query, ignoreCase = true) ||
                    tool.requestSummary.contains(query, ignoreCase = true) ||
                    tool.resultSummary?.contains(query, ignoreCase = true) == true
                ) {
                    SearchMatch(tool.callId, itemIndex, tool.resultSummary ?: tool.requestSummary)
                } else {
                    null
                }
            }
        }
    }
