package com.helix.app.chat

import com.helix.core.storage.repository.SessionHistoryCaller
import com.helix.core.storage.repository.SessionHistoryRepository
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Instant

/** Host conversation operations; no plugin, remote executor, or UI-selected-session authority. */
internal object SessionTools {
    fun register(
        registry: ToolRegistry,
        repository: SessionHistoryRepository,
        onRenamed: () -> Unit = {},
    ) {
        SessionToolDescriptors.names.forEach { name ->
            registry.register(
                SessionToolDescriptors.descriptor(name),
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                        execute(repository, call, onRenamed)
                },
            )
        }
    }

    private fun execute(
        repository: SessionHistoryRepository,
        call: ExecutableToolCall,
        onRenamed: () -> Unit,
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
                        SessionToolHistory.history(repository, caller, call) { checkActive(call) }
                    }

                    "session.read" -> {
                        SessionToolHistory.read(repository, caller, call)
                    }

                    "session.rename" -> {
                        return rename(repository, caller, call, onRenamed)
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
        } catch (_: IllegalArgumentException) {
            ToolExecutorResult.Failed(
                "SESSION_DATA_UNAVAILABLE: content is inaccessible, changed, invalid or too large. Rediscover first.",
                sideEffectFree = true,
            )
        } catch (_: IOException) {
            ToolExecutorResult.Failed(
                "SESSION_CONTENT_UNAVAILABLE: saved content could not be read.",
                sideEffectFree = true,
            )
        }
    }

    private fun rename(
        repository: SessionHistoryRepository,
        caller: SessionHistoryCaller,
        call: ExecutableToolCall,
        onRenamed: () -> Unit,
    ): ToolExecutorResult {
        checkActive(call)
        val changed = repository.rename(caller, call.text("expectedTitle"), call.text("title"))
        if (!changed) {
            return ToolExecutorResult.Failed(
                "SESSION_CHANGED: read session.status before retry.",
                sideEffectFree = true,
            )
        }
        // Notifications are advisory. A failed observer cannot undo a committed rename.
        val notified = notifyRename(onRenamed)
        return ToolExecutorResult.Completed(
            buildJsonObject {
                put("sessionId", caller.sessionId)
                put("title", call.text("title").trim())
                put("applied", true)
            },
            auditDetail = buildJsonObject { put("observerNotified", notified) },
        )
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Advisory UI invalidation after the durable commit.
    private fun notifyRename(callback: () -> Unit): Boolean =
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

    private fun ExecutableToolCall.text(key: String) = args.getValue(key).jsonPrimitive.content
}
