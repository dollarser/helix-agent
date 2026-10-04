package com.helix.app.memory

import android.content.Context
import com.helix.app.chat.ForbiddenContentGuard
import com.helix.core.workspace.memory.MarkdownMemoryStore
import java.io.File

// KTX edit returns Unit; report a failed durable settings commit instead of claiming success.
@android.annotation.SuppressLint("UseKtx")
internal fun createMemoryService(
    context: Context,
    project: (String) -> com.helix.core.workspace.memory.ProjectMemoryScopeKey? = { null },
    projectForTool: (String, String, String) -> com.helix.core.workspace.memory.ProjectMemoryScopeKey? =
        { _, _, _ -> null },
): MemoryService {
    val preferences = context.getSharedPreferences("memory-settings", Context.MODE_PRIVATE)
    return MemoryService(
        MarkdownMemoryStore(File(context.filesDir, "memory").toPath()) { ForbiddenContentGuard.reasonFor(it) != null },
        setting = { preferences.getBoolean(it, false) },
        saveSetting = { key, value -> check(preferences.edit().putBoolean(key, value).commit()) },
        project = project,
        documents = MemoryDocuments(context.contentResolver),
        projectForTool = projectForTool,
    )
}
