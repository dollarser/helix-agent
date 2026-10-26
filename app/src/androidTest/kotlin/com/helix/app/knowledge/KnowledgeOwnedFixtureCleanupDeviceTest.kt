package com.helix.app.knowledge

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Exact ID/token opt-in recovery for this runner's misdirected synthetic fixture, never broad deletion. */
class KnowledgeOwnedFixtureCleanupDeviceTest {
    @Test fun removeExplicitOwnedSyntheticShare(): Unit =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val id = arguments.getString("helix.cleanupSourceId")
            val token = arguments.getString("helix.cleanupSourceToken")
            assumeTrue(id != null || token != null)
            requireNotNull(id)
            requireNotNull(token)
            UUID.fromString(id)
            UUID.fromString(token.removePrefix("external-"))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val service = (context.applicationContext as HelixApplication).knowledge
            val row = service.items.first().single { it.id == id }
            assertEquals(token, row.rawText)
            assertEquals("Synthetic material 知识引用", service.originalBytes(row, 0).toString(Charsets.UTF_8))
            service.remove(id)
            assertFalse(service.items.first().any { it.id == id })
        }
}
