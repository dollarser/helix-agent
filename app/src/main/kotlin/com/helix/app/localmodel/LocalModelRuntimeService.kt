package com.helix.app.localmodel

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Single native owner, no Room, credentials, Agent state, or network access initiated here. */
@Suppress("SwallowedException") // Closed result pipe means interrupted delivery, never a success receipt.
class LocalModelRuntimeService : Service() {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor()
    private var loaded = false
    private var active: String? = null
    private var output: ParcelFileDescriptor? = null
    private val binder =
        object : ILocalModelRuntime.Stub() {
            override fun load(
                asset: ParcelFileDescriptor,
                size: Long,
                sha256: String,
                contextTokens: Int,
                threads: Int,
            ): Int =
                synchronized(lock) {
                    asset.use {
                        checkCaller()
                        check(active == null)
                        require(contextTokens in 512..32768 && threads in 1..8)
                        require(size in 1..com.helix.provider.api.local.ModelAssetRef.MAX_ASSET_BYTES)
                        require(sha256.matches(Regex("[a-f0-9]{64}")))
                        // Hash the handed-off descriptor, not a re-opened path.
                        val digest = MessageDigest.getInstance("SHA-256")
                        var actual = 0L
                        val duplicate = ParcelFileDescriptor.dup(asset.fileDescriptor)
                        ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                actual += count
                                require(actual <= size)
                                digest.update(buffer, 0, count)
                            }
                        }
                        require(actual == size && digest.digest().joinToString("") { "%02x".format(it) } == sha256)
                        android.system.Os.lseek(asset.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
                        LlamaNative.unload()
                        loaded = false
                        val memory = android.app.ActivityManager.MemoryInfo()
                        getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
                        val budget = (memory.availMem - 256L * 1024 * 1024).coerceAtLeast(1)
                        val window = LlamaNative.load(asset.fd, contextTokens, threads, budget)
                        loaded = window > 0
                        window
                    }
                }

            override fun generate(
                generationId: String,
                request: ParcelFileDescriptor,
                result: ParcelFileDescriptor,
            ) {
                var handedOff = false
                try {
                    checkCaller()
                    synchronized(lock) {
                        check(loaded && active == null)
                        require(generationId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                        LlamaNative.prepare()
                        active = generationId
                        output = result
                        try {
                            worker.execute { runGeneration(request, result) }
                            handedOff = true
                        } finally {
                            if (!handedOff) {
                                active = null
                                output = null
                            }
                        }
                    }
                } finally {
                    if (!handedOff) {
                        try {
                            request.close()
                        } finally {
                            result.close()
                        }
                    }
                }
            }

            override fun cancel(generationId: String): Int {
                checkCaller()
                return synchronized(lock) {
                    if (active == null) {
                        2
                    } else {
                        check(active == generationId)
                        LlamaNative.cancel()
                        output?.close()
                        0
                    }
                }
            }

            override fun unload(): Boolean {
                checkCaller()
                return synchronized(lock) {
                    if (active != null) {
                        false
                    } else {
                        LlamaNative.unload()
                        loaded = false
                        true
                    }
                }
            }

            override fun shutdown() {
                checkCaller()
                Process.killProcess(Process.myPid())
            }
        }

    private fun runGeneration(
        request: ParcelFileDescriptor,
        result: ParcelFileDescriptor,
    ) {
        try {
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(request).use { it.readBounded() }
            val reply = LlamaNative.generate(bytes)
            require(reply.size <= MAX_MESSAGE_BYTES)
            ParcelFileDescriptor.AutoCloseOutputStream(result).use { it.write(reply) }
        } catch (_: java.io.IOException) {
            // Closed result pipe is client loss, not success.
            LlamaNative.cancel()
        } finally {
            try {
                request.close()
            } finally {
                try {
                    result.close()
                } finally {
                    synchronized(lock) {
                        active = null
                        output = null
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        LlamaNative.cancel()
        worker.shutdownNow()
        super.onDestroy()
        // A lost main-process binding must not leave an unobserved native executor or loaded weights.
        Process.killProcess(Process.myPid())
    }

    private fun checkCaller() {
        check(android.os.Binder.getCallingUid() == Process.myUid())
    }

    private fun java.io.InputStream.readBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_MESSAGE_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_MESSAGE_BYTES = 1024 * 1024
    }
}
