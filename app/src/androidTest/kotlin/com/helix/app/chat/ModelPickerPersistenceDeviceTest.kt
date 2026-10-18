package com.helix.app.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.profile.PersistedSafetyProfileStore
import com.helix.app.provider.BuiltInModel
import com.helix.app.provider.BuiltInModelCatalog
import com.helix.app.provider.BuiltInModelRegistration
import com.helix.app.provider.ProviderFactory
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTestStatusStore
import com.helix.app.sendTestMessage
import com.helix.app.ui.ComposerModelMenu
import com.helix.core.agent.RunControlConfig
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.ScopeRootResolver
import com.helix.feature.files.AttachmentImporter
import com.helix.feature.files.SafImportPipeline
import com.helix.feature.files.SafSourceOpener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Production ChatService + isolated Room + scripted wire. Never changes installed user records. */
class ModelPickerPersistenceDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anActiveTurnRejectsSaveWithoutChangingTheRequestOrStoredSelection() =
        runBlocking<Unit> {
            Fixture().use { f ->
                f.open()
                assertEquals(
                    SessionModelSelectionResult.APPLIED,
                    f.chat
                        .requestSessionModelSelection(
                            "saved",
                            BuiltInModel.PROVIDER_ID,
                            BuiltInModel.MODEL_ID,
                            ReasoningEffort.LOW,
                        ).await(),
                )
                val before = f.storage.sessions.resolve("saved")
                val controls = f.storage.sessionRunControls.forSession("saved")
                f.wire.script(stalledResponse("data: {\"choices\":[{\"delta\":{\"content\":\"waiting\"}}]}\n\n"))
                f.chat.sendTestMessage("Isolated stalled fixture")
                val running =
                    withTimeout(15_000) {
                        f.chat.screen.first { it.activeTurn?.state?.isTerminal == false && f.wire.callCount == 1 }
                    }
                assertEquals(
                    SessionModelSelectionResult.BUSY,
                    f.chat
                        .requestSessionModelSelection(
                            "saved",
                            BuiltInModel.PROVIDER_ID,
                            BuiltInModel.MODEL_ID,
                            ReasoningEffort.HIGH,
                        ).await(),
                )
                assertEquals(
                    before.modelId,
                    f.storage.sessions
                        .resolve("saved")
                        .modelId,
                )
                assertEquals(controls, f.storage.sessionRunControls.forSession("saved"))
                assertEquals(
                    "low",
                    Json
                        .parseToJsonElement(f.wire.lastRequestBody)
                        .jsonObject[
                        "reasoning_effort",
                    ]?.jsonPrimitive
                        ?.content,
                )
                f.chat.stop(running.activeTurn!!.id)
                withTimeout(10_000) { f.chat.screen.first { it.activeTurn?.state?.isTerminal == true } }
            }
        }

    @Test fun saveCommitsModelAndReasoningTogetherAndTheNextRequestUsesThem() =
        runBlocking<Unit> {
            Fixture().use { f ->
                f.open()
                val receipt = AtomicReference<SessionModelSelectionResult?>()
                showPicker(f) { receipt.set(it) }
                val before = f.storage.sessions.resolve("saved")
                compose.onNodeWithTag("chat-model-menu").performClick()
                val modelTag = "chat-model-${BuiltInModel.PROVIDER_ID}-${BuiltInModel.MODEL_ID}"
                compose.onNodeWithTag(modelTag).performClick()
                compose.onNodeWithTag("chat-reasoning-menu").performClick()
                compose.onNodeWithTag("chat-reasoning-high").performClick()
                assertEquals(before, f.storage.sessions.resolve("saved"))
                assertEquals(ReasoningEffort.OFF, f.chat.runControl.value.reasoning)
                assertEquals(0, f.wire.callCount)
                compose.onNodeWithTag("chat-model-picker-save").performClick()
                compose.waitUntil(10_000) { receipt.get() != null }
                assertEquals(SessionModelSelectionResult.APPLIED, receipt.get())
                assertEquals(ReasoningEffort.HIGH, f.chat.runControl.value.reasoning)
                assertEquals(
                    BuiltInModel.MODEL_ID,
                    f.storage.sessions
                        .resolve("saved")
                        .modelId,
                )
                assertEquals(
                    ReasoningEffort.HIGH,
                    f.storage.sessionRunControls
                        .forSession("saved")
                        ?.reasoning,
                )
                f.chat.closeSession()
                f.open()
                assertEquals(ReasoningEffort.HIGH, f.chat.runControl.value.reasoning)
                f.wire.script(sseResponse(textAnswerStream("Fixture response")))
                f.chat.sendTestMessage("Fixture request, no external inference")
                val terminal = withTimeout(15_000) { f.chat.screen.first { it.activeTurn?.state?.isTerminal == true } }
                assertEquals(TurnState.COMPLETED, terminal.activeTurn?.state)
                val body = Json.parseToJsonElement(f.wire.lastRequestBody).jsonObject
                assertEquals(BuiltInModel.MODEL_ID, body["model"]?.jsonPrimitive?.content)
                assertEquals("xhigh", body["reasoning_effort"]?.jsonPrimitive?.content)
                assertEquals(1, f.wire.callCount)
            }
        }

    @Test fun staleSaveAndUnknownEffortAreRejectedWithoutOverwritingOtherSettings() =
        runBlocking<Unit> {
            Fixture().use { f ->
                f.open()
                val id = "saved"
                val provider = BuiltInModel.PROVIDER_ID
                val model = BuiltInModel.MODEL_ID
                val expected = SessionModelSelectionSnapshot(provider, "future", ReasoningEffort.OFF)
                val low =
                    f.chat
                        .requestSessionModelSelection(
                            id,
                            provider,
                            model,
                            ReasoningEffort.LOW,
                            expected,
                        ).await()
                assertEquals(SessionModelSelectionResult.APPLIED, low)
                val stale =
                    f.chat
                        .requestSessionModelSelection(
                            id,
                            provider,
                            model,
                            ReasoningEffort.HIGH,
                            expected,
                        ).await()
                assertEquals(SessionModelSelectionResult.SETTINGS_CHANGED, stale)
                assertEquals(
                    ReasoningEffort.LOW,
                    f.storage.sessionRunControls
                        .forSession(id)
                        ?.reasoning,
                )
                val unknown =
                    f.chat
                        .requestSessionModelSelection(
                            id,
                            provider,
                            "future",
                            ReasoningEffort.HIGH,
                        ).await()
                assertEquals(SessionModelSelectionResult.SETTINGS_CHANGED, unknown)
                assertEquals(
                    model,
                    f.storage.sessions
                        .resolve(id)
                        .modelId,
                )
                assertEquals(0, f.wire.callCount)
            }
        }

    @Test fun savePreservesOtherCurrentRunControls() =
        runBlocking<Unit> {
            Fixture().use { f ->
                f.open()
                val id = "saved"
                val provider = BuiltInModel.PROVIDER_ID
                val model = BuiltInModel.MODEL_ID
                f.chat.setChatToolsEnabled(false)
                withTimeout(10_000) { f.chat.runControl.first { !it.chatToolsEnabled } }
                val controls = f.chat.runControl.value
                val medium =
                    f.chat
                        .requestSessionModelSelection(
                            id,
                            provider,
                            model,
                            ReasoningEffort.MEDIUM,
                        ).await()
                assertEquals(SessionModelSelectionResult.APPLIED, medium)
                assertEquals(controls.copy(reasoning = ReasoningEffort.MEDIUM), f.chat.runControl.value)
                assertFalse(
                    f.storage.sessionRunControls
                        .forSession(id)!!
                        .chatToolsEnabled,
                )
                assertEquals(0, f.wire.callCount)
            }
        }

    @Test fun savedDraftRemainsEphemeralUntilExistingMaterializationAndKeepsReasoning() =
        runBlocking<Unit> {
            Fixture().use { f ->
                f.ready()
                f.chat.newSessionDraft()
                val draft =
                    withTimeout(10_000) {
                        f.chat.screen.first { it.isDraft && it.openSessionId != null }
                    }
                val id = requireNotNull(draft.openSessionId)
                val snapshot =
                    SessionModelSelectionSnapshot(
                        draft.badge?.providerId,
                        draft.badge?.model,
                        f.chat.runControl.value.reasoning,
                    )
                val result =
                    f.chat
                        .requestSessionModelSelection(
                            id,
                            BuiltInModel.PROVIDER_ID,
                            BuiltInModel.MODEL_ID,
                            ReasoningEffort.MEDIUM,
                            snapshot,
                        ).await()
                assertEquals(SessionModelSelectionResult.APPLIED, result)
                assertNull(f.storage.sessions.find(id))
                assertEquals(ReasoningEffort.MEDIUM, f.chat.runControl.value.reasoning)
                assertEquals(id, f.chat.materializeDraftSession(id))
                assertEquals(
                    BuiltInModel.MODEL_ID,
                    f.storage.sessions
                        .resolve(id)
                        .modelId,
                )
                assertEquals(
                    ReasoningEffort.MEDIUM,
                    f.storage.sessionRunControls
                        .forSession(id)
                        ?.reasoning,
                )
                assertEquals(
                    BuiltInModel.PROVIDER_ID to BuiltInModel.MODEL_ID,
                    ConversationLaunchStore(f.preferences).draftModel(),
                )
                assertEquals(0, f.wire.callCount)
            }
        }

    private fun showPicker(
        f: Fixture,
        onSaved: (SessionModelSelectionResult) -> Unit,
    ) {
        compose.setContent {
            val screen by f.chat.screen.collectAsState()
            val control by f.chat.runControl.collectAsState()
            val rows by f.providers.rows.collectAsState()
            MaterialTheme {
                ComposerModelMenu(
                    rows,
                    screen.badge?.providerId,
                    screen.badge?.model,
                    true,
                    { provider, model, reasoning ->
                        f.chat
                            .requestSessionModelSelection(
                                screen.openSessionId,
                                provider,
                                model,
                                reasoning,
                                SessionModelSelectionSnapshot(
                                    screen.badge?.providerId,
                                    screen.badge?.model,
                                    control.reasoning,
                                ),
                            ).await()
                            .also(onSaved)
                    },
                    reasoning = control.reasoning,
                    sessionKey = screen.openSessionId,
                )
            }
        }
    }

    private class Fixture : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "picker-${UUID.randomUUID()}.db"
        private val root = File(context.cacheDir, name).apply { mkdirs() }
        val storage = HelixStorage.open(context, name, File(root, "content"))
        val preferences = InMemoryLineStore()
        val wire = ScriptedSseWire()
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.IO)
        private val status = ProviderTestStatusStore(preferences)
        val providers: ProviderService
        val chat: ChatService

        init {
            BuiltInModelRegistration.ensureRegistered(storage)
            status.builtInCatalog.save(
                BuiltInModelCatalog(System.currentTimeMillis(), listOf(BuiltInModel.MODEL_ID, "future")),
            )
            providers =
                ProviderService(
                    storage,
                    ProviderFactory(
                        { error("No credentials") },
                        wire.wireClient,
                        { error("No images") },
                        builtInWire = wire.wireClient,
                    ),
                    status,
                    idGenerator = { UUID.randomUUID().toString() },
                    scope = scope,
                )
            storage.sessions.create(
                "saved",
                "Fixture",
                BuiltInModel.PROVIDER_ID,
                "future",
                System.currentTimeMillis(),
            )
            val staging =
                AttachmentStagingSupport(
                    AttachmentImporter(
                        SafImportPipeline(
                            ScopeRootResolver { root.toPath() },
                            SafSourceOpener { error("No attachments") },
                        ),
                    ),
                    "fixture",
                    { error("No attachments") },
                    { root.toPath().resolve(it.relativePath) },
                )
            chat =
                ChatService(
                    storage,
                    providers,
                    PersistedSafetyProfileStore(InMemoryLineStore(), false),
                    conversationLaunchStore = ConversationLaunchStore(preferences),
                    toolPipeline = (context.applicationContext as HelixApplication).appContainer.toolPipeline,
                    idGenerator = { UUID.randomUUID().toString() },
                    scope = scope,
                    attachmentStaging = staging,
                )
            providers.refresh()
        }

        suspend fun ready() {
            withTimeout(10_000) {
                providers.rows.first { rows ->
                    rows.any {
                        it.id == BuiltInModel.PROVIDER_ID &&
                            it.modelSelectable(BuiltInModel.MODEL_ID)
                    }
                }
            }
        }

        suspend fun open() {
            ready()
            val session = storage.sessions.resolve("saved")
            val persisted =
                storage.sessionRunControls.forSession("saved")?.let {
                    RunControlConfig(
                        mode = it.mode,
                        chatToolsEnabled = it.chatToolsEnabled,
                        budgets = it.budgets,
                        reasoning = it.reasoning,
                        goalBudgets = it.goalBudgets,
                        immediateMessages = it.immediateMessages,
                    )
                }
            chat.openSession("saved")
            withTimeout(10_000) {
                combine(chat.screen, chat.runControl) { screen, control ->
                    screen.openSessionId == "saved" && screen.badge?.providerId == session.providerId &&
                        screen.badge?.model == session.modelId && (persisted == null || control == persisted)
                }.first { it }
            }
        }

        override fun close() {
            runBlocking<Unit> { job.cancelAndJoin() }
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
