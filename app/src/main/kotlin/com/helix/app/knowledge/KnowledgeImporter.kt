package com.helix.app.knowledge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal const val MAX_KNOWLEDGE_TEXT = 200_000
private const val MAX_FILE_BYTES = 16 * 1024 * 1024
private const val MAX_FILES = 8
private val originalExtensions =
    mapOf(
        "text/plain" to "txt",
        "text/html" to "html",
        "application/pdf" to "pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/gif" to "gif",
        "image/webp" to "webp",
    )

internal fun knowledgeOriginal(
    root: File,
    item: CollectedSource,
    index: Int,
): File {
    val metadata = JSONArray(item.filesJson).getJSONObject(index)
    val name = metadata.getString("stored")
    require(Regex("$index\\.[a-z0-9]{1,8}").matches(name))
    return File(File(root, item.id), name)
}

internal class KnowledgeImporter(
    private val context: Context,
    private val root: File,
    private val store: KnowledgeStore,
) {
    @Suppress("TooGenericExceptionCaught") // Persist an explicit incomplete result for failed materialization.
    suspend fun import(intent: Intent, source: String): CollectedSource {
        require(intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE))
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val uris = sharedUris(intent)
        require(text.length <= MAX_KNOWLEDGE_TEXT)
        require(uris.size <= MAX_FILES && (text.isNotBlank() || uris.isNotEmpty()))
        val item = newItem(intent, text, source)
        val directory = File(root, item.id)
        check(directory.mkdirs())
        store.insert(item)
        var current = item
        try {
            File(directory, "original.txt").writeText(text)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(text.toByteArray())
            val files = JSONArray()
            val useFileTitle = text.isBlank() && intent.getStringExtra(Intent.EXTRA_SUBJECT).isNullOrBlank()
            uris.forEachIndexed { index, uri ->
                files.put(copyFile(intent, uri, directory, index, digest))
                val title = if (useFileTitle) files.getJSONObject(0).getString("name").take(100) else current.title
                current = current.copy(filesJson = files.toString(), title = title)
                store.save(current)
            }
            val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
            current = finalizeImport(current, fingerprint, directory)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            current = current.copy(state = "INCOMPLETE", detail = "import-interrupted")
            store.save(current)
        }
        return current
    }

    private fun newItem(
        intent: Intent,
        text: String,
        source: String,
    ): CollectedSource {
        val title =
            intent.getStringExtra(Intent.EXTRA_SUBJECT)?.take(160)?.ifBlank { null }
                ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.take(100) ?: "Shared files"
        return CollectedSource(
            id = UUID.randomUUID().toString(),
            title = title,
            receivedAt = System.currentTimeMillis(),
            source = source.take(200),
            sourceUrl = Regex("https?://[^\\s<>]+").find(text)?.value.orEmpty(),
            rawText = text,
        )
    }

    private suspend fun finalizeImport(
        item: CollectedSource,
        fingerprint: String,
        directory: File,
    ): CollectedSource {
        val previous = store.duplicate(fingerprint)
        return if (previous != null) {
            store.remove(item.id)
            directory.deleteRecursively()
            previous
        } else {
            item.copy(state = "QUEUED", fingerprint = fingerprint).also { store.save(it) }
        }
    }

    private fun copyFile(
        intent: Intent,
        uri: Uri,
        directory: File,
        index: Int,
        digest: MessageDigest,
    ): JSONObject {
        require(uri.scheme == "content")
        // External callers cannot enlist our UID to read our own ungranted FileProvider paths.
        require(uri.authority?.substringAfterLast('@') != "${context.packageName}.fileprovider")
        val name =
            context.contentResolver
                .query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { if (it.moveToFirst()) it.getString(0)?.take(200) else null } ?: "Shared file ${index + 1}"
        val mime = context.contentResolver.getType(uri) ?: intent.type ?: "application/octet-stream"
        val part = File(directory, "$index.part")
        val fileDigest = MessageDigest.getInstance("SHA-256")
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            part.outputStream().use { output ->
                copyBounded(input, output, fileDigest)
            }
        }
        val stored = "$index.${originalExtensions[mime] ?: "bin"}"
        check(part.renameTo(File(directory, stored)))
        digest.update(fileDigest.digest())
        return JSONObject().put("name", name).put("mime", mime).put("stored", stored)
    }

    private fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        digest: MessageDigest,
    ) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_FILE_BYTES) { "file-too-large" }
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> {
        val streams =
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            } else {
                listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            }
        val clip = intent.clipData
        val clips = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
        return (streams + clips).distinct()
    }
}
