package com.helix.app.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MarketplaceSourceStoreTest {
    private fun archive(version: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val files =
                mapOf(
                    "repo/.codex-plugin/marketplace.json" to """{"name":"fixtures","plugins":[
                    {"name":"review","source":"./plugins/review"}]}""",
                    "repo/plugins/review/.codex-plugin/plugin.json" to """{"name":"review","version":"$version"}""",
                    "repo/plugins/review/.mcp.json" to """{"mcpServers":{"docs":{
                    "url":"https://example.invalid/mcp","headers":{"Authorization":"PRIVATE"}}}}""",
                    "repo/plugins/review/skills/guide/SKILL.md" to
                        "---\nname: guide\ndescription: Review the fixture\n---\nCheck results.",
                )
            files.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun fixture(test: (MarketplaceSourceStore, java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("marketplace-store-")
        try {
            test(MarketplaceSourceStore(root), root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun previewDoesNotRegisterAndCommittedSnapshotSurvivesReopen() =
        fixture { store, root ->
            val preview = store.preview(archive("1.0"))
            assertTrue(store.list().isEmpty())
            val added = store.add(preview)
            assertEquals(added.contentHash, store.add(preview).contentHash)
            assertEquals(
                "1.0",
                MarketplaceSourceStore(root)
                    .list()
                    .single()
                    .entries
                    .single()
                    .version,
            )
            store.remove(added.identity, added.contentHash)
            assertTrue(store.list().isEmpty())
        }

    @Test fun cancelledPublicationLeavesPreviousCatalogAndNoStagingFiles() =
        fixture { store, root ->
            val old = store.add(store.preview(archive("1.0")))
            val replacement = store.preview(archive("2.0"))
            var checks = 0
            assertThrows(IllegalStateException::class.java) { store.add(replacement) { ++checks == 2 } }
            assertEquals(old.contentHash, store.list().single().contentHash)
            Files.list(root).use { paths -> assertEquals(1L, paths.count()) }
        }

    @Test fun concurrentChangesAndStaleSelectionCannotInstallDifferentContents() =
        fixture { store, _ ->
            val old = store.add(store.preview(archive("1.0")))
            val stale = store.preview(archive("2.0"))
            val current = store.add(store.preview(archive("3.0")))
            assertThrows(IllegalArgumentException::class.java) { store.add(stale) }
            assertThrows(IllegalArgumentException::class.java) {
                store.candidate(old.identity, old.contentHash, "review")
            }
            assertThrows(IllegalArgumentException::class.java) { store.remove(old.identity, old.contentHash) }
            assertEquals(current.contentHash, store.list().single().contentHash)
        }

    @Test fun tamperedPreviewCannotPublishAndExternalCatalogDoesNotFetchContent() =
        fixture { store, _ ->
            val metadata =
                """
                {"name":"remote","plugins":[
                  {"name":"review","source":{
                    "source":"github","repo":"example/review"}}]}
                """.trimIndent().toByteArray()
            val preview = store.preview(metadata)
            assertTrue(preview.catalog.entries.all { it.bundle == null })
            assertTrue(store.list().isEmpty())
            val changed = preview.bytes.indexOf('r'.code.toByte())
            preview.bytes[changed] = 'x'.code.toByte()
            assertThrows(IllegalArgumentException::class.java) { store.add(preview) }
            assertTrue(store.list().isEmpty())
        }

    @Test fun preparedSnapshotDropsCredentialsAndRejectsCorruption() =
        fixture { store, root ->
            val preview = store.preview(archive("1.0"))
            store.add(preview)
            val path = Files.list(root).use { it.findFirst().get() }
            val saved = Files.readString(path)
            assertTrue(!saved.contains("PRIVATE") && !saved.contains("Authorization"))
            val expected =
                requireNotNull(
                    preview.catalog.entries
                        .single()
                        .bundle,
                ).endpoints.single()
            val loaded =
                requireNotNull(
                    store
                        .list()
                        .single()
                        .entries
                        .single()
                        .bundle,
                ).endpoints.single()
            assertTrue(loaded.needsCredential)
            assertEquals(expected.authBindingHash, loaded.authBindingHash)
            Files.writeString(path, saved.replace("\"name\":\"fixtures\"", "\"name\":\"changed\""))
            assertThrows(IllegalArgumentException::class.java) { store.list() }
        }
}
