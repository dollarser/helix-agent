package com.helix.app.connector

import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.extensions.skills.connector.ConnectorPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun actualArchiveReportsWholeBundleFailureAndValidIndividualSkills() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("connectorSample") == "true")
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val service = app.appContainer.connectorService
        val incoming = app.getExternalFilesDir(null)!!.toPath().resolve("hxa125-sample.zip")
        val bytes = Files.readAllBytes(incoming)
        assertEquals(
            "5832c88558e00a616af438b1f3d73badf3c2c09edc85f4c54ff9833b789ca476",
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
            assertEquals(0, bundle.endpoints.size)
            val before = service.list()
            val failure = assertThrows(IllegalArgumentException::class.java) { service.install(bundle) }
            assertEquals("metadata values must be strings", failure.message)
            assertEquals(before, service.list())
            for (name in listOf("mcp-installer", "wecom-unified")) {
                val skill = bundle.skills.single { it.directory == name }
                val single = ConnectorPackageReader().parse(skill.files.mapKeys { "$name/${it.key}" })
                val installed = service.install(single)
                try {
                    val key = installed.skills.single()
                    assertFalse(service.skillEnabled(key))
                    service.setSkillEnabled(key, true)
                    assertTrue(
                        app.appContainer.skillRepository
                            .read(key)
                            .body
                            .isNotBlank(),
                    )
                    service.setSkillEnabled(key, false)
                    assertFalse(service.skillEnabled(key))
                } finally {
                    service.remove(installed)
                }
            }
            assertEquals(before, service.list())
        } finally {
            Files.delete(file)
            Files.delete(incoming)
        }
    }
}
