package com.helix.app.connector

import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.security.MessageDigest

/** Real exported market packages, installed locally without connecting their remote services. */
@RunWith(AndroidJUnit4::class)
class WorkBuddySuppliedArchiveDeviceTest {
    @Test
    fun marketplaceImportEnableReadAndRemove() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("workBuddySample") == "true")
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val expected =
            mapOf(
                "github" to "38432941152707cee393eb54a1e437b1fc1ee4beb119244eac20c980f02cbcec",
                "kling-ai-plugin" to "851247253981d1edec38dca3d99134f614c300c07b1d0fabc388b76771abacb2",
            )
        expected.forEach { (name, hash) -> assertPackage(app, name, hash) }
    }

    private fun assertPackage(
        app: HelixApplication,
        name: String,
        hash: String,
    ) {
        val service = app.appContainer.connectorService
        val incoming = app.getExternalFilesDir(null)!!.toPath().resolve("$name.zip")
        val bytes = Files.readAllBytes(incoming)
        assertEquals(
            hash,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
        val root = app.filesDir.toPath().resolve("workspaces")
        Files.createDirectories(root)
        val file = Files.createTempFile(root, "workbuddy-", ".zip")
        try {
            Files.write(file, bytes)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file.toFile())
            val bundle = service.preview(uri)
            assertEquals(if (name == "github") 1 else 3, bundle.skills.size)
            assertEquals(listOf(name), bundle.endpoints.map { it.name })
            val before = service.list()
            val serversBefore =
                app.appContainer.storage.mcpServers
                    .list()
            val installed = service.install(bundle)
            try {
                assertEquals(bundle.skills.size, installed.skills.size)
                assertEquals(
                    serversBefore,
                    app.appContainer.storage.mcpServers
                        .list(),
                )
                assertTrue(installed.endpoints.none { service.enabled(it) })
                installed.skills.forEach { key ->
                    assertSkill(app, key, bundle.skills.single { it.directory == key.name }.files)
                }
            } finally {
                service.remove(installed)
            }
            assertEquals(before, service.list())
        } finally {
            Files.delete(file)
            Files.delete(incoming)
        }
    }

    private fun assertSkill(
        app: HelixApplication,
        key: com.helix.extensions.skills.SkillKey,
        files: Map<String, ByteArray>,
    ) {
        val service = app.appContainer.connectorService
        val repository = app.appContainer.skillRepository
        assertFalse(service.skillEnabled(key))
        service.setSkillEnabled(key, true)
        assertTrue(repository.read(key).body.isNotBlank())
        files.filterKeys { it.startsWith("references/") }.forEach { (relative, content) ->
            assertEquals(content.toString(Charsets.UTF_8), repository.readResource(key, relative).content)
        }
        service.setSkillEnabled(key, false)
        assertFalse(service.skillEnabled(key))
    }
}
