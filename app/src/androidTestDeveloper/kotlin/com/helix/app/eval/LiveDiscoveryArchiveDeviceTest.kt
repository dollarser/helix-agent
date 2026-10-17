package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.ProviderId
import com.helix.core.model.SessionId
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.policy.SessionPermissionConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Opt-in synthetic archive task; no task-specific search query or exported credentials. */
class LiveDiscoveryArchiveDeviceTest {
    // One bounded journey; cleanup preserves every primary failure and fails independently otherwise.
    @Test
    @Suppress("LongMethod", "TooGenericExceptionCaught", "ThrowingExceptionFromFinally")
    fun realModelCreatesOneVerifiedArchive(): Unit =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Current owner-authorized live inputs required", args.getString("liveDiscovery") == "true")
            val provider = requireNotNull(args.getString("liveProviderId"))
            val model = requireNotNull(args.getString("liveModel"))
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            require(
                c.storage.providerConfigs
                    .resolve(provider)
                    .model == model,
            )
            val marker = "DISCOVERY_${UUID.randomUUID().toString().replace("-", "") }"
            val root = File(app.filesDir, "workspaces/app/work/$marker")
            val source = File(root, "source")
            check(source.mkdirs())
            File(source, "marker.txt").writeText(marker)
            val archive = File(root, "result.zip")
            val session = c.chatService.createSession("Live discovery archive fixture", provider, model)
            var turn: String? = null
            var failure: Throwable? = null
            try {
                c.storage.sessionPermissionConfigs.setForSession(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                turn =
                    c.agentRuntime
                        .submit(
                            SubmitTurnCommand(
                                session = SessionId(session),
                                providerId = ProviderId(provider),
                                mode = AgentMode.ACT,
                                text =
                                    "Create exactly one ZIP archive using files.archive from directory " +
                                        "scope:app:work/$marker/source to scope:app:work/$marker/result.zip. " +
                                        "Only use tools.search and files.archive; reply DONE after success. " +
                                        "Do not modify the source or repeat the archive operation.",
                                budgets = TurnBudgets(maxOutputTokens = 2048),
                                clientRequestId = UUID.randomUUID().toString(),
                            ),
                        ).value
                withTimeout(240_000) {
                    while (c.storage.turns
                            .resolve(requireNotNull(turn))
                            .state !in terminalStates
                    ) {
                        delay(200)
                    }
                }
                val record = c.storage.turns.resolve(requireNotNull(turn))
                assertEquals(record.errorCode, "COMPLETED", record.state)
                val calls = c.storage.toolCalls.listByTurn(requireNotNull(turn))
                assertEquals(1, calls.count { it.name == "files.archive" })
                assertEquals("COMPLETED", calls.single { it.name == "files.archive" }.state)
                ZipFile(archive).use { zip ->
                    val entries = zip.entries().toList().filterNot { it.isDirectory }
                    // files.archive preserves scope-relative paths, matching ArchiveToolsTest's round trip.
                    assertEquals(listOf("work/$marker/source/marker.txt"), entries.map { it.name })
                    assertEquals(marker, zip.getInputStream(entries.single()).bufferedReader().use { it.readText() })
                }
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    turn?.let {
                        c.chatService.stopTask(it)
                        val models = c.storage.modelCalls.listByTurn(it)
                        val calls = c.storage.toolCalls.listByTurn(it)
                        InstrumentationRegistry.getInstrumentation().sendStatus(
                            0,
                            android.os.Bundle().apply {
                                putString(
                                    "stream",
                                    "LIVE_ARCHIVE modelCalls=${models.size} toolCalls=${calls.size} " +
                                        "usage=${models.map { call -> call.usage }}\n",
                                )
                            },
                        )
                    }
                    c.storage.sessions.archive(session, System.currentTimeMillis())
                    check(root.deleteRecursively())
                } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                }
            }
        }

    private val terminalStates = setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED", "NEEDS_REVIEW")
}
