package com.helix.app.chat

import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID

/** Recovery is confined to future session work. Existing request/ToolCall bindings never move. */
internal class SessionWorkspaceRecovery(
    private val storage: HelixStorage,
    private val validate: (String) -> String,
) {
    fun availableDirectory(reference: String?): String? {
        val path = reference?.let { runCatching { FileScopePath.fromModelReference(it) }.getOrNull() }
        if (path == null || storage.workspaces.find(path.scopeId)?.availability != "READY") return null
        return availableWorkspaceDirectory(requireNotNull(reference)) {
            if (storage.workspaces.find(path.scopeId)?.ownership == "MANAGED") {
                val root = storage.workspaces.managedDirectory(path.scopeId)
                val directory =
                    com.helix.core.workspace.resolveFileScopePath(
                        path,
                        com.helix.core.workspace
                            .ScopeRootResolver { root },
                    )
                require(
                    java.nio.file.Files
                        .isDirectory(directory),
                ) { "Workspace directory unavailable" }
            }
            validate(it)
        }
    }

    fun sharedDirectory(sessionId: String): String? {
        val row = storage.sessions.resolve(sessionId)
        val binding = storage.workspaces.binding(sessionId) ?: return null
        val reference = FileScopePath(binding.workspaceId, binding.relativePath).toModelReference()
        return reference.takeIf { row.directoryRef == it }?.let(::availableDirectory)
    }

    fun recover(
        sessionId: String,
        now: Long,
    ) = storage.withTransaction {
        if (sharedDirectory(sessionId) != null) return@withTransaction
        val row = storage.sessions.resolve(sessionId)
        val directory = storage.workspaces.freshDirectory(sessionId, now)
        storage.sessions.updateDetails(sessionId, row.title, directory)
        record(sessionId, now)
    }

    fun record(
        sessionId: String,
        now: Long,
    ) {
        val binding = requireNotNull(storage.workspaces.binding(sessionId))
        storage.auditEvents.append(
            UUID.randomUUID().toString(),
            sessionId,
            "workspace.recovered",
            "system",
            buildJsonObject {
                put("workspaceId", binding.workspaceId)
                put("revision", binding.revision)
                put("reason", "SOURCE_UNAVAILABLE")
            }.toString(),
            now,
        )
    }

    fun hasNotice(sessionId: String): Boolean {
        val binding = storage.workspaces.binding(sessionId) ?: return false
        val events =
            storage.auditEvents.listByCorrelation(sessionId).filter { event ->
                event.type in setOf("workspace.recovered", "workspace.bound") &&
                    runCatching {
                        val data =
                            kotlinx.serialization.json.Json
                                .parseToJsonElement(event.redactedPayload)
                                .jsonObject
                        data["workspaceId"]?.jsonPrimitive?.content == binding.workspaceId &&
                            data["revision"]?.jsonPrimitive?.content == binding.revision.toString()
                    }.getOrDefault(false)
            }
        return events.any { it.type == "workspace.recovered" } && events.none { it.type == "workspace.bound" }
    }
}

/** Only resource availability failures permit fallback; cancellation and storage failures propagate. */
@Suppress("SwallowedException", "ReturnCount")
internal fun availableWorkspaceDirectory(
    reference: String,
    validate: (String) -> String,
): String? =
    try {
        validate(reference).takeIf { it == reference }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: com.helix.core.workspace.ScopeNotAvailable) {
        null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: SecurityException) {
        null
    }
