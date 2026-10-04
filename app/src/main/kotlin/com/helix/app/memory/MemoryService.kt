package com.helix.app.memory

import com.helix.core.workspace.memory.MarkdownMemoryStore
import com.helix.core.workspace.memory.MemoryContext
import com.helix.core.workspace.memory.MemoryEntry
import com.helix.core.workspace.memory.MemoryMarkdown
import com.helix.core.workspace.memory.MemoryScope
import com.helix.core.workspace.memory.ProjectMemoryScopeKey

/** User settings are separate from model-writable Markdown. Models cannot enable their own access. */
@Suppress("TooManyFunctions") // One application facade for scoped management, settings and tool access.
class MemoryService(
    private val store: MarkdownMemoryStore,
    private val setting: (String) -> Boolean,
    private val saveSetting: (String, Boolean) -> Unit,
    private val project: (String) -> ProjectMemoryScopeKey? = { null },
    private val documents: MemoryDocuments? = null,
    private val projectForTool: (String, String, String) -> ProjectMemoryScopeKey? = { _, _, _ -> null },
) {
    val enabled: Boolean get() = setting("enabled")
    val autoGlobal: Boolean get() = setting("auto-global")
    val autoProject: Boolean get() = setting("auto-project")

    fun configure(
        key: String,
        value: Boolean,
    ) {
        require(key in setOf("enabled", "auto-global", "auto-project"))
        saveSetting(key, value)
    }

    fun scope(
        name: String,
        sessionId: String?,
    ): MemoryScope =
        when (name) {
            "global" -> {
                MemoryScope.Global
            }

            "project" -> {
                MemoryScope.Project(
                    requireNotNull(sessionId?.let(project)) { "PROJECT_MEMORY_IDENTITY_UNAVAILABLE" },
                )
            }

            else -> {
                error("MEMORY_INVALID_SCOPE")
            }
        }

    fun projectAvailable(sessionId: String?): Boolean = sessionId?.let(project) != null

    fun scopeForTool(
        name: String,
        sessionId: String?,
        turnId: String?,
        toolCallId: String,
    ): MemoryScope =
        if (name == "project") {
            MemoryScope.Project(
                requireNotNull(projectForTool(requireNotNull(sessionId), requireNotNull(turnId), toolCallId)) {
                    "PROJECT_MEMORY_CALL_UNAVAILABLE"
                },
            )
        } else {
            scope(name, sessionId)
        }

    fun list(scope: MemoryScope): List<MemoryEntry> = store.index(scope)

    fun search(
        scope: MemoryScope,
        query: String,
    ): List<MemoryEntry> = store.search(scope, query)

    fun read(
        scope: MemoryScope,
        path: String,
    ): MemoryEntry = store.read(scope, path)

    fun save(
        scope: MemoryScope,
        path: String,
        markdown: String,
        expectedHash: String,
    ): MemoryEntry = store.write(scope, path, markdown, expectedHash)

    fun edit(
        scope: MemoryScope,
        path: String,
        hash: String,
        old: String,
        replacement: String,
    ): MemoryEntry = store.edit(scope, path, hash, old, replacement)

    fun delete(
        scope: MemoryScope,
        path: String,
        hash: String,
    ) = store.delete(scope, path, hash)

    fun requireModelAccess(
        scope: MemoryScope,
        writing: Boolean,
    ) {
        check(enabled) { "MEMORY_DISABLED" }
        if (writing) {
            check(
                if (scope ==
                    MemoryScope.Global
                ) {
                    autoGlobal
                } else {
                    autoProject
                },
            ) { "MEMORY_AUTO_WRITE_DISABLED" }
        }
    }

    fun context(sessionId: String): String =
        if (!enabled) {
            ""
        } else {
            MemoryContext.render(
                store,
                listOfNotNull(MemoryScope.Global, project(sessionId)?.let(MemoryScope::Project)),
            )
        }

    fun importDocument(uri: android.net.Uri): String = requireNotNull(documents).read(uri)

    fun exportDocument(
        uri: android.net.Uri,
        scope: MemoryScope,
        path: String,
    ) = requireNotNull(documents).write(uri, read(scope, path).markdown)

    fun newMarkdown(
        type: String,
        source: String,
        body: String,
    ): String = MemoryMarkdown.encode(type, source, body, System.currentTimeMillis())
}
