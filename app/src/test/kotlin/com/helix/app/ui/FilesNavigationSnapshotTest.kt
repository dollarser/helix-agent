package com.helix.app.ui

import androidx.compose.runtime.saveable.SaverScope
import com.helix.app.files.FileManagerService
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilesNavigationSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun restoreKeepsFolderSearchButNeverRestoresGrantsOrDestructiveIntent() {
        val root = temporary.newFolder().toPath()
        val resolver = ScopeRootResolver { root }
        val files = FileManagerService(WorkspaceArtifactStore(resolver), resolver, "app")
        val state = FilesScreenState(files)
        state.openLocation("missing-grant", "notes/subfolder")
        state.searchOpen = true
        state.searchQuery = "draft"
        state.openFile = FileManagerService.FileEntry("note.md", "notes/subfolder/note.md", false, 1, 0)
        state.permanentDelete = listOf("private.txt")
        state.newFolderOpen = true
        val saver = filesNavigationSaver(files)
        val scope =
            object : SaverScope {
                override fun canBeSaved(value: Any) = true
            }
        val saved = with(saver) { requireNotNull(scope.save(state)) }
        val restored = requireNotNull(saver.restore(saved))
        assertEquals("notes/subfolder", restored.currentPath)
        assertEquals("draft", restored.searchQuery)
        assertTrue(restored.searchOpen)
        assertFalse(restored.hasSelectedSource)
        assertEquals("notes/subfolder/note.md", restored.openFile?.relativePath)
        assertEquals(FilePreviewState.Loading, restored.preview)
        assertNull(restored.permanentDelete)
        assertFalse(restored.newFolderOpen)
        restored.replaceSources(listOf(files.defaultSource))
        assertTrue(restored.homeOpen)
        assertEquals("app", restored.selectedScopeId)
    }
}
