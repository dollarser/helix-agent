package com.helix.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.approval.SessionPermissionEditService
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PermissionAtomicityDeviceTest {
    private fun withStorage(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "permission-atomic-${UUID.randomUUID()}"
        val content = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, content)
        try {
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }

    private fun service(
        storage: HelixStorage,
        failAudit: Boolean = false,
    ) = SessionPermissionEditService(
        configs = storage.sessionPermissionConfigs,
        availability = storage.toolAvailability,
        idGenerator = { UUID.randomUUID().toString() },
        appendAudit = { id, correlation, type, actor, payload, timestamp ->
            check(!failAudit) { "injected audit failure" }
            storage.auditEvents.append(id, correlation, type, actor, payload, timestamp)
        },
        transaction = storage::withTransaction,
    )

    @Test
    fun defaultChangesOnlyFutureSessionsAndResetIsASnapshot() =
        withStorage { storage ->
            val edit = service(storage)
            storage.sessions.create("old", "old", null, null, 1L)
            edit.setNewSessionDefault(SessionPermissionMode.FULL_ACCESS, 2L)
            storage.sessions.create("new", "new", null, null, 3L)
            assertEquals(SessionPermissionMode.READ_ONLY, edit.activeConfigFor("old")?.mode)
            assertEquals(SessionPermissionMode.FULL_ACCESS, edit.activeConfigFor("new")?.mode)
            edit.resetSessionToDefault("old", 4L)
            edit.setNewSessionDefault(SessionPermissionMode.WORKSPACE, 5L)
            assertEquals(SessionPermissionMode.FULL_ACCESS, edit.activeConfigFor("old")?.mode)
        }

    @Test
    fun auditFailureRollsBackConfigDefaultAndDraft() =
        withStorage { storage ->
            storage.sessions.create("s", "session", null, null, 1L)
            val edit = service(storage, failAudit = true)
            assertTrue(
                runCatching {
                    edit.saveSessionConfig("s", SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS), 2L)
                }.isFailure,
            )
            assertEquals(SessionPermissionMode.READ_ONLY, edit.activeConfigFor("s")?.mode)
            assertTrue(runCatching { edit.setNewSessionDefault(SessionPermissionMode.FULL_ACCESS, 3L) }.isFailure)
            assertEquals(SessionPermissionMode.READ_ONLY, edit.appDefault().mode)
            assertTrue(
                runCatching {
                    edit.saveCustomDraft(
                        "s",
                        SessionPermissionMode.READ_ONLY,
                        SessionPermissionConfig.copyPreset(SessionPermissionMode.READ_ONLY),
                        4L,
                    )
                }.isFailure,
            )
            assertEquals(null, edit.customDraftFor("s"))
        }

    @Test
    fun concurrentRuleEditsPreserveBothChanges() =
        withStorage { storage ->
            storage.sessions.create("s", "session", null, null, 1L)
            val edit = service(storage)
            edit.saveCustomDraft(
                "s",
                SessionPermissionMode.READ_ONLY,
                SessionPermissionConfig.copyPreset(SessionPermissionMode.READ_ONLY),
                2L,
            )
            edit.activateCustomDraft("s", 3L)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val effects = listOf(OperationEffect.COMMAND_EXECUTION, OperationEffect.FILE_MUTATION_WORKSPACE)
                val futures =
                    effects.map { effect ->
                        pool.submit { edit.setCustomRule("s", effect, OperationRule.DENY, 4L) }
                    }
                futures.forEach { it.get(10, TimeUnit.SECONDS) }
                effects.forEach { assertEquals(OperationRule.DENY, edit.activeConfigFor("s")?.rules?.get(it)) }
            } finally {
                pool.shutdownNow()
            }
        }
}
