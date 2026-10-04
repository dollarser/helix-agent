package com.helix.extensions.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PluginDirectoryTest {
    private val reader = PluginPackageReader()
    private val manifest =
        """{"${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"sample"}"""
            .toByteArray()

    @Test fun directoryUsesTheSameContentIdentityAsPortableFiles() =
        fixture { root ->
            val expected = reader.parse(mapOf("plugin.json" to manifest)).contentHash
            assertEquals(expected, reader.readDirectory(root).contentHash)
        }

    @Test fun symlinkResourcesAreRejected() =
        fixture { root ->
            Files.createSymbolicLink(root.resolve("resource"), root.resolve("plugin.json"))
            assertThrows(IllegalArgumentException::class.java) { reader.readDirectory(root) }
        }

    @Test fun emptyMcpDirectoryIsReportedWithoutRejectingThePackage() =
        fixture { root ->
            Files.createDirectory(root.resolve("mcp.json"))
            assertTrue("PLUGIN_MCP_NOT_FILE" in reader.readDirectory(root).diagnostics)
        }

    @Test fun cancellationAndOversizedFilesFailClosed() =
        fixture { root ->
            assertThrows(IllegalStateException::class.java) { reader.readDirectory(root) { true } }
            Files.write(root.resolve("large"), ByteArray(PluginPackageReader.MAX_FILE_BYTES + 1))
            assertThrows(IllegalArgumentException::class.java) { reader.readDirectory(root) }
        }

    @Test fun missingManifestAndExcessiveDepthAreRejected() =
        fixture { root ->
            Files.delete(root.resolve("plugin.json"))
            assertThrows(IllegalArgumentException::class.java) { reader.readDirectory(root) }
            Files.write(root.resolve("plugin.json"), manifest)
            Files.createDirectories((1..33).fold(root) { path, _ -> path.resolve("nested") })
            assertThrows(IllegalArgumentException::class.java) { reader.readDirectory(root) }
        }

    private fun fixture(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("plugin-directory-test")
        try {
            Files.write(root.resolve("plugin.json"), manifest)
            test(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
