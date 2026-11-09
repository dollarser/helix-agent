package com.helix.app.eval

import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.app.provider.BuiltInModel
import com.helix.app.provider.ProviderTestStatusStore
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.ToolName
import com.helix.core.model.TurnState
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import com.helix.tools.framework.ToolDispatchRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Isolated-emulator test session; every effect still enters the production Dispatcher and original scope. */
internal class MobileInteractionSession(
    private val app: HelixApplication,
    packages: Set<String> = emptySet(),
) : AutoCloseable {
    private val c = app.appContainer
    private val center = AutomationPermissionCenter(app)
    private val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
    private val oldScope = lines.lines(MobileUseGrantStore.CONFIG_KEY)
    private val oldRoute = lines.lines(MobileUseGrantStore.EXECUTION_KEY)
    private val plugin = c.pluginService.list().single { it.native?.pluginId == "mobile-use" }
    private val session = "mobile-interaction-${UUID.randomUUID()}"
    private val provider = "fixture-${UUID.randomUUID()}"
    private val providerStatus = ProviderTestStatusStore(PrefsLineStore(app, "helix-ui", synchronous = true))
    private var sequence = 0
    private val diagnostics = Executors.newSingleThreadScheduledExecutor()
    val timings = JSONArray()

    init {
        check(
            c.storage.turns
                .listActive()
                .isEmpty(),
        )
        check(c.profileStore.profile == SafetyProfile.ADVANCED)
        val now = System.currentTimeMillis()
        val store = MobileUseGrantStore(lines::lines, lines::setLines, { null })
        store.configureGlobal(packages, wholePhone = packages.isEmpty())
        store.configureExecution(background = false, allowMoveFromMain = false)
        c.pluginService.setEnabled(plugin.id, true)
        // Synthetic vision-recipient fixture only: no server, credentials, probe or model request.
        // This seeded capability state tests local PNG publication, not provider/model readiness.
        val capability = BuiltInModel.capabilities.copy(source = CapabilitySource.MANUAL)
        c.storage.providerConfigs.save(
            ProviderConfigSpec(
                provider,
                "Offline image preparation fixture",
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                "http://127.0.0.1:9/v1",
                "fixture-vision",
                "{}",
                null,
                ProviderCapabilities.toJsonString(capability),
                authKind = "NONE",
            ),
        )
        providerStatus.recordPassed(provider, now, capability, listOf("fixture-vision"))
        c.storage.sessions.create(
            session,
            "Synthetic mobile acceptance",
            provider,
            "fixture-vision",
            now,
        )
        c.storage.turns.start(session, session, now)
        c.pluginService.catalog.select(session, plugin.id, true)
        c.sessionPermissionEdit.saveSessionConfig(
            session,
            SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
            now,
        )
    }

    fun dispatch(
        name: String,
        args: JsonObject,
    ): ToolDispatchOutcome {
        val descriptor = requireNotNull(c.toolPipeline.resolveLatest(name))
        val started = System.nanoTime()
        val watchdog =
            diagnostics.schedule({
                val directory = java.io.File(app.filesDir, "mobile-interaction-evidence").apply { mkdirs() }
                java.io.File(directory, "blocked-$sequence.txt").writeText(
                    Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                        "${thread.name}: ${thread.state}\n${stack.joinToString("\n") }"
                    },
                )
            }, 15, TimeUnit.SECONDS)
        val result =
            try {
                c.toolPipeline.dispatcher.dispatch(
                    ToolDispatchRequest(
                        toolCallId = "$session-${++sequence}",
                        turnId = session,
                        sessionId = session,
                        toolName = ToolName(name),
                        toolVersion = descriptor.version,
                        args = args,
                        mode = AgentMode.ACT,
                        profile = SafetyProfile.ADVANCED,
                        executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                        dataOrigin = DataOrigin.WORKSPACE,
                        scope = requireNotNull(center.conversationGrant(session)).scope,
                        uiToken = "chat:$session",
                    ),
                )
            } finally {
                watchdog.cancel(false)
            }
        timings.put(
            JSONObject()
                .put("tool", name)
                .put("elapsedMillis", (System.nanoTime() - started) / 1_000_000.0)
                .put("succeeded", result is ToolDispatchOutcome.Succeeded),
        )
        return result
    }

    fun output(
        name: String,
        args: JsonObject = JsonObject(emptyMap()),
    ): JsonObject {
        val result = dispatch(name, args)
        check(result is ToolDispatchOutcome.Succeeded) { "$name: $result" }
        return Json.parseToJsonElement(result.result.payload).jsonObject
    }

    override fun close() {
        diagnostics.shutdownNow()
        val row = c.storage.turns.resolve(session)
        val stopping = c.storage.turns.updateState(row, TurnState.CANCELLING, row.stepCount, null, null)
        c.storage.turns.updateState(stopping, TurnState.CANCELLED, row.stepCount, System.currentTimeMillis(), null)
        c.pluginService.catalog.select(session, plugin.id, false)
        center.stopSession()
        lines.setLines(MobileUseGrantStore.CONFIG_KEY, oldScope)
        lines.setLines(MobileUseGrantStore.EXECUTION_KEY, oldRoute)
        c.pluginService.setEnabled(plugin.id, plugin.enabled)
        c.storage.deleteSessionPermanently(session)
        providerStatus.clear(provider)
        c.storage.providerConfigs.delete(provider)
    }
}
