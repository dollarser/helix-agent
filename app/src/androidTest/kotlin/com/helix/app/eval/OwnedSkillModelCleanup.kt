package com.helix.app.eval

import com.helix.app.AppContainer
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState
import com.helix.extensions.skills.SkillSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

/** Exact resources created by one opt-in test; keep evidence/resources if its executor has not exited. */
@Suppress("LongParameterList")
internal suspend fun finishOwnedSkillModelRun(
    container: AppContainer,
    session: String?,
    provider: String?,
    directory: Path,
    name: String,
    previousSession: String?,
) = withContext(NonCancellable) {
    try {
        if (session != null) {
            stopOwnedCase(container.storage, container.chatService, session)
            withTimeout(15_000) {
                while (container.turnEngine?.liveExecution?.hasActive(session) == true) delay(20)
            }
            val turns = container.storage.turns.listBySession(session)
            requireResolvedSkillFixture(
                turns.map { TurnState.valueOf(it.state) },
                turns
                    .flatMap { container.storage.toolCalls.listByTurn(it.id) }
                    .map { ToolCallState.valueOf(it.state) },
            )
            container.chatService.preparePermanentDeletion(session)
            container.storage.deleteSessionPermanently(session)
        }
        try {
            container.skillRepository
                .list()
                .filter {
                    it.key.source == SkillSource.USER_IMPORTED && it.key.name == name
                }.forEach { container.skillRepository.removePermanentlyForPrivacy(it.key) }
        } finally {
            try {
                if (Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.walk(directory).use { paths ->
                        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
                    }
                }
            } finally {
                provider?.let { container.providerService.delete(it) }
            }
        }
    } finally {
        if (previousSession != null && container.storage.sessions.find(previousSession) != null) {
            container.chatService.openSession(previousSession)
        } else {
            container.chatService.closeSession()
        }
    }
}

/** Durable facts remain authoritative even when the process-local Job has exited. */
internal fun requireResolvedSkillFixture(
    turns: List<TurnState>,
    calls: List<ToolCallState>,
) {
    val settled = setOf(TurnState.COMPLETED, TurnState.FAILED, TurnState.CANCELLED)
    check(turns.all { it in settled } && calls.all { it.isTerminal }) { "OWNED_SKILL_EFFECTS_UNRESOLVED" }
}
