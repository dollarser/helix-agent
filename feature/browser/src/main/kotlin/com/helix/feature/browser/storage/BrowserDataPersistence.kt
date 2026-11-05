package com.helix.feature.browser.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Serializes initial browser data loading and edits, publishing only successful writes. */
internal class BrowserDataPersistence(
    private val failed: () -> Unit,
    load: suspend () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var ready = false

    init {
        scope.launch {
            mutex.withLock {
                try {
                    load()
                    ready = true
                } catch (_: IOException) {
                    failed()
                } catch (_: IllegalArgumentException) {
                    failed()
                }
            }
        }
    }

    fun <T> edit(
        state: MutableStateFlow<T>,
        save: (T) -> Unit,
        update: (T) -> T,
        after: (T) -> Unit,
    ) {
        scope.launch {
            mutex.withLock {
                if (!ready) {
                    failed()
                    return@withLock
                }
                try {
                    val updated = update(state.value)
                    withContext(Dispatchers.IO) { save(updated) }
                    state.value = updated
                    after(updated)
                } catch (_: IOException) {
                    failed()
                } catch (_: IllegalArgumentException) {
                    failed()
                }
            }
        }
    }
}
