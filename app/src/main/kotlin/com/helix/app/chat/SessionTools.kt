package com.helix.app.chat

import com.helix.core.storage.repository.SessionAccessRepository
import com.helix.core.storage.repository.SessionHistoryCaller
import com.helix.core.storage.repository.SessionHistoryRepository
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Instant

/** Host conversation operations; no plugin, remote executor, or UI-selected-session authority. */
internal object SessionTools {
    fun register(
        registry: ToolRegistry,
        repository: SessionHistoryRepository,
        accessRepository: SessionAccessRepository,
        onRenamed: () -> Unit = {},
    ) {
        SessionToolDescriptors.names.forEach { name ->
            registry.register(
                SessionToolDescriptors.descriptor(name),
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                        execute(repository, call, onRenamed, accessRepository)
                },
            )
        }
    }

    private fun execute(
        repository: SessionHistoryRepository,
        call: ExecutableToolCall,
        onRenamed: () -> Unit,
        accessRepository: SessionAccessRepository,
    ): ToolExecutorResult {
        return try {
            checkActive(call)
            val caller =
                SessionHistoryCaller(
                    requireNotNull(call.sessionId),
                    requireNotNull(call.turnId),
                    call.toolCallId,
                    call.toolName,
                )
            val session = repository.caller(caller)
            if (call.toolName in setOf("session.list", "session.rename", "session.archive", "session.restore") ||
                "targetSessionId" in call.args
            ) {
                return SessionTargetTools.execute(
                    repository,
                    accessRepository,
                    caller,
                    call,
                    onRenamed,
                ) {
                    checkActive(call)
                }
            }
            val output =
                when (call.toolName) {
                    "session.status" -> {
                        buildJsonObject {
                            put("sessionId", session.id)
                            put("title", session.title)
                            put("archived", session.archivedAt != null)
                            put("currentTurnId", caller.turnId)
                            put("access", "current_conversation_and_saved_references")
                        }
                    }

                    "session.history", "session.search" -> {
                        SessionToolHistory.history(repository, caller, call, checkActive = { checkActive(call) })
                    }

                    "session.read" -> {
                        SessionToolHistory.read(repository, caller, call, checkActive = { checkActive(call) })
                    }

                    else -> {
                        throw IllegalArgumentException("Unknown session tool")
                    }
                }
            repository.caller(caller)
            checkActive(call)
            ToolExecutorResult.Completed(output)
        } catch (_: SessionReadStopped) {
            if (call.cancel.isCancelled()) ToolExecutorResult.Cancelled else ToolExecutorResult.TimedOut
        } catch (invalid: IllegalArgumentException) {
            invalidContent(invalid)
        } catch (_: IOException) {
            ToolExecutorResult.Failed(
                "SESSION_CONTENT_UNAVAILABLE: saved content could not be read.",
                sideEffectFree = true,
            )
        }
    }

    private fun invalidContent(invalid: IllegalArgumentException): ToolExecutorResult.Failed {
        val codes =
            setOf(
                "REFERENCE_SOURCE_CHANGED",
                "REFERENCE_SOURCE_UNAVAILABLE",
                "REFERENCE_CONTENT_BLOCKED",
                "REFERENCE_IDENTITY_CHANGED",
                "REFERENCE_NOT_USER_SHARED",
            )
        val code = invalid.message?.takeIf { it in codes } ?: "SESSION_DATA_UNAVAILABLE"
        return ToolExecutorResult.Failed(
            "$code: content is inaccessible, changed, unshared, blocked or too large. " +
                "Reselect changed references; never substitute a different source.",
            sideEffectFree = true,
        )
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Advisory UI invalidation after the durable commit.
    internal fun notifyRename(callback: () -> Unit): Boolean =
        try {
            callback()
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            false // Commit is already authoritative even if the observer's scope was cancelled.
        } catch (_: Exception) {
            false
        }

    private fun checkActive(call: ExecutableToolCall) {
        if (call.cancel.isCancelled() || !Instant.now().isBefore(call.deadline)) throw SessionReadStopped()
    }

    private class SessionReadStopped : java.util.concurrent.CancellationException()
}
