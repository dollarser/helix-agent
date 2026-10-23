package com.helix.app.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Fixed public model bytes only. No speech text, credentials, user URLs, archive paths or cloud synthesis. */
@Suppress("TooManyFunctions") // One fixed-model owner keeps transfer, verification and installation policy together.
class LocalSpeechModel(
    private val context: Context,
    private val root: File = File(context.filesDir, "tts"),
    private val connectionFactory: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    partSource: List<Part>? = null,
    private val kind: Kind = Kind.TTS,
) {
    enum class Kind(
        val lockAsset: String,
        val installation: String,
    ) {
        TTS("tts-model-lock.json", "melo-int8"),
        ASR("asr-model-lock.json", "zipformer-int8"),
    }

    companion object {
        private val asrUse = Mutex()

        fun asr(context: Context) = LocalSpeechModel(context, File(context.filesDir, "asr"), kind = Kind.ASR)
    }

    data class Part(
        val name: String,
        val url: String,
        val size: Long,
        val sha256: String,
    )

    val parts: List<Part> =
        partSource ?: context.assets.open(kind.lockAsset).bufferedReader().use {
            val files = JSONObject(it.readText()).getJSONObject("files")
            files
                .keys()
                .asSequence()
                .map { name ->
                    require(name.matches(Regex("[A-Za-z0-9_.-]+")) && !name.startsWith('.'))
                    val row = files.getJSONObject(name)
                    Part(name, row.getString("url"), row.getLong("size"), row.getString("sha256"))
                }.toList()
        }
    val totalBytes = parts.sumOf { it.size }
    val downloadedBytes = MutableStateFlow<Long?>(null)
    private val mutableRecoveryAttempts = MutableStateFlow(0)
    val recoveryAttempts = mutableRecoveryAttempts.asStateFlow()

    init {
        require(parts.size in 1..12 && parts.map { it.name }.distinct().size == parts.size)
        require(
            parts.all {
                it.name.matches(Regex("[A-Za-z0-9_.-]+")) && !it.name.startsWith('.') &&
                    it.size in 1..80_000_000 && it.sha256.matches(Regex("[a-f0-9]{64}"))
            },
        )
    }

    private val activeCall = AtomicReference<HttpURLConnection?>(null)
    private val policy =
        com.helix.app.localmodel.LocalModelDownloadPolicy(
            redirectHostSuffixes = setOf("huggingface.co", "hf.co"),
        )

    suspend fun ready(): File? =
        withContext(Dispatchers.IO) {
            val directory = File(root, kind.installation)
            directory.takeIf { parts.all { matches(File(directory, it.name), it) } }
        }

    /** Only locked model parts; never follows links or removes unrelated application files. */
    suspend fun installedBytes(): Long =
        withContext(Dispatchers.IO) {
            val directory = File(root, kind.installation)
            parts.sumOf { part -> File(directory, part.name).takeIf { it.isFile }?.length() ?: 0L }
        }

    suspend fun removeInstalled() =
        withContext(Dispatchers.IO) {
            withInstallationLease {
                val directory = File(root, kind.installation)
                require(directory.canonicalFile == File(root.canonicalFile, kind.installation))
                parts.forEach { part ->
                    val file = File(directory, part.name)
                    require(file.canonicalFile == File(directory.canonicalFile, part.name))
                    if (file.exists()) check(file.delete()) { "MODEL_REMOVE_FAILED" }
                }
                if (directory.isDirectory && directory.list().orEmpty().isEmpty()) {
                    check(directory.delete()) { "MODEL_REMOVE_FAILED" }
                }
            }
        }

    internal suspend fun acquireAsrUse(): AutoCloseable {
        require(kind == Kind.ASR)
        val owner = Any()
        asrUse.lock(owner)
        return AutoCloseable { asrUse.unlock(owner) }
    }

    private suspend fun <T> withInstallationLease(action: suspend () -> T): T =
        if (kind == Kind.ASR) {
            asrUse.withLock {
                currentCoroutineContext().ensureActive()
                action()
            }
        } else {
            action()
        }

    fun cancelDownload() {
        activeCall.getAndSet(null)?.disconnect()
    }

    @Suppress("NestedBlockDepth") // Bounded file transaction: response, input and private staging output own lifetimes.
    suspend fun download() =
        withContext(Dispatchers.IO) {
            root.mkdirs()
            root
                .listFiles()
                .orEmpty()
                .filter {
                    it.name.matches(Regex("\\.download-[a-f0-9-]{36}")) &&
                        it.canonicalFile.parentFile == root.canonicalFile
                }.forEach { check(it.deleteRecursively()) }
            val stage = File(root, ".download-${UUID.randomUUID()}")
            check(stage.mkdir())
            downloadedBytes.value = 0
            mutableRecoveryAttempts.value = 0
            var complete = 0L
            val deadline = android.os.SystemClock.elapsedRealtime() + 10 * 60_000
            try {
                parts.forEach { part ->
                    currentCoroutineContext().ensureActive()
                    downloadPart(part, File(stage, part.name), complete, deadline)
                    complete += part.size
                }
                currentCoroutineContext().ensureActive()
                withInstallationLease {
                    val destination = File(root, kind.installation)
                    if (!destination.exists()) {
                        check(stage.renameTo(destination))
                    } else {
                        require(destination.canonicalFile == File(root.canonicalFile, kind.installation))
                        // Replace only verified lock entries; unknown files are never part of model cleanup.
                        parts.forEach { part ->
                            val target = File(destination, part.name)
                            require(target.canonicalFile == File(destination.canonicalFile, part.name))
                            check(File(stage, part.name).renameTo(target)) { "MODEL_INSTALL_FAILED" }
                        }
                    }
                }
            } finally {
                activeCall.getAndSet(null)?.disconnect()
                stage.deleteRecursively()
                downloadedBytes.value = null
            }
        }

    @Suppress("NestedBlockDepth") // One bounded transfer owns response/input/output cleanup.
    private suspend fun downloadPart(part: Part, target: File, complete: Long, deadline: Long) {
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                val call = open(URI(part.url))
                try {
                    check(call.responseCode == 200) { "MODEL_DOWNLOAD_FAILED" }
                    call.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            var count = input.read(buffer)
                            while (count != -1) {
                                currentCoroutineContext().ensureActive()
                                check(
                                    android.os.SystemClock.elapsedRealtime() <= deadline,
                                ) { "MODEL_DOWNLOAD_TIMEOUT" }
                                copied += count
                                check(copied <= part.size) { "MODEL_SIZE_LIMIT" }
                                output.write(buffer, 0, count)
                                downloadedBytes.value = complete + copied
                                count = input.read(buffer)
                            }
                        }
                    }
                    check(matches(target, part)) { "MODEL_INTEGRITY_FAILED" }
                } finally {
                    activeCall.compareAndSet(call, null)
                    call.disconnect()
                }
                return
            } catch (failure: java.io.IOException) {
                activeCall.getAndSet(null)?.disconnect()
                currentCoroutineContext().ensureActive()
                if (!transientDownloadFailure(failure) || attempt != 0 ||
                    android.os.SystemClock.elapsedRealtime() >= deadline
                ) {
                    throw failure
                }
                downloadedBytes.value = complete
                mutableRecoveryAttempts.value += 1
                kotlinx.coroutines.delay(300L + kotlin.random.Random.nextLong(300L))
            }
        }
    }

    private fun transientDownloadFailure(failure: java.io.IOException) =
        failure is java.net.SocketException || failure is java.net.SocketTimeoutException ||
            failure is java.net.UnknownHostException

    private suspend fun open(source: URI): HttpURLConnection {
        policy.validateRedirect(source)
        var current = source
        repeat(6) {
            val connection = connectionFactory(current)
            activeCall.set(connection)
            currentCoroutineContext().ensureActive()
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) return connection
            val target = current.resolve(requireNotNull(connection.getHeaderField("Location")))
            connection.disconnect()
            policy.validateRedirect(target)
            current = target
        }
        error("MODEL_REDIRECT_LIMIT")
    }

    private fun matches(
        file: File,
        part: Part,
    ): Boolean {
        if (!file.isFile || file.length() != part.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var count = input.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == part.sha256
    }
}
