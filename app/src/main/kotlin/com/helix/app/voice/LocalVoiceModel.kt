package com.helix.app.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Fixed public model bytes only. No speech text, credentials, user URLs, archive paths or cloud synthesis. */
class LocalVoiceModel(
    private val context: Context,
    private val root: File = File(context.filesDir, "tts"),
    private val connectionFactory: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    partSource: List<Part>? = null,
) {
    data class Part(
        val name: String,
        val url: String,
        val size: Long,
        val sha256: String,
    )

    val parts: List<Part> =
        partSource ?: context.assets.open("tts-model-lock.json").bufferedReader().use {
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
            val directory = File(root, "melo-int8")
            directory.takeIf { parts.all { matches(File(directory, it.name), it) } }
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
            var complete = 0L
            val deadline = android.os.SystemClock.elapsedRealtime() + 10 * 60_000
            try {
                parts.forEach { part ->
                    currentCoroutineContext().ensureActive()
                    val call = open(URI(part.url))
                    try {
                        check(call.responseCode == 200) { "MODEL_DOWNLOAD_FAILED" }
                        val target = File(stage, part.name)
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
                    complete += part.size
                }
                currentCoroutineContext().ensureActive()
                val destination = File(root, "melo-int8")
                check(!destination.exists() || destination.deleteRecursively())
                check(stage.renameTo(destination))
            } finally {
                activeCall.getAndSet(null)?.disconnect()
                stage.deleteRecursively()
                downloadedBytes.value = null
            }
        }

    private fun open(source: URI): HttpURLConnection {
        policy.validateRedirect(source)
        var current = source
        repeat(6) {
            val connection = connectionFactory(current)
            activeCall.set(connection)
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
