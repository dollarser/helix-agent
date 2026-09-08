package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Main-thread UI actions only; disposal cancels work through the composition scope. */
internal class ImportActionState(
    private val scope: CoroutineScope,
) {
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    @Suppress("TooGenericExceptionCaught") // UI boundary reports failure; cancellation is rethrown.
    fun launch(
        onFailure: (Exception) -> Unit = {},
        block: suspend () -> Unit,
    ) {
        if (busy || !scope.isActive) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            busy = true
            failed = false
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                failed = true
                onFailure(failure)
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun rememberImportActionState(): ImportActionState {
    val scope = rememberCoroutineScope()
    return remember(scope) { ImportActionState(scope) }
}
