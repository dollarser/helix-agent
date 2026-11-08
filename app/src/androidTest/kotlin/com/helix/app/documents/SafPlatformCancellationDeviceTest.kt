package com.helix.app.documents

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Android API evaluation only, separate from the production passive CancelSignal dispatch contract. */
class SafPlatformCancellationDeviceTest {
    @Test fun cooperativeProviderCanCancelQueryAndAssetOpen() {
        listOf(false, true).forEach { asset ->
            Fixture(cooperative = true).use { f ->
                val operation = f.workers.submit { f.open(asset) }
                await(f.provider.entered)
                f.signal.cancel()
                val failure = assertThrows(ExecutionException::class.java) { operation.get(5, TimeUnit.SECONDS) }
                assertTrue(failure.cause is OperationCanceledException)
                assertTrue(f.provider.cancelReceived.count == 0L)
            }
        }
    }

    @Test fun cancellationDoesNotForceAnUncooperativeProviderToReturn() {
        listOf(false, true).forEach { asset ->
            Fixture(cooperative = false).use { f ->
                val operation = f.workers.submit { f.open(asset) }
                await(f.provider.entered)
                f.signal.cancel()
                assertTrue(f.signal.isCanceled)
                await(f.provider.cancelReceived)
                assertFalse(operation.isDone)
                assertTrue(f.provider.returned.count != 0L)
                f.provider.releaseOperation.countDown()
                operation.get(5, TimeUnit.SECONDS)
                await(f.provider.returned)
            }
        }
    }

    @Test fun providerCancellationCallbackCanItselfBlockItsCallingThread() =
        Fixture(cooperative = true).use { f ->
            f.provider.holdCancellation = true
            val operation = f.workers.submit { f.open(true) }
            await(f.provider.entered)
            val cancellation = f.workers.submit { f.signal.cancel() }
            await(f.provider.cancelReceived)
            // Therefore signal.cancel() cannot be synchronously added to the caller's deadline/stop path.
            assertFalse(cancellation.isDone)
            assertFalse(operation.isDone)
            f.provider.releaseCancellation.countDown()
            cancellation.get(5, TimeUnit.SECONDS)
            val failure = assertThrows(ExecutionException::class.java) { operation.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is OperationCanceledException)
        }

    private class Fixture(
        cooperative: Boolean,
    ) : AutoCloseable {
        private val context =
            ApplicationProvider.getApplicationContext<Context>().also {
                check(it.packageName == "com.helix.agent.developer.asrvalidation")
                check(it.applicationContext.javaClass == android.app.Application::class.java)
            }
        private val file = File(context.cacheDir, "saf-cancel-${UUID.randomUUID()}").apply { writeText("fixture") }
        val provider = CancellationProvider(file, cooperative)
        val signal = CancellationSignal()
        val workers = Executors.newFixedThreadPool(2)
        private val resolver: ContentResolver

        init {
            provider.attachInfo(
                context,
                ProviderInfo().apply {
                    authority = AUTHORITY
                    exported = false
                },
            )
            resolver = ContentResolver.wrap(provider)
        }

        fun open(asset: Boolean) {
            if (asset) {
                requireNotNull(resolver.openAssetFileDescriptor(URI, "r", signal)).use { descriptor ->
                    descriptor.createInputStream().use { check(it.read() == 'f'.code) }
                }
            } else {
                requireNotNull(resolver.query(URI, arrayOf("name"), null, null, null, signal)).use {
                    check(it.moveToFirst())
                }
            }
        }

        override fun close() {
            provider.releaseCancellation.countDown()
            provider.releaseOperation.countDown()
            workers.shutdown()
            check(
                workers.awaitTermination(10, TimeUnit.SECONDS),
            ) { "Cancellation fixture still running; file retained" }
            check(file.delete())
        }
    }

    private class CancellationProvider(
        private val file: File,
        private val cooperative: Boolean,
    ) : ContentProvider() {
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val cancelReceived = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        val releaseCancellation = CountDownLatch(1)
        var holdCancellation = false

        override fun onCreate(): Boolean = true

        private fun waitForRequest(signal: CancellationSignal?) {
            requireNotNull(signal).setOnCancelListener {
                cancelReceived.countDown()
                if (holdCancellation) await(releaseCancellation)
                if (cooperative) releaseOperation.countDown()
            }
            entered.countDown()
            try {
                await(releaseOperation)
                if (cooperative) signal.throwIfCanceled()
            } finally {
                returned.countDown()
            }
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
            cancellationSignal: CancellationSignal?,
        ): Cursor {
            waitForRequest(cancellationSignal)
            return MatrixCursor(arrayOf("name")).apply { addRow(arrayOf("fixture")) }
        }

        override fun openTypedAssetFile(
            uri: Uri,
            mimeTypeFilter: String,
            opts: Bundle?,
            signal: CancellationSignal?,
        ): AssetFileDescriptor = openAssetFile(uri, "r", signal)

        override fun openAssetFile(
            uri: Uri,
            mode: String,
            signal: CancellationSignal?,
        ): AssetFileDescriptor {
            waitForRequest(signal)
            return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, -1)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = error("This fixture requires the signal overload")

        override fun getType(uri: Uri): String = "text/plain"

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri = error("Read-only fixture")

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = error("Read-only fixture")

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = error("Read-only fixture")
    }

    private companion object {
        const val AUTHORITY = "com.helix.private.cancellation"
        val URI: Uri = Uri.parse("content://$AUTHORITY/fixture")

        fun await(latch: CountDownLatch) {
            check(latch.await(10, TimeUnit.SECONDS)) { "Cancellation fixture did not reach the expected state" }
        }
    }
}
