package com.helix.app.ui

import com.helix.app.files.FileManagerService
import com.helix.app.files.FileSource
import com.helix.app.files.FileSourceKind
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilesHomeStateTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun state(): FilesScreenState {
        val root = temporary.newFolder().toPath()
        val resolver = ScopeRootResolver { root }
        return FilesScreenState(FileManagerService(WorkspaceArtifactStore(resolver), resolver, "app"))
    }

    private fun workspace(id: Int) = FileSource("ws-$id", "Session $id", FileSourceKind.WORKSPACE, true)

    @Test fun addingSessionsDoesNotAddHomeLocationsOrChangeTheBrowsedFolder() {
        val state = state()
        val local = state.sources.single()
        val added = FileSource("saf-folder", "My folder", FileSourceKind.SAF, true)
        state.replaceSources(listOf(local, added))
        state.openLocation(added.scopeId, "Documents")
        state.replaceSources(listOf(local) + (1..100).map(::workspace) + added)
        assertEquals(listOf(local, added), state.locations)
        assertEquals(100, state.workspaces.size)
        assertEquals(added.scopeId, state.selectedScopeId)
        assertEquals("Documents", state.currentPath)
        assertFalse(state.homeOpen)
    }

    @Test fun workspaceRootReturnsToCollectionThenStableHome() {
        val state = state()
        state.replaceSources(state.sources + workspace(1))
        state.openWorkspaces()
        state.openLocation("ws-1", "nested")
        state.goBack()
        assertEquals("", state.currentPath)
        state.goBack()
        assertTrue(state.workspacesOpen)
        assertFalse(state.homeOpen)
        state.goBack()
        assertTrue(state.homeOpen)
        assertFalse(state.workspacesOpen)
    }

    @Test fun removedWorkspaceClosesStaleActionsAndReturnsHome() {
        val state = state()
        val local = state.sources.single()
        state.replaceSources(listOf(local, workspace(1)))
        state.openLocation("ws-1")
        state.newFolderOpen = true
        state.replaceSources(listOf(local))
        assertTrue(state.homeOpen)
        assertFalse(state.workspacesOpen)
        assertFalse(state.newFolderOpen)
        assertEquals("app", state.selectedScopeId)
    }

    @Test fun auxiliaryNavigationNeverSelectsAnotherSessionAutomatically() {
        val state = state()
        state.replaceSources(state.sources + workspace(1))
        state.openWorkspaces()
        state.replaceSources(state.sources + workspace(2))
        assertEquals("app", state.selectedScopeId)
        assertTrue(state.workspacesOpen)
        state.goHome()
        assertTrue(state.homeOpen)
        assertFalse(state.workspacesOpen)
    }
}
