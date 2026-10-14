package com.helix.app.pdf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.files.PdfPage
import com.helix.tools.files.PdfPageReader
import com.helix.tools.files.PdfReadException
import com.helix.tools.files.PdfSourceSnapshot
import com.helix.tools.framework.ExecutableToolCall
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The slot follows actual Binder completion/death, not the coroutine's cancellation. */
internal class AndroidPdfPageReader(
    private val context: Context,
    private val workspace: WorkspaceArtifactStore,
) : PdfPageReader {
    private val capacity = PdfParseCapacity()
    private val watchdog =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "pdf-cancel").apply {
                isDaemon =
                    true
            }
        }

    override fun read(
        call: ExecutableToolCall,
        path: String,
        page: Int,
        image: Boolean,
    ): PdfPage {
        val lease = capacity.acquire(call)
        var folder: File? = null
        try {
            PdfSourceSnapshot.checkActive(call)
            folder = Files.createTempDirectory(context.cacheDir.toPath(), "pdf-").toFile()
            val source = File(folder, "source.pdf")
            val hash = snapshot(call, path, source)
            val output = File(folder, "page.png")
            val bundle = isolatedRead(call, source, output, page, image, lease)
            PdfSourceSnapshot.checkActive(call)
            bundle.getString("error")?.let { throw PdfReadException(it) }
            val png = if (bundle.getBoolean("image")) readImage(call, output) else null
            return PdfPage(
                bundle.getInt("pageCount"),
                hash,
                bundle.getString("text").orEmpty(),
                bundle.getBoolean("textTruncated"),
                bundle.getBoolean("textExtractionSupported"),
                png,
            )
        } catch (failure: PdfReadException) {
            throw failure
        } catch (_: IOException) {
            throw PdfReadException("PDF_SOURCE_UNAVAILABLE")
        } catch (_: SecurityException) {
            throw PdfReadException("PDF_ACCESS_DENIED")
        } finally {
            // Native parser owns only duplicated FDs; unlinking private staging never edits the source.
            folder?.deleteRecursively()
            // isolatedRead transfers this responsibility to Binder death if native work started.
            lease.finishHost()
        }
    }

    private fun snapshot(
        call: ExecutableToolCall,
        path: String,
        source: File,
    ): String =
        workspace.openRead(FileScopePath.fromModelReference(path)).use { input ->
            source.outputStream().use { output -> PdfSourceSnapshot.copy(call, input, output) }
        }

    private fun readImage(
        call: ExecutableToolCall,
        output: File,
    ): ByteArray {
        if (output.length() !in 1..PdfSourceSnapshot.MAX_PNG_BYTES.toLong()) throw PdfReadException("IMAGE_LIMIT")
        return output.inputStream().use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                PdfSourceSnapshot.checkActive(call)
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > PdfSourceSnapshot.MAX_PNG_BYTES) throw PdfReadException("IMAGE_LIMIT")
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
    }

    private fun isolatedRead(
        call: ExecutableToolCall,
        source: File,
        output: File,
        page: Int,
        image: Boolean,
        lease: PdfParseCapacity.Lease,
    ): android.os.Bundle {
        val connected = CountDownLatch(1)
        val remote = AtomicReference<IPdfPageReader?>()
        val retired = AtomicBoolean(false)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    binder: IBinder,
                ) {
                    val api = IPdfPageReader.Stub.asInterface(binder)
                    try {
                        binder.linkToDeath({
                            lease.nativeExited()
                        }, 0)
                        remote.set(api)
                        if (retired.get()) shutdown(api)
                    } catch (_: RemoteException) {
                        lease.nativeExited()
                    }
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // Connection/UI state is not the physical Binder-death receipt.
                }

                override fun onNullBinding(name: ComponentName) {
                    connected.countDown()
                }

                override fun onBindingDied(name: ComponentName) {
                    connected.countDown()
                }
            }
        val bound =
            context.bindIsolatedService(
                Intent(context, PdfReaderService::class.java),
                Context.BIND_AUTO_CREATE,
                "pdf_" + UUID.randomUUID().toString().replace("-", ""),
                context.mainExecutor,
                connection,
            )
        if (!bound) parserUnavailable()
        var timer: java.util.concurrent.ScheduledFuture<*>? = null
        val result =
            try {
                while (!connected.await(25, TimeUnit.MILLISECONDS)) PdfSourceSnapshot.checkActive(call)
                PdfSourceSnapshot.checkActive(call)
                val api = remote.get() ?: parserUnavailable()
                lease.beginNative()
                timer =
                    watchdog.scheduleWithFixedDelay({
                        if (call.cancel.isCancelled() ||
                            !java.time.Instant
                                .now()
                                .isBefore(call.deadline)
                        ) {
                            shutdown(api)
                        }
                    }, 0, 25, TimeUnit.MILLISECONDS)
                nativeRead(api, source, output, page, image)
            } catch (_: RemoteException) {
                PdfSourceSnapshot.checkActive(call)
                throw PdfReadException("PARSER_CONNECTION_FAILED")
            } finally {
                retired.set(true)
                timer?.cancel(false)
                remote.get()?.let(::shutdown)
                if (lease.awaitingExit) {
                    try {
                        lease.awaitExit(2, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                context.unbindService(connection)
                // If death is delayed, its callback retains and later releases capacity.
            }
        // Never consume output while its isolated writer may still be alive.
        if (lease.awaitingExit) throw PdfReadException("PARSER_EXIT_UNCONFIRMED")
        return result
    }

    private fun nativeRead(
        api: IPdfPageReader,
        source: File,
        output: File,
        page: Int,
        image: Boolean,
    ): android.os.Bundle =
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            ParcelFileDescriptor
                .open(
                    output,
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                        ParcelFileDescriptor.MODE_WRITE_ONLY,
                ).use { destination ->
                    api.read(input, destination, page, image)
                }
        }

    private fun parserUnavailable(): Nothing = throw PdfReadException("PARSER_UNAVAILABLE")

    private fun shutdown(remote: IPdfPageReader) {
        try {
            remote.shutdown()
        } catch (_: RemoteException) {
            // Death notification is authoritative for releasing native execution capacity.
        }
    }
}
