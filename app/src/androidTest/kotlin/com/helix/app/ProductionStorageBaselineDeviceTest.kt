package com.helix.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Current-schema Room workload used by app resource diagnostics; it tests no historical upgrade. */
@RunWith(AndroidJUnit4::class)
class ProductionStorageBaselineDeviceTest {
    @Test
    fun currentBaselineOpensWritesReopensAndCloses() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val databaseName = "production-storage-$suffix.db"
        val contentRoot = File(context.cacheDir, "production-storage-$suffix").also { it.mkdirs() }
        try {
            HelixStorage.open(context, databaseName, contentRoot).useStorage { storage ->
                storage.sessions.create("session", "Baseline", null, null, 1L)
                assertEquals("Baseline", storage.sessions.resolve("session").title)
            }
            HelixStorage.open(context, databaseName, contentRoot).useStorage { storage ->
                assertEquals("Baseline", storage.sessions.resolve("session").title)
            }
        } finally {
            context.deleteDatabase(databaseName)
            contentRoot.deleteRecursively()
        }
    }

    private fun HelixStorage.useStorage(block: (HelixStorage) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
