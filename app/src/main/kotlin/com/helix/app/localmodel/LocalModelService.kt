package com.helix.app.localmodel

import android.content.Context
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.local.LocalModelLoadRequest
import com.helix.provider.api.local.LocalModelProvider
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

/** Explicit user-operated asset management; no automatic model downloads. */
class LocalModelService(
    context: Context,
    private val storage: HelixStorage,
    private val contextTokensFor: (ProviderConfig) -> Int = { 4096 },
) {
    private val store = ModelAssetStore(File(context.filesDir, "models"))
    private val runtime = LocalInferenceRuntimeClient(context, store)
    private val transfers = File(context.cacheDir, "model-transfers").also { check(it.mkdirs() || it.isDirectory) }
    private val transferLock = Mutex()

    init {
        val known =
            storage.providerConfigs
                .list()
                .map { it.id }
                .toSet()
        store.list().filter { it.id !in known }.forEach { asset ->
            register(asset, context.getString(com.helix.app.R.string.local_model_recovered, asset.id.take(8)))
        }
    }

    fun provider(config: ProviderConfig): LocalModelProvider? {
        if (config.provisioning != ProviderProvisioningKind.ON_DEVICE_ASSET) return null
        val asset =
            store.list().singleOrNull { it.id == config.model }
                ?: throw com.helix.provider.api.local.LocalRuntimeException(
                    com.helix.core.model.ModelErrorCode.MODEL_ASSET_INVALID,
                )
        return LocalModelProvider(config, asset, runtime, LocalModelLoadRequest(asset, contextTokensFor(config), 2))
    }

    suspend fun download(
        url: String,
        hash: String,
        size: Long,
        name: String,
    ) = withContext(Dispatchers.IO) {
        transferLock.withLock {
            val asset = ModelAssetRef(hash, hash, size)
            require(name.isNotBlank() && name.length <= 128)
            val source = URI(url)
            require(
                source.scheme == "https" && source.host != null && source.userInfo == null && source.fragment == null,
            )
            val partial = File(transfers, "$hash.part")
            // Only one pending transfer is retained; stale partials cannot accumulate beyond one asset quota.
            transfers
                .listFiles()
                .orEmpty()
                .filter { it != partial }
                .forEach { check(it.delete()) }
            require(
                !java.nio.file.Files
                    .isSymbolicLink(partial.toPath()),
            )
            require(partial.length() <= size)
            if (partial.length() == size) check(partial.delete())
            val connection = source.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            val offset = partial.length()
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            try {
                val code = connection.responseCode
                val resumed =
                    LocalModelTransferProtocol.resumes(
                        code,
                        connection.getHeaderField("Content-Range"),
                        offset,
                        size,
                    )
                val start = if (resumed) offset else 0L
                copyTransfer(connection, partial, resumed, start, size)
                partial.inputStream().use { input ->
                    store.publish(hash, size, input) { coroutineContext.ensureActive() }
                }
                check(partial.delete())
                register(asset, name)
            } catch (failure: IllegalArgumentException) {
                partial.delete()
                throw failure
            } finally {
                connection.disconnect()
            }
        }
    }

    @Suppress("NestedBlockDepth") // Two closeable resources enclose one bounded, cancellable copy loop.
    private suspend fun copyTransfer(
        connection: HttpURLConnection,
        partial: File,
        resumed: Boolean,
        start: Long,
        size: Long,
    ) {
        connection.inputStream.use { input ->
            java.io.FileOutputStream(partial, resumed).use { output ->
                val buffer = ByteArray(65536)
                var count = start
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= size)
                    output.write(buffer, 0, read)
                }
                require(count == size)
                output.fd.sync()
            }
        }
    }

    private fun register(
        asset: ModelAssetRef,
        name: String,
    ) {
        storage.providerConfigs.overwrite(
            ProviderConfigSpec(
                id = asset.id,
                displayName = name,
                protocol = null,
                endpoint = null,
                model = asset.id,
                headersJson = "{}",
                secretAlias = null,
                capabilitySnapshot =
                    ProviderCapabilities.toJsonString(
                        ProviderCapabilities(false, false, false, false, false, false, null, CapabilitySource.MANUAL),
                    ),
                provisioningKind = "ON_DEVICE_ASSET",
                transportKind = "ON_DEVICE_LOCAL",
                authKind = "NONE",
            ),
        )
    }

    fun assetSize(model: String): Long? = store.list().firstOrNull { it.id == model }?.sizeBytes

    suspend fun unload(model: String) {
        val status = runtime.runtimeStatus()
        val loaded = status.loaded?.takeIf { it.request.asset.id == model } ?: return
        check(status.activeGenerationId == null) { "Model is in use" }
        check(runtime.unload(loaded.handle) == com.helix.provider.api.local.LocalUnloadResult.UNLOADED)
    }

    suspend fun delete(model: String) {
        val asset =
            store.list().singleOrNull { it.id == model }
                ?: runtime
                    .runtimeStatus()
                    .loaded
                    ?.request
                    ?.asset
                    ?.takeIf { it.id == model }
                ?: return
        runtime.deleteAsset(asset)
    }
}
