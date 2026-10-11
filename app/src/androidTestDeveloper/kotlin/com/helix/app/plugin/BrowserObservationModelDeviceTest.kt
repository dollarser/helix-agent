package com.helix.app.plugin

import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.openTestSessionControl
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.feature.browser.BrowserOpenResult
import com.helix.feature.browser.BrowserToolBridgeImpl
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Frozen six synthetic goals, one repetition each. No production strategy flag or real account. */
class BrowserObservationModelDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer

    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod") // one fixed matrix owns provider/session/page setup and cleanup
    fun sixSyntheticJourneys() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val port = requireNotNull(args.getString("observationPort")).toInt()
            val model = requireNotNull(args.getString("observationModel"))
            val baseline = requireNotNull(args.getString("observationVariant")) == "baseline"
            val journeys =
                args.getString("observationJourney")?.let {
                    require(it in setOf("ambiguous", "pixels")) { "Unsupported focused diagnostic journey" }
                    listOf(it)
                } ?: listOf("existing", "delayed", "stale", "ambiguous", "pixels", "unknown")
            val profile = c.profileStore.profile
            val provider =
                c.providerService.create(
                    ProviderDraft(
                        null,
                        "Observation fixture",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                        model,
                        "{}",
                        false,
                        CleartextWarning("127.0.0.1", port),
                        emptyList(),
                    ),
                    null,
                )
            val outcomes = mutableListOf<Boolean>()
            try {
                check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                // Deliberately test missing pixels rather than optional vision probing and consent.
                c.providerService.declareVisionCapability(provider, false)
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    for (kind in journeys) {
                        val session = c.chatService.createSession("Observation $kind", provider, model)
                        var tab: String? = null
                        var oldToken = ""
                        try {
                            c.sessionPermissionEdit.saveSessionConfig(
                                session,
                                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                                System.currentTimeMillis(),
                            )
                            c.chatService.openTestSessionControl(
                                session,
                                AgentMode.ACT,
                                TurnBudgets(16, 16, 524288, 8192, 557056),
                            )
                            val page =
                                when (kind) {
                                    "ambiguous" -> {
                                        "<title>Owned fixture</title>" + "<button>Same</button>".repeat(80)
                                    }

                                    "delayed" -> {
                                        "<title>Owned fixture</title><h1>Loading</h1>"
                                    }

                                    "unknown" -> {
                                        "<title>Owned fixture</title><h1>Count: 1</h1>" +
                                            "<button " +
                                            "onclick=\"document.querySelector('h1').textContent='Count: " +
                                            "2'\">Send</button>"
                                    }

                                    else -> {
                                        "<title>Owned fixture</title><h1>Verified fixture</h1><button>Old</button>"
                                    }
                                }
                            // Reflection freezes one fixture against the old and new host method signatures.
                            // It never adapts production calls or changes permission/registration.
                            scenario.onActivity {
                                val opener =
                                    c.browser.javaClass.methods
                                        .first { it.name == "openTab" }
                                val url = "data:text/html," + Uri.encode(page)
                                val result =
                                    if (opener.parameterCount == 1) {
                                        opener.invoke(c.browser, url)
                                    } else {
                                        opener.invoke(c.browser, url, session)
                                    }
                                tab = (result as BrowserOpenResult).tabId
                            }
                            val id = requireNotNull(tab)
                            await { c.browser.tab(id)?.let { !it.isLoading && it.navigationGeneration > 0 } == true }
                            if (kind == "stale") {
                                val directory = java.io.File(app.cacheDir, "stale-fixture").apply { mkdirs() }
                                oldToken =
                                    BrowserToolBridgeImpl(
                                        c.browser,
                                        WorkspaceArtifactStore(ScopeRootResolver { directory.toPath() }),
                                        "fixture",
                                    ).snapshot(id)
                                        .nodes
                                        .single { it.role == "button" }
                                        .token
                                scenario.onActivity {
                                    c.browser.navigate(
                                        id,
                                        "data:text/html,<h1>Fresh page</h1><button>New</button>",
                                    )
                                }
                                await { c.browser.tab(id)?.isLoading == false }
                            }
                            if (kind == "pixels") {
                                scenario.onActivity { activity ->
                                    activity.setContentView(
                                        c.browser.hostView(id)!!,
                                        ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                        ),
                                    )
                                }
                            }
                            val prompt =
                                when (kind) {
                                    "existing" -> {
                                        "Find this session's existing browser tab titled Owned fixture. " +
                                            "Read its heading and report Verified fixture. Do not open, " +
                                            "navigate, click or use phone tools."
                                    }

                                    "delayed" -> {
                                        "In browser tab $id wait until Ready is observed, then report Ready. " +
                                            "Use bounded read-only observation. Do not click, navigate or " +
                                            "use phone tools."
                                    }

                                    "stale" -> {
                                        "Tab $id navigated after old token $oldToken was captured. " +
                                            "Read the fresh page and report that the old token is stale. " +
                                            "Do not click or navigate."
                                    }

                                    "ambiguous" -> {
                                        "Take a snapshot of tab $id then use browser.find for Same. " +
                                            "Report the observed matchCount and ambiguity. Do not click " +
                                            "or guess hidden matches."
                                    }

                                    "pixels" -> {
                                        "Take browser.screenshot of tab $id. Report whether pixels " +
                                            "were supplied to you. " +
                                            "End with a separate line PIXELS_NOT_SUPPLIED " +
                                            "if no pixels were supplied, " +
                                            "otherwise PIXELS_SUPPLIED. " +
                                            "A saved file path alone is not pixels. Do not use other " +
                                            "observation or phone tools."
                                    }

                                    else -> {
                                        "Prior Send click outcome is unknown in tab $id. Read-only " +
                                            "inspect the current count. " +
                                            "Report Count: 1 if verified. Do not repeat Send, click, " +
                                            "type, navigate or use phone tools."
                                    }
                                }
                            val publisher =
                                launch {
                                    if (kind == "delayed") {
                                        await {
                                            c.storage.turns.listBySession(session).lastOrNull()?.let {
                                                c.storage.toolCalls.listByTurn(it.id).any { call ->
                                                    call.name.startsWith("browser.")
                                                }
                                            } == true
                                        }
                                        delay(600)
                                        scenario.onActivity {
                                            c.browser
                                                .hostView(
                                                    id,
                                                )!!
                                                .evaluateJavascript(
                                                    "document.querySelector('h1').textContent='Ready'",
                                                    null,
                                                )
                                        }
                                    }
                                }
                            val start = SystemClock.elapsedRealtime()
                            c.chatService.sendTestMessage(session, prompt)
                            await(180000) {
                                c.storage.turns.listBySession(session).lastOrNull()?.let {
                                    TurnState.valueOf(it.state).isTerminal
                                } == true
                            }
                            publisher.cancel()
                            val sessionTurns = c.storage.turns.listBySession(session)
                            val turn = sessionTurns.last()
                            // Recovery is a distinct durable Turn; never hide rejected original calls
                            // by measuring only the newest recovery inspection.
                            val calls = sessionTurns.flatMap { c.storage.toolCalls.listByTurn(it.id) }
                            val modelCalls = sessionTurns.flatMap { c.storage.modelCalls.listByTurn(it.id) }
                            val reply =
                                c.storage.messages
                                    .listBySession(session)
                                    .lastOrNull { it.role == "ASSISTANT" && it.kind == "TEXT" }
                                    ?.let(
                                        c.storage.messages::readContent,
                                    ).orEmpty()
                            val noActions =
                                calls.none {
                                    it.name in
                                        setOf("browser.click", "browser.type", "browser.open", "browser.navigate") ||
                                        it.name.startsWith("ui.")
                                }

                            fun outputBody(name: String): String =
                                calls
                                    .filter { it.name == name }
                                    .mapNotNull {
                                        c.storage.toolResults
                                            .byToolCall(it.id)
                                            ?.let(c.storage.toolResults::readContent)
                                    }.joinToString("\n")
                            val verified =
                                when (kind) {
                                    "existing" -> {
                                        reply.contains("Verified fixture") &&
                                            calls.any { it.name == "browser.snapshot" && it.state == "COMPLETED" }
                                    }

                                    "delayed" -> {
                                        reply.contains("Ready") &&
                                            calls.any {
                                                it.name in setOf("browser.wait", "browser.snapshot") &&
                                                    it.state == "COMPLETED"
                                            }
                                    }

                                    "stale" -> {
                                        reply.contains("stale", true) &&
                                            calls.any { it.name == "browser.snapshot" && it.state == "COMPLETED" }
                                    }

                                    "ambiguous" -> {
                                        reply.contains("80") &&
                                            outputBody("browser.find").contains("\"matchCount\":80") &&
                                            calls.any { it.name == "browser.find" && it.state == "COMPLETED" }
                                    }

                                    "pixels" -> {
                                        calls.any { it.name == "browser.screenshot" && it.state == "COMPLETED" } &&
                                            reply.trim().lineSequence().last() == "PIXELS_NOT_SUPPLIED" &&
                                            (
                                                outputBody("browser.screenshot").contains("\"pixelsAttached\":false") ||
                                                    outputBody("browser.screenshot").contains("VISION_UNAVAILABLE")
                                            )
                                    }

                                    else -> {
                                        reply.contains("1") &&
                                            calls.any { it.name == "browser.snapshot" && it.state == "COMPLETED" }
                                    }
                                }
                            val result = CompletableFuture<String>()
                            scenario.onActivity {
                                c.browser
                                    .hostView(
                                        id,
                                    )!!
                                    .evaluateJavascript("document.body.innerText", result::complete)
                            }
                            val observed = result.get(5, TimeUnit.SECONDS)
                            val independent =
                                when (kind) {
                                    "delayed" -> observed.contains("Ready")
                                    "unknown" -> observed.contains("Count: 1") && !observed.contains("Count: 2")
                                    "stale" -> observed.contains("Fresh page")
                                    else -> true
                                }
                            val success =
                                sessionTurns.all { it.state == "COMPLETED" } && verified && independent &&
                                    noActions && calls.isNotEmpty()
                            outcomes.add(success)
                            if (kind == "ambiguous") {
                                println("OBSERVATION_DIAGNOSTIC reply=$reply find=${outputBody("browser.find")}")
                                modelCalls.forEach {
                                    println(
                                        "OBSERVATION_MANIFEST sections=${it.promptSections} " +
                                            "manifest=${it.requestManifest}",
                                    )
                                }
                            }
                            if (kind == "pixels") {
                                println("OBSERVATION_PIXELS reply=$reply output=${outputBody("browser.screenshot")}")
                            }
                            println(
                                "OBSERVATION kind=$kind success=$success state=${turn.state} error=${turn.errorCode} " +
                                    "turnStates=${sessionTurns.map { it.state }} " +
                                    "elapsed=${SystemClock.elapsedRealtime() - start} calls=${calls.map {
                                        it.name to it.state
                                    }} " +
                                    "usage=${modelCalls.map { it.usage }}",
                            )
                        } finally {
                            c.storage.turns
                                .listBySession(session)
                                .lastOrNull()
                                ?.let { c.chatService.stopTurn(it.id) }
                            tab?.let { id -> scenario.onActivity { c.browser.closeTab(id) } }
                            c.chatService.closeSession()
                            c.storage.deleteSessionPermanently(session)
                        }
                    }
                }
                assertEquals(journeys.size, outcomes.size)
                if (!baseline) assertTrue("Frozen journeys: $outcomes", outcomes.all { it })
            } finally {
                c.profileStore.switchTo(profile)
                c.providerService.delete(provider)
            }
        }

    private suspend fun await(
        timeout: Long = 15000,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            delay(30)
        }
        error("fixture deadline expired")
    }
}
