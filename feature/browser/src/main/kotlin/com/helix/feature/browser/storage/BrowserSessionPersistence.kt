package com.helix.feature.browser.storage

import com.helix.feature.browser.BrowserTabController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Ordered checkpoints; close can await durable storage without doing disk I/O on the UI thread. */
internal class BrowserSessionPersistence(
    file: File,
    restored: (BrowserSession) -> Unit,
    private val failed: () -> Unit,
) {
    private val store = BrowserSessionStore(file)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val completion = CompletableDeferred<Unit>()
    val ready: Deferred<Unit> = completion
    private var writable = false
    private var queued: BrowserSession? = null
    private var saved: BrowserSession? = null

    init {
        scope.launch {
            mutex.withLock {
                try {
                    val session = withContext(Dispatchers.IO) { store.read() }
                    restored(session)
                    saved = session
                    queued = session
                    writable = true
                } catch (_: IOException) {
                    failed()
                } catch (_: IllegalArgumentException) {
                    failed()
                } catch (_: NoSuchElementException) {
                    failed()
                } finally {
                    completion.complete(Unit)
                }
            }
        }
    }

    fun update(state: BrowserTabController.State) {
        if (!ready.isCompleted || !writable) return
        val snapshot = BrowserSession.from(state)
        if (snapshot == queued) return
        queued = snapshot
        scope.launch {
            mutex.withLock {
                try {
                    withContext(Dispatchers.IO) { store.write(snapshot) }
                    saved = snapshot
                } catch (_: IOException) {
                    queued = null
                    failed()
                } catch (_: IllegalArgumentException) {
                    queued = null
                    failed()
                }
            }
        }
    }

    suspend fun flush(state: BrowserTabController.State): Boolean {
        ready.await()
        update(state)
        return mutex.withLock { writable && saved == BrowserSession.from(state) }
    }

    fun allowExplicitReset() {
        check(ready.isCompleted)
        writable = true
        queued = null
    }
}
