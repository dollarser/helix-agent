package com.helix.app.knowledge

import android.content.ContentUris
import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.storage.content.DocumentStorage
import com.helix.core.storage.content.MediaStoreDocuments
import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeStore
import com.helix.core.workspace.AtomicFileWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Properties
import java.util.UUID

/** Actual MediaStore, synthetic data, independent package only. No directory picker or storage permission. */
class SharedKnowledgeDocumentsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun openingAnOriginalCannotBorrowAnotherCollectionReference() =
        fixture { _, store ->
            val otherName = "knowledge-share-other-${UUID.randomUUID()}"
            try {
                val other = MediaStoreDocuments(context, otherName)
                val file = other.save("notes", "other.md", "Other collection".toByteArray())
                val sharing = store.documents as com.helix.core.storage.content.DocumentShareAccess
                try {
                    sharing.shareReference(file.reference)
                    fail("A resource reference must not grant another collection")
                } catch (_: IllegalStateException) {
                    assertEquals("Other collection", other.read(file.reference, 100).bytes.toString(Charsets.UTF_8))
                }
            } finally {
                clearKnowledgeDocuments(context, otherName)
            }
        }

    @Test fun sharedMarkdownHasRealPublicPlacementAndNoRoomBody() =
        fixture { name, store ->
            store.saveNote(KnowledgeNote("note", "合成笔记", "# Shared\n\nSynthetic body", 1, 2))
            val file = store.documents.list("notes").single()
            val uri =
                ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    file.reference.toLong(),
                )
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                check(it.moveToFirst())
                assertEquals("Documents/Helix/$name/notes/", it.getString(0))
            }
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            assertTrue(text.endsWith("# Shared\n\nSynthetic body"))
            assertFalse(context.getDatabasePath(name).exists())
            assertEquals("# Shared\n\nSynthetic body", KnowledgeStore(context, name).note("note")!!.body)
        }

    @Test fun externalEditRenameAndDeleteAreReflectedWithoutOverwritingDraft() =
        fixture { _, store ->
            store.saveNote(KnowledgeNote("note", "Synthetic", "old", 1, 2))
            val observed = store.note("note")!!
            val file = store.documents.list("notes").single()
            val uri =
                ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    file.reference.toLong(),
                )
            context.contentResolver.openOutputStream(uri, "rwt")!!.use {
                it.write(
                    file.bytes
                        .toString(Charsets.UTF_8)
                        .replace("\nold", "\nexternal")
                        .toByteArray(),
                )
            }
            assertEquals("external", store.note("note")!!.body)
            var refused = false
            try {
                store.saveNote(observed.copy(body = "stale editor"))
            } catch (_: IllegalStateException) {
                refused = true
            }
            assertTrue(refused)
            assertEquals(
                1,
                context.contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "renamed.md")
                    },
                    null,
                    null,
                ),
            )
            assertEquals("external", store.note("note")!!.body)
            assertEquals(1, context.contentResolver.delete(uri, null, null))
            store.refresh()
            assertNull(store.note("note"))
        }

    @Test fun preparedPublicationRecoversOriginalIdentityWithoutReexecutingBusinessAction() =
        fixture { name, store ->
            store.saveNote(KnowledgeNote("note", "Synthetic", "old", 1, 2))
            val old = store.documents.list("notes").single()
            val next = prepareKnowledgeSave(context, name, old, "new")
            val recovered = MediaStoreDocuments(context, name).list("notes").single()
            assertEquals(next.toString(), recovered.reference)
            assertTrue(recovered.bytes.toString(Charsets.UTF_8).endsWith("\nnew"))
            assertEquals("new", KnowledgeStore(context, name).note("note")!!.body)
            assertFalse(java.io.File(context.noBackupFilesDir, "document-publication/$name.properties").exists())
        }

    @Test fun recoveryRetainsBothVersionsIfOldContentChanged() =
        fixture { name, store ->
            store.saveNote(KnowledgeNote("note", "Synthetic", "old", 1, 2))
            val old = store.documents.list("notes").single()
            val next = prepareKnowledgeSave(context, name, old, "new")
            val oldUri =
                ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    old.reference.toLong(),
                )
            context.contentResolver.openOutputStream(oldUri, "rwt")!!.use {
                it.write(
                    old.bytes
                        .toString(Charsets.UTF_8)
                        .replace("\nold", "\nexternal")
                        .toByteArray(),
                )
            }
            try {
                val active = MediaStoreDocuments(context, name).list("notes").single()
                assertTrue(active.bytes.toString(Charsets.UTF_8).endsWith("\nexternal"))
                store.refresh()
                val pending = store.pendingSaves.value.single()
                store.saveNote(KnowledgeNote("unrelated", "Unrelated", "Still usable", 1, 2))
                val recoveredId = store.recoverSaveAsNote(pending.token)
                assertEquals("new", store.note(recoveredId)!!.body)
                assertEquals("external", store.note("note")!!.body)
                assertTrue(store.pendingSaves.value.isEmpty())
                assertEquals(
                    next.toString(),
                    store.documents
                        .list("recovered")
                        .single()
                        .reference,
                )
                assertTrue(
                    context.contentResolver
                        .openInputStream(oldUri)!!
                        .use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }.endsWith("\nexternal"),
                )
                val nextUri =
                    ContentUris.withAppendedId(
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        next,
                    )
                context.contentResolver.openInputStream(nextUri)!!.use { assertTrue(it.read() >= 0) }
            } finally {
                // Exact owned pending row; this is fixture cleanup, never production recovery policy.
                context.contentResolver.delete(
                    ContentUris.withAppendedId(
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        next,
                    ),
                    null,
                    null,
                )
            }
        }

    private fun fixture(block: suspend (String, KnowledgeStore) -> Unit) =
        runBlocking {
            check(context.packageName.endsWith(".asrvalidation"))
            val name = "knowledge-documents-${UUID.randomUUID()}"
            try {
                block(name, KnowledgeStore(context, name))
            } finally {
                clearKnowledgeDocuments(context, name)
            }
        }
}
