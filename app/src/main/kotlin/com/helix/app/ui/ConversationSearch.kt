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

    var currentIndex = 0
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

    // 1. User messages
    val userMessages = entry.messages.filter { it.role == "user" }
    for (msg in userMessages) {
        val itemIndex = currentIndex++
        if (msg.content.contains(query, ignoreCase = true)) {
            results.add(SearchMatch(targetId = msg.id, listIndex = itemIndex, snippet = msg.content))
        }
    }

    // 2. Operations / Tools item
    val operationsIndex = currentIndex++
    val matchingTool =
        entry.tools.firstOrNull { tool ->
            tool.toolName.contains(query, ignoreCase = true) ||
                tool.requestSummary.contains(query, ignoreCase = true) ||
                (tool.resultSummary?.contains(query, ignoreCase = true) == true)
        }
    if (matchingTool != null) {
        val snippet = matchingTool.resultSummary ?: matchingTool.requestSummary
        results.add(SearchMatch(targetId = matchingTool.callId, listIndex = operationsIndex, snippet = snippet))
    }

    // 3. Assistant messages
    val assistantMessages = entry.messages.filter { it.role != "user" }
    for (msg in assistantMessages) {
        val itemIndex = currentIndex++
        if (msg.content.contains(query, ignoreCase = true)) {
            results.add(SearchMatch(targetId = msg.id, listIndex = itemIndex, snippet = msg.content))
        }
    }

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
