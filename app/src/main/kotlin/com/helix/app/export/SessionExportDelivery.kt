package com.helix.app.export

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import com.helix.core.storage.export.PreparedSessionExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A cancellation watcher closes the descriptor even while a provider is blocking a write. */
internal object SessionExportDelivery {
    suspend fun write(
        resolver: ContentResolver,
        target: Uri,
        prepared: PreparedSessionExport,
        onProgress: (Long) -> Unit,
    ) = coroutineScope {
        val signal = CancellationSignal()
        val active = AtomicReference<OutputStream?>()
        val finished = AtomicBoolean(false)
        val cancelling = AtomicBoolean(false)
        val watcher =
            launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) {
                        cancelling.set(true)
                        signal.cancel()
                        try {
                            active.getAndSet(null)?.close()
                        } catch (_: IOException) {
                            // The cancelled writer still fails; cleanup reports whether a partial target remains.
                        }
                    }
                }
            }
        try {
            withContext(Dispatchers.IO) {
                val descriptor = open(resolver, target, signal, cancelling)
                val output = CheckedOutput(descriptor)
                active.set(output)
                val context = currentCoroutineContext()
                try {
                    prepared.deliver(output, context::ensureActive, onProgress)
                } catch (failure: IOException) {
                    // Closing a blocked Android descriptor surfaces InterruptedIOException; preserve user cancellation.
                    if (cancelling.get()) throw CancellationException("Export cancelled", failure)
                    context.ensureActive()
                    throw failure
                } finally {
                    active.compareAndSet(output, null)
                }
            }
        } finally {
            finished.set(true)
            withContext(NonCancellable) { watcher.cancelAndJoin() }
        }
    }

    private fun open(
        resolver: ContentResolver,
        target: Uri,
        signal: CancellationSignal,
        cancelling: AtomicBoolean,
    ): ParcelFileDescriptor =
        try {
            resolver.openFileDescriptor(target, "wt", signal) ?: throw IOException("Export target unavailable")
        } catch (failure: OperationCanceledException) {
            if (cancelling.get()) throw CancellationException("Export cancelled while opening target", failure)
            throw failure
        }

    private class CheckedOutput(
        private val descriptor: ParcelFileDescriptor,
    ) : ParcelFileDescriptor.AutoCloseOutputStream(descriptor) {
        override fun close() {
            try {
                if (descriptor.canDetectErrors()) descriptor.checkError()
            } finally {
                super.close()
            }
        }
    }
}
