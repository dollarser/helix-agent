package com.helix.feature.files.test

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.feature.files.ContentResolverSafTreeDestination
import com.helix.feature.files.SafGrantStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

/**
 * HXA-058 (device gate): the REAL [ContentResolverSafTreeDestination] (the 导出-into-authorized-
 * tree seam) driven through the REAL `ContentResolver` against the in-APK MUTABLE
 * [ExportSinkTreeProvider] — the create-document insert (`buildDocumentUriUsingTree`, the
 * operation `DocumentsContract.createDocument` performs internally), the conflict detection,
 * the ambiguous-parent refusal, and the post-write size/re-read visibility (a write made through
 * `openFile("w")` is visible to the next size query and re-read — the export verification path).
 *
 * The WRITE-mode re-verification of the grant itself is HXA-057's device suite
 * ([SafTreeScopeDeviceTest]); here the grant registry is a real file-backed [SafGrantStore] and
 * the destination resolves it through the same registry the service does.
 */
class SafTreeDestinationDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val treeUri = ExportSinkTreeProvider.TREE_URI

    private fun storePath(tag: String): Path =
        Files
            .createDirectories(context.cacheDir.toPath().resolve("saf-dest-it-$tag"))
            .resolve("saf-grants.json")

    private fun fixture(
        tag: String,
        grant: Boolean = true,
    ): Pair<SafGrantStore, ContentResolverSafTreeDestination> {
        val store = SafGrantStore(storePath(tag)) { 0L }
        if (grant) store.grant(treeUri, "Sink")
        return store to ContentResolverSafTreeDestination(resolver, store)
    }

    @Test
    fun createsAChildDocumentUnderTheRootAndTheWriteIsVisibleToQueries() {
        val (store, dest) = fixture("create")
        val scopeId = store.deriveScopeId(treeUri)

        val uri = dest.destinationUri(scopeId, "", "report.txt", "text/plain", overwrite = false)
        assertTrue(uri.startsWith("content://${ExportSinkTreeProvider.AUTHORITY}/document/"))

        // The write-through path: open the created document, write, and the size query + re-read
        // must see exactly what was written (the post-export verification contract).
        val docUri = android.net.Uri.parse(uri)
        val payload = "written through the real resolver".toByteArray()
        resolver.openOutputStream(docUri, "w")!!.use { it.write(payload) }
        resolver.openInputStream(docUri)!!.use {
            assertEquals(payload.toList(), it.readBytes().toList())
        }
    }

    // The same-name file exists and overwrite is OFF → a conflict the UI must surface, never clobbered.
    @Test
    fun aSameNameFileConflictsWhenOverwriteIsOff() {
        val (store, dest) = fixture("conflict")
        val scopeId = store.deriveScopeId(treeUri)
        assertThrows(FileAlreadyExistsException::class.java) {
            dest.destinationUri(scopeId, "", "note.txt", "text/plain", overwrite = false)
        }
    }

    // Overwrite ON reuses the EXISTING document (the UI moved on after the user chose 覆盖).
    @Test
    fun overwriteOnReusesTheExistingDocumentUri() {
        val (store, dest) = fixture("overwrite")
        val scopeId = store.deriveScopeId(treeUri)

        val uri = dest.destinationUri(scopeId, "", "note.txt", "text/plain", overwrite = true)
        assertEquals("content://${ExportSinkTreeProvider.AUTHORITY}/document/note", uri)

        // And it is writable: the overwrite path truncates the existing document.
        val docUri = android.net.Uri.parse(uri)
        resolver.openOutputStream(docUri, "w")!!.use { it.write("v2".toByteArray()) }
        resolver.openInputStream(docUri)!!.use { assertEquals("v2", String(it.readBytes())) }
    }

    // A same-name target with TWO existing matches (ambiguous) is refused — never a guess.
    @Test
    fun anAmbiguousSameNameTargetFailsClosed() {
        val (store, dest) = fixture("ambiguous")
        val scopeId = store.deriveScopeId(treeUri)
        assertThrows(FileNotFoundException::class.java) {
            dest.destinationUri(scopeId, "dup", "same.txt", "text/plain", overwrite = false)
        }
    }

    // A parent directory that does not exist (or is ambiguous) is refused before any create.
    @Test
    fun aMissingParentFailsClosed() {
        val (store, dest) = fixture("missing-parent")
        val scopeId = store.deriveScopeId(treeUri)
        assertThrows(FileNotFoundException::class.java) {
            dest.destinationUri(scopeId, "no/such/dir", "x.txt", "text/plain", overwrite = false)
        }
    }

    // A scope the registry does not know is refused before any platform call.
    @Test
    fun anUnknownScopeFailsClosed() {
        val (store, dest) = fixture("unknown", grant = false)
        assertThrows(ScopeNotAvailable::class.java) {
            dest.destinationUri(store.deriveScopeId(treeUri), "", "x.txt", "text/plain", overwrite = false)
        }
    }

    // A grant for a DIFFERENT tree (a root this provider does not serve) cannot create a document.
    @Test
    fun aGrantForAnotherTreeFailsClosedOnCreate() {
        val (store, dest) = fixture("other-tree")
        val otherTree = "content://${ExportSinkTreeProvider.AUTHORITY}/tree/other-root"
        val otherScope = store.grant(otherTree, "Other").scopeId
        assertThrows(IOException::class.java) {
            dest.destinationUri(otherScope, "", "x.txt", "text/plain", overwrite = false)
        }
    }

    // The created document's metadata is queryable (the name the UI shows is the provider's truth).
    @Test
    fun aCreatedDocumentReportsItsNameThroughTheMetadataQuery() {
        val (store, dest) = fixture("metadata")
        val scopeId = store.deriveScopeId(treeUri)
        val uri = dest.destinationUri(scopeId, "", "created.txt", "text/plain", overwrite = false)
        val name =
            resolver.query(android.net.Uri.parse(uri), null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                c.moveToFirst().let { if (it && idx >= 0) c.getString(idx) else null }
            }
        assertNotNull(name)
        assertEquals("created.txt", name)
    }
}
