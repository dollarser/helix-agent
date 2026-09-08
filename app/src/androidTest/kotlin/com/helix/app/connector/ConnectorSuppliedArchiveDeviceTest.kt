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

/** Local supplied archive only; imported scripts and redacted credentials are never executed. */
@RunWith(AndroidJUnit4::class)
class ConnectorSuppliedArchiveDeviceTest {
    @Test
    fun actualArchiveImportsAllComponentsWithoutExecutingDependencies() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("connectorSample") == "true")
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val service = app.appContainer.connectorService
        val incoming = app.getExternalFilesDir(null)!!.toPath().resolve("hxa125-sample.zip")
        val bytes = Files.readAllBytes(incoming)
        assertEquals(
            InstrumentationRegistry.getArguments().getString("connectorSampleSha256")
                ?: "5832c88558e00a616af438b1f3d73badf3c2c09edc85f4c54ff9833b789ca476",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
        val root = app.filesDir.toPath().resolve("workspaces")
        Files.createDirectories(root)
        val file = Files.createTempFile(root, "supplied-", ".zip")
        try {
            Files.write(file, bytes)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file.toFile())
            val bundle = service.preview(uri)
            assertEquals(4, bundle.skills.size)
            assertEquals(2, bundle.endpoints.size)
            assertTrue(bundle.endpoints.all { it.needsCredential })
            val before = service.list()
            val serversBefore =
                app.appContainer.storage.mcpServers
                    .list()
            val installed = service.install(bundle)
            try {
                assertEquals(4, installed.skills.size)
                assertEquals(
                    serversBefore,
                    app.appContainer.storage.mcpServers
                        .list(),
                )
                assertTrue(installed.endpoints.none { service.enabled(it) })
                for (key in installed.skills) {
                    assertFalse(service.skillEnabled(key))
                    service.setSkillEnabled(key, true)
                    assertTrue(
                        app.appContainer.skillRepository
                            .read(key)
                            .body
                            .isNotBlank(),
                    )
                    assertOriginalPreserved(app, bundle, key)
                    service.setSkillEnabled(key, false)
                }
                assertTrue(installed.diagnostics.any { it.startsWith("SKILL_METADATA_NORMALIZED:dingtalk-doc") })
                assertTrue(installed.diagnostics.any { it.startsWith("SKILL_REQUIRES_BINARY:dingtalk-doc:dws") })
            } finally {
                service.remove(installed)
            }
            assertEquals(before, service.list())
        } finally {
            Files.delete(file)
            Files.delete(incoming)
        }
    }

    private fun assertOriginalPreserved(
        app: HelixApplication,
        bundle: com.helix.extensions.skills.connector.ConnectorPackage,
        key: com.helix.extensions.skills.SkillKey,
    ) {
        if (key.name.startsWith("dingtalk-")) {
            val original =
                app.appContainer.skillRepository.readResource(
                    key,
                    "references/helix-import/original-SKILL.md.txt",
                )
            val expected =
                bundle.skills.single { it.directory == key.name }.files.getValue(
                    "references/helix-import/original-SKILL.md.txt",
                )
            assertEquals(expected.toString(Charsets.UTF_8), original.content)
        }
    }
}
