package com.helix.app.infrastructure.conversation

import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ExpertDefinition
import com.helix.core.storage.repository.ExpertLibraryRepository
import com.helix.core.storage.repository.ExpertProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** App-owned reusable Expert definitions and the explicit Session profile selection. */
class ConversationExperts internal constructor(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val currentSessionId: () -> String?,
) {
    /** Active Turns keep their admitted profile snapshot. */
    fun loadSessionExpert(sessionId: String): Deferred<ExpertProfile?> =
        scope.async {
            storage.sessionExperts.forSession(sessionId)
        }

    fun savedExperts(): Deferred<List<ExpertDefinition>> = scope.async { storage.experts.list() }

    fun removeExpert(id: String): Deferred<Boolean> = scope.async { storage.experts.remove(id) }

    fun saveExpert(
        id: String,
        name: String,
        instruction: String,
        description: String = "",
        plugins: List<String> = emptyList(),
        skills: List<String> = emptyList(),
    ): Deferred<Boolean> =
        scope.async {
            val profile = ExpertProfile(id, name.trim(), instruction.trim(), skills, plugins)
            storage.experts.save(ExpertDefinition(profile, description.trim()))
            true
        }

    fun importExpert(
        text: String,
        id: String,
    ): Deferred<Boolean> =
        scope.async {
            storage.experts.save(ExpertLibraryRepository.parseImport(text, id))
            true
        }

    fun selectExpert(
        sessionId: String,
        expertId: String?,
    ): Deferred<Boolean> =
        scope.async {
            if (currentSessionId() != sessionId) return@async false
            var selected = false
            storage.withTransaction {
                if (currentSessionId() != sessionId || storage.sessions.find(sessionId) == null) return@withTransaction
                if (expertId == null) {
                    storage.sessionExperts.clearForSession(sessionId, clock.now().toEpochMilli())
                } else {
                    val profile = storage.experts.find(expertId)?.profile ?: return@withTransaction
                    storage.sessionExperts.setForSession(sessionId, profile, clock.now().toEpochMilli())
                }
                selected = true
            }
            selected
        }
}
