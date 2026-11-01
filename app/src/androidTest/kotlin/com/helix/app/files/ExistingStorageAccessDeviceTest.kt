package com.helix.app.files

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Existing daily grant only, app-owned external fixture. Never prompts, grants or visits other user files. */
class ExistingStorageAccessDeviceTest {
    @Test fun alreadyGrantedPlatformStorageBrowsesOriginalOwnedFixture(): Unit =
        runBlocking {
            val flag = InstrumentationRegistry.getArguments().getString("helix.existingStorageProbe")
            org.junit.Assume.assumeTrue("Existing-storage probe opt-in absent", flag != null)
            require(flag == "true")
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
            require(app.packageName == "com.helix.agent.developer")
            val access = SharedStorageAccess(app)
            check(access.isGranted()) { "Existing owner storage permission is required; it was not changed" }
            val fixture =
                File(
                    requireNotNull(app.getExternalFilesDir(null)),
                    "path-probe-${UUID.randomUUID()}",
                ).apply { mkdir() }
            try {
                val original = File(fixture, "original.txt").apply { writeText("Synthetic in-place storage") }
                val root = access.root().toRealPath()
                val relative = root.relativize(fixture.toPath().toRealPath()).toString()
                val service = app.awaitAppContainer().fileManager
                val selection = service.directoryChoices(FileScopePath(SharedStorageAccess.SCOPE_ID, relative))
                assertTrue(selection.sources.any { SharedStorageAccess.isScope(it.scopeId) })
                assertTrue(requireNotNull(selection.access).directPath)
                assertEquals(listOf("original.txt"), selection.entries.map { it.name })
                assertEquals("Synthetic in-place storage", original.readText())
                File(app.noBackupFilesDir, "existing-storage-result.json").writeText(
                    buildJsonObject {
                        put("existingGrant", true)
                        put("platformVolumeCount", access.directories().size)
                        put("sourceInPlace", true)
                        put("newGrant", false)
                        put("sessionOrRulesChanged", false)
                    }.toString(),
                )
            } finally {
                fixture.deleteRecursively()
            }
        }
}
