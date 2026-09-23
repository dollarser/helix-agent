package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.helix.core.model.CompactManifestCodec
import com.helix.core.model.MessageRefEntry
import com.helix.core.storage.entity.ModelCallEntity
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.entity.TurnEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RequestManifestRecoveryDeviceTest {
    @Test
    fun turnInterruptionPreservesRequestManifestOnModelCall() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, HelixDatabase::class.java).build()
        try {
            database.sessionDao().insert(SessionEntity("s-rec", "Recovery Test", null, null, 1, null))
            database.turnDao().insert(TurnEntity("t-rec", "s-rec", "req-rec", 1, 1, null, null))

            val manifest =
                CompactManifestCodec.bounded(
                    callId = "c-rec",
                    timestamp = 1774300000000L,
                    checkpoint = null,
                    messages = listOf(MessageRefEntry("m-prev", MessageRefEntry.ROLE_USER)),
                    inputIds = listOf("inp-uuid-1"),
                )
            val manifestJson = CompactManifestCodec.encodeCompact(manifest)

            database.modelCallDao().insert(
                ModelCallEntity(
                    id = "c-rec",
                    turnId = "t-rec",
                    providerSnapshot = "{}",
                    state = "RUNNING",
                    usage = null,
                    requestId = "req-rec",
                    requestManifest = manifestJson,
                ),
            )

            // Mark turn as INTERRUPTED
            database.openHelper.writableDatabase.execSQL(
                "UPDATE turns SET state = 'INTERRUPTED' WHERE id = 't-rec'",
            )

            // Run reconciliation
            val interruptedCount = database.modelCallDao().interruptForInterruptedTurns()
            assertEquals(1, interruptedCount)

            val recovered = database.modelCallDao().byId("c-rec")
            assertNotNull(recovered)
            assertEquals("INTERRUPTED", recovered?.state)
            assertEquals(manifestJson, recovered?.requestManifest)
        } finally {
            database.close()
        }
    }
}
