package com.helix.app.provider

import android.os.Bundle
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.ui.ChatScreen
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelProvider
import com.helix.runtime.cli.client.CliRuntimeConnection
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Properties
import java.util.UUID

/** Host SIGKILL at fetch/persist/ack boundaries; real Runtime, synthetic request, no model replay. */
class CliResultOwnerKillDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val storage get() = app.appContainer.storage
    private val marker get() = File(app.filesDir, "cli-result-owner.properties")
    private val expired get() = InstrumentationRegistry.getArguments().getString("cli.result.expired") == "true"
    private val client = CliModelJobClient(CliRuntimeSupervisor(app))
    private val store get() = SubscriptionResultStore(storage, File(app.filesDir, "workspaces/app"))

    @Test fun resultSurvivesOwnerDeath() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("cli.result.phase")
        assumeTrue("Dedicated host runner required", phase != null)
        if (phase == "prepare") {
            prepare(requireNotNull(args.getString("cli.result.boundary")))
        } else {
            val facts = Properties().apply { marker.inputStream().use { load(it) } }
            if (phase != "abort") {
                if (expired) recoverExpired(facts) else recover(facts)
            }
            if (phase == "recover-final" || phase == "abort") cleanup(facts)
        }
    }

    private fun prepare(boundary: String) {
        check(!marker.exists())
        val id =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)
        val job = "job_$id"
        val facts =
            Properties().apply {
                setProperty("id", id)
                setProperty("boundary", boundary)
            }
        marker.outputStream().use { facts.store(it, "Owned result fixture") }
        storage.sessions.create(id, "CLI result boundary", null, null, 1)
        storage.turns.start(id, id, 2)
        storage.modelCalls.append(id, id, "fixture", "RUNNING")
        val request = ModelRequest("helix-fixture", listOf(ModelMessage(ModelRole.USER, "fixture")))
        SubscriptionJobBindingStore(storage).record(LocalModelCallContext(id, id), job, request, CliModelProvider.CODEX)
        submitAndWait(job)
        val fetched = client.fetchResult(job) as CliModelJobClient.StateOutcome.Ok
        val events = requireNotNull(fetched.events)
        if (boundary != "fetched") store.persist(LocalModelCallContext(id, id), fetched.record, events)
        if (boundary == "acknowledged") {
            val ack = client.acknowledgeResult(fetched.record) as CliModelJobClient.StateOutcome.Ok
            assertNotNull(ack.record.reconciledAtEpochMillis)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "CLI_RESULT_READY pid=${android.os.Process.myPid()} job=$job\n") },
        )
        Thread.sleep(30000)
        error("Host did not kill the result owner")
    }

    private fun submitAndWait(job: String) {
        val supervisor = CliRuntimeSupervisor(app)
        val opened = supervisor.openConnection() as CliRuntimeConnection.Opened
        try {
            submitCliResultFixture(opened, job, CliModelProvider.CODEX)
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (!(client.query(job) as CliModelJobClient.StateOutcome.Ok).record.state.terminal) {
                check(android.os.SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(25)
            }
        } finally {
            supervisor.closeConnection(opened)
        }
    }

    private fun recover(facts: Properties) {
        if (localOnly()) {
            recoverLocal(facts)
            return
        }
        val id = facts.getProperty("id")
        val ownership = LocalModelCallContext(id, id)
        val record = (client.query("job_$id") as CliModelJobClient.StateOutcome.Ok).record
        val local = store.read(ownership, record)
        val fetched = client.fetchResult(record.jobId) as CliModelJobClient.StateOutcome.Ok
        val events = local ?: requireNotNull(fetched.events)
        if (record.reconciledAtEpochMillis != null) assertNotNull(local)
        recoverThroughUi(id)
        val ack = client.query(record.jobId) as CliModelJobClient.StateOutcome.Ok
        assertNotNull(ack.record.reconciledAtEpochMillis)
        assertEquals(events, store.read(ownership, ack.record))
        assertEquals(1, storage.modelCalls.listByTurn(id).size)
        assertEquals(1, storage.auditEvents.listByCorrelation(id).count { it.type == "cli.job_prepared" })
        assertTrue(storage.artifacts.listBySession(id).size == 1)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "CLI_RESULT_RECOVERED job=${record.jobId}\n") },
        )
    }

    private fun recoverThroughUi(id: String) {
        val container = app.appContainer
        val chat = container.chatService
        chat.openSession(id)
        compose.waitUntil(10000) {
            chat.screen.value.subscriptionRecoveries
                .any { it.modelCallId == id }
        }
        var expiryLabel = ""
        compose.setContent {
            expiryLabel =
                androidx.compose.ui.res
                    .stringResource(com.helix.app.R.string.subscription_recovery_expired)
            MaterialTheme {
                ChatScreen(
                    chat,
                    container.providerService,
                    container.privacyDeletionService,
                )
            }
        }
        if (!localOnly()) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("subscription-query-$id"))
            compose.onNodeWithTag("subscription-query-$id").performClick()
            compose.waitUntil(10000) {
                chat.screen.value.subscriptionRecoveries
                    .single()
                    .status != null
            }
        }
        if (expired) {
            assertEquals(
                SubscriptionRecoveryStatus.EVIDENCE_EXPIRED,
                chat.screen.value.subscriptionRecoveries
                    .single()
                    .status,
            )
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(expiryLabel))
            compose.onNodeWithText(expiryLabel).assertIsDisplayed()
            if (!chat.screen.value.subscriptionRecoveries
                    .single()
                    .localResultAvailable
            ) {
                compose.onNodeWithTag("subscription-result-$id").assertDoesNotExist()
                return
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("subscription-result-$id"))
        compose.onNodeWithTag("subscription-result-$id").performClick()
        compose.waitUntil(10000) {
            chat.screen.value.subscriptionRecoveries
                .single()
                .output != null
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("subscription-result-text-$id"))
        compose.onNodeWithTag("subscription-result-text-$id").assertTextEquals("HELIX_OK")
        assertEquals("INTERRUPTED", storage.turns.resolve(id).state)
        assertEquals("INTERRUPTED", storage.modelCalls.resolve(id).state)
    }

    private fun localOnly(): Boolean =
        InstrumentationRegistry.getArguments().getString("cli.result.localOnly") == "true"

    private fun recoverLocal(facts: Properties) {
        val id = facts.getProperty("id")
        assertTrue(client.query("job_$id") is CliModelJobClient.StateOutcome.Unavailable)
        val ownership = LocalModelCallContext(id, id)
        val events = requireNotNull(store.readLocal(ownership))
        recoverThroughUi(id)
        assertEquals(events, store.readLocal(ownership))
        assertEquals(1, storage.modelCalls.listByTurn(id).size)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "CLI_RESULT_RECOVERED job=job_$id\n") },
        )
    }

    private fun recoverExpired(facts: Properties) {
        val id = facts.getProperty("id")
        val record = (client.query("job_$id") as CliModelJobClient.StateOutcome.Ok).record
        assertEquals("EVIDENCE_EXPIRED", record.state.name)
        assertEquals(null, record.reconciledAtEpochMillis)
        assertEquals(null, (client.fetchResult(record.jobId) as CliModelJobClient.StateOutcome.Ok).events)
        recoverThroughUi(id)
        assertEquals("INTERRUPTED", storage.turns.resolve(id).state)
        assertEquals("INTERRUPTED", storage.modelCalls.resolve(id).state)
        assertEquals(1, storage.modelCalls.listByTurn(id).size)
        assertEquals(1, storage.auditEvents.listByCorrelation(id).count { it.type == "cli.job_prepared" })
        assertEquals(record, (client.query(record.jobId) as CliModelJobClient.StateOutcome.Ok).record)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "CLI_RESULT_RECOVERED job=${record.jobId}\n") },
        )
    }

    private fun cleanup(facts: Properties) {
        val id = facts.getProperty("id")
        client.cancel("job_$id")
        client.reconcile("job_$id")
        val manifest = storage.deleteSessionPermanently(id)
        manifest.unreferencedWorkspacePaths.forEach { relative ->
            File(app.filesDir, "workspaces/app/$relative").delete()
        }
        check(marker.delete())
    }
}
