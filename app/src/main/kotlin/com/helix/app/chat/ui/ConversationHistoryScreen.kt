package com.helix.app.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.chat.SessionDirectory
import com.helix.app.chat.SessionHistoryActions
import com.helix.app.ui.SessionRenameDialog

/** Secondary history/search surface. Opening a row returns to the primary Conversation route. */
@Composable
@Suppress("FunctionName")
internal fun ConversationHistoryScreen(
    directory: SessionDirectory,
    actions: SessionHistoryActions,
    focusSearch: Boolean,
    onOpenConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
) {
    val sessions by directory.sessions.collectAsStateWithLifecycle()
    val search by directory.search.collectAsStateWithLifecycle()
    var renameId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(directory) {
        directory.refresh()
    }

    SessionListSection(
        sessions = sessions,
        search = search,
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
