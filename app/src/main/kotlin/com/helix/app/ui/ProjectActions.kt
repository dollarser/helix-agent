package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.helix.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class ProjectActions(
    private val scope: CoroutineScope,
) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<Int?>(null)
        private set

    @Suppress("TooGenericExceptionCaught")
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error =
                    when (failure.message) {
                        "PROJECT_SESSION_BUSY" -> R.string.project_busy
                        "PROJECT_CHANGED" -> R.string.project_changed
                        else -> R.string.project_failed
                    }
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun rememberProjectActions(): ProjectActions {
    val scope = rememberCoroutineScope()
    return remember(scope) { ProjectActions(scope) }
}
