package com.helix.core.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PreviewDatabaseGateDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun cleanup() {
        context.deleteDatabase(DATABASE)
    }

    @Test fun cancelAndRestartKeepOldRecordsUntilExplicitConfirmation() {
        seed()
        val before = context.getDatabasePath(DATABASE).readBytes()
        repeat(3) {
            request()
            assertArrayEquals(before, context.getDatabasePath(DATABASE).readBytes())
            assertMarker()
        }
        request().confirm()
        HelixStorage.openDevelopmentDatabase(context, DATABASE).close()
        val room = HelixStorage.openDevelopmentDatabase(context, DATABASE)
        try {
            room.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name='preview_marker'").use {
                assertFalse(it.moveToFirst())
            }
        } finally {
            room.close()
        }
    }

    @Test fun changedDatabaseInvalidatesConfirmation() {
        seed()
        val pending = request()
        context.openOrCreateDatabase(DATABASE, 0, null).use {
            it.execSQL("INSERT INTO preview_marker VALUES ('added-after-warning')")
        }
        var cleanupCalls = 0
        assertThrows(IllegalStateException::class.java) { pending.confirm { cleanupCalls++ } }
        assertEquals(0, cleanupCalls)
        assertMarker()
        request().confirm()
        HelixStorage.openDevelopmentDatabase(context, DATABASE).close()
    }

    @Test fun consumedConfirmationCannotDeleteNewDatabase() {
        seed()
        val pending = request()
        pending.confirm()
        seed()
        assertThrows(IllegalStateException::class.java) { pending.confirm() }
        assertMarker()
    }

    @Test fun writesInWalInvalidateConfirmationEvenWhenMainFileIsUnchanged() {
        seed()
        context.openOrCreateDatabase(DATABASE, 0, null).use { writer ->
            assertTrue(writer.enableWriteAheadLogging())
            val pending = request()
            val before = context.getDatabasePath(DATABASE).readBytes()
            writer.execSQL("INSERT INTO preview_marker VALUES ('new-wal-record')")
            assertArrayEquals(before, context.getDatabasePath(DATABASE).readBytes())
            assertThrows(IllegalStateException::class.java) { pending.confirm() }
            writer.rawQuery("SELECT count(*) FROM preview_marker", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
            }
        }
        assertMarker()
    }

    @Test fun refusedDeletionPreservesDataAndReportsFailure() {
        seed()
        val wrapper =
            object : ContextWrapper(context) {
                override fun deleteDatabase(name: String): Boolean = false
            }
        val pending = request(wrapper)
        assertThrows(IllegalStateException::class.java) { pending.confirm() }
        assertMarker()
    }

    @Test fun cleanupFailureKeepsTheDatabaseAndDoesNotRetryCleanup() {
        seed()
        val pending = request()
        var calls = 0
        assertThrows(IOException::class.java) {
            pending.confirm {
                calls++
                throw IOException("synthetic cleanup failure")
            }
        }
        assertThrows(IllegalStateException::class.java) { pending.confirm { calls++ } }
        assertEquals(1, calls)
        assertMarker()
    }

    @Test fun deletionExceptionIsNotSwallowedOrRetried() {
        seed()
        var calls = 0
        val wrapper =
            object : ContextWrapper(context) {
                override fun deleteDatabase(name: String): Boolean {
                    calls++
                    throw IOException("synthetic deletion failure")
                }
            }
        val pending = request(wrapper)
        assertThrows(IOException::class.java) { pending.confirm() }
        assertThrows(IllegalStateException::class.java) { pending.confirm() }
        assertEquals(1, calls)
        assertMarker()
    }

    @Test fun corruptionIsFailureAndNeverResetConsentOrAutomaticDeletion() {
        val file = context.getDatabasePath(DATABASE)
        file.parentFile!!.mkdirs()
        val bytes = ByteArray(4096) { 65 }
        file.writeBytes(bytes)
        val failure = runCatching { HelixStorage.openDevelopmentDatabase(context, DATABASE) }.exceptionOrNull()
        assertTrue(failure != null && failure !is PreviewDatabaseResetRequired)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun missingDatabaseNeedsNoConfirmation() {
        HelixStorage.openDevelopmentDatabase(context, DATABASE).close()
        HelixStorage.openDevelopmentDatabase(context, DATABASE).close()
    }

    private fun request(target: Context = context): PreviewDatabaseResetRequired =
        assertThrows(
            PreviewDatabaseResetRequired::class.java,
        ) { HelixStorage.openDevelopmentDatabase(target, DATABASE) }

    private fun seed() {
        context.openOrCreateDatabase(DATABASE, 0, null).use {
            it.execSQL("CREATE TABLE preview_marker (value TEXT)")
            it.execSQL("INSERT INTO preview_marker VALUES ('synthetic-old-data')")
            it.version = 9
        }
    }

    private fun assertMarker() {
        context.openOrCreateDatabase(DATABASE, 0, null).use { db ->
            db.rawQuery("SELECT value FROM preview_marker", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("synthetic-old-data", it.getString(0))
            }
        }
    }

    private companion object {
        const val DATABASE = "preview-confirmation-test.db"
    }
}
