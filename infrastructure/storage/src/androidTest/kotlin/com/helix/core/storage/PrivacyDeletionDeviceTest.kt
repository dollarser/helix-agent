package com.helix.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.storage.content.ContentRef
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** HXA-104 Room + content-store irreversible deletion and shared-hash boundary. */
@RunWith(AndroidJUnit4::class)
class PrivacyDeletionDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databases = mutableListOf<String>()
    private val directories = mutableListOf<File>()

    @After
    fun tearDown() {
        databases.forEach(context::deleteDatabase)
        directories.forEach(File::deleteRecursively)
    }

    @Test
    fun permanentSessionDeletionCascadesRowsAndReferenceCountsContentBodies() {
        val storage = openStorage("privacy-shared")
        storage.sessions.create("session-a", "A", null, null, 1L)
        storage.sessions.create("session-b", "B", null, null, 2L)
        val first = storage.messages.append("message-a", "session-a", null, "USER", "TEXT", "same private body")
        val second = storage.messages.append("message-b", "session-b", null, "USER", "TEXT", "same private body")
        val ref = ContentRef.parse(requireNotNull(first.contentRef))
        assertTrue(storage.contentStore.exists(ref))
        assertTrue(second.contentRef == first.contentRef)
        storage.auditEvents.append("audit-a", "session-a", "test", "USER", "{}", 3L)

        val firstDeletion = storage.deleteSessionPermanently("session-a")
        assertTrue(firstDeletion.deletedContentBodies == 0)
        assertTrue(storage.contentStore.exists(ref))
        assertTrue(storage.auditEvents.listByCorrelation("session-a").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { storage.sessions.resolve("session-a") }

        val secondDeletion = storage.deleteSessionPermanently("session-b")
        assertTrue(secondDeletion.deletedContentBodies == 1)
        assertFalse(storage.contentStore.exists(ref))
        storage.close()
    }

    @Test
    fun missingSessionFailsWithoutDeletingAnyBody() {
        val storage = openStorage("privacy-missing")
        storage.sessions.create("session-kept", "kept", null, null, 1L)
        val message = storage.messages.append("message-kept", "session-kept", null, "USER", "TEXT", "keep me")
        val ref = ContentRef.parse(requireNotNull(message.contentRef))

        assertThrows(IllegalArgumentException::class.java) { storage.deleteSessionPermanently("missing") }
        assertTrue(storage.contentStore.exists(ref))
        assertTrue(storage.sessions.resolve("session-kept").id == "session-kept")
        storage.close()
    }

    private fun openStorage(tag: String): HelixStorage {
        val database = "hxa104-$tag-${System.nanoTime()}.db"
        val directory = File(context.cacheDir, database).apply { mkdirs() }
        databases += database
        directories += directory
        return HelixStorage.open(context, database, directory)
    }
}
