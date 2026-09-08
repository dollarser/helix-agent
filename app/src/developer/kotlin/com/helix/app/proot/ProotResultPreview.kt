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
    ): ProotRecoveredOutput {
        check(scratch.mkdirs() || scratch.isDirectory)
        val directory = Files.createTempDirectory(scratch.toPath(), "proot-preview-").toFile()
        try {
            val extracted = ZipJobExtractor.extract(archive, directory)
            val stdout = readStream(File(directory, "stdout.txt"))
            val stderr = readStream(File(directory, "stderr.txt"))
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

    private fun readStream(file: File): Pair<String, Boolean> {
        if (!file.isFile) return "" to false
        val text = StringBuilder()
        file.reader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            var count = reader.read(buffer, 0, minOf(buffer.size, MAX_STREAM_CHARACTERS + 1 - text.length))
            while (count != -1) {
                text.append(buffer, 0, count)
                if (text.length > MAX_STREAM_CHARACTERS) break
                count = reader.read(buffer, 0, minOf(buffer.size, MAX_STREAM_CHARACTERS + 1 - text.length))
            }
        }
        val truncated = text.length > MAX_STREAM_CHARACTERS
        var end = minOf(text.length, MAX_STREAM_CHARACTERS)
        if (truncated && text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end) to truncated
    }
}
