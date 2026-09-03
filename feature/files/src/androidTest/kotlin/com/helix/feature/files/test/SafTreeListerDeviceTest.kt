package com.helix.feature.files.test

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.feature.files.ContentResolverSafTreeLister
import com.helix.feature.files.SafTreeEnumerationLimit
import com.helix.feature.files.SafTreeImportEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-058 (device gate): the REAL [ContentResolverSafTreeLister] (the 文件夹导入 enumeration)
 * driven through the REAL `ContentResolver` against the in-APK [TreeDocumentsProvider] — the
 * `DocumentsContract` children queries, the same governed read path a host provider takes.
 * Covers: every file with its RAW (untrusted) display-name segments, the honest sizes, the
 * document URIs; and the fail-closed boundaries (an unparseable tree URI, a tree whose provider
 * no longer answers).
 */
class SafTreeListerDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val lister = ContentResolverSafTreeLister(resolver)
    private val treeUri = "content://${TreeDocumentsProvider.AUTHORITY}/tree/root"

    @Test
    fun listTreeReturnsEveryFileWithRawSegmentsSizesAndDocumentUris() {
        val entries = lister.listTree(treeUri)
        assertEquals(4, entries.size)

        val byPath = entries.associate { it.rawSegments.joinToString("/") to it }
        val a = byPath["a.txt"]!!
        assertEquals(5L, a.sizeBytes)
        assertEquals("content://${TreeDocumentsProvider.AUTHORITY}/document/a", a.documentUri)

        val b = byPath["sub/b.txt"]!!
        assertEquals(1L, b.sizeBytes)
        assertEquals(listOf("sub", "b.txt"), b.rawSegments)

        // The ambiguous pair is enumerated as TWO entries (the PLANNER resolves the ambiguity —
        // the lister never guesses).
        val dups = entries.filter { it.rawSegments.joinToString("/") == "dup/same.txt" }
        assertEquals(2, dups.size)
        assertTrue(dups.all { it.sizeBytes == 1L })
    }

    // A tree URI that has no parseable root document id fails closed (no walk attempted).
    @Test
    fun anUnparseableTreeUriFailsClosed() {
        assertThrows(ScopeNotAvailable::class.java) { lister.listTree("file:///no-tree") }
    }

    // A content tree whose provider no longer answers (revoked / gone / root deleted): the first
    // children query fails → the whole enumeration is refused (a truncated walk would omit files).
    @Test
    fun aTreeWhoseProviderGoneFailsClosed() {
        assertThrows(SafTreeEnumerationLimit::class.java) {
            lister.listTree("content://com.helix.feature.files.nosuch/tree/root")
        }
    }
}
