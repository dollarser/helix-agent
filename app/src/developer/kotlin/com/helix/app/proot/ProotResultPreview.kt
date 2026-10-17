package com.helix.app.proot

import com.helix.runtime.proot.core.ZipJobExtractor
import java.io.File
import java.nio.file.Files

/** Strict archive validation precedes bounded display; extracted content is never executed or imported. */
internal object ProotResultPreview {
    internal const val MAX_STREAM_CHARACTERS = 65536

    fun read(
        archive: File,
        scratch: File,
        maxStreamCharacters: Int = MAX_STREAM_CHARACTERS,
    ): ProotRecoveredOutput {
        require(maxStreamCharacters in 1..MAX_STREAM_CHARACTERS)
        check(scratch.mkdirs() || scratch.isDirectory)
        val directory = Files.createTempDirectory(scratch.toPath(), "proot-preview-").toFile()
        try {
            val extracted = ZipJobExtractor.extract(archive, directory)
            val stdout = readStream(File(directory, "stdout.txt"), maxStreamCharacters)
            val stderr = readStream(File(directory, "stderr.txt"), maxStreamCharacters)
            val files = extracted.manifest.entries.filter { it.path !in setOf("stdout.txt", "stderr.txt") }
            return ProotRecoveredOutput(
                stdout.first,
                stderr.first,
                stdout.second || stderr.second,
                files.map { ProotRecoveredFile(it.path, it.size, it.sha256) },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun readStream(
        file: File,
        limit: Int,
    ): Pair<String, Boolean> {
        if (!file.isFile) return "" to false
        val text = StringBuilder()
        file.reader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            var count = reader.read(buffer, 0, minOf(buffer.size, limit + 1 - text.length))
            while (count != -1) {
                text.append(buffer, 0, count)
                if (text.length > limit) break
                count = reader.read(buffer, 0, minOf(buffer.size, limit + 1 - text.length))
            }
        }
        val truncated = text.length > limit
        var end = minOf(text.length, limit)
        if (truncated && text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end) to truncated
    }
}
