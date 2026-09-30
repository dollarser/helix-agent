package com.helix.app.localmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ModelMetadata
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.local.LoadedLocalModel
import com.helix.provider.api.local.LocalCancelResult
import com.helix.provider.api.local.LocalGenerationRequest
import com.helix.provider.api.local.LocalInferenceRuntimePort
import com.helix.provider.api.local.LocalModelInspection
import com.helix.provider.api.local.LocalModelLoadRequest
import com.helix.provider.api.local.LocalRuntimeException
import com.helix.provider.api.local.LocalRuntimeStatus
import com.helix.provider.api.local.LocalUnloadResult
import com.helix.provider.api.local.ModelAssetRef
import com.helix.provider.api.local.ModelAssetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Main-process supervisor. One loaded model and one generation; death invalidates every handle. */
@Suppress("SwallowedException", "TooManyFunctions") // Explicit port phases and cleanup share one generation owner.
class LocalInferenceRuntimeClient(
    private val context: Context,
    private val store: ModelAssetStore,
) : LocalInferenceRuntimePort {
    private val ownership = Mutex()
    private val io = LocalRuntimeCalls("helix-model-io")
    private val controls = LocalRuntimeCalls("helix-model-control")
    private val termination = LocalRuntimeCalls("helix-model-shutdown", 1)
    private val exited = java.util.Collections.synchronizedSet(linkedSetOf<String>())

    @Volatile private var remote: ILocalModelRuntime? = null

    @Volatile private var binding: ServiceConnection? = null

    @Volatile private var death = CompletableDeferred<Unit>()

    @Volatile private var loaded: LoadedLocalModel? = null

    @Volatile private var active: String? = null
    private var modelWindow: Long? = null

    @Suppress("TooGenericExceptionCaught") // Every failed bind must release its ServiceConnection before propagation.
    private suspend fun connect(): ILocalModelRuntime {
        remote?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        loaded = null
        modelWindow = null
        val ready = CompletableDeferred<ILocalModelRuntime>()
        val instanceDeath = CompletableDeferred<Unit>()
        death = instanceDeath
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    binder: IBinder,
                ) {
                    try {
                        binder.linkToDeath({ instanceDeath.complete(Unit) }, 0)
                    } catch (failure: android.os.RemoteException) {
                        instanceDeath.complete(Unit)
                        ready.completeExceptionally(LocalRuntimeException(ModelErrorCode.LOCAL_RUNTIME_CRASHED))
                        return
                    }
                    ready.complete(ILocalModelRuntime.Stub.asInterface(binder))
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    instanceDeath.complete(Unit)
                }

                override fun onBindingDied(name: ComponentName) {
                    ready.completeExceptionally(LocalRuntimeException(ModelErrorCode.LOCAL_RUNTIME_CRASHED))
                }

                override fun onNullBinding(name: ComponentName) {
                    ready.completeExceptionally(LocalRuntimeException(ModelErrorCode.LOCAL_RUNTIME_CRASHED))
                }
            }
        binding?.let(context::unbindService)
        binding = connection
        val intent = Intent(context, LocalModelRuntimeService::class.java)
        return try {
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                throw LocalRuntimeException(ModelErrorCode.LOCAL_RUNTIME_CRASHED)
            }
            withTimeout(10000) { ready.await() }.also { remote = it }
        } catch (failure: Exception) {
            runCatching { context.unbindService(connection) }.exceptionOrNull()?.let(failure::addSuppressed)
            if (binding === connection) binding = null
            throw failure
        }
    }

    override suspend fun inspect(asset: ModelAssetRef): LocalModelInspection {
        load(loaded?.request?.takeIf { it.asset == asset } ?: LocalModelLoadRequest(asset, 2048, 2))
        return LocalModelInspection(
            asset,
            ModelMetadata(vision = false, contextWindow = modelWindow),
            ProviderCapabilities(false, false, false, false, false, false, modelWindow, CapabilitySource.MANUAL),
        )
    }

    override suspend fun load(request: LocalModelLoadRequest): LoadedLocalModel =
        ownership.withLock {
            if (remote?.asBinder()?.isBinderAlive == false) {
                loaded = null
                active = null
                modelWindow = null
            }
            loaded
                ?.takeIf { it.request == request && remote?.asBinder()?.isBinderAlive == true }
                ?.let { return@withLock it }
            check(active == null)
            val service = connect()
            val file =
                try {
                    withContext(Dispatchers.IO) {
                        store.verifiedFile(request.asset) { coroutineContext.ensureActive() }
                    }
                } catch (failure: IllegalArgumentException) {
                    throw LocalRuntimeException(ModelErrorCode.MODEL_ASSET_INVALID)
                } catch (failure: java.io.IOException) {
                    throw LocalRuntimeException(ModelErrorCode.MODEL_ASSET_INVALID)
                }
            val window =
                try {
                    io.call {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                            service.load(
                                it,
                                request.asset.sizeBytes,
                                request.asset.sha256,
                                request.contextTokens,
                                request.threads,
                            )
                        }
                    }
                } catch (failure: CancellationException) {
                    withContext(NonCancellable) { terminate() }
                    throw failure
                } catch (failure: android.os.RemoteException) {
                    loaded = null
                    throw LocalRuntimeException(ModelErrorCode.LOCAL_RUNTIME_CRASHED)
                }
            if (window <= 0) {
                loaded = null
                throw LocalRuntimeException(
                    if (window ==
                        -2
                    ) {
                        ModelErrorCode.LOCAL_RUNTIME_OOM
                    } else {
                        ModelErrorCode.MODEL_LOAD_FAILED
                    },
                )
            }
            modelWindow = minOf(window.toLong(), request.contextTokens.toLong())
            LoadedLocalModel(UUID.randomUUID().toString(), request).also { loaded = it }
        }

    override fun generate(request: LocalGenerationRequest) =
        flow {
            ownership.withLock {
                check(loaded?.handle == request.modelHandle && active == null)
                val service =
                    remote?.takeIf { it.asBinder().isBinderAlive }
                        ?: throw LocalRuntimeException(ModelErrorCode.LOCAL_RUNTIME_CRASHED)
                val bytes = LocalRuntimeCodec.encode(request.request)
                val directory = File(context.cacheDir, "model-requests").also { check(it.mkdirs() || it.isDirectory) }
                val input = File.createTempFile("request-", ".tmp", directory)
                val pipe = ParcelFileDescriptor.createPipe()
                active = request.generationId
                var forcedExit = false
                var submissionAcknowledged = false
                try {
                    withContext(Dispatchers.IO) { input.writeBytes(bytes) }
                    io.call {
                        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
                            service.generate(request.generationId, it, pipe[1])
                        }
                    }
                    submissionAcknowledged = true
                    pipe[1].close()
                    val reply =
                        io.call(onCancel = { runCatching { pipe[0].close() } }) {
                            readReply(pipe[0])
                        }
                    if (!service.asBinder().isBinderAlive) {
                        throw LocalRuntimeException(ModelErrorCode.LOCAL_RUNTIME_CRASHED)
                    }
                    LocalRuntimeCodec.decode(reply).forEach { emit(it) }
                } catch (failure: android.os.RemoteException) {
                    throw LocalRuntimeException(ModelErrorCode.LOCAL_RUNTIME_CRASHED)
                } finally {
                    forcedExit = finishGeneration(request.generationId, submissionAcknowledged, pipe, input)
                }
                if (forcedExit) throw LocalRuntimeException(ModelErrorCode.LOCAL_CANCEL_TIMEOUT)
            }
        }

    private suspend fun finishGeneration(
        generationId: String,
        acknowledged: Boolean,
        pipe: Array<ParcelFileDescriptor>,
        input: File,
    ): Boolean {
        var confirmed = false
        try {
            val forced = withContext(NonCancellable) { confirmGenerationExit(generationId, acknowledged) }
            confirmed = true
            return forced
        } finally {
            // A failed termination retains active ownership but never leaks local resources.
            if (confirmed) {
                synchronized(exited) {
                    exited.add(generationId)
                    if (exited.size > 32) exited.remove(exited.first())
                }
                active = null
            }
            pipe.forEach { runCatching { it.close() } }
            input.delete()
        }
    }

    private suspend fun confirmGenerationExit(
        generationId: String,
        acknowledged: Boolean,
    ): Boolean =
        try {
            if (acknowledged) {
                cancel(generationId)
                false
            } else {
                // A queued generate may start after an early cancel reports no active worker.
                terminate()
                true
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            terminate()
            true
        } catch (_: LocalRuntimeException) {
            terminate()
            true
        }

    // Exit proofs: recorded completion, absent process, dead Binder, or native acknowledgement.
    @Suppress("ReturnCount")
    override suspend fun cancel(generationId: String): LocalCancelResult {
        if (generationId in exited) return LocalCancelResult.EXITED
        val service = remote ?: return LocalCancelResult.EXITED
        if (!service.asBinder().isBinderAlive) return LocalCancelResult.EXITED
        return try {
            withTimeout(4000) {
                while (controls.call { service.cancel(generationId) } != 2) delay(20)
                LocalCancelResult.EXITED
            }
        } catch (failure: android.os.RemoteException) {
            if (service.asBinder().isBinderAlive) {
                throw LocalRuntimeException(ModelErrorCode.LOCAL_CANCEL_TIMEOUT)
            }
            LocalCancelResult.EXITED
        }
    }

    override suspend fun unload(modelHandle: String): LocalUnloadResult {
        if (!ownership.tryLock()) return LocalUnloadResult.BUSY
        return try {
            val matching = loaded?.handle == modelHandle
            val service = remote
            if (matching && withTimeout(4000) { controls.call { service?.unload() } } == false) {
                LocalUnloadResult.BUSY
            } else {
                if (matching) loaded = null
                LocalUnloadResult.UNLOADED
            }
        } finally {
            ownership.unlock()
        }
    }

    suspend fun deleteAsset(asset: ModelAssetRef) {
        check(ownership.tryLock()) { "Model is in use" }
        try {
            check(active == null) { "Model is in use" }
            if (loaded?.request?.asset == asset) {
                val service = remote?.takeIf { it.asBinder().isBinderAlive }
                check(withTimeout(4000) { controls.call { service?.unload() } } != false) { "Model is in use" }
                loaded = null
            }
            withContext(Dispatchers.IO) { store.delete(asset) }
        } finally {
            ownership.unlock()
        }
    }

    override suspend fun runtimeStatus(): LocalRuntimeStatus =
        if (remote?.asBinder()?.isBinderAlive == true) {
            LocalRuntimeStatus(loaded, active)
        } else {
            LocalRuntimeStatus(null, null)
        }

    override suspend fun terminate() {
        val service = remote
        val originalDeath = death
        val originalBinding = binding
        if (service != null && service.asBinder().isBinderAlive) {
            try {
                // Keep this lane independent of blocked load/generate/read calls.
                withTimeout(4000) { termination.call { service.shutdown() } }
            } catch (failure: android.os.RemoteException) {
                if (service.asBinder().isBinderAlive) {
                    throw LocalRuntimeException(ModelErrorCode.LOCAL_CANCEL_TIMEOUT)
                }
            }
            val exited =
                kotlinx.coroutines.withTimeoutOrNull(5000) {
                    originalDeath.await()
                    true
                } ?: false
            if (!exited && service.asBinder().isBinderAlive) {
                throw LocalRuntimeException(ModelErrorCode.LOCAL_CANCEL_TIMEOUT)
            }
        }
        // A stale stop completion cannot clear a newer binding or loaded handle.
        if (remote === service && binding === originalBinding) {
            originalBinding?.let { runCatching { context.unbindService(it) } }
            binding = null
            remote = null
            loaded = null
            modelWindow = null
            active = null
        }
    }
}
