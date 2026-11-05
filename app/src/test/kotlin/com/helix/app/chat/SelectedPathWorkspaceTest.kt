package com.helix.app.chat

import com.helix.app.files.SharedStorageAccess
import com.helix.core.storage.dao.WorkspaceDao
import com.helix.core.storage.entity.WorkspaceEntity
import com.helix.core.storage.repository.WorkspaceRepository
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

class SelectedPathWorkspaceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun internalStorageAndRegisteredAliasesCannotBecomeWorkingDirectories() {
        val records = mutableMapOf<String, WorkspaceEntity>()
        val repository = WorkspaceRepository(dao(records), temp.newFolder().toPath())
        val roots = ScopeRootResolver { error("Internal storage must be rejected before resolution") }
        val service = SessionWorkspaceService(repository, roots) { error("No SAF") }
        listOf("scope:app:", "scope:app:work").forEach { reference ->
            assertThrows(IllegalArgumentException::class.java) { service.bind(reference) }
        }
        assertEquals(0, records.size)
        records["alias"] =
            WorkspaceEntity("alias", "PATH", "scope:app:work", "fixture", "EXTERNAL", "READY", "witness", null, 1)
        assertThrows(IllegalArgumentException::class.java) { service.bind("scope:alias:") }
        assertEquals("READY", records.getValue("alias").availability)
    }

    @Test fun chosenRootRetainsItsScopeAndRevocationCannotOpenUnselectedGlobalAlias() {
        val root = temp.newFolder().toPath().toRealPath()
        var granted = true
        val records = mutableMapOf<String, WorkspaceEntity>()
        val repository = WorkspaceRepository(dao(records), temp.newFolder().toPath())
        val legacy = ScopeRootResolver { throw ScopeNotAvailable("Not a global Agent scope") }
        val selected =
            ScopeRootResolver { id ->
                if (!granted || id != SharedStorageAccess.SCOPE_ID) throw ScopeNotAvailable("Permission absent")
                root
            }
        val service = SessionWorkspaceService(repository, legacy, selected) { error("No SAF") }
        val reference = service.bind(FileScopePath(SharedStorageAccess.SCOPE_ID, "").toModelReference())
        val id = FileScopePath.fromModelReference(reference).scopeId
        assertEquals(root, service.resolveRoot(id))
        assertThrows(ScopeNotAvailable::class.java) { service.resolveRoot(SharedStorageAccess.SCOPE_ID) }
        granted = false
        assertThrows(ScopeNotAvailable::class.java) { service.resolveRoot(id) }
        assertEquals("UNAVAILABLE", records.getValue(id).availability)
        granted = true
        val fresh = service.bind(FileScopePath(SharedStorageAccess.SCOPE_ID, "").toModelReference())
        org.junit.Assert.assertNotEquals(reference, fresh)
        assertEquals(root, service.resolveRoot(FileScopePath.fromModelReference(fresh).scopeId))
        assertEquals("UNAVAILABLE", records.getValue(id).availability)
    }

    private fun dao(records: MutableMap<String, WorkspaceEntity>): WorkspaceDao =
        Proxy.newProxyInstance(
            WorkspaceDao::class.java.classLoader,
            arrayOf(WorkspaceDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "find" -> {
                    records[args!![0]]
                }

                "byIdentity" -> {
                    records.values.firstOrNull { it.identityKey == args!![0] }
                }

                "insert" -> {
                    val row = args!![0] as WorkspaceEntity
                    records[row.id] = row
                    null
                }

                "compareAvailability" -> {
                    val id = args!![0] as String
                    val row = records.getValue(id)
                    if (row.availability ==
                        args[1]
                    ) {
                        records[id] = row.copy(availability = args[2] as String)
                        1
                    } else {
                        0
                    }
                }

                "retire" -> {
                    val id = args!![0] as String
                    val row = records.getValue(id)
                    records[id] =
                        row.copy(identityKey = row.identityKey + ":retired:" + id, availability = "UNAVAILABLE")
                    1
                }

                else -> {
                    error("Unexpected DAO call: ${method.name}")
                }
            }
        } as WorkspaceDao
}
