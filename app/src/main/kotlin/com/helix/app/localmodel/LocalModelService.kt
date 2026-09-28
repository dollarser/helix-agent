package com.helix.app.localmodel

import android.content.Context
import android.os.storage.StorageManager
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

private fun allocatableBytes(context: Context): Long =
    runCatching {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir))
    }.getOrDefault(0L)

/** Explicit user-operated asset management; no automatic model downloads. */
class LocalModelService(
    context: Context,
    private val storage: HelixStorage,
    private val usableSpaceBytes: () -> Long = { allocatableBytes(context) },
    private val connectionFactory: (URI) -> HttpURLConnection = { uri ->
        uri.toURL().openConnection() as HttpURLConnection
    },
    private val contextTokensFor: (ProviderConfig) -> Int = { 4096 },
) {
    private val store = ModelAssetStore(File(context.filesDir, "models"))
    private val runtime = LocalInferenceRuntimeClient(context, store)
    private val downloader =
        LocalModelDownloader(
            store = store,
            transfers = File(context.cacheDir, "model-transfers"),
            usableSpaceBytes = usableSpaceBytes,
            connectionFactory = connectionFactory,
        )

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
        onProgress: (LocalModelTransferProgress) -> Unit = {},
    ): String = downloadAsset(url, hash, size, name, LocalModelDownloadPolicy(), onProgress)

    suspend fun downloadCatalog(
        entryId: String,
        source: LocalModelCatalogSource,
        onProgress: (LocalModelTransferProgress) -> Unit = {},
    ): String {
        val entry = LocalModelCatalog.entry(entryId)
        val location = entry.location(source)
        return downloadAsset(
            location.downloadUrl,
            entry.sha256,
            entry.sizeBytes,
            entry.displayName,
            LocalModelDownloadPolicy(redirectHostSuffixes = location.redirectHostSuffixes),
            onProgress,
        )
    }

    /** AndroidTest-only real socket path; production callers cannot opt into cleartext. */
    internal suspend fun downloadForTest(
        url: String,
        hash: String,
        size: Long,
        name: String,
        onProgress: (LocalModelTransferProgress) -> Unit = {},
    ): String = downloadAsset(url, hash, size, name, LocalModelDownloadPolicy(allowHttp = true), onProgress)

    private suspend fun downloadAsset(
        url: String,
        hash: String,
        size: Long,
        name: String,
        policy: LocalModelDownloadPolicy,
        onProgress: (LocalModelTransferProgress) -> Unit,
    ): String {
        require(name.isNotBlank() && name.length <= 128)
        val asset = ModelAssetRef(hash, hash, size)
        val verified = downloader.download(url, asset, policy, onProgress)
        register(verified, name)
        return verified.id
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
