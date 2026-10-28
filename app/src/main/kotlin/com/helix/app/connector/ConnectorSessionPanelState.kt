package com.helix.app.connector

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.helix.app.plugin.PluginSelectionUnavailable
import com.helix.app.plugin.PluginService
import com.helix.app.plugin.PluginSessionRow
import com.helix.app.plugin.PluginSetupReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** All facts are re-read after a write; an optimistic checkbox never represents a grant. */
internal class ConnectorSessionPanelState(
    private val readFacts: () -> Pair<String, List<PluginSessionRow>>,
    private val saveSelection: (Map<String, Boolean>, Set<String>) -> Unit,
    private val canApply: () -> Boolean,
) {
    constructor(service: PluginService, sessionId: String, pluginId: String?, canApply: () -> Boolean) : this(
        { service.sessionTitle(sessionId) to service.sessionRows(sessionId, pluginId) },
        { expected, selected -> service.applySessionSelection(sessionId, expected, selected) },
        canApply,
    )

    private var expected: Map<String, Boolean> = emptyMap()

    var rows by mutableStateOf(emptyList<PluginSessionRow>())
        private set
    var title by mutableStateOf("")
        private set
    var loaded by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var needsConfiguration by mutableStateOf(false)
        private set
    var setupReason by mutableStateOf<PluginSetupReason?>(null)
        private set
    val empty get() = if (loading || failed) false else rows.isEmpty()
    val editable get() = !loading && !busy && !failed

    @Suppress("TooGenericExceptionCaught") // Application-service errors remain visible; cancellation propagates.
    suspend fun refresh() {
        loading = true
        try {
            val facts =
                withContext(Dispatchers.IO) { readFacts() }
            title = facts.first
            rows = facts.second
            expected = rows.associate { it.id to it.selected }
            loaded = true
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

    fun select(
        row: PluginSessionRow,
        enabled: Boolean,
    ) {
        if (!editable) return
        rows = rows.map { if (it.id == row.id) it.copy(selected = enabled) else it }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun apply(): Boolean {
        if (!editable) return false
        busy = true
        failed = false
        return try {
            withContext(Dispatchers.IO) {
                check(canApply()) { "SESSION_CHANGED" }
                saveSelection(expected, rows.filter { it.selected }.map { it.id }.toSet())
            }
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: PluginSelectionUnavailable) {
            setupReason = error.reason
            failed = true
            false
        } catch (_: Exception) {
            failed = true
            false
        } finally {
            busy = false
        }
    }
}
