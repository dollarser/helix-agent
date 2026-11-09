package com.helix.app.eval

import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
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
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.TurnEntity
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.RegisteredToolBinding
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Host owns official setup/oracle; only the real ChatService performs the supplied task. */
class AndroidWorldPilotDeviceTest {
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
            validateInputs()
            connectAutomation()
            validateScope()
            createProviderAndSession()
            configureSessionTools()
            installExecutionGuard()
            projectSession()
            submitAndAwait()
        }

        private fun validateInputs() {
            goal =
                String(Base64.decode(requireNotNull(args.getString("goalBase64")), Base64.NO_WRAP), Charsets.UTF_8)
            val goal = requireNotNull(goal)
            require(goal.isNotBlank()) { "Empty task goal" }
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
                packages.isNotEmpty() && packages.all { it.matches(Regex("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")) },
            )
        }

        private fun connectAutomation() {
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

        private fun validateScope() {
            stage = "scope_validation"
            val catalog = container.pluginService.catalog
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

        private suspend fun createProviderAndSession() {
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
            val session = requireNotNull(session)
            originalSessionPermission = container.storage.sessionPermissionConfigs.forSession(session)
            stage = "plugin_selection"
            pluginId =
                container.pluginService
                    .list()
                    .single { it.native?.pluginId == "mobile-use" }
                    .id
            val pluginId = requireNotNull(pluginId)
            container.pluginService.catalog.select(session, pluginId, true)
        }

        private fun configureSessionTools() {
            val session = requireNotNull(session)
            if (uiAssistance || fullAccess) {
                val targetPackage = taskPackage(task) ?: "com.android.systemui"
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

        private fun installExecutionGuard() {
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
                                        implementationRevision =
                                            registered.binding.implementationRevision + "-benchmark-ui-guard",
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

        private suspend fun projectSession() {
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

        private suspend fun submitAndAwait() {
            val session = requireNotNull(session)
            val goal = requireNotNull(goal)
            stage = "tool_exposure_capture"
            exposed = exposedEvaluationTools(container, AgentMode.ACT)
            stage = "message_submission"
            container.chatService.sendTestMessage(session, goal)
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

        private suspend fun restoreSessionResources(owned: String) {
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
            cleanup("deselect owned plugin") {
                pluginId?.let { container.pluginService.catalog.select(owned, it, false) }
            }
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
                restoreSessionResources(owned)
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

        private fun JsonObjectBuilder.writeIdentityEvidence(turn: TurnEntity?) {
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
            put("toolVersions", exposed)
            put("packages", JsonArray(packages.sorted().map(::JsonPrimitive)))
        }

        private fun JsonObjectBuilder.writePolicyEvidence() {
            put("uiAssistanceEnabled", uiAssistance)
            put("fullAccessRequested", fullAccess)
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
                    "Assisted UI-only: target-app actions and identified brightness seekbar"
                } else {
                    "no auto-approval; pending approval is BLOCKED"
                },
            )
        }

        private fun JsonObjectBuilder.writeApprovalEvidence() {
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

        private fun JsonObjectBuilder.writeCallEvidence(calls: List<ToolCallEntity>) {
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
        }

        private fun JsonObjectBuilder.writeTextEvidence() {
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
                    writeIdentityEvidence(turn)
                    writePolicyEvidence()
                    writeApprovalEvidence()
                    writeCallEvidence(calls)
                    writeTextEvidence()
                }
            check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            temporary.writeText(evidence.toString())
            check(temporary.renameTo(destination)) { "Could not publish attempt evidence" }
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
                        "TARGET_VALIDATION_FAILED:${error.javaClass.simpleName}"
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

    private fun taskPackage(task: String): String? =
        when (task) {
            "SystemBrightnessMin" -> null
            "ClockTimerEntry" -> "com.google.android.deskclock"
            "FilesMoveFile" -> "com.google.android.documentsui"
            "ExpenseAddSingle" -> "com.arduia.expense"
            else -> error("BLOCKED: no approved UI assistance policy for $task")
        }

    private fun safeUiApproval(
        turnId: String,
        task: String,
        name: String,
        arguments: String,
        ui: UiAutomation,
    ): Boolean {
        val target = taskPackage(task)
        val input = Json.parseToJsonElement(arguments).jsonObject
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
                active == (target ?: "com.android.systemui")
            }

            name in setOf("ui.back", "ui.home") -> {
                true
            }

            name == "ui.system" -> {
                allowedSystemAction(target, input)
            }

            name == "ui.launch" -> {
                allowedLaunch(target, input)
            }

            target != null -> {
                allowedTargetAction(target, active, name, input, appBounds)
            }

            else -> {
                allowedBrightnessAction(turnId, name, input, active)
            }
        }
    }

    private fun allowedSystemAction(
        target: String?,
        input: JsonObject,
    ): Boolean {
        val action = input["action"]?.jsonPrimitive?.content
        return action in setOf("back", "home") || (target == null && action == "quick_settings")
    }

    private fun allowedLaunch(
        target: String?,
        input: JsonObject,
    ): Boolean {
        val destination = input["packageName"]?.jsonPrimitive?.content
        return target != null && destination == target
    }

    private fun allowedTargetAction(
        target: String,
        active: String?,
        name: String,
        input: JsonObject,
        appBounds: Rect,
    ): Boolean {
        if (active != target) return false
        return when {
            name == "ui.gesture" -> {
                GuardUiGeometry.withinBounds(input, appBounds)
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
    }

    private fun allowedBrightnessAction(
        turnId: String,
        name: String,
        input: JsonObject,
        active: String?,
    ): Boolean =
        when {
            name == "ui.set_progress" && active == "com.android.systemui" -> recordedBrightnessToken(turnId, input)
            else -> false
        }

    private fun recordedBrightnessToken(
        turnId: String,
        input: JsonObject,
    ): Boolean {
        val token = input["token"]?.jsonPrimitive?.content ?: return false
        // Read existing evidence only: a fresh snapshot here would invalidate the model's token.
        val node =
            container.storage.toolCalls
                .listByTurn(turnId)
                .asReversed()
                .asSequence()
                .filter { it.name in setOf("ui.snapshot", "ui.find", "ui.wait") && it.state == "COMPLETED" }
                .mapNotNull { observation ->
                    container.storage.toolResults
                        .byToolCall(observation.callId)
                        ?.let { container.storage.toolResults.readContent(it) }
                        ?.let { findToken(Json.parseToJsonElement(it), token) }
                }.firstOrNull()
        return node != null && brightnessTokenNode(node)
    }

    private fun brightnessTokenNode(node: JsonObject): Boolean {
        val description =
            node["contentDescription"]
                ?.jsonPrimitive
                ?.content
                .orEmpty()
                .lowercase()
        val viewId = node["viewId"]?.jsonPrimitive?.content.orEmpty()
        return node["canSetProgress"]?.jsonPrimitive?.content == "true" &&
            node["redacted"]?.jsonPrimitive?.content != "true" &&
            ("brightness" in description || "亮度" in description) &&
            viewId in setOf("com.android.systemui:id/slider", "com.android.systemui:id/brightness_slider")
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
                    if (task == "SystemBrightnessMin") {
                        "BLOCKED: brightness permits navigation/quick_settings and a recorded nonredacted " +
                            "SystemUI brightness slider token only; rejected ${call.name}"
                    } else {
                        "BLOCKED: UI assistance does not permit ${call.name} for $task on the current target"
                    }
                }
                val approval = requireNotNull(container.storage.approvals.byToolCall(call.callId))
                if (approval.id !in requests) {
                    requests[approval.id] = call.callId
                    container.chatService.approveApproval(approval.id)
                }
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
}
