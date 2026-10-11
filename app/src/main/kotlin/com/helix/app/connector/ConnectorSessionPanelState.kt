package com.helix.app.connector

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.helix.app.plugin.PluginSelectionUnavailable
import com.helix.app.plugin.PluginService
import com.helix.app.plugin.PluginSessionRow
import com.helix.app.plugin.PluginSetupReason
import com.helix.extensions.skills.SkillListItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** All facts are re-read after a write; an optimistic checkbox never represents a grant. */
internal class ConnectorSessionPanelState(
    private val service: PluginService,
    private val sessionId: String,
    private val pluginId: String?,
) {
    var rows by mutableStateOf(emptyList<PluginSessionRow>())
        private set
    var skills by mutableStateOf(emptyList<SkillListItem>())
        private set
    var title by mutableStateOf("")
        private set
    var loading by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var needsConfiguration by mutableStateOf(false)
        private set
    var updated by mutableStateOf(false)
        private set
    var setupReason by mutableStateOf<PluginSetupReason?>(null)
        private set
    val empty get() = if (loading || failed) false else rows.isEmpty() && skills.isEmpty()
    val editable get() = !loading && !busy

    @Suppress("TooGenericExceptionCaught") // Application-service errors remain visible; cancellation propagates.
    suspend fun refresh() {
        loading = true
        try {
            val facts =
                withContext(Dispatchers.IO) {
                    Triple(
                        service.sessionTitle(sessionId),
                        service.sessionRows(sessionId, pluginId),
                        if (pluginId == null) service.standaloneSkills(sessionId) else emptyList(),
                    )
                }
            title = facts.first
            rows = facts.second
            skills = facts.third
            failed = false
            needsConfiguration = false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun select(
        row: PluginSessionRow,
        enabled: Boolean,
    ) {
        if (!editable) return
        busy = true
        updated = false
        setupReason = null
        try {
            withContext(Dispatchers.IO) { service.selectFromUser(sessionId, row.id, enabled) }
            refresh()
            updated = !failed
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: PluginSelectionUnavailable) {
            needsConfiguration = error.reason == PluginSetupReason.MOBILE_SCOPE
            setupReason = error.reason
        } catch (_: Exception) {
            failed = true
        } finally {
            busy = false
        }
    }
}
