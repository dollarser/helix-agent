package com.helix.app.connector

import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import com.helix.core.model.SecretAlias
import com.helix.extensions.skills.connector.ConnectorPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ConnectorDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val service get() = container.connectorService

    @Test
    fun contentUriZipImportsWithoutNetworkThenReloadsAndUsesExistingSkillRepository() {
        val name = "connector-${UUID.randomUUID()}"
        val root = app.filesDir.toPath().resolve("workspaces")
        Files.createDirectories(root)
        val zip = Files.createTempFile(root, "connector-device-", ".zip")
        try {
            ZipOutputStream(Files.newOutputStream(zip)).use { output ->
                fixture(name).forEach { (path, bytes) ->
                    output.putNextEntry(ZipEntry("package/$path"))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", zip.toFile())
            val preview = service.preview(uri)
            val before =
                container.storage.mcpServers
                    .list()
                    .size
            val installed = service.install(preview)
            try {
                assertEquals(
                    before,
                    container.storage.mcpServers
                        .list()
                        .size,
                )
                assertEquals(installed, service.install(preview))
                assertFalse(service.skillEnabled(installed.skills.single()))
                service.setSkillEnabled(installed.skills.single(), true)
                assertTrue(
                    container.skillRepository
                        .read(installed.skills.single())
                        .body
                        .contains("Read"),
                )
                val reloaded =
                    ConnectorService(
                        app,
                        container.storage,
                        container.mcpService,
                        container.skillImportService,
                        container.skillRepository,
                    )
                assertEquals(installed, reloaded.list().single { it.id == installed.id })
                assertTrue(reloaded.skillEnabled(installed.skills.single()))
                assertFalse(reloaded.enabled(installed.endpoints.single()))
            } finally {
                service.remove(installed)
            }
        } finally {
            Files.deleteIfExists(zip)
        }
    }

    @Test
    fun removingOneBundlePreservesSharedSkillAndDeletesOnlyItsCredential() {
        val name = "shared-${UUID.randomUUID()}"
        val reader = ConnectorPackageReader()
        val first = service.install(reader.parse(fixture(name)))
        val second =
            service.install(
                reader.parse(fixture(name) + mapOf("license.txt" to "second version".toByteArray())),
            )
        val alias = SecretAlias(first.endpoints.single().id)
        try {
            assertEquals(first.skills, second.skills)
            service.setSkillEnabled(first.skills.single(), true)
            container.storage.secrets.put(alias, "connector-device-fixture")
            service.remove(first)
            assertTrue(service.skillEnabled(second.skills.single()))
            assertThrows(Exception::class.java) { container.storage.secrets.get(alias) }
            service.remove(second)
            assertFalse(service.skillEnabled(second.skills.single()))
            assertThrows(IllegalArgumentException::class.java) { service.setSkillEnabled(second.skills.single(), true) }
        } finally {
            service.list().filter { it.id == first.id || it.id == second.id }.forEach(service::remove)
        }
    }

    @Test
    fun invalidSkillDoesNotInstallBundleOrGrantEarlierValidSkill() {
        val name = "invalid-${UUID.randomUUID()}"
        val before = service.list().size
        val data = fixture(name) + mapOf("skills/broken/SKILL.md" to "no frontmatter".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            service.install(ConnectorPackageReader().parse(data))
        }
        assertEquals(before, service.list().size)
        assertFalse(container.skillRepository.list().any { it.key.name == name })
    }

    @Test
    fun credentialAliasChangeDisablesExistingServerWithoutSchemaMigration() {
        val id = "credential-${UUID.randomUUID()}"
        val repository = container.storage.mcpServers
        val server =
            repository.registerHttp(
                com.helix.core.storage.repository
                    .McpHttpServerSpec(id, "https://example.com/mcp", null),
            )
        repository.update(server, true, server.trustState)
        repository.replaceAuthAlias(id, "connector-fixture-alias")
        assertEquals("connector-fixture-alias", repository.resolve(id).authAlias)
        assertFalse(repository.resolve(id).enabled)
        assertThrows(IllegalArgumentException::class.java) { repository.replaceAuthAlias(id, "../invalid") }
        assertEquals("connector-fixture-alias", repository.resolve(id).authAlias)
    }

    @Test
    @Suppress("LongMethod") // fixture setup plus replacement and disable lifecycle assertions
    fun disabledOrReplacedServerRejectsCapturedExecutorBeforeNetwork() {
        val registry =
            com.helix.tools.framework
                .ToolRegistry()
        val implementations =
            com.helix.tools.framework
                .ToolImplementationRegistry()
        val mcp =
            com.helix.app.mcp.McpAppService(
                com.helix.app.mcp
                    .McpStorageBridge(container.storage),
                { com.helix.core.model.SafetyProfile.STANDARD },
                registry,
                implementations,
            )
        val config = mcp.registerDisabled("lifecycle-${UUID.randomUUID()}", "https://connector.invalid/mcp", null)
        // Synthetic metadata isolates activation lifecycle; this is not a connection-test result.
        val snapshot =
            com.helix.extensions.mcp.McpHandshakeSnapshot(
                config.id,
                config.endpoint.full,
                config.endpoint.origin,
                config.endpoint.residence(),
                com.helix.extensions.mcp
                    .McpServerIdentity("fixture", "1", "2025-03-26"),
                com.helix.extensions.mcp.McpMetadataSnapshot(
                    com.helix.extensions.mcp
                        .McpCapabilitySnapshot(true, false, false, false, false, false, false),
                    listOf(
                        com.helix.extensions.mcp.McpToolMetadata(
                            "search",
                            null,
                            "Fixture",
                            kotlinx.serialization.json.Json
                                .parseToJsonElement("""{"type":"object","properties":{}}""")
                                as kotlinx.serialization.json.JsonObject,
                            null,
                            "a".repeat(64),
                            emptyMap(),
                        ),
                    ),
                    emptyList(),
                    emptyList(),
                    false,
                    false,
                    false,
                ),
            )
        mcp.enable(snapshot, setOf("search"))
        val descriptor = registry.all().single()
        val captured = implementations.resolve(descriptor.name, descriptor.version)
        val call =
            com.helix.tools.framework.ExecutableToolCall(
                "connector-lifecycle",
                descriptor.name.value,
                descriptor.version.value.toString(),
                kotlinx.serialization.json.JsonObject(emptyMap()),
                com.helix.core.model.ExecutionTargetType.LOCAL_ANDROID,
                java.time.Instant
                    .now()
                    .plusSeconds(30),
                com.helix.tools.framework.NoCancellation,
            )
        try {
            mcp.enable(snapshot, setOf("search"))
            assertTrue(mcp.isActive(config.id.value))
            val replaced = captured.execute(call) as com.helix.tools.framework.ToolExecutorResult.Failed
            assertEquals("MCP tool failed: IllegalStateException", replaced.detail)
            val current = implementations.resolve(descriptor.name, descriptor.version)
            mcp.disable(config.id.value)
            assertFalse(mcp.isActive(config.id.value))
            assertTrue(registry.all().isEmpty())
            val disabled = current.execute(call) as com.helix.tools.framework.ToolExecutorResult.Failed
            assertEquals("MCP tool failed: IllegalStateException", disabled.detail)
        } finally {
            mcp.disable(config.id.value)
        }
    }

    private fun fixture(name: String): Map<String, ByteArray> =
        mapOf(
            ".codex-plugin/plugin.json" to """{"name":"$name"}""",
            ".mcp.json" to """{"mcp_servers":{"docs":{"url":"https://example.com/mcp"}}}""",
            "skills/$name/SKILL.md" to "---\nname: $name\ndescription: Device fixture\n---\nRead selected documents.\n",
            "skills/$name/references/readme.md" to "Fixture resource",
        ).mapValues { it.value.toByteArray() }
}
