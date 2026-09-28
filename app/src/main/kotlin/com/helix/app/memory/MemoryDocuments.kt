package com.helix.app.memory

import android.content.ContentResolver
import android.net.Uri
import com.helix.core.workspace.memory.MemoryMarkdown

/** Only invoked for a document URI returned by an explicit system picker action. */
class MemoryDocuments(
    private val resolver: ContentResolver,
) {
    fun read(uri: Uri): String {
        val bytes =
            requireNotNull(resolver.openInputStream(uri)).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var count = input.read(buffer)
                while (count >= 0) {
                    require(output.size() + count <= MemoryMarkdown.MAX_BYTES) { "MEMORY_TOO_LARGE" }
                    output.write(buffer, 0, count)
                    count = input.read(buffer)
                }
                output.toByteArray()
            }
        val markdown =
            Charsets.UTF_8
                .newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        require(
            com.helix.app.chat.ForbiddenContentGuard
                .reasonFor(markdown) == null,
        ) { "MEMORY_SENSITIVE_CONTENT" }
        return markdown
    }

    fun write(
        uri: Uri,
        markdown: String,
    ) {
        require(markdown.toByteArray(Charsets.UTF_8).size <= MemoryMarkdown.MAX_BYTES)
        require(
            com.helix.app.chat.ForbiddenContentGuard
                .reasonFor(markdown) == null,
        ) { "MEMORY_SENSITIVE_CONTENT" }
        requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
    }
}
