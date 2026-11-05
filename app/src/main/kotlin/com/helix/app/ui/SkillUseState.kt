package com.helix.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.helix.app.skills.SkillSupportSnapshot
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillListItem
import com.helix.extensions.skills.SkillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ephemeral inspection state; durable content identity and session choices stay in their repositories. */
@Suppress("TooGenericExceptionCaught")
internal class SkillUseState(
    private val repository: SkillRepository,
    val session: String,
    val key: SkillKey,
    val actions: () -> SkillUseActions,
    private val scope: CoroutineScope,
) {
    var row by mutableStateOf<SkillListItem?>(null)
        private set
    var snapshot by mutableStateOf<SkillSupportSnapshot?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var revision by mutableIntStateOf(0)
    var dependency by mutableStateOf<String?>(null)

    suspend fun reload() {
        try {
            val result =
                withContext(Dispatchers.IO) {
                    repository.list(session).single { it.key == key } to inspectSupport()
                }
            row = result.first
            snapshot = result.second
            failed = false
        } catch (
            cancel: CancellationException,
        ) {
            throw cancel
        } catch (_: Exception) {
            row = null
            snapshot = null
            failed = true
        }
    }

    fun enable(enabled: Boolean) =
        operate {
            withContext(Dispatchers.IO) { repository.setEnabled(key, enabled, SkillEnablementScope.SESSION, session) }
            revision++
        }

    fun invoke(arguments: String) =
        operate {
            val current =
                withContext(Dispatchers.IO) { inspectSupport() }
            check(current?.web?.provided != false && current?.unsupported.orEmpty().isEmpty())
            val activation = withContext(Dispatchers.IO) { repository.prepareUserActivation(key, session, arguments) }
            check(actions().context.canApply())
            check(requireNotNull(actions().invoke).invoke(activation))
            actions().completed()
        }

    private fun inspectSupport() =
        actions()
            .context.plugins
            ?.skillSupport
            ?.inspect(key, session)
            ?: SkillSupportSnapshot(repository.inspect(key), repository.inspectFiles(key), null)

    private fun operate(block: suspend () -> Unit) {
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    check(actions().context.canApply())
                    block()
                    failed = false
                } catch (
                    cancel: CancellationException,
                ) {
                    throw cancel
                } catch (_: Exception) {
                    failed = true
                } finally {
                    busy = false
                }
            }
        }
    }
}
