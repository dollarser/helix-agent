package com.helix.app.connector

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.helix.app.plugin.PluginSelectionUnavailable
import com.helix.app.plugin.PluginSessionRow
import com.helix.app.plugin.PluginSetupReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val MOBILE_USE_PLUGIN_ID = "bundled-plugin:mobile-use"

/** One immutable conversation target; returning from settings only refreshes facts, never selects. */
internal class MobileUseSessionState(
    private val read: () -> PluginSessionRow?,
    private val select: (Boolean) -> Unit,
    private val isCurrent: () -> Boolean,
    restoredSetupReason: PluginSetupReason? = null,
) {
    private val operation = Mutex()
    var row by mutableStateOf<PluginSessionRow?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var expired by mutableStateOf(false)
        private set
    var setupReason by mutableStateOf(restoredSetupReason.takeIf { isCurrent() })
        private set
    val active get() =
        !failed && !loading && !expired && setupReason == null && row?.let {
            it.selected && it.available && it.enabled && it.setupReason == null &&
                (it.ready || it.readyComponents > 0)
        } == true

    @Suppress("TooGenericExceptionCaught") // Read failures stay visible; cancellation is not a failure.
    suspend fun refresh() =
        operation.withLock {
            loading = true
            try {
                val actual = withContext(Dispatchers.IO) { if (isCurrent()) read() else null }
                expired = !isCurrent()
                row = actual?.takeIf { !expired && it.id == MOBILE_USE_PLUGIN_ID }
                failed = !expired && row == null
                if (expired || (setupReason != null && row?.setupReason != setupReason)) setupReason = null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
            }
        }

    @Suppress("TooGenericExceptionCaught") // Never infer a grant from a checkbox or a navigation return.
    suspend fun choose(enabled: Boolean) {
        if (busy || loading || !operation.tryLock()) return
        busy = true
        failed = false
        setupReason = null
        try {
            withContext(Dispatchers.IO) { selectCurrent(enabled) }
            val actual = withContext(Dispatchers.IO) { if (isCurrent()) read() else null }
            expired = !isCurrent()
            row = actual?.takeIf { !expired && it.id == MOBILE_USE_PLUGIN_ID }
            failed = !expired && row == null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (required: PluginSelectionUnavailable) {
            setupReason = required.reason.takeIf { isCurrent() }
            expired = !isCurrent()
        } catch (_: Exception) {
            failed = true
        } finally {
            busy = false
            operation.unlock()
        }
    }

    private fun selectCurrent(enabled: Boolean) {
        if (!isCurrent()) return
        val actual = checkNotNull(read())
        check(actual.id == MOBILE_USE_PLUGIN_ID && (!enabled || actual.available))
        val prerequisite = actual.setupReason
        if (enabled && prerequisite in setOf(PluginSetupReason.MOBILE_BACKEND, PluginSetupReason.MOBILE_SCOPE)) {
            throw PluginSelectionUnavailable(checkNotNull(prerequisite))
        }
        // The target stays immutable; a stale settings return cannot write into a new conversation.
        if (isCurrent()) select(enabled)
    }

    fun dismissSetup() {
        setupReason = null
    }
}
