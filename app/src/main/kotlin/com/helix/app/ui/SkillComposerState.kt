package com.helix.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.helix.app.plugin.PluginService
import com.helix.core.model.input.InputSkillActivation
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillListItem
import com.helix.extensions.skills.SkillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Discovery is read-only. Selection rechecks the independent source and the exact snapshot. */
@Suppress("TooGenericExceptionCaught")
internal class SkillComposerState(
    private val repository: SkillRepository,
    private val session: String,
    private val plugins: PluginService?,
) {
    var rows by mutableStateOf<List<SkillListItem>>(emptyList())
        private set
    var failed by mutableStateOf(false)
        private set

    suspend fun reload() {
        try {
            rows = withContext(Dispatchers.IO) { repository.listSelectable(session) }
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    suspend fun prepare(id: String): InputSkillActivation? {
        val key = rows.singleOrNull { skillSuggestionId(it.key) == id }?.key ?: return null
        return withContext(Dispatchers.IO) {
            val current = repository.listSelectable(session).singleOrNull { it.key == key }
            check(current?.enabled == true)
            val support = plugins?.skillSupport?.inspect(key, session)
            check(support?.web?.provided != false && support?.unsupported.orEmpty().isEmpty())
            val activation = repository.prepareUserActivation(key, session, "")
            InputSkillActivation(key.source.name, key.name, key.snapshotHash, activation.arguments, key.ownerId)
        }
    }
}

internal fun skillSuggestionId(key: SkillKey): String =
    "skill:${key.source.name}:${key.name}:${key.snapshotHash}:${key.ownerId}"
