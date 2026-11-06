package com.helix.app

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.maintenance.PreviewPrivateStateGate
import com.helix.app.maintenance.PreviewResetSection
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.PreviewDatabaseResetRequired
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Exercises real SQLite metadata under an isolated cache root, never the daily Room database. */
class StartupRoomPreflightDeviceTest {
    @Test fun incompatibleRoomDataStaysUntouchedUntilItsOwnConfirmation() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "startup-preflight-").toFile()
        val context =
            object : ContextWrapper(base) {
                override fun getDatabasePath(name: String) = File(root, name)

                override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
            }
        val database = context.getDatabasePath("helix.db")
        val retained = File(root, "user-file.txt").apply { writeText("synthetic retained file") }
        try {
            SQLiteDatabase.openOrCreateDatabase(database, null).use {
                it.execSQL("CREATE TABLE synthetic_old_data (value TEXT)")
                it.execSQL("INSERT INTO synthetic_old_data VALUES ('preserved until confirmed')")
                it.version = 9
            }
            val before = database.readBytes()
            val gate =
                PreviewPrivateStateGate(
                    root,
                    databaseReset = {
                        try {
                            HelixStorage.requireCurrentPreview(context)
                            null
                        } catch (required: PreviewDatabaseResetRequired) {
                            required
                        }
                    },
                    readMobile = { emptyList() },
                    validateMobile = { true },
                    clearMobile = { error("No mobile reset is needed") },
                    rebootProvesStopped = { true },
                )
            var consumers = 0
            var recovery = 0
            val initialization =
                AppInitialization(
                    factory = {
                        consumers++
                        Any()
                    },
                    onReady = { recovery++ },
                    preflight = gate::inspect,
                )
            assertTrue(runCatching { initialization.await() }.isFailure)
            val warning = initialization.state.value as AppStartupState.ResetRequired
            assertEquals(setOf(PreviewResetSection.DATABASE), warning.request.sections)
            assertArrayEquals(before, database.readBytes())
            assertEquals(0, consumers)
            assertEquals(0, recovery)
            initialization.confirmReset(warning.request)
            initialization.await()
            assertEquals(1, consumers)
            assertEquals(1, recovery)
            assertFalse(database.exists())
            assertEquals("synthetic retained file", retained.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
