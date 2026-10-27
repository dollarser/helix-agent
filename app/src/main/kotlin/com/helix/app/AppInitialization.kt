package com.helix.app

import com.helix.app.maintenance.PreviewResetRequest
import com.helix.app.maintenance.PreviewResetSection
import com.helix.app.maintenance.PreviewResetStep
import com.helix.core.storage.PreviewDatabaseResetRequired
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface AppStartupState {
    data object Loading : AppStartupState

    data class ResetRequired(
        val request: PreviewResetRequest,
        val failed: Boolean = false,
    ) : AppStartupState

    data object Ready : AppStartupState

    data object Failed : AppStartupState
}

/** Only an explicit foreground confirmation can replace a failed initialization attempt. */
internal class AppInitialization<T : Any>(
    private val factory: () -> T,
    private val onReady: (T) -> Unit = {},
    private val onFailure: (Exception) -> Unit = {},
    private val beforeReset: () -> Unit = {},
    private val preflight: () -> PreviewResetRequest? = { null },
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<AppStartupState>(AppStartupState.Loading)
    val state = mutableState.asStateFlow()

    @Volatile private var ready: T? = null
    private var attempt = start()

    fun readyOrNull(): T? = ready

    fun await(): T = synchronized(lock) { attempt }.await()

    suspend fun awaitAsync(): T = synchronized(lock) { attempt }.awaitAsync()

    fun confirmReset(request: PreviewResetRequest) =
        synchronized(lock) {
            val current = mutableState.value as? AppStartupState.ResetRequired ?: return@synchronized
            if (current.request !== request) return@synchronized
            mutableState.value = AppStartupState.Loading
            attempt = start(request)
        }

    fun recheck() =
        synchronized(lock) {
            if (mutableState.value in setOf(AppStartupState.Ready, AppStartupState.Loading)) return@synchronized
            mutableState.value = AppStartupState.Loading
            attempt = start()
        }

    @Suppress("TooGenericExceptionCaught") // Publish failure without swallowing it or deleting data.
    private fun start(reset: PreviewResetRequest? = null): BackgroundInitialization<T> =
        BackgroundInitialization("helix-container-init") {
            try {
                reset?.confirm(beforeReset)
                preflight()?.let { throw it }
                factory().also {
                    onReady(it)
                    ready = it
                    mutableState.value = AppStartupState.Ready
                }
            } catch (required: PreviewResetRequest) {
                mutableState.value = AppStartupState.ResetRequired(required)
                throw required
            } catch (database: PreviewDatabaseResetRequired) {
                // A database can change after the preflight; preserve the foreground review boundary.
                val databaseStep =
                    PreviewResetStep(
                        PreviewResetSection.DATABASE,
                        database::verifyUnchanged,
                        { database.confirm() },
                    )
                val required = preflight() ?: PreviewResetRequest(listOf(databaseStep))
                mutableState.value = AppStartupState.ResetRequired(required)
                throw required
            } catch (failure: Exception) {
                mutableState.value = AppStartupState.Failed
                onFailure(failure)
                if (reset != null) {
                    try {
                        preflight()?.let { mutableState.value = AppStartupState.ResetRequired(it, failed = true) }
                    } catch (inspection: Exception) {
                        onFailure(inspection)
                    }
                }
                throw failure
            }
        }
}
