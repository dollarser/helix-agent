package com.helix.app.files

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.workspace.ScopeRootResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual 15-minute WorkManager delivery. A synthetic unavailable source must fail closed; no real SAF grant. */
class PeriodicSyncDeviceTest {
    @Test fun periodicDeliveryReviewsUnavailableSyntheticSourceWithoutWriting(): Unit =
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
            require(app.packageName.endsWith(".asrvalidation"))
            val state = File(app.noBackupFilesDir, "directory-sync/rules.json")
            require(
                !state.exists() || DirectorySyncCodec.decode(state.readText()).isEmpty(),
            ) { "Fixture needs no existing rules" }
            val root = File(app.cacheDir, "periodic-${UUID.randomUUID()}").apply { mkdirs() }
            val relative = "periodic-${UUID.randomUUID()}"
            File(root, relative).mkdirs()
            File(root, "$relative/original.txt").writeText("Synthetic unchanged")
            val backend = NioManualFileBackend("app", ScopeRootResolver { root.toPath() }, false) {}
            val fixture = directorySyncService(app, ManualFileOperations({ backend }, { true }))
            var actual: DirectorySyncService? = null
            var id: String? = null
            try {
                val rule = fixture.create(SyncRoot("app", relative), SyncRoot("saf-periodic-synthetic", relative))
                id = rule.id
                val started = android.os.SystemClock.elapsedRealtime()
                fixture.enable(rule.id, true)
                actual = requireNotNull(app.awaitAppContainer().fileManager.directorySync).value
                assertEquals("READY", actual.list().single().status)
                withTimeout(19 * 60_000L) {
                    while (actual.list().single().status != "REVIEW") delay(1000)
                }
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                assertFalse(actual.list().single().enabled)
                assertEquals("Synthetic unchanged", File(root, "$relative/original.txt").readText())
                File(app.cacheDir, "periodic-sync-result.json").writeText(
                    buildJsonObject {
                        put("actualWorkManager", true)
                        put("elapsedMs", elapsed)
                        put("unavailableSourceReviewed", true)
                        put("syntheticOnly", true)
                        put("successfulPeriodicCopy", false)
                    }.toString(),
                )
            } finally {
                id?.let { (actual ?: fixture).remove(it) }
                root.deleteRecursively()
            }
        }
}
