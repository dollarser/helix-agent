package com.helix.app.proot

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.ProviderId
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionId
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.policy.SessionPermissionConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in real provider, new synthetic session only. Credentials stay inside the normal Provider path. */
class LiveJobAwaitDeviceTest {
    // Preserve every primary test failure; cleanup is suppressed on it, or independently fails the test.
    @Test
    @Suppress("LongMethod", "TooGenericExceptionCaught", "ThrowingExceptionFromFinally")
    fun realModelWaitsAndCollectsOneOriginalExecution() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Owner-authorized live model inputs required", args.getString("liveJob") == "true")
            val provider = requireNotNull(args.getString("liveProviderId"))
            val model = requireNotNull(args.getString("liveModel"))
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            require(
                c.storage.providerConfigs
                    .resolve(provider)
                    .model == model,
            )
            val previous = c.profileStore.profile
            val marker = "J1_LIVE_${UUID.randomUUID().toString().replace("-", "") }"
            val output = File(app.filesDir, "workspaces/app/output/$marker.txt")
            val session = c.chatService.createSession("Live original Job fixture", provider, model)
            var turn: String? = null
            var failure: Throwable? = null
            try {
                ensureInstalledRuntime(app)
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                check(ProotToolModule.verifyNow() is com.helix.runtime.proot.ipc.ProotRuntimeAvailability.Verified)
                c.storage.sessionPermissionConfigs.setForSession(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                val discovery =
                    if (args.getString("liveDiscoveryExplicit") == "true") {
                        "First call tools.search with query='linux job time await', limit=8. " +
                            "Use only returned schemas on the next request. " +
                            "Each successful search REPLACES the discovery window; " +
                            "do not run separate exact searches in parallel. "
                    } else {
                        ""
                    }
                val instruction =
                    discovery +
                        "Only use tools.search, code.linux.job.start, time.now, jobs.await " +
                        "and code.linux.job.collect. Start exactly ONE Linux Job with script " +
                        "`sleep 2; printf $marker > result.txt`, leaseSeconds=20 and output=" +
                        "scope:app:output/${output.name}. Read the time, await the ORIGINAL handle, " +
                        "collect the original result, then reply DONE. Never repeat the script. " +
                        "A start acceptance is not completion. Do not use other tools."
                turn =
                    c.agentRuntime
                        .submit(
                            SubmitTurnCommand(
                                session = SessionId(session),
                                providerId = ProviderId(provider),
                                mode = AgentMode.ACT,
                                text = instruction,
                                budgets = TurnBudgets(12, 12, 65536, 2048, 100000),
                                clientRequestId = UUID.randomUUID().toString(),
                            ),
                        ).value
                withTimeout(240_000) {
                    while (c.storage.turns
                            .resolve(requireNotNull(turn))
                            .state !in
                        setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED", "NEEDS_REVIEW")
                    ) {
                        delay(200)
                    }
                }
                val record = c.storage.turns.resolve(requireNotNull(turn))
                assertEquals(record.errorCode, "COMPLETED", record.state)
                val calls = c.storage.toolCalls.listByTurn(requireNotNull(turn))
                assertEquals(1, calls.count { it.name == DetachedJobTools.START })
                assertTrue(calls.any { it.name == "time.now" && it.state == "COMPLETED" })
                assertTrue(calls.any { it.name == DetachedJobTools.AWAIT && it.state == "COMPLETED" })
                assertTrue(calls.any { it.name == DetachedJobTools.COLLECT && it.state == "COMPLETED" })
                assertEquals(marker, output.readText())
                val job = DetachedJobDashboard.read(c.storage).single { it.sessionId == session }
                assertTrue(!job.settlementPending)
                val modelCalls = c.storage.modelCalls.listByTurn(requireNotNull(turn))
                val count = modelCalls.size
                val usage = modelCalls.map { it.usage }
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    0,
                    android.os.Bundle().apply {
                        val message = "LIVE_JOB model=$model modelCalls=$count toolCalls=${calls.size} usage=$usage\n"
                        putString("stream", message)
                    },
                )
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    turn?.let { c.chatService.stopTask(it) }
                    val jobs =
                        DetachedJobDashboard
                            .read(
                                c.storage,
                            ).filter { it.sessionId == session && it.settlementPending }
                    jobs.forEach { job ->
                        ProotToolModule.performBackgroundJobAction(job, BackgroundJobAction.CANCEL) { false }
                        val binding = ProotJobBindingStore(c.storage).resolveDetached(session, job.callId)
                        val client =
                            com.helix.runtime.proot.client
                                .DetachedJobClient(app)
                        withTimeout(30_000) {
                            while (client
                                    .query(binding)
                                    .record
                                    ?.state
                                    ?.isTerminal != true
                            ) {
                                delay(100)
                            }
                        }
                        check(
                            ProotToolModule.performBackgroundJobAction(job, BackgroundJobAction.COLLECT) { false } ==
                                BackgroundJobActionOutcome.SETTLED,
                        )
                    }
                } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                } finally {
                    c.profileStore.switchTo(previous)
                    c.storage.sessions.archive(session, System.currentTimeMillis())
                    output.delete()
                }
            }
        }
}
