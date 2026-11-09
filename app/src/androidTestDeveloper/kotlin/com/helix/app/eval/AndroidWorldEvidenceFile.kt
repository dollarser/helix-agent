package com.helix.app.eval

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A torn write must leave the previous checkpoint intact, never a partial JSON document. */
internal object AndroidWorldEvidenceFile {
    fun publish(
        destination: File,
        text: String,
        limitBytes: Int? = null,
    ) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(limitBytes == null || bytes.size <= limitBytes) { "Evidence exceeds byte limit" }
        val parent = requireNotNull(destination.parentFile)
        check(parent.mkdirs() || parent.isDirectory)
        val temporary = File.createTempFile("${destination.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }
}
