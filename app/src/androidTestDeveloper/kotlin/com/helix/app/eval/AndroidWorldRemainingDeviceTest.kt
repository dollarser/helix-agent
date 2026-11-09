package com.helix.app.eval

import android.app.UiAutomation
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.app.openTestSessionControl
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import com.helix.extensions.plugin.executionContext
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.RegisteredToolBinding
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Host owns official setup/oracle; only the real ChatService performs the supplied task. */
class AndroidWorldRemainingDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer

    @Test
    fun executeOfficialGoal() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("helix.androidWorld") == "true")
            FixtureRun(args).execute()
        }

    private inner class FixtureRun(
        private val args: android.os.Bundle,
    ) {
        private fun identity(name: String): String =
            requireNotNull(args.getString(name)).also {
                require(it.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "Invalid $name" }
            }

        private val runId = identity("runId")
        private val caseId = identity("caseId")
        private val task = identity("task")
        private val uiAssistance = args.getString("helix.androidWorldUiAssistance") == "true"
        private val fullAccess = args.getString("helix.androidWorldFullAccess") == "true"
        private val guardBlocks = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        private val guardChecks = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        private val originalBindings = linkedMapOf<String, List<RegisteredToolBinding>>()
        private val guardedBindings = linkedMapOf<String, List<RegisteredToolBinding>>()
        private val bindingPreflight = mutableListOf<JsonObject>()
        private var mobileUseReadyBeforeModel: Boolean? = null
        private var effectivePermissionMode: String? = null
        private var originalSessionPermission: SessionPermissionConfig? = null
        private val approvalRequests = linkedMapOf<String, String>()
        private val disabledTools = mutableListOf<com.helix.tools.framework.ToolDescriptor>()
        private val attempt = requireNotNull(args.getString("attempt")).toInt().also { require(it > 0) }
        private val destination = File(app.filesDir, "androidworld/$runId/$caseId/attempt-$attempt.json")

        init {
            check(!destination.exists()) { "Attempt evidence already exists; never overwrite or reuse it" }
        }

        private val previousSession = container.chatService.screen.value.openSessionId
        private val previous = container.chatService.runControl.value
        private val started = SystemClock.elapsedRealtime()
        private val model = args.getString("model") ?: "Qwen3.8-27B"
        private val endpoint = args.getString("endpoint") ?: "http://10.0.2.2:30008/v1"
        private var goal: String? = null
        private var provider: String? = null
        private var session: String? = null
        private var pluginId: String? = null
        private var failure: String? = null
        private var failureStackTrace: String? = null
        private var failureStage: String? = null
        private var stage = "input_validation"
        private var status = "ERROR"
        private var observedTurnState: String? = null
        private val cleanupErrors = mutableListOf<String>()
        private var exposed = JsonObject(emptyMap())
        private var packages = emptySet<String>()
        private var automation: UiAutomation? = null
        private var originalServices: String? = null
        private var serviceRestarted = false

        private val catalog get() = container.pluginService.catalog
        private lateinit var grants: MobileUseGrantStore

        suspend fun execute() {
            try {
                runAttempt()
            } catch (error: Exception) {
                failure = "${error.javaClass.simpleName}: ${error.message}"
                failureStage = stage
                failureStackTrace = error.stackTraceToString()
                status = if (error.message.orEmpty().startsWith("BLOCKED:")) "BLOCKED" else "ERROR"
            } finally {
                cleanUpAttempt()
                publishAttempt()
            }
            check(failure == null && cleanupErrors.isEmpty()) { "${failure.orEmpty()}; cleanup: $cleanupErrors" }
        }

        private suspend fun runAttempt() {
            validateInput()
            validateEmulator()
            reconnectAccessibility()
            validateScope()
            createSession()
            selectPlugin()
            restrictTools()
            installGuard()
            configureSession()
            verifyBindings()
            submitTask()
        }

        private fun validateInput() {
            goal =
                String(Base64.decode(requireNotNull(args.getString("goalBase64")), Base64.NO_WRAP), Charsets.UTF_8)
            require(requireNotNull(goal).isNotBlank()) { "Empty task goal" }
            check(
                android.os.Build.FINGERPRINT
                    .contains("generic") ||
                    android.os.Build.MODEL
                        .contains("sdk_gphone"),
            ) {
                "BLOCKED: fixture is emulator-only"
            }
            check(
                container.storage.turns
                    .listActive()
                    .isEmpty(),
            ) { "BLOCKED: unrelated active turns" }
            check(container.profileStore.profile == SafetyProfile.ADVANCED) { "BLOCKED: Advanced profile required" }
            packages = requireNotNull(args.getString("packages")).split(',').toSet()
            require(
                packages.isNotEmpty() &&
                    packages.all { it == "android" || it.matches(Regex("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")) },
            )
        }

        private fun validateEmulator() {
            stage = "isolated_emulator_validation"
            val expectedAvd = requireNotNull(args.getString("avdName"))
            require(expectedAvd.matches(Regex("Helix_Benchmark_[A-Za-z0-9_]+")))
            val ui =
                InstrumentationRegistry
                    .getInstrumentation()
                    .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation = ui
            check(readShell(ui, "getprop ro.boot.qemu.avd_name").trim() == expectedAvd) {
                "BLOCKED: only the designated isolated benchmark AVD is permitted"
            }
        }

        private fun reconnectAccessibility() {
            val ui = requireNotNull(automation)
            stage = "accessibility_reconnection"
            val center = AutomationPermissionCenter(app)
            val component = "${app.packageName}/com.helix.extensions.mobileuse.automation.HelixAccessibilityService"
            val services = readShell(ui, "settings get secure enabled_accessibility_services").trim()
            check(
                component in
                    serviceComponents(
                        services
                            .takeUnless {
                                it == "null"
                            }.orEmpty(),
                    ),
            ) { "BLOCKED: Helix Accessibility was not already granted" }
            check(readShell(ui, "settings get secure accessibility_enabled").trim() == "1") {
                "BLOCKED: Accessibility was not already enabled"
            }
            originalServices = services
            reconnectGrantedService(ui, center, component, services)
        }

        private fun validateScope() {
            stage = "scope_validation"
            val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
            grants =
                MobileUseGrantStore(lines::lines, lines::setLines, { catalog.selectionId(it, "mobile-use") })
            val grant = grants.globalConfiguration()
            check(
                lines.lines(MobileUseGrantStore.CONFIG_KEY).isNotEmpty() && grant != null &&
                    !grant.scope.allApplications && grant.scope.allowedPackages == packages &&
                    grant.shareScreens && !grant.backgroundDisplay && !grant.allowMoveFromMain,
            ) { "BLOCKED: exact preauthorized package/screen/main-display scope required" }
        }

        private suspend fun createSession() {
            stage = "provider_creation"
            provider =
                container.providerService.create(
                    ProviderDraft(
                        null,
                        "AndroidWorld-$runId",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse(endpoint),
                        model,
                        "{}",
                        false,
                    ),
                    null,
                )
            val provider = requireNotNull(provider)
            stage = "provider_connection_test"
            val probe = container.providerService.runConnectionTest(provider)
            check(probe is com.helix.provider.api.ProbeOutcome.Ok) { "Provider connection failed: $probe" }
            stage = "session_creation"
            session = container.chatService.createSession("AndroidWorld-$caseId-$attempt", provider, model)
            originalSessionPermission = container.storage.sessionPermissionConfigs.forSession(requireNotNull(session))
        }

        private suspend fun selectPlugin() {
            val session = requireNotNull(session)
            stage = "plugin_selection"
            pluginId =
                container.pluginService
                    .list()
                    .single { it.native?.pluginId == "mobile-use" }
                    .id
            catalog.select(session, requireNotNull(pluginId), true)
        }

        private suspend fun restrictTools() {
            val session = requireNotNull(session)
            if (uiAssistance || fullAccess) {
                val targetPackage = taskPackage(task)
                if (targetPackage == "com.android.chrome") {
                    check("com.google.android.documentsui" in packages) {
                        "BLOCKED: browser file-manager prerequisite is outside the finite grant"
                    }
                }
                check(targetPackage in packages) { "BLOCKED: task target is outside the approved package grant" }
                container.toolPipeline.registry
                    .all()
                    .filterNot { tool ->
                        val discovery =
                            tool.name.value == "tools.search" &&
                                tool.operationClass == ToolOperationClass.READ_ONLY
                        tool.name.value.startsWith("ui.") || discovery
                    }.forEach { tool ->
                        container.storage.toolAvailability.set(
                            tool.origin.canonicalOf(),
                            tool.name.value,
                            ToolAvailabilityScope.SESSION,
                            session,
                            ToolAvailabilityState.DISABLED,
                            System.currentTimeMillis(),
                        )
                        disabledTools += tool
                    }
            }
        }

        private suspend fun installGuard() {
            val session = requireNotNull(session)
            if (fullAccess) {
                stage = "install_test_only_ui_execution_guard"
                val ownedSession = session
                val ui = requireNotNull(automation)
                val groups =
                    container.toolPipeline.registry
                        .snapshot()
                        .groupBy { it.ref.owner }
                groups
                    .filterValues { rows ->
                        rows.any {
                            it.descriptor.name.value
                                .startsWith("ui.")
                        }
                    }.forEach { (owner, rows) ->
                        originalBindings[owner] = rows
                        val replacements =
                            rows.map { registered ->
                                if (!registered.descriptor.name.value
                                        .startsWith("ui.")
                                ) {
                                    registered.binding
                                } else {
                                    registered.binding.copy(
                                        executor =
                                            guardExecutor(
                                                registered,
                                                ownedSession,
                                                task,
                                                ui,
                                                guardBlocks,
                                                guardChecks,
                                            ),
                                    )
                                }
                            }
                        guardedBindings[owner] = container.toolPipeline.registry.replaceOwner(owner, replacements)
                    }
                stage = "set_owned_session_full_access"
                container.sessionPermissionEdit.saveSessionConfig(
                    ownedSession,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
            }
        }

        private suspend fun configureSession() {
            val session = requireNotNull(session)
            effectivePermissionMode =
                container.storage.sessionPermissionConfigs
                    .forSession(session)
                    ?.mode
                    ?.name
            check(!fullAccess || effectivePermissionMode == SessionPermissionMode.FULL_ACCESS.name)
            stage = "session_projection_and_configuration"
            container.chatService.openTestSessionControl(
                session,
                AgentMode.ACT,
                com.helix.core.agent.TurnBudgetBounds
                    .validate(TurnBudgets(maxOutputTokens = 4096)),
            )
        }

        private fun verifyBindings() {
            val session = requireNotNull(session)
            if (fullAccess) {
                stage = "guarded_plugin_scope_preflight"
                val plugins = requireNotNull(container.toolPipeline.plugins)
                mobileUseReadyBeforeModel = plugins.ready("mobile-use")
                val expectedScope = grants.find(session)?.scope?.toScopeRef()
                guardedBindings.forEach { (owner, rows) ->
                    rows
                        .filter {
                            it.descriptor.name.value
                                .startsWith("ui.")
                        }.forEach { guarded ->
                            val original =
                                requireNotNull(originalBindings[owner]).single {
                                    it.descriptor.name == guarded.descriptor.name &&
                                        it.descriptor.version == guarded.descriptor.version
                                }
                            val context = plugins.executionContext(guarded.descriptor, session)
                            val actualScope = context?.scope?.toScopeRef()
                            val oldRevision = original.binding.implementationRevision
                            val newRevision = guarded.binding.implementationRevision
                            val sameRevision = oldRevision == newRevision
                            val preservedContract = original.descriptor == guarded.descriptor && sameRevision
                            val freshIncarnation = original.ref.incarnation != guarded.ref.incarnation
                            val resolved = container.toolPipeline.registry.resolveBinding(guarded.ref)
                            val currentBinding = resolved == guarded
                            val scopeMatches = expectedScope != null && actualScope == expectedScope
                            bindingPreflight +=
                                buildJsonObject {
                                    put("tool", guarded.descriptor.name.value)
                                    put("owner", owner)
                                    put("implementationRevision", guarded.binding.implementationRevision)
                                    put("oldIncarnation", original.ref.incarnation)
                                    put("newIncarnation", guarded.ref.incarnation)
                                    put("preservedContract", preservedContract)
                                    put("freshIncarnation", freshIncarnation)
                                    put("currentBinding", currentBinding)
                                    put("expectedScope", expectedScope)
                                    put("actualScope", actualScope)
                                    put("scopeMatches", scopeMatches)
                                }
                            check(preservedContract && freshIncarnation && currentBinding && scopeMatches) {
                                "Guarded Mobile Use binding/scope preflight failed: ${guarded.descriptor.name.value}"
                            }
                        }
                }
                check(mobileUseReadyBeforeModel == true && bindingPreflight.isNotEmpty()) {
                    "Guarded Mobile Use plugin is not ready before model submission"
                }
            }
        }

        private suspend fun submitTask() {
            val session = requireNotNull(session)
            stage = "tool_exposure_capture"
            exposed = exposedEvaluationTools(container, AgentMode.ACT)
            stage = "message_submission"
            container.chatService.sendTestMessage(session, requireNotNull(goal))
            stage = "await_terminal"
            awaitTerminal(
                session,
                task,
                uiAssistance && !fullAccess,
                requireNotNull(automation),
                approvalRequests,
                guardBlocks,
            )
            status = "EXECUTED"
        }

        private fun reconnectGrantedService(
            ui: UiAutomation,
            center: AutomationPermissionCenter,
            component: String,
            services: String,
        ) {
            if (center.serviceState() != AutomationServiceState.CONNECTED) {
                serviceRestarted = true
                val remaining = services.split(':').filter { it != component }.joinToString(":")
                try {
                    writeServices(ui, remaining)
                    SystemClock.sleep(250)
                } finally {
                    writeServices(ui, services)
                }
            }
            val connectDeadline = SystemClock.elapsedRealtime() + 15_000
            while (center.serviceState() != AutomationServiceState.CONNECTED &&
                SystemClock.elapsedRealtime() < connectDeadline
            ) {
                SystemClock.sleep(100)
            }
            check(center.serviceState() == AutomationServiceState.CONNECTED) {
                "BLOCKED: preauthorized Accessibility did not reconnect"
            }
        }

        private suspend fun cleanup(
            name: String,
            action: suspend () -> Unit,
        ) {
            try {
                action()
            } catch (error: Exception) {
                cleanupErrors += "$name: ${error.javaClass.simpleName}: ${error.message}"
            }
        }

        private suspend fun cleanUpAttempt() {
            if (guardBlocks.isNotEmpty()) {
                status = "BLOCKED"
                failure = "Test-only UI execution safety boundary rejected a call"
                failureStage = "execution_safety_guard"
            }
            observedTurnState =
                session?.let {
                    container.storage.turns
                        .listBySession(it)
                        .lastOrNull()
                        ?.state
                }

            // Stop only this attempt and join physical execution before restoring selection.
            var ownedStopped = session == null
            session?.let { owned ->
                cleanup("stop owned execution") {
                    stopAndJoinOwnedVisualCase(container, owned)
                    ownedStopped = true
                }
                if (!ownedStopped) return@let
                restoreOwnedSession(owned)
            }
            if (ownedStopped) {
                cleanup("delete owned provider") { provider?.let { container.providerService.delete(it) } }
            }
            cleanup("restore preauthorized accessibility list") {
                if (serviceRestarted) {
                    writeServices(requireNotNull(automation), requireNotNull(originalServices))
                }
            }
        }

        private suspend fun restoreOwnedSession(owned: String) {
            if (fullAccess) {
                cleanup("restore owned session permission mode") {
                    container.sessionPermissionEdit.saveSessionConfig(
                        owned,
                        requireNotNull(originalSessionPermission),
                        System.currentTimeMillis(),
                    )
                }
            }
            restoreExecutors()
            restoreAvailability(owned)
            cleanup("deselect owned plugin") {
                pluginId?.let { container.pluginService.catalog.select(owned, it, false) }
            }
            restoreProjection(owned)
        }

        private suspend fun restoreExecutors() {
            cleanup("restore original UI executors") {
                originalBindings.forEach { (owner, rows) ->
                    val expected = requireNotNull(guardedBindings[owner])
                    val current =
                        container.toolPipeline.registry
                            .snapshot()
                            .filter { it.ref.owner == owner }
                    check(current.map { it.ref }.toSet() == expected.map { it.ref }.toSet()) {
                        "Registry changed concurrently; restoration refused"
                    }
                    container.toolPipeline.registry.replaceOwner(owner, rows.map { it.binding })
                }
            }
        }

        private suspend fun restoreAvailability(owned: String) {
            cleanup("restore owned tool availability") {
                disabledTools.forEach { tool ->
                    container.storage.toolAvailability.remove(
                        tool.origin.canonicalOf(),
                        tool.name.value,
                        ToolAvailabilityScope.SESSION,
                        owned,
                    )
                }
            }
        }

        private suspend fun restoreProjection(owned: String) {
            cleanup("restore session") {
                if (container.chatService.screen.value.openSessionId == owned) {
                    container.chatService.closeSession()
                    previousSession?.let { container.chatService.openSession(it) }
                    container.chatService.setMode(previous.mode)
                    container.chatService.setTurnBudgets(previous.budgets)
                } else {
                    check(container.chatService.screen.value.openSessionId == previousSession) {
                        "Session changed concurrently; not overwritten"
                    }
                }
            }
        }

        private fun publishAttempt() {
            val turn =
                session?.let {
                    container.storage.turns
                        .listBySession(it)
                        .lastOrNull()
                }
            val calls = turn?.let { container.storage.toolCalls.listByTurn(it.id) }.orEmpty()
            val evidence =
                buildJsonObject {
                    identityEvidence(turn)
                    policyEvidence()
                    approvalEvidence()
                    resultEvidence(calls)
                }
            check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            temporary.writeText(evidence.toString())
            check(temporary.renameTo(destination)) { "Could not publish attempt evidence" }
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.identityEvidence(
            turn: com.helix.core.storage.entity.TurnEntity?,
        ) {
            put("runId", runId)
            put("avdName", args.getString("avdName"))
            put("preauthorizedServiceRestarted", serviceRestarted)
            put("caseId", caseId)
            put("task", task)
            put("attempt", attempt)
            put("goal", goal)
            put("providerId", provider)
            put("model", model)
            put("endpoint", endpoint)
            put("sessionId", session)
            put("turnId", turn?.id)
            put("status", status)
            put("failure", failure)
            put("failureStage", failureStage)
            put("failureStackTrace", failureStackTrace)
            put("cleanupErrors", JsonArray(cleanupErrors.map(::JsonPrimitive)))
            put("turnState", observedTurnState)
            put("settledTurnState", turn?.state)
            put("errorCode", turn?.errorCode)
            put("elapsedMs", SystemClock.elapsedRealtime() - started)
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.policyEvidence() {
            put("toolVersions", exposed)
            put("packages", JsonArray(packages.sorted().map(::JsonPrimitive)))
            put("uiAssistanceEnabled", uiAssistance)
            put("fullAccessRequested", fullAccess)
            put(
                "remainingTaskPolicy",
                if (task.startsWith("Browser")) {
                    "Files task.html -> Chrome resolver -> verified local URI and renderer only"
                } else {
                    "Recorder app only; host audio/permission preflight required"
                },
            )
            put("effectiveSessionPermissionMode", effectivePermissionMode)
            put(
                "finalSessionPermissionMode",
                session?.let {
                    container.storage.sessionPermissionConfigs
                        .forSession(it)
                        ?.mode
                        ?.name
                },
            )
            put("mobileUseReadyBeforeModel", mobileUseReadyBeforeModel)
            put("bindingPreflight", JsonArray(bindingPreflight))
            put(
                "readOnlyObservationPolicy",
                "Finite approved stage packages; original production scope checks retained",
            )
            put("testOnlyExecutionGuard", fullAccess)
            put("scopeBlocks", JsonArray(guardBlocks.toList()))
            put("executionGuardChecks", JsonArray(guardChecks.toList()))
            put(
                "fullAccessAdaptation",
                if (fullAccess) {
                    "Session FULL_ACCESS with test-only UI/task safety guard; not unrestricted product score"
                } else {
                    "none"
                },
            )
            put(
                "approvalPolicy",
                if (fullAccess) {
                    "No per-call approvals; FULL_ACCESS with independent pre-executor safety guard"
                } else if (uiAssistance) {
                    "Assisted UI-only: recorder or verified local browser task stages"
                } else {
                    "no auto-approval; pending approval is BLOCKED"
                },
            )
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.approvalEvidence() {
            put(
                "approvalRequests",
                JsonArray(
                    approvalRequests.map { (approvalId, callId) ->
                        val approval = container.storage.approvals.byToolCall(callId)
                        buildJsonObject {
                            put("approvalId", approvalId)
                            put("callId", callId)
                            put("decision", approval?.decision)
                            put("decidedAt", approval?.decidedAt)
                            put("consumedAt", approval?.consumedAt)
                        }
                    },
                ),
            )
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.resultEvidence(
            calls: List<com.helix.core.storage.entity.ToolCallEntity>,
        ) {
            put("maxOutputTokens", 4096)
            put("timeoutSeconds", 240)
            put(
                "calls",
                JsonArray(
                    calls.map { call ->
                        val result = container.storage.toolResults.byToolCall(call.callId)
                        buildJsonObject {
                            put("name", call.name)
                            put("args", call.argsJson)
                            put("state", call.state)
                            put("resultStatus", result?.status)
                            put("resultSummary", result?.summary)
                            put("result", result?.let { container.storage.toolResults.readContent(it) })
                        }
                    },
                ),
            )
            put(
                "text",
                session?.let { id ->
                    container.storage.messages
                        .listBySession(id)
                        .filter {
                            it.role == "ASSISTANT" &&
                                it.kind == "TEXT"
                        }.mapNotNull { container.storage.messages.readContent(it) }
                        .joinToString("\n")
                },
            )
        }
    }

    private fun readShell(
        ui: UiAutomation,
        command: String,
    ): String =
        ui.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { String(it.readBytes(), Charsets.UTF_8) }
        }

    private fun writeServices(
        ui: UiAutomation,
        services: String,
    ) {
        // UiAutomation tokenizes arguments itself; shell quote characters would become data.
        val expected = serviceComponents(services)
        val command =
            if (expected.isEmpty()) {
                "settings delete secure enabled_accessibility_services"
            } else {
                "settings put secure enabled_accessibility_services ${expected.joinToString(":")}"
            }
        readShell(ui, command)
        val current = readShell(ui, "settings get secure enabled_accessibility_services").trim()
        check(serviceComponents(current.takeUnless { it == "null" }.orEmpty()) == expected) {
            "Accessibility component grant set changed during restoration"
        }
    }

    private fun serviceComponents(services: String): Set<String> {
        if (services.isEmpty()) return emptySet()
        val components = services.split(':')
        require(components.all { it.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) }) {
            "Invalid Accessibility component list; no settings command was issued"
        }
        return components.toSet()
    }

    private fun guardExecutor(
        original: RegisteredToolBinding,
        ownedSession: String,
        task: String,
        ui: UiAutomation,
        blocks: MutableList<JsonObject>,
        checks: MutableList<JsonObject>,
    ): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.sessionId != ownedSession) return original.executor.execute(call)
                val reason =
                    try {
                        val ownedTurn = call.turnId?.let { container.storage.turns.resolve(it) }
                        if (ownedTurn?.sessionId != ownedSession) {
                            "TURN_OWNERSHIP_MISMATCH"
                        } else if (!safeUiApproval(
                                requireNotNull(call.turnId),
                                task,
                                call.toolName,
                                call.args.toString(),
                                ui,
                            )
                        ) {
                            "OUTSIDE_TASK_UI_SCOPE_OR_UNVERIFIED_TARGET"
                        } else {
                            null
                        }
                    } catch (error: Exception) {
                        "TARGET_VALIDATION_FAILED:" +
                            (error.message?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: error.javaClass.simpleName)
                    }
                val event =
                    buildJsonObject {
                        put("callId", call.toolCallId)
                        put("turnId", call.turnId)
                        put("tool", call.toolName)
                        put("allowedToDelegate", reason == null)
                        put("reason", reason)
                    }
                checks += event
                return if (reason != null) {
                    blocks += event
                    ToolExecutorResult.Failed("BENCHMARK_SCOPE_BLOCKED:$reason", sideEffectFree = true)
                } else {
                    original.executor.execute(call)
                }
            }
        }

    private fun taskPackage(task: String): String =
        when (task) {
            "AudioRecorderRecordAudio", "AudioRecorderRecordAudioWithFileName" -> "com.dimowner.audiorecorder"
            "BrowserDraw", "BrowserMaze" -> "com.android.chrome"
            else -> error("BLOCKED: task is not in the fixed remaining manifest: $task")
        }

    private data class BrowserNode(
        val text: String,
        val description: String,
        val viewId: String,
        val className: String,
        val bounds: Rect,
        val inRenderer: Boolean,
        val clickable: Boolean,
    )

    private fun browserTree(ui: UiAutomation): Pair<String?, List<BrowserNode>> {
        val root = ui.rootInActiveWindow ?: error("BROWSER_ACTIVE_WINDOW_UNAVAILABLE")
        val nodes = mutableListOf<BrowserNode>()
        var visited = 0

        fun visit(
            node: AccessibilityNodeInfo,
            renderer: Boolean,
        ) {
            check(++visited <= 5000) { "BROWSER_TREE_TOO_LARGE" }
            val className = node.className?.toString().orEmpty()
            val inside = renderer || className == "android.webkit.WebView"
            if (node.isVisibleToUser) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                nodes +=
                    BrowserNode(
                        node.text?.toString().orEmpty(),
                        node.contentDescription?.toString().orEmpty(),
                        node.viewIdResourceName.orEmpty(),
                        className,
                        bounds,
                        inside,
                        node.isClickable,
                    )
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    visit(child, inside)
                } finally {
                    child.recycle()
                }
            }
        }
        return try {
            val packageName = root.packageName?.toString()
            visit(root, false)
            packageName to nodes
        } finally {
            root.recycle()
        }
    }

    private fun observedBrowserNode(
        turnId: String,
        input: JsonObject,
    ): JsonObject? {
        val token = input["token"]?.jsonPrimitive?.content ?: return null
        return container.storage.toolCalls
            .listByTurn(turnId)
            .asReversed()
            .asSequence()
            .filter { it.state == "COMPLETED" && it.name in setOf("ui.snapshot", "ui.find", "ui.wait") }
            .mapNotNull { container.storage.toolResults.byToolCall(it.callId) }
            .mapNotNull { container.storage.toolResults.readContent(it) }
            .firstNotNullOfOrNull { findToken(Json.parseToJsonElement(it), token) }
    }

    private fun selectedBrowserNode(
        turnId: String,
        name: String,
        input: JsonObject,
        nodes: List<BrowserNode>,
    ): BrowserNode? =
        when (name) {
            "ui.click" -> observedBrowserNode(turnId, input)?.let { matchObservedNode(it, nodes) }
            "ui.click_match" -> matchBrowserNode(input, nodes)
            else -> null
        }

    private fun matchObservedNode(
        observed: JsonObject,
        nodes: List<BrowserNode>,
    ): BrowserNode? {
        val text = observed["text"]?.jsonPrimitive?.content.orEmpty()
        val description = observed["contentDescription"]?.jsonPrimitive?.content.orEmpty()
        val viewId = observed["viewId"]?.jsonPrimitive?.content.orEmpty()
        val bounds = observed["bounds"]?.jsonObject ?: return null
        val rectangle =
            Rect(
                bounds
                    .getValue("left")
                    .jsonPrimitive.content
                    .toInt(),
                bounds
                    .getValue("top")
                    .jsonPrimitive.content
                    .toInt(),
                bounds
                    .getValue("right")
                    .jsonPrimitive.content
                    .toInt(),
                bounds
                    .getValue("bottom")
                    .jsonPrimitive.content
                    .toInt(),
            )
        return nodes.singleOrNull {
            it.text == text && it.description == description && it.viewId == viewId && it.bounds == rectangle
        }
    }

    private fun matchBrowserNode(
        input: JsonObject,
        nodes: List<BrowserNode>,
    ): BrowserNode? {
        if (input["match"]?.jsonPrimitive?.content?.lowercase() !in setOf(null, "exact")) return null
        val text = input["text"]?.jsonPrimitive?.content
        val description = input["contentDescription"]?.jsonPrimitive?.content
        val viewId = input["viewId"]?.jsonPrimitive?.content
        val hasSelector = text != null || description != null || viewId != null
        return if (hasSelector) {
            nodes.singleOrNull { matchesBrowserFields(it, text, description, viewId) }
        } else {
            null
        }
    }

    private fun matchesBrowserFields(
        node: BrowserNode,
        text: String?,
        description: String?,
        viewId: String?,
    ): Boolean =
        (text == null || node.text == text) &&
            (description == null || node.description == description) && (viewId == null || node.viewId == viewId)

    private fun verifiedLocalTaskUri(value: String): Boolean {
        val uri = Uri.parse(value)
        if (uri.query != null || uri.fragment != null) return false
        return (
            uri.scheme == "file" && uri.authority.isNullOrEmpty() &&
                uri.path == "/storage/emulated/0/Download/task.html"
        ) ||
            (
                uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents" &&
                    uri.path == "/document/primary:Download/task.html"
            )
    }

    private fun safeBrowserAction(
        turnId: String,
        task: String,
        name: String,
        arguments: String,
        ui: UiAutomation,
    ): Boolean {
        check(task in setOf("BrowserDraw", "BrowserMaze")) { "BROWSER_TASK_UNSUPPORTED" }
        val input = Json.parseToJsonElement(arguments).jsonObject
        return when {
            name in setOf("ui.device", "ui.apps", "tools.search") -> {
                container.toolPipeline.resolveLatest(name)?.operationClass == ToolOperationClass.READ_ONLY
            }

            name in setOf("ui.back", "ui.home") -> {
                true
            }

            name == "ui.system" -> {
                input["action"]?.jsonPrimitive?.content in setOf("back", "home")
            }

            name == "ui.launch" -> {
                input["packageName"]?.jsonPrimitive?.content in
                    setOf("com.google.android.documentsui", "com.helix.agent.developer")
            }

            else -> {
                safeBrowserStage(turnId, name, input, ui)
            }
        }
    }

    private fun safeBrowserStage(
        turnId: String,
        name: String,
        input: JsonObject,
        ui: UiAutomation,
    ): Boolean {
        val (active, nodes) = browserTree(ui)
        val browserPackages =
            setOf(
                "com.google.android.documentsui",
                "com.android.chrome",
                "android",
                "com.android.intentresolver",
            )
        check(active in browserPackages) { "BROWSER_STAGE_PACKAGE_UNVERIFIED" }
        return when {
            name in setOf("ui.snapshot", "ui.find", "ui.wait") -> true

            name == "ui.click_match" && input["packageName"] != null &&
                input["packageName"]?.jsonPrimitive?.content != active -> false

            active == "com.google.android.documentsui" -> safeFilesAction(turnId, name, input, nodes)

            active in setOf("android", "com.android.intentresolver") -> safeResolverAction(turnId, name, input, nodes)

            else -> safeRendererAction(turnId, name, input, nodes)
        }
    }

    private fun safeFilesAction(
        turnId: String,
        name: String,
        input: JsonObject,
        nodes: List<BrowserNode>,
    ): Boolean {
        if (name in setOf("ui.scroll", "ui.screenshot")) return true
        return selectedBrowserNode(turnId, name, input, nodes)?.let { selected ->
            selected.text in setOf("Downloads", "Download", "task.html") ||
                selected.description in setOf("Show roots", "Show navigation drawer")
        } ?: false
    }

    private fun safeResolverAction(
        turnId: String,
        name: String,
        input: JsonObject,
        nodes: List<BrowserNode>,
    ): Boolean {
        check(
            nodes.any {
                it.viewId in
                    setOf(
                        "android:id/resolver_list",
                        "android:id/resolver_drawer",
                        "com.android.intentresolver:id/resolver_list",
                        "com.android.intentresolver:id/resolver_drawer",
                    )
            },
        ) {
            "BROWSER_RESOLVER_IDENTITY_UNVERIFIED"
        }
        val selected = selectedBrowserNode(turnId, name, input, nodes) ?: return false
        return selected.text == "Chrome" || (
            selected.text in setOf("Just once", "JUST ONCE") &&
                selected.viewId in setOf("android:id/button_once", "com.android.intentresolver:id/button_once")
        )
    }

    private fun safeRendererAction(
        turnId: String,
        name: String,
        input: JsonObject,
        nodes: List<BrowserNode>,
    ): Boolean {
        val renderer = verifiedRenderer(nodes)
        return when (name) {
            "ui.screenshot" -> {
                true
            }

            "ui.gesture" -> {
                BrowserGuardGeometry.gestureInsideBounds(input, renderer.bounds)
            }

            "ui.scroll" -> {
                safeRendererScroll(turnId, input, nodes, renderer.bounds)
            }

            else -> {
                selectedBrowserNode(turnId, name, input, nodes)?.let {
                    it.inRenderer && !it.bounds.isEmpty && renderer.bounds.contains(it.bounds)
                } ?: false
            }
        }
    }

    private fun verifiedRenderer(nodes: List<BrowserNode>): BrowserNode {
        val address = nodes.singleOrNull { !it.inRenderer && it.viewId == "com.android.chrome:id/url_bar" }
        check(address != null && verifiedLocalTaskUri(address.text)) { "BROWSER_LOCAL_URI_UNVERIFIED" }
        val renderer = nodes.singleOrNull { it.className == "android.webkit.WebView" && !it.bounds.isEmpty }
        check(renderer != null && !Rect.intersects(renderer.bounds, address.bounds)) {
            "BROWSER_RENDERER_BOUNDS_UNVERIFIED"
        }
        check(nodes.none { !it.inRenderer && it.clickable && Rect.intersects(it.bounds, renderer.bounds) }) {
            "BROWSER_NATIVE_OVERLAY_PRESENT"
        }
        return renderer
    }

    private fun safeRendererScroll(
        turnId: String,
        input: JsonObject,
        nodes: List<BrowserNode>,
        bounds: Rect,
    ): Boolean {
        val tokenNode = observedBrowserNode(turnId, input) ?: return false
        return selectedBrowserNode(turnId, "ui.click", input, nodes)?.let { selected ->
            tokenNode["scrollable"]?.jsonPrimitive?.content == "true" && selected.inRenderer &&
                !selected.bounds.isEmpty && bounds.contains(selected.bounds)
        } ?: false
    }

    private fun safeUiApproval(
        turnId: String,
        task: String,
        name: String,
        arguments: String,
        ui: UiAutomation,
    ): Boolean {
        val target = taskPackage(task)
        return when {
            name in setOf("ui.snapshot", "ui.find", "ui.wait", "ui.screenshot") -> {
                safeObservation(turnId, name, ui)
            }

            target == "com.android.chrome" -> {
                safeBrowserAction(turnId, task, name, arguments, ui)
            }

            else -> {
                safeRecorderAction(target, name, Json.parseToJsonElement(arguments).jsonObject, ui)
            }
        }
    }

    private fun safeObservation(
        turnId: String,
        name: String,
        ui: UiAutomation,
    ): Boolean {
        val root = ui.rootInActiveWindow
        val activePackage =
            try {
                root?.packageName?.toString()
            } finally {
                root?.recycle()
            }
        val observationPackages =
            setOf(
                "com.google.android.apps.nexuslauncher",
                "com.google.android.documentsui",
                "com.android.chrome",
                "com.dimowner.audiorecorder",
                "android",
            )
        val sessionId =
            container.storage.turns
                .resolve(turnId)
                ?.sessionId ?: return false
        val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
        val grants =
            MobileUseGrantStore(lines::lines, lines::setLines, {
                container.pluginService.catalog.selectionId(it, "mobile-use")
            })
        return grants.find(sessionId)?.scope?.let { scope ->
            activePackage in observationPackages && activePackage in scope.allowedPackages &&
                !scope.allApplications &&
                container.toolPipeline.resolveLatest(name)?.operationClass == ToolOperationClass.READ_ONLY
        } ?: false
    }

    private fun safeRecorderAction(
        target: String,
        name: String,
        input: JsonObject,
        ui: UiAutomation,
    ): Boolean {
        val root = ui.rootInActiveWindow
        val appBounds = Rect()
        root?.getBoundsInScreen(appBounds)
        val active =
            try {
                root?.packageName?.toString()
            } finally {
                root?.recycle()
            }
        return when {
            name in setOf("ui.device", "ui.apps", "tools.search") -> {
                container.toolPipeline.resolveLatest(name)?.operationClass == ToolOperationClass.READ_ONLY
            }

            name in setOf("ui.snapshot", "ui.find", "ui.wait") -> {
                active == target
            }

            name in setOf("ui.back", "ui.home") -> {
                true
            }

            name == "ui.system" -> {
                input["action"]?.jsonPrimitive?.content in setOf("back", "home")
            }

            name == "ui.launch" -> {
                input["packageName"]?.jsonPrimitive?.content == target
            }

            active != target -> {
                false
            }

            else -> {
                safeRecorderInteraction(target, name, input, appBounds)
            }
        }
    }

    private fun safeRecorderInteraction(
        target: String,
        name: String,
        input: JsonObject,
        bounds: Rect,
    ): Boolean =
        when {
            name == "ui.gesture" -> {
                !bounds.isEmpty && BrowserGuardGeometry.gestureInsideBounds(input, bounds)
            }

            name == "ui.click_match" && input["packageName"] != null &&
                input["packageName"]?.jsonPrimitive?.content != target -> {
                false
            }

            else -> {
                name in
                    setOf(
                        "ui.click",
                        "ui.click_match",
                        "ui.long_click",
                        "ui.set_text",
                        "ui.ime_enter",
                        "ui.scroll",
                        "ui.set_progress",
                        "ui.screenshot",
                    )
            }
        }

    private fun findToken(
        value: JsonElement,
        token: String,
    ): JsonObject? =
        when (value) {
            is JsonObject -> {
                if (value["token"]?.let { (it as? JsonPrimitive)?.content } == token) {
                    value
                } else {
                    value.values.firstNotNullOfOrNull { findToken(it, token) }
                }
            }

            is JsonArray -> {
                value.firstNotNullOfOrNull { findToken(it, token) }
            }

            else -> {
                null
            }
        }

    private fun awaitTerminal(
        session: String,
        task: String,
        assisted: Boolean,
        ui: UiAutomation,
        requests: MutableMap<String, String>,
        guardBlocks: List<JsonObject>,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 240_000
        while (SystemClock.elapsedRealtime() < deadline) {
            check(guardBlocks.isEmpty()) { "BLOCKED: test-only UI execution safety boundary rejected a call" }
            val turn =
                container.storage.turns
                    .listBySession(session)
                    .lastOrNull()
            if (turn != null) {
                approvePendingCalls(turn.id, task, assisted, ui, requests)
                if (TurnState.valueOf(turn.state).isTerminal) return
            }
            Thread.sleep(100)
        }
        error("AndroidWorld attempt exceeded 240 seconds")
    }

    private fun approvePendingCalls(
        turnId: String,
        task: String,
        assisted: Boolean,
        ui: UiAutomation,
        requests: MutableMap<String, String>,
    ) {
        container.storage.toolCalls
            .listByTurn(turnId)
            .filter { it.state == "AWAITING_APPROVAL" }
            .forEach { call ->
                check(assisted && safeUiApproval(turnId, task, call.name, call.argsJson, ui)) {
                    "BLOCKED: UI assistance does not permit ${call.name} for $task on the current target"
                }
                val approval = requireNotNull(container.storage.approvals.byToolCall(call.callId))
                if (approval.id !in requests) {
                    requests[approval.id] = call.callId
                    container.chatService.approveApproval(approval.id)
                }
            }
    }
}
