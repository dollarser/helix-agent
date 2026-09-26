package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.chat.ChatService

/** Secondary history/search surface. Opening a row returns to the primary Conversation route. */
@Composable
@Suppress("FunctionName")
internal fun ConversationHistoryScreen(
    chatService: ChatService,
    focusSearch: Boolean,
    onOpenConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
) {
    val sessions by chatService.sessions.collectAsStateWithLifecycle()
    val search by chatService.sessionSearch.collectAsStateWithLifecycle()
    var renameId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        chatService.refreshSessions()
    }

    SessionListSection(
        sessions = sessions,
        search = search,
        onNew = onNewConversation,
        onOpen = onOpenConversation,
        onArchive = chatService::archiveSession,
        onRestore = chatService::restoreSession,
        onTasks = {},
        onRename = { renameId = it },
        onNavigation = {},
        onSearch = chatService::searchSessions,
        showHeader = false,
        focusSearch = focusSearch,
    )

    renameId?.let { id ->
        SessionRenameDialog(
            sessions.firstOrNull { it.id == id }?.title.orEmpty(),
            onDismiss = { renameId = null },
            onSave = {
                chatService.renameSession(id, it)
                renameId = null
            },
        )
    }
}
