package com.helix.app.provider

import android.content.Context
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelProvider
import com.helix.runtime.cli.client.CliModelRequestCodec
import com.helix.runtime.cli.client.CliRuntimeConnection
import com.helix.runtime.cli.client.CliRuntimeProtocol
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

/** Account-free production Binder/PFD result retrieval, with a repeated read before cleanup. */
class CliResultFetchDeviceTest {
    @Test fun codexResultCanBeReadTwice() = verify(CliModelProvider.CODEX)

    @Test fun claudeResultCanBeReadTwice() = verify(CliModelProvider.CLAUDE)

    @Test fun grokResultCanBeReadTwice() = verify(CliModelProvider.GROK)

    @Test fun copilotResultCanBeReadTwice() = verify(CliModelProvider.COPILOT)

    private fun verify(provider: CliModelProvider) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val supervisor = CliRuntimeSupervisor(context)
        val client = CliModelJobClient(supervisor)
        val opened = supervisor.openConnection() as CliRuntimeConnection.Opened
        val job =
            "job_" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
        try {
            submitCliResultFixture(opened, job, provider)
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (!(client.query(job) as CliModelJobClient.StateOutcome.Ok).record.state.terminal) {
                assertTrue(android.os.SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(25)
            }
            val first = client.fetchResult(job) as CliModelJobClient.StateOutcome.Ok
            assertNotNull(first.events)
            assertTrue(first.events!!.isNotEmpty())
            assertEquals(null, first.record.reconciledAtEpochMillis)
            assertEquals(first, client.fetchResult(job))
            assertEquals(first.record, (client.query(job) as CliModelJobClient.StateOutcome.Ok).record)
            persistAndAcknowledge(context, client, provider, first)
        } finally {
            client.cancel(job)
            client.reconcile(job)
            supervisor.closeConnection(opened)
        }
    }

    private fun verifyCorruptionIsRejected(
        storage: com.helix.core.storage.HelixStorage,
        root: java.io.File,
        ownership: LocalModelCallContext,
        record: com.helix.runtime.cli.client.CliModelJobRecord,
        store: SubscriptionResultStore,
    ) {
        val file =
            java.io.File(
                root,
                storage.artifacts
                    .listBySession("session")
                    .single()
                    .relativePath,
            )
        val original = file.readBytes()
        val corrupted = original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        file.writeBytes(corrupted)
        try {
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { store.read(ownership, record) }
        } finally {
            file.writeBytes(original)
        }
        assertNotNull(store.read(ownership, record))
    }

    private fun persistAndAcknowledge(
        context: Context,
        client: CliModelJobClient,
        provider: CliModelProvider,
        fetched: CliModelJobClient.StateOutcome.Ok,
    ) {
        val name = "cli-durable-${UUID.randomUUID()}"
        val root = java.io.File(context.cacheDir, name)
        val storage =
            com.helix.core.storage.HelixStorage
                .open(context, name, java.io.File(root, "content"))
        try {
            storage.sessions.create("session", "Result fixture", null, null, 1)
            storage.turns.start("turn", "session", 2)
            storage.modelCalls.append("call", "turn", "fixture", "INTERRUPTED")
            val ownership = LocalModelCallContext("turn", "call")
            SubscriptionJobBindingStore(storage).record(
                ownership,
                fetched.record.jobId,
                ModelRequest("helix-fixture", listOf(ModelMessage(ModelRole.USER, "fixture"))),
                provider,
            )
            val resultStore = SubscriptionResultStore(storage, root)
            val events = requireNotNull(fetched.events)
            resultStore.persist(ownership, fetched.record, events)
            resultStore.persist(ownership, fetched.record, events)
            assertEquals(1, storage.artifacts.listBySession("session").size)
            verifyCorruptionIsRejected(storage, root, ownership, fetched.record, resultStore)
            val wrong = client.acknowledgeResult(fetched.record.copy(outputSha256 = "0".repeat(64)))
            assertTrue(wrong is CliModelJobClient.StateOutcome.Unavailable)
            assertEquals(fetched, client.fetchResult(fetched.record.jobId))
            val ack = client.acknowledgeResult(fetched.record) as CliModelJobClient.StateOutcome.Ok
            assertNotNull(ack.record.reconciledAtEpochMillis)
            assertEquals(ack, client.acknowledgeResult(fetched.record))
            assertEquals(null, (client.fetchResult(fetched.record.jobId) as CliModelJobClient.StateOutcome.Ok).events)
            val artifact = storage.artifacts.listBySession("session").single()
            val bytes = java.io.File(root, artifact.relativePath).readBytes()
            assertEquals(
                fetched.events,
                com.helix.runtime.cli.client.CliModelEventCodec
                    .decode(bytes),
            )
            val deletion = storage.deleteSessionPermanently("session")
            assertEquals(listOf(artifact.relativePath), deletion.unreferencedWorkspacePaths)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}

internal fun submitCliResultFixture(
    connection: CliRuntimeConnection.Opened,
    job: String,
    provider: CliModelProvider,
) {
    val payload =
        CliModelRequestCodec.encode(
            ModelRequest("helix-fixture", listOf(ModelMessage(ModelRole.USER, "fixture"))),
            provider,
        )
    val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    val (read, write) = ParcelFileDescriptor.createPipe()
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
        ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(payload) }
        data.writeInterfaceToken(CliRuntimeProtocol.DESCRIPTOR)
        data.writeString(job)
        data.writeString(hash)
        data.writeParcelable(read, 0)
        assertTrue(connection.binder.transact(CliRuntimeProtocol.TRANSACTION_JOB_SUBMIT, data, reply, 0))
        assertEquals(CliRuntimeProtocol.REPLY_JOB_ACCEPTED, reply.readInt())
    } finally {
        read.close()
        data.recycle()
        reply.recycle()
    }
}
