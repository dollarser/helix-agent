package com.helix.app.documents

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.helix.app.files.BoundDocumentBackend
import com.helix.app.files.SafManualFileBackend
import com.helix.core.model.NoCancellation
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafTreeGrantCheck
import com.helix.feature.files.SafTreeGrantFacts
import com.helix.feature.files.SafTreeScopeService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** UUID-owned Room/content roots and provider only; never constructs the production app container. */
internal class SafSnapshotCapacityFixture : AutoCloseable {
    private val context =
        ApplicationProvider.getApplicationContext<Context>().also {
            check(it.packageName == "com.helix.agent.developer.asrvalidation")
            check(it.applicationContext.javaClass == android.app.Application::class.java)
        }
    private val name = "saf-capacity-${UUID.randomUUID()}"
    private val root = File(context.cacheDir, name).apply { check(mkdirs()) }
    val storage = HelixStorage.open(context, name, File(root, "bodies"))
    private val content = storage.contentStore as FileContentStore
    val provider = BlockingSnapshotProvider(File(root, "report.pdf").apply { writeText("%PDF-private fixture\n") })
    val callers = Executors.newFixedThreadPool(CAPACITY)
    val auxiliary = Executors.newSingleThreadExecutor()
    val dispatch: SnapshotDispatchFixture

    init {
        storage.sessions.create(SELECTED, "Selected fixture", null, null, 1)
        storage.sessions.create(OTHER, "Retained fixture", null, null, 1)
        val authority = "com.helix.private.snapshot"
        provider.attachInfo(
            context,
            ProviderInfo().apply {
                this.authority = authority
                exported = false
            },
        )
        val grants = SafGrantStore(File(root, "grants.json").toPath())
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val scope = grants.grant(tree.toString(), "Private fixture").scopeId
        val service =
            SafTreeScopeService(grants, SafTreeGrantCheck { SafTreeGrantFacts(true, authority, "root", true, false) })
        val backend = SafManualFileBackend(ContentResolver.wrap(provider), grants, service, scope)
        val bound =
            BoundDocumentBackend(scope, { it }, {
                check(it == scope)
                backend
            })
        val workspace =
            WorkspaceArtifactStore(
                ScopeRootResolver { error("SAF must not resolve a native directory") },
                documentBackend = {
                    check(it == scope)
                    bound
                },
            )
        dispatch = SnapshotDispatchFixture(RoomDocumentSnapshots(storage, workspace), scope)
    }

    fun stages(): List<File> =
        content.root
            .walkTopDown()
            .filter { it.isFile && it.name.startsWith("binary.tmp-") }
            .toList()

    fun assertIndependentCommitAndGc(stageCount: Int) {
        auxiliary
            .submit {
                storage.messages.append("independent", OTHER, null, "USER", "TEXT", "unrelated durable message")
                storage.collectGarbage(0, Long.MAX_VALUE)
            }.get(10, TimeUnit.SECONDS)
        assertEquals(
            "unrelated durable message",
            storage.messages.readContent(requireNotNull(storage.messages.find("independent"))),
        )
        assertEquals(stageCount, stages().size)
        assertEquals(1, storage.documentSnapshots.listBySession(OTHER).size)
    }

    fun retainOtherSnapshot() {
        assertTrue(dispatch.dispatch("retained", OTHER, NoCancellation) is ToolDispatchOutcome.Succeeded)
        assertEquals(1, storage.documentSnapshots.listBySession(OTHER).size)
    }

    override fun close() {
        provider.release()
        callers.shutdown()
        auxiliary.shutdown()
        // No deletion or database close while an actual executor might still use the fixture.
        val callersStopped = callers.awaitTermination(15, TimeUnit.SECONDS)
        val auxiliaryStopped = auxiliary.awaitTermination(15, TimeUnit.SECONDS)
        awaitSnapshotCondition { dispatch.entered.get() == dispatch.exited.get() && provider.active.get() == 0 }
        check(callersStopped && auxiliaryStopped) { "Fixture callers still running; private resources retained" }
        storage.close()
        check(context.deleteDatabase(name))
        check(root.deleteRecursively())
    }

    companion object {
        const val CAPACITY = 32
        const val SELECTED = "selected"
        const val OTHER = "other"
    }
}

internal fun awaitSnapshotCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
    check(condition()) { "Snapshot boundary did not reach the expected state" }
}
