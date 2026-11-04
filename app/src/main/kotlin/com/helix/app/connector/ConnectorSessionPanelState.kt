package com.helix.app.connector

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.helix.app.plugin.PluginSelectionUnavailable
import com.helix.app.plugin.PluginService
import com.helix.app.plugin.PluginSessionRow
import com.helix.app.plugin.PluginSetupReason
import com.helix.app.plugin.canSelectForSession
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
    private var restoredDraft: Map<String, Boolean> = emptyMap()
    var setupPluginId by mutableStateOf<String?>(null)
        private set
    var pendingRepair by mutableStateOf<String?>(null)
        private set
    val setupRow get() = rows.singleOrNull { it.id == setupPluginId }

    fun dismissSetup() {
        setupPluginId = null
        setupReason = null
    }

    fun cancelRepair() {
        pendingRepair = null
        dismissSetup()
    }

    fun beginRepair(): PluginSessionRow? {
        val row = setupRow
        if (row == null || !editable || !canApply()) return null
        pendingRepair = row.id
        setupPluginId = null
        val reason = setupReason ?: row.setupReason
        setupReason = null
        return row.copy(setupReason = reason)
    }

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
    suspend fun refresh(afterConfiguration: Boolean = false) {
        loading = true
        try {
            val facts =
                withContext(Dispatchers.IO) { readFacts() }
            title = facts.first
            val draft = if (loaded) rows.associate { it.id to it.selected } else restoredDraft
            val pending = pendingRepair.takeIf { afterConfiguration && canApply() }
            rows =
                facts.second.map { row ->
                    val repaired = row.id == pending && row.canSelectForSession
                    val desired = if (expected[row.id] == row.selected) draft[row.id] ?: row.selected else row.selected
                    row.copy(selected = if (row.id == pending) row.selected || repaired else desired)
                }
            expected = facts.second.associate { it.id to it.selected }
            restoredDraft = emptyMap()
            loaded = true
            failed = false
            needsConfiguration = false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        } finally {
            if (afterConfiguration) pendingRepair = null
            loading = false
        }
    }

    fun select(
        row: PluginSessionRow,
        enabled: Boolean,
    ) {
        if (!editable) return
        if (enabled && !row.canSelectForSession) {
            setupPluginId = row.id
            setupReason = if (!row.enabled) PluginSetupReason.DISABLED else row.setupReason
            return
        }
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
            setupPluginId = error.pluginId ?: rows.singleOrNull { it.selected && expected[it.id] != true }?.id
            rows = rows.map { if (it.id == setupPluginId && expected[it.id] != true) it.copy(selected = false) else it }
            failed = setupRow == null
            false
        } catch (_: Exception) {
            failed = true
            false
        } finally {
            busy = false
        }
    }

    companion object {
        fun saver(create: () -> ConnectorSessionPanelState) =
            androidx.compose.runtime.saveable.listSaver<ConnectorSessionPanelState, String>(
                save = { state ->
                    listOf(state.pendingRepair.orEmpty(), state.setupPluginId.orEmpty()) +
                        state.expected.flatMap { (id, selected) -> listOf("expected", id, selected.toString()) } +
                        (if (state.loaded) state.rows.associate { it.id to it.selected } else state.restoredDraft)
                            .flatMap { (id, selected) -> listOf("draft", id, selected.toString()) }
                },
                restore = { values ->
                    create().apply {
                        pendingRepair = values[0].ifEmpty { null }
                        setupPluginId = values[1].ifEmpty { null }
                        val entries = values.drop(2).chunked(3)
                        expected = entries.filter { it[0] == "expected" }.associate { it[1] to it[2].toBooleanStrict() }
                        restoredDraft =
                            entries.filter { it[0] == "draft" }.associate { it[1] to it[2].toBooleanStrict() }
                    }
                },
            )
    }
}
