package com.helix.app.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginInputTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun zip(
        path: String,
        text: String,
    ): ByteArray =
        ByteArrayOutputStream()
            .also { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }.toByteArray()

    @Test fun streamSnapshotParsesJsonRegardlessOfOriginalMimeOrSuffix() {
        val bytes = """{"mcpServers":{"synthetic":{"url":"https://example.invalid/mcp"}}}""".toByteArray()
        assertEquals("synthetic", previewPluginInput(bytes, temporary.root.toPath()).endpoints.single().name)
    }

    @Test fun unsafeOrInvalidZipDoesNotEscapeAndAlwaysRemovesItsTemporaryInput() {
        val cache = temporary.newFolder().toPath()
        listOf(zip("../escape", "synthetic"), byteArrayOf(80, 75, 0, 1)).forEach { bytes ->
            assertThrows(Exception::class.java) { previewPluginInput(bytes, cache) }
            assertEquals(0L, Files.list(cache).use { it.count() })
        }
    }
}
