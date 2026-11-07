package com.helix.feature.conversation.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.feature.conversation.application.SessionDirectory
import com.helix.feature.conversation.application.SessionHistoryActions
import com.helix.feature.conversation.presentation.SessionRenameDialog

/** Secondary history/search surface. Opening a row returns to the primary Conversation route. */
@Composable
@Suppress("FunctionName")
fun ConversationHistoryScreen(
    directory: SessionDirectory,
    actions: SessionHistoryActions,
    focusSearch: Boolean,
    onOpenConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
) {
    val sessions by directory.sessions.collectAsStateWithLifecycle()
    val search by directory.search.collectAsStateWithLifecycle()
    val readFailed by directory.readFailed.collectAsStateWithLifecycle()
    var renameId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(directory) {
        directory.refresh()
    }

    SessionListSection(
        sessions = sessions,
        search = search,
        readFailed = readFailed,
        onRetryRead = directory::refresh,
        onNew = onNewConversation,
        onOpen = onOpenConversation,
        onArchive = actions.archive,
        onRestore = actions.restore,
        onTasks = {},
        onRename = { renameId = it },
        onNavigation = {},
        onSearch = directory::search,
        showHeader = false,
        focusSearch = focusSearch,
    )

    renameId?.let { id ->
        SessionRenameDialog(
            sessions.firstOrNull { it.id == id }?.title.orEmpty(),
            onDismiss = { renameId = null },
            onSave = {
                actions.rename(id, it)
                renameId = null
            },
        )
    }
}
