package com.helix.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillListItem
import com.helix.extensions.skills.SkillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("TooGenericExceptionCaught")
internal class SkillSessionState(
    private val repository: SkillRepository,
    private val session: String,
    private val scope: CoroutineScope,
    private val canApply: () -> Boolean,
) {
    var rows by mutableStateOf<List<SkillListItem>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var revision by mutableIntStateOf(0)
    var failed by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set

    suspend fun reload() {
        try {
            rows = withContext(Dispatchers.IO) { repository.listSelectable(session) }
            loaded = true
            failed = false
        } catch (
            cancel: CancellationException,
        ) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }

    fun enable(
        key: SkillKey,
        enabled: Boolean,
    ) {
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    check(canApply())
                    withContext(
                        Dispatchers.IO,
                    ) { repository.setEnabled(key, enabled, SkillEnablementScope.SESSION, session) }
                    revision++
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
