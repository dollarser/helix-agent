package com.helix.app.diagnostics

import android.util.Log
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AppLogSnapshotDeviceTest {
    @Test
    fun currentProcessSnapshotMasksSyntheticCredentialsWithoutPermissions(): Unit =
        runBlocking {
            val marker = "HELIX_SYNTHETIC_${UUID.randomUUID()}"
            val token = "ghp_" + "A".repeat(32)
            Log.i("HelixSynthetic", "$marker $token")
            val snapshot = AppLogReader.read()
            assertTrue("Own synthetic log must be readable", marker in snapshot.text)
            assertFalse("Synthetic token must be masked before UI search", token in snapshot.text)
            assertTrue(snapshot.text.toByteArray().size <= 256 * 1024)
            withTimeout(5000) {
                val cancelled = launch { AppLogReader.read() }
                delay(1)
                cancelled.cancelAndJoin()
                assertTrue(cancelled.isCompleted)
            }
        }
}
