package com.helix.app.knowledge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixTheme
import com.helix.app.R
import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Real DocumentsContract/MediaStore IO; picker results are injected, never actual OS grants. */
class KnowledgeRestoreDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver

    @Test fun selectedLibraryRestoresIdentityOriginalAndSearchWithoutChangingSource() =
        runBlocking {
            fixture { tree, target, service ->
                val source = LibraryRestoreTree(resolver, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val before = source.list("notes").single()
                val registry = Registry()
                render(service, registry)
                openRestore()
                compose.onNodeWithTag("knowledge-restore-select").performClick()
                compose.runOnIdle {
                    registry.deliver(
                        Activity.RESULT_OK,
                        Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }
                compose.waitUntil(15_000) { target.notes.value.size == 1 && target.items.value.size == 1 }
                val completed = context.getString(R.string.knowledge_restore_complete)
                compose.waitUntil(10_000) { compose.onAllNodesWithText(completed).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(completed).assertIsDisplayed()
                assertEquals(
                    "note",
                    target.notes.value
                        .single()
                        .id,
                )
                assertEquals("# Synthetic restored", target.searchNotes("restored").single().body)
                val original = knowledgeOriginal(target, target.get("source")!!, 0)
                assertEquals("original bytes", original.bytes.toString(Charsets.UTF_8))
                assertEquals(before.hash, source.read(before.reference, 4096).hash)
                val selection = Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                assertEquals(2, service.restoreLibrary(selection))
                assertEquals(1, target.notes.value.size)
                assertEquals(1, registry.launches)
                capture("library-restore-complete.png")
            }
        }

    @Test fun dismissAndCanceledPickerNeverImportOrSend() =
        runBlocking {
            fixture { _, target, service ->
                val registry = Registry()
                render(service, registry)
                openRestore()
                compose.onNodeWithTag("knowledge-restore-close").performClick()
                assertEquals(0, registry.launches)
                openRestore()
                compose.onNodeWithTag("knowledge-restore-select").performClick()
                compose.runOnIdle { registry.deliver(Activity.RESULT_CANCELED, null) }
                compose.waitForIdle()
                assertEquals(1, registry.launches)
                assertTrue(target.notes.value.isEmpty() && target.items.value.isEmpty())
                compose.onNodeWithTag("knowledge-restore-dialog").assertDoesNotExist()
            }
        }

    @Test fun missingReadGrantCannotBecomeSuccessfulEmptyRestore() =
        runBlocking {
            fixture { tree, target, service ->
                try {
                    service.restoreLibrary(Intent().setData(tree))
                    org.junit.Assert.fail("Expected missing selection grant")
                } catch (_: IllegalArgumentException) {
                    assertTrue(target.notes.value.isEmpty() && target.items.value.isEmpty())
                }
            }
        }

    private fun render(
        service: KnowledgeService,
        registry: Registry,
    ) {
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                HelixTheme { KnowledgeScreen(service) { error("Restore must not send a model message") } }
            }
        }
    }

    private fun openRestore() {
        compose.onNodeWithTag("knowledge-library-more").performClick()
        compose.onNodeWithTag("knowledge-restore-library").performClick()
        compose.onNodeWithTag("knowledge-restore-dialog").assertIsDisplayed()
    }

    private suspend fun fixture(action: suspend (Uri, KnowledgeStore, KnowledgeService) -> Unit) {
        check(context.packageName.endsWith(".asrvalidation"))
        val suffix = UUID.randomUUID().toString()
        val oldName = "restore-old-$suffix"
        val targetName = "restore-target-$suffix"
        val old = KnowledgeStore(context, oldName)
        old.saveNote(KnowledgeNote("note", "Synthetic", "# Synthetic restored", 1, 2))
        val original = old.documents.save("originals/source", "0.txt", "original bytes".toByteArray())
        val files =
            JSONArray().put(
                JSONObject()
                    .put("name", "source.txt")
                    .put("mime", "text/plain")
                    .put("reference", original.reference),
            )
        old.insert(CollectedSource("source", "Synthetic source", 3, "share", "", "raw", files.toString(), "READY"))
        val authority = "${instrumentation.context.packageName}.manualfiles"
        val root = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(root, "root")
        val folder = directory(rootDocument, "library-$suffix")
        val tree = DocumentsContract.buildTreeDocumentUri(authority, DocumentsContract.getDocumentId(folder))
        val notes = directory(folder, "notes")
        val sources = directory(folder, "sources")
        val originals = directory(directory(folder, "originals"), "source")
        listOf("notes" to notes, "sources" to sources, "originals/source" to originals).forEach { (path, parent) ->
            old.documents.list(path).forEach { file ->
                val output =
                    requireNotNull(
                        DocumentsContract.createDocument(resolver, parent, "application/octet-stream", file.name),
                    )
                resolver.openOutputStream(output, "w")!!.use { it.write(file.bytes) }
            }
        }
        val target = KnowledgeStore(context, targetName)
        val service = KnowledgeService(context, store = target)
        try {
            service.recovery.join()
            action(tree, target, service)
        } finally {
            service.close()
            clearKnowledgeDocuments(context, oldName)
            clearKnowledgeDocuments(context, targetName)
            deleteTree(folder)
        }
    }

    private fun directory(
        parent: Uri,
        name: String,
    ) = requireNotNull(
        DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name),
    )

    private fun deleteTree(parent: Uri) {
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val ids =
            resolver
                .query(
                    query,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null,
                    null,
                    null,
                )!!
                .use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
                }
        ids.forEach { (id, type) ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(parent, id)
            if (type == DocumentsContract.Document.MIME_TYPE_DIR) {
                deleteTree(uri)
            } else {
                check(DocumentsContract.deleteDocument(resolver, uri))
            }
        }
        check(DocumentsContract.deleteDocument(resolver, parent))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
            try {
                context.cacheDir.resolve(name).outputStream().use {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private class Registry : ActivityResultRegistry() {
        var launches = 0
        private var code = 0

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches++
            code = requestCode
        }

        fun deliver(
            result: Int,
            data: Intent?,
        ) {
            check(dispatchResult(code, result, data))
        }
    }
}
