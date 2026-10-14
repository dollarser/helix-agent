package com.helix.app.documents

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.helix.extensions.documents.DocumentException
import com.helix.extensions.documents.DocumentLimits
import com.helix.extensions.documents.DocumentParser
import com.helix.extensions.documents.DocumentResult
import com.helix.extensions.documents.DocumentSnapshot
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
internal class AndroidDocumentParser(
    private val context: Context,
    private val sourceFile: (ExecutableToolCall, DocumentSnapshot) -> File,
) : DocumentParser {
    private val capacity = DocumentParseCapacity()
    private val watchdog =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "pdf-cancel").apply {
                isDaemon =
                    true
            }
        }

    override fun parse(
        call: ExecutableToolCall,
        snapshot: DocumentSnapshot,
        operation: String,
    ): DocumentResult {
        val lease = capacity.acquire(call)
        var folder: File? = null
        try {
            DocumentLimits.checkActive(call)
            folder = Files.createTempDirectory(context.cacheDir.toPath(), "document-").toFile()
            val output = File(folder, "image.png")
            if (snapshot.format == "pdf") {
                require("startBlock" !in call.args && "imageId" !in call.args)
            } else {
                require("page" !in call.args && "region" !in call.args)
            }
            val request =
                android.os.Bundle().apply {
                    putString("format", snapshot.format)
                    putString("operation", operation)
                    putInt("page", call.args["page"]?.jsonPrimitive?.intOrNull ?: 1)
                    putInt("startBlock", call.args["startBlock"]?.jsonPrimitive?.intOrNull ?: 1)
                    putString("imageId", call.args["imageId"]?.jsonPrimitive?.content)
                    call.args["region"]?.jsonArray?.let { values ->
                        putFloatArray("region", values.map { it.jsonPrimitive.float }.toFloatArray())
                    }
                }
            val result = isolatedRead(call, sourceFile(call, snapshot), output, request, lease)
            DocumentLimits.checkActive(call)
            result.getString("error")?.let { throw DocumentException(it) }
            val metadata =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(
                        requireNotNull(result.getString("metadata")),
                    ).jsonObject
            return DocumentResult(metadata, if (result.getBoolean("image")) readImage(call, output) else null)
        } catch (
            failure: DocumentException,
        ) {
            throw failure
        } catch (
            _: IOException,
        ) {
            throw DocumentException("SOURCE_UNAVAILABLE")
        } catch (
            _: SecurityException,
        ) {
            throw DocumentException("SCOPE_DENIED")
        } finally {
            folder?.deleteRecursively()
            lease.finishHost()
        }
    }

    private fun readImage(
        call: ExecutableToolCall,
        output: File,
    ): ByteArray {
        if (output.length() !in 1..DocumentLimits.IMAGE_BYTES.toLong()) throw DocumentException("IMAGE_LIMIT")
        return output.inputStream().use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                DocumentLimits.checkActive(call)
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > DocumentLimits.IMAGE_BYTES) throw DocumentException("IMAGE_LIMIT")
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
    }

    private fun isolatedRead(
        call: ExecutableToolCall,
        source: File,
        output: File,
        request: android.os.Bundle,
        lease: DocumentParseCapacity.Lease,
    ): android.os.Bundle {
        val connected = CountDownLatch(1)
        val remote = AtomicReference<IDocumentReader?>()
        val retired = AtomicBoolean(false)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    binder: IBinder,
                ) {
                    val api = IDocumentReader.Stub.asInterface(binder)
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
                Intent(context, DocumentReaderService::class.java),
                Context.BIND_AUTO_CREATE,
                "pdf_" + UUID.randomUUID().toString().replace("-", ""),
                context.mainExecutor,
                connection,
            )
        if (!bound) parserUnavailable()
        var timer: java.util.concurrent.ScheduledFuture<*>? = null
        val result =
            try {
                while (!connected.await(25, TimeUnit.MILLISECONDS)) DocumentLimits.checkActive(call)
                DocumentLimits.checkActive(call)
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
                nativeRead(api, source, output, request)
            } catch (_: RemoteException) {
                DocumentLimits.checkActive(call)
                throw DocumentException("PARSER_CONNECTION_FAILED")
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
        if (lease.awaitingExit) throw DocumentException("PARSER_EXIT_UNCONFIRMED")
        return result
    }

    private fun nativeRead(
        api: IDocumentReader,
        source: File,
        output: File,
        request: android.os.Bundle,
    ): android.os.Bundle =
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            ParcelFileDescriptor
                .open(
                    output,
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                        ParcelFileDescriptor.MODE_WRITE_ONLY,
                ).use { destination ->
                    api.read(input, destination, request)
                }
        }

    private fun parserUnavailable(): Nothing = throw DocumentException("PARSER_UNAVAILABLE")

    private fun shutdown(remote: IDocumentReader) {
        try {
            remote.shutdown()
        } catch (_: RemoteException) {
            // Death notification is authoritative for releasing native execution capacity.
        }
    }
}
