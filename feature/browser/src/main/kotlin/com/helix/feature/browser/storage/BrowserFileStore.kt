package com.helix.feature.browser.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Same-directory atomic replacement; failure never falls back to overwriting the old file. */
internal class BrowserFileStore(
    private val replace: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    },
) {
    fun write(
        file: File,
        content: String,
    ) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Browser data exceeds storage limit" }
        val temporary = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            replace(temporary.toPath(), file.toPath())
        } finally {
            temporary.delete()
        }
    }

    fun read(file: File): String? {
        if (!file.exists()) return null
        return file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var count = input.read(buffer)
            while (count >= 0) {
                require(output.size() + count <= MAX_BYTES) { "Browser data exceeds storage limit" }
                output.write(buffer, 0, count)
                count = input.read(buffer)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}
