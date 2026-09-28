package com.helix.app.localmodel

import com.helix.provider.api.local.ModelAssetRef
import com.helix.provider.api.local.ModelAssetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.coroutineContext

/** Pure transfer/verification boundary shared by curated and advanced model installation. */
internal class LocalModelDownloader(
    private val store: ModelAssetStore,
    private val transfers: File,
    private val usableSpaceBytes: () -> Long,
    private val connectionFactory: (URI) -> HttpURLConnection,
) {
    private val transferLock = Mutex()

    init {
        check(transfers.mkdirs() || transfers.isDirectory)
    }

    suspend fun download(
        url: String,
        asset: ModelAssetRef,
        policy: LocalModelDownloadPolicy,
        onProgress: (LocalModelTransferProgress) -> Unit,
    ): ModelAssetRef =
        withContext(Dispatchers.IO) {
            transferLock.withLock {
                val source = URI(url)
                policy.validateInitial(source)
                store.requireCanPublish(asset)
                existingVerified(asset)?.let {
                    onProgress(ready(asset))
                    return@withLock it
                }
                val partial = preparePartial(asset)
                val offset = partial.length()
                require(
                    usableSpaceBytes() >=
                        LocalModelInstallSpace.additionalBytesRequired(asset.sizeBytes, offset),
                ) { "Insufficient app-private storage for model download and verification" }
                if (offset == asset.sizeBytes) {
                    try {
                        publishPartial(partial, asset, onProgress)
                        return@withLock asset
                    } catch (failure: IllegalArgumentException) {
                        partial.delete()
                        throw failure
                    }
                }
                downloadThenPublish(source, partial, asset, offset, policy, onProgress)
            }
        }

    private fun preparePartial(asset: ModelAssetRef): File {
        val partial = File(transfers, "${asset.sha256}.part")
        transfers
            .listFiles()
            .orEmpty()
            .filter { it != partial }
            .forEach { check(it.delete()) }
        require(
            !java.nio.file.Files
                .isSymbolicLink(partial.toPath()),
        )
        require(partial.length() <= asset.sizeBytes)
        return partial
    }

    private suspend fun downloadThenPublish(
        source: URI,
        partial: File,
        asset: ModelAssetRef,
        offset: Long,
        policy: LocalModelDownloadPolicy,
        onProgress: (LocalModelTransferProgress) -> Unit,
    ): ModelAssetRef {
        val connection = openTransfer(source, offset, policy)
        try {
            val resumed =
                LocalModelTransferProtocol.resumes(
                    connection.responseCode,
                    connection.getHeaderField("Content-Range"),
                    offset,
                    asset.sizeBytes,
                )
            copyTransfer(connection, partial, resumed, if (resumed) offset else 0L, asset.sizeBytes, onProgress)
            publishPartial(partial, asset, onProgress)
            return asset
        } catch (failure: IllegalArgumentException) {
            partial.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun existingVerified(asset: ModelAssetRef): ModelAssetRef? =
        store.list().singleOrNull { it.id == asset.id && it.sizeBytes == asset.sizeBytes }?.takeIf {
            runCatching { store.verifiedFile(it) }.isSuccess
        }

    private suspend fun publishPartial(
        partial: File,
        asset: ModelAssetRef,
        onProgress: (LocalModelTransferProgress) -> Unit,
    ) {
        onProgress(LocalModelTransferProgress(LocalModelTransferPhase.VERIFYING, asset.sizeBytes, asset.sizeBytes))
        val transferContext = coroutineContext
        partial.inputStream().use { input ->
            store.publish(asset.sha256, asset.sizeBytes, input) { transferContext.ensureActive() }
        }
        check(partial.delete())
        onProgress(ready(asset))
    }

    private fun openTransfer(
        source: URI,
        offset: Long,
        policy: LocalModelDownloadPolicy,
    ): HttpURLConnection {
        var current = source
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = connectionFactory(current)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            if (connection.responseCode !in REDIRECT_CODES) return connection
            val location = requireNotNull(connection.getHeaderField("Location")) { "Redirect without Location" }
            val target = current.resolve(location)
            connection.disconnect()
            require(redirectCount < MAX_REDIRECTS) { "Too many model source redirects" }
            policy.validateRedirect(target)
            current = target
        }
        error("unreachable redirect loop")
    }

    @Suppress("NestedBlockDepth")
    private suspend fun copyTransfer(
        connection: HttpURLConnection,
        partial: File,
        resumed: Boolean,
        start: Long,
        size: Long,
        onProgress: (LocalModelTransferProgress) -> Unit,
    ) {
        connection.inputStream.use { input ->
            java.io.FileOutputStream(partial, resumed).use { output ->
                val buffer = ByteArray(65_536)
                var count = start
                var nextProgress = start
                onProgress(LocalModelTransferProgress(LocalModelTransferPhase.DOWNLOADING, count, size))
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= size)
                    output.write(buffer, 0, read)
                    if (count >= nextProgress || count == size) {
                        onProgress(LocalModelTransferProgress(LocalModelTransferPhase.DOWNLOADING, count, size))
                        nextProgress = count + PROGRESS_STEP_BYTES
                    }
                }
                require(count == size)
                output.fd.sync()
            }
        }
    }

    private fun ready(asset: ModelAssetRef) =
        LocalModelTransferProgress(LocalModelTransferPhase.READY, asset.sizeBytes, asset.sizeBytes)

    private companion object {
        const val MAX_REDIRECTS = 4
        const val PROGRESS_STEP_BYTES = 4L * 1024 * 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
