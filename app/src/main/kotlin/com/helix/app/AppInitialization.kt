package com.helix.app

import com.helix.core.storage.PreviewDatabaseResetRequired
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface AppStartupState {
    data object Loading : AppStartupState

    data class ResetRequired(
        val request: PreviewDatabaseResetRequired,
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
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<AppStartupState>(AppStartupState.Loading)
    val state = mutableState.asStateFlow()

    @Volatile private var ready: T? = null
    private var attempt = start()

    fun readyOrNull(): T? = ready

    fun await(): T = synchronized(lock) { attempt }.await()

    suspend fun awaitAsync(): T = synchronized(lock) { attempt }.awaitAsync()

    fun confirmReset(request: PreviewDatabaseResetRequired) =
        synchronized(lock) {
            val current = mutableState.value as? AppStartupState.ResetRequired ?: return@synchronized
            if (current.request !== request) return@synchronized
            mutableState.value = AppStartupState.Loading
            attempt = start(request)
        }

    @Suppress("TooGenericExceptionCaught") // Publish failure without swallowing it or deleting data.
    private fun start(reset: PreviewDatabaseResetRequired? = null): BackgroundInitialization<T> =
        BackgroundInitialization("helix-container-init") {
            try {
                reset?.confirm(beforeReset)
                factory().also {
                    onReady(it)
                    ready = it
                    mutableState.value = AppStartupState.Ready
                }
            } catch (required: PreviewDatabaseResetRequired) {
                mutableState.value = AppStartupState.ResetRequired(required)
                throw required
            } catch (failure: Exception) {
                mutableState.value = AppStartupState.Failed
                onFailure(failure)
                throw failure
            }
        }
}
