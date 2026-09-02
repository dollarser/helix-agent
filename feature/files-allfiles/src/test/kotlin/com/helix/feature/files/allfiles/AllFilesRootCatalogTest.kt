package com.helix.feature.files.allfiles

import com.helix.core.workspace.FileScopePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-045: [AllFilesRootCatalog] — the fixed, closed set of all-files roots.
 *
 * Axes covered: the catalog is non-empty and key-unique; every `af-<key>` scope id is a legal
 * [FileScopePath] scope id (doc 10: 模型只看到 scopeId); `byKey` round-trips and is null for an
 * unknown key (fail closed — the catalog is closed, no arbitrary-path entry in this milestone).
 */
class AllFilesRootCatalogTest {
    @Test
    fun theCatalogIsNonEmptyAndKeyUnique() {
        assertTrue(AllFilesRootCatalog.ROOTS.isNotEmpty())
        val keys = AllFilesRootCatalog.ROOTS.map { it.key }
        assertEquals("root keys must be unique", keys.size, keys.toSet().size)
        // Every root names a well-known public-storage directory type (non-empty).
        AllFilesRootCatalog.ROOTS.forEach { assertTrue(it.directoryType.isNotEmpty()) }
    }

    @Test
    fun everyRootScopeIdIsAFileScopePathScopeId() {
        AllFilesRootCatalog.ROOTS.forEach { root ->
            val scopeId = AllFilesRootCatalog.scopeId(root.key)
            assertTrue("scope id must carry the af- prefix: $scopeId", scopeId.startsWith("af-"))
            // Construction + round-trip of the model reference must succeed (a legal scope id).
            val path = FileScopePath(scopeId, "work/a.txt")
            assertEquals(scopeId, path.scopeId)
            val reparsed = FileScopePath.fromModelReference(path.toModelReference())
            assertEquals(scopeId, reparsed.scopeId)
        }
    }

    @Test
    fun byKeyReturnsTheRootAndIsNullForAnUnknownKey() {
        val download = AllFilesRootCatalog.ROOTS.first { it.key == "download" }
        assertEquals(download, AllFilesRootCatalog.byKey("download"))
        assertNull(
            "an out-of-catalog key is not selectable (fail closed)",
            AllFilesRootCatalog.byKey("/sdcard/Android/data"),
        )
        assertNull(AllFilesRootCatalog.byKey("af-download"))
    }
}
