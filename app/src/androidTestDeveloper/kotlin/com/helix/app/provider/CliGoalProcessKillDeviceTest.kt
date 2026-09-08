package com.helix.app.provider

import android.os.Bundle
import androidx.compose.material3.MaterialTheme
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
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.ProbeOutcome
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Properties

/** Production Goal and subscription adapter, with the Runtime's account-free debug wait model. */
class CliGoalProcessKillDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val successful get() = InstrumentationRegistry.getArguments().getString("cli.owner.successful") == "true"
    private val resultHeld =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    private val marker get() = File(app.filesDir, "cli-goal-kill.properties")

    @Test fun originalSubscriptionJobIsRecoverable() =
        runBlocking {
            val phase = InstrumentationRegistry.getArguments().getString("cli.owner.phase")
            assumeTrue("Requires dedicated host SIGKILL runner", phase != null)
            if (phase == "prepare") {
                prepare()
            } else {
                val facts = Properties().apply { marker.inputStream().use { load(it) } }
                if (phase != "abort") recover(facts)
                if (phase == "recover-final" || phase == "abort") cleanup(facts)
            }
        }

    private suspend fun configure(): Properties {
        check(!marker.exists())
        val provider =
            when (InstrumentationRegistry.getArguments().getString("cli.owner.provider")) {
                "CODEX" -> SubscriptionProviderModule.CODEX_ID
                "CLAUDE" -> SubscriptionProviderModule.CLAUDE_ID
                "GROK" -> SubscriptionProviderModule.GROK_ID
                "COPILOT" -> SubscriptionProviderModule.COPILOT_ID
                else -> error("Missing provider")
            }
        val previous = container.chatService.runControl.value
        val facts =
            Properties().apply {
                setProperty("provider", provider)
                setProperty(
                    "model",
                    container.storage.providerConfigs
                        .resolve(provider)
                        .model,
                )
                setProperty("profile", container.profileStore.profile.name)
                setProperty("mode", previous.mode.name)
                setProperty("budgets", previous.budgets.toStorageString())
            }
        save(facts)
        container.profileStore.switchTo(SafetyProfile.ADVANCED)
        setModel(provider, "helix-fixture")
        val probe = container.providerService.runConnectionTest(provider)
        assertTrue("Synthetic Runtime probe failed: $probe", probe is ProbeOutcome.Ok)
        val model = if (successful) "helix-fixture" else "helix-fixture-wait"
        val session =
            container.chatService.createSession(
                "CLI Goal kill ${java.util.UUID.randomUUID()}",
                provider,
                model,
            )
        facts.setProperty("session", session)
        save(facts)
        return facts
    }

    private suspend fun prepare() {
        val facts = configure()
        val provider = facts.getProperty("provider")
        val chat = container.chatService
        val session = facts.getProperty("session")
        val goal =
            chat.createGoal(
                "CLI interrupted",
                listOf("Verified response"),
                GoalBudgets(3, 4, 100000, 120000, 60000, 0),
            )
        facts.setProperty("goal", goal)
        save(facts)
        chat.openSession(session)
        await { chat.screen.value.openSessionId == session }
        chat.setMode(AgentMode.GOAL)
        chat.setTurnBudgets(TurnBudgets(3, 4, 10000, 512, 10000))
        if (successful) holdSuccessfulProviderResult(container, resultHeld)
        chat.continueGoal(goal, "Wait for the synthetic Runtime fixture.")
        await {
            container.storage.auditEvents
                .listByCorrelation(session)
                .any { it.type == "cli.job_prepared" }
        }
        val audit =
            container.storage.auditEvents
                .listByCorrelation(session)
                .single { it.type == "cli.job_prepared" }
        val binding =
            kotlinx.serialization.json.Json.parseToJsonElement(
                audit.redactedPayload,
            ) as kotlinx.serialization.json.JsonObject
        facts.setProperty("call", binding.getValue("modelCallId").jsonPrimitive.content)
        facts.setProperty("binding", binding.toString())
        val job = binding.getValue("jobId").jsonPrimitive.content
        val client = CliModelJobClient(CliRuntimeSupervisor(app))
        CliGoalBoundaryVerifier(container, successful, resultHeld).verify(client, job, facts.getProperty("call"))
        val run =
            container.storage.goalRuns
                .listByGoal(goal)
                .single()
        val stored = container.storage.goals.resolve(goal)
        val pending = container.storage.goalUsageReservations.pendingForRun(run.id)
        facts.setProperty("tokens", (stored.totalTokens + pending.sumOf { it.reservedTokens }).toString())
        save(facts)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("stream", "CLI_OWNER_KILL_READY pid=${android.os.Process.myPid()} job=$job\n")
            },
        )
        Thread.sleep(15000)
        error("Host did not kill main App")
    }

    private fun openRecoveryConversation(facts: Properties) {
        val chat = container.chatService
        val session = facts.getProperty("session")
        chat.closeSession()
        await { chat.screen.value.openSessionId == null }
        compose.setContent {
            MaterialTheme { ChatScreen(chat, container.providerService, container.privacyDeletionService) }
        }
        val title =
            container.storage.sessions
                .resolve(session)
                .title
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performClick()
        await {
            chat.screen.value.subscriptionRecoveries
                .any { it.modelCallId == facts.getProperty("call") }
        }
        assertEquals(session, chat.screen.value.openSessionId)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "CLI_RECOVERY_CONVERSATION_OPENED\n") },
        )
    }

    private fun exerciseRecoveryButtons(facts: Properties) {
        val chat = container.chatService
        val callId = facts.getProperty("call")
        openRecoveryConversation(facts)
        assertTrue(!chat.screen.value.isSending)
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("subscription-query-$callId"))
        compose.onNodeWithTag("subscription-query-$callId").performClick()
        await {
            chat.screen.value.subscriptionRecoveries
                .single { it.modelCallId == callId }
                .status != null
        }
        val status =
            chat.screen.value.subscriptionRecoveries
                .single { it.modelCallId == callId }
                .status
        assertTrue(status != SubscriptionRecoveryStatus.UNKNOWN)
        if (status == SubscriptionRecoveryStatus.SUCCEEDED_UNVERIFIED) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("subscription-result-$callId"))
            compose.onNodeWithTag("subscription-result-$callId").performClick()
            await {
                chat.screen.value.subscriptionRecoveries
                    .single { it.modelCallId == callId }
                    .output != null
            }
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("subscription-result-text-$callId"))
            compose.onNodeWithTag("subscription-result-text-$callId").assertTextEquals("HELIX_OK")
        }
        if (status == SubscriptionRecoveryStatus.RUNNING) {
            compose.onNodeWithTag("subscription-stop-$callId").performClick()
            await {
                chat.screen.value.subscriptionRecoveries
                    .single { it.modelCallId == callId }
                    .status in
                    setOf(SubscriptionRecoveryStatus.STOP_REQUESTED, SubscriptionRecoveryStatus.STOPPED)
            }
        }
    }

    private fun recover(facts: Properties) {
        val storage = container.storage
        val goalId = facts.getProperty("goal")
        await { storage.goals.resolve(goalId).state == "PAUSED" }
        val goal = storage.goals.resolve(goalId)
        assertEquals(1, goal.modelCalls)
        assertEquals(facts.getProperty("tokens").toLong(), goal.totalTokens)
        assertEquals(
            "INTERRUPTED",
            storage.goalRuns
                .listByGoal(goalId)
                .single()
                .outcome,
        )
        assertEquals(
            "INTERRUPTED",
            storage.turns
                .listBySession(facts.getProperty("session"))
                .single()
                .state,
        )
        val binding = SubscriptionJobBindingStore(storage).resolve(facts.getProperty("call"))
        assertEquals(facts.getProperty("binding"), binding.toString())
        val id = binding.getValue("jobId").jsonPrimitive.content
        val client = CliModelJobClient(CliRuntimeSupervisor(app))
        val record = (client.query(id) as CliModelJobClient.StateOutcome.Ok).record
        assertEquals(binding.getValue("requestSha256").jsonPrimitive.content, record.requestSha256)
        exerciseRecoveryButtons(facts)
        await { (client.query(id) as CliModelJobClient.StateOutcome.Ok).record.state.terminal }
        val terminal = (client.reconcile(id) as CliModelJobClient.StateOutcome.Ok).record
        if (successful) {
            assertEquals(CliModelJobState.SUCCEEDED, terminal.state)
        } else {
            assertTrue(terminal.state == CliModelJobState.CANCELLED || terminal.state == CliModelJobState.INTERRUPTED)
        }
        Thread.sleep(2000)
        assertEquals(
            1,
            storage.auditEvents.listByCorrelation(facts.getProperty("session")).count { it.type == "cli.job_prepared" },
        )
        assertEquals(goal, storage.goals.resolve(goalId))
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("stream", "CLI_OWNER_RECOVERED state=${terminal.state} job=$id\n")
            },
        )
    }

    private suspend fun cleanup(facts: Properties) {
        val chat = container.chatService
        chat.stop()
        chat.closeSession()
        chat.setMode(AgentMode.valueOf(facts.getProperty("mode")))
        chat.setTurnBudgets(TurnBudgets.parse(facts.getProperty("budgets")))
        facts.getProperty("goal")?.let { container.privacyDeletionService.deleteGoal(it) }
        facts.getProperty("session")?.let { container.storage.sessions.archive(it, System.currentTimeMillis()) }
        setModel(facts.getProperty("provider"), facts.getProperty("model"))
        ProviderTestStatusStore(
            com.helix.app.internal
                .PrefsLineStore(app, "helix-ui"),
        ).clear(facts.getProperty("provider"))
        container.profileStore.switchTo(SafetyProfile.valueOf(facts.getProperty("profile")))
        check(marker.delete())
    }

    private fun setModel(
        provider: String,
        model: String,
    ) {
        val row = container.storage.providerConfigs.resolve(provider)
        container.storage.providerConfigs.overwrite(
            ProviderConfigSpec(
                row.id,
                row.displayName,
                ProviderProtocol.parse(row.protocol),
                row.endpoint,
                model,
                row.headersJson,
                row.secretAlias,
                row.capabilitySnapshot,
            ),
        )
    }

    private fun save(facts: Properties) = marker.outputStream().use { facts.store(it, "Owned CLI Goal fixture") }

    private fun await(predicate: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 15000
        while (!predicate()) {
            assertTrue("CLI Goal fixture timed out", android.os.SystemClock.elapsedRealtime() < until)
            Thread.sleep(25)
        }
    }
}
