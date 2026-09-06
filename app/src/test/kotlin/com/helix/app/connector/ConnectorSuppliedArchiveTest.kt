package com.helix.app.connector

import com.helix.extensions.skills.SkillImportService
import com.helix.extensions.skills.connector.ConnectorPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Local user-supplied archive; no plugin code or network is executed. */
class ConnectorSuppliedArchiveTest {
    @Test
    fun inspectProductionReaderAndSkillImporter() {
        val source = System.getenv("HELIX_CONNECTOR_SAMPLE_ZIP")
        assumeTrue("requires a local sample", !source.isNullOrBlank())
        val bundle = ConnectorPackageReader().readZip(Path.of(requireNotNull(source)))
        assertEquals(
            setOf("dingtalk-doc", "dingtalk-shared", "mcp-installer", "wecom-unified"),
            bundle.skills
                .map {
                    it.directory
                }.toSet(),
        )
        assertEquals(2, bundle.endpoints.size)
        println(
            "sample: skills=${bundle.skills.size}, endpoints=${bundle.endpoints.size}, " +
                "diagnostics=${bundle.diagnostics}",
        )
        assertMcpEnvelopeImported(requireNotNull(source))
        val outcomes = mutableMapOf<String, String>()
        val root = Files.createTempDirectory("connector-sample-")
        try {
            val importer = SkillImportService(root.resolve("staging"))
            bundle.skills.forEach { skill ->
                val directory = root.resolve("source").resolve(skill.directory)
                skill.files.forEach { (name, bytes) ->
                    val target = directory.resolve(ConnectorPackageReader.safePath(name))
                    Files.createDirectories(target.parent)
                    Files.write(target, bytes)
                }
                try {
                    val staged = importer.stageDirectory(directory)
                    val snapshot = importer.commit(staged, root.resolve("snapshots"))
                    outcomes[skill.directory] = "accepted"
                    println(
                        "${skill.directory}: accepted, snapshot=${snapshot.snapshotHash}, files=${skill.files.size}",
                    )
                } catch (failure: IllegalArgumentException) {
                    outcomes[skill.directory] = requireNotNull(failure.message)
                    println("${skill.directory}: rejected: ${failure.message}")
                }
            }
            assertEquals(
                mapOf(
                    "dingtalk-doc" to "accepted",
                    "dingtalk-shared" to "accepted",
                    "mcp-installer" to "accepted",
                    "wecom-unified" to "accepted",
                ),
                outcomes,
            )
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    private fun assertMcpEnvelopeImported(source: String) {
        ZipFile(source).use { zip ->
            val entry =
                zip.entries().asSequence().single {
                    it.name.startsWith("qwenwork-mcp-") &&
                        it.name.endsWith(".json")
                }
            val rawConfig = zip.getInputStream(entry).use { it.readBytes() }
            val bundle = ConnectorPackageReader().readJson(rawConfig)
            assertEquals(2, bundle.endpoints.size)
            org.junit.Assert.assertTrue(bundle.endpoints.all { it.needsCredential })
        }
    }
}
