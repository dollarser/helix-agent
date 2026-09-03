package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.CleartextBindingStore
import com.helix.app.provider.ProviderFactory
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTestStatusStore
import com.helix.core.model.ModelRole
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.core.workspace.ScopeRootResolver
import com.helix.feature.files.AttachmentImporter
import com.helix.feature.files.SafImportPipeline
import com.helix.feature.files.SafSourceMetadata
import com.helix.feature.files.SafSourceOpener
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * HXA-049 (ADR-0014) device acceptance for the chat-attachment send path's fail-closed
 * re-verification and egress-target binding, on real Room + real workspace files:
 *
 * - RETRY RE-VERIFICATION: a retry of a turn whose user message bound an attachment re-verifies
 *   the durable artifact file against the bound SHA-256 snapshot and blocks fail-closed (NO new
 *   turn) when the file was tampered with or deleted; an unchanged file retries exactly as before.
 * - EGRESS BINDING: the per-send confirmation is bound to the exact provider+origin shown in the
 *   disclosure dialog — an endpoint edit between the dialog and the confirm tap blocks with
 *   「出网目标已变化，请重新发送」 and starts no turn; an unchanged target proceeds, clears the
 *   staged list and persists the `message_attachments` binding.
 *
 * The model never runs: the [WireClient] fails fast, so a confirmed send turn reaches the FAILED
 * terminal deterministically — exactly the state the retry button targets. No real network is
 * touched and no real path ever crosses into UI/logs/audit/model state.
 */
@RunWith(AndroidJUnit4::class)
class ChatServiceAttachmentRetryDeviceTest {
    /** The fake SAF uri -> the local file it serves (uri is never a real path). */
    private val sourceFiles = HashMap<String, File>()

    @Test
    fun retryIsBlockedFailClosedWhenTheBoundArtifactIsTampered() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = newFixture(context)
        try {
            val artifactFile = openSessionWithStagedAttachment(fixture, "retry re-verification body one\n")
            sendToDisclosure(fixture)
            confirmUntilTurnFails(fixture)
            val turnsBeforeRetry =
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .size

            artifactFile.appendText("tampered\n")
            fixture.service.retry()
            await(fixture, "the tampered retry is blocked fail-closed") {
                fixture.service.screen.value.blockedReason != null
            }

            val blocked = fixture.service.screen.value.blockedReason
            assertTrue(
                "the retry must be blocked fail-closed, was: $blocked",
                blocked != null && blocked.contains("附件快照复核未通过"),
            )
            assertEquals(
                "a blocked retry must not start a new turn",
                turnsBeforeRetry,
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .size,
            )
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun retryIsBlockedFailClosedWhenTheBoundArtifactIsDeleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = newFixture(context)
        try {
            val artifactFile = openSessionWithStagedAttachment(fixture, "retry re-verification body two\n")
            sendToDisclosure(fixture)
            confirmUntilTurnFails(fixture)
            val turnsBeforeRetry =
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .size

            assertTrue("the artifact file must exist to delete", artifactFile.delete())
            fixture.service.retry()
            await(fixture, "the deleted retry is blocked fail-closed") {
                fixture.service.screen.value.blockedReason != null
            }

            val blocked = fixture.service.screen.value.blockedReason
            assertTrue(
                "the retry must be blocked fail-closed, was: $blocked",
                blocked != null && blocked.contains("附件快照复核未通过"),
            )
            assertEquals(
                "a blocked retry must not start a new turn",
                turnsBeforeRetry,
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .size,
            )
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun retryProceedsWhenTheBoundArtifactIsUnchanged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = newFixture(context)
        try {
            openSessionWithStagedAttachment(fixture, "retry re-verification body three\n")
            sendToDisclosure(fixture)
            confirmUntilTurnFails(fixture)
            val turnsBeforeRetry =
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .size
            assertEquals("the confirmed send must have started exactly one turn", 1, turnsBeforeRetry)

            // The turn's job may still be completing when the screen refresh lands (the
            // admission releases on job completion): a retry swallowed by the admission is
            // simply probed again until the new turn row appears.
            var probes = 0
            do {
                fixture.service.retry()
                Thread.sleep(RETRY_PROBE_MILLIS)
                probes++
            } while (
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .size <= turnsBeforeRetry &&
                probes < MAX_RETRY_PROBES
            )

            assertEquals(
                "an unchanged retry must start exactly one new turn",
                turnsBeforeRetry + 1,
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .size,
            )
            await(fixture, "the retried turn terminalizes at the wire") {
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .all { it.state == TurnState.FAILED.name }
            }
            assertNull("an unchanged retry must not block", fixture.service.screen.value.blockedReason)
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun confirmIsBlockedWhenTheEgressTargetDriftsAfterTheDisclosure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = newFixture(context)
        try {
            openSessionWithStagedAttachment(fixture, "retry re-verification body four\n")
            sendToDisclosure(fixture)

            // Between the dialog and the confirm tap, the provider's endpoint moved to a
            // DIFFERENT origin: the old confirmation must not open the new wire path.
            fixture.storage.providerConfigs.overwrite(fixture.providerSpec.copy(endpoint = "https://two.invalid/v1"))
            fixture.service.confirmSend()
            await(fixture, "the drifted confirmation is blocked") {
                fixture.service.screen.value.blockedReason != null
            }

            assertEquals("出网目标已变化，请重新发送", fixture.service.screen.value.blockedReason)
            assertTrue(
                "no turn may start through a drifted origin",
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .isEmpty(),
            )
            assertNull("the disclosure is NOT re-shown on drift", fixture.service.screen.value.pendingDisclosure)
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun confirmProceedsAndBindsTheAttachmentWhenTheTargetIsUnchanged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val body = "retry re-verification body five\n"
        val fixture = newFixture(context)
        try {
            openSessionWithStagedAttachment(fixture, body)
            sendToDisclosure(fixture)
            fixture.service.confirmSend()
            await(fixture, "the confirmed send starts its turn") {
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .isNotEmpty()
            }
            await(fixture, "the confirmed turn terminalizes at the wire") {
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .all { it.state == TurnState.FAILED.name }
            }

            assertTrue(
                "the staged list must be cleared once the approved send went out",
                fixture.service.screen.value.pendingAttachments
                    .isEmpty(),
            )
            val userMessage =
                fixture.storage
                    .messages
                    .listBySession(SESSION_ID)
                    .single { it.role == ModelRole.USER.name }
            val binding =
                fixture.storage.messageAttachments
                    .listByMessage(userMessage.id)
                    .single()
            val artifact =
                fixture.storage.artifacts
                    .listBySession(SESSION_ID)
                    .single()
            assertEquals(artifact.id, binding.artifactId)
            assertEquals("REFERENCE", binding.purpose)
            assertEquals(
                FileContentStore.sha256Hex(body.toByteArray(Charsets.UTF_8)),
                binding.boundSha256,
            )
            val sourceName = sourceFiles[URI_KEY]!!.name
            val content = fixture.storage.messages.readContent(userMessage)
            assertTrue(
                "the model-visible user message must carry the labelled attachment block",
                content != null && content.contains(sourceName),
            )
            assertTrue(
                "the attachment block must carry the UNTRUSTED marker",
                content != null &&
                    content.contains(
                        AttachmentContext.UNTRUSTED_MARKER,
                    ),
            )
        } finally {
            settleAndClose(fixture)
        }
    }

    /** Creates the session, opens it, stages one text attachment and returns the durable artifact file. */
    private fun openSessionWithStagedAttachment(
        fixture: Fixture,
        body: String,
    ): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "source-${UUID.randomUUID()}.txt")
        source.writeText(body)
        sourceFiles[URI_KEY] = source
        fixture.storage.sessions.create(
            SESSION_ID,
            "attachment retry",
            PROVIDER_ID,
            "model-x",
            System.currentTimeMillis(),
        )
        fixture.service.openSession(SESSION_ID)
        fixture.service.stageAttachment(URI_KEY)
        await(fixture, "the attachment stages") { fixture.service.screen.value.pendingAttachments.size == 1 }
        val artifact =
            fixture.storage.artifacts
                .listBySession(SESSION_ID)
                .single()
        return fixture.workspaceRoot
            .toPath()
            .resolve(artifact.relativePath)
            .toFile()
    }

    private fun sendToDisclosure(fixture: Fixture) {
        fixture.service.send("帮我总结这个附件")
        await(fixture, "the per-send egress disclosure is shown") {
            fixture.service.screen.value.pendingDisclosure != null
        }
    }

    private fun confirmUntilTurnFails(fixture: Fixture) {
        fixture.service.confirmSend()
        await(fixture, "the confirmed turn reaches the FAILED terminal (the wire fails fast)") {
            val screen = fixture.service.screen.value
            screen.retryTargetTurnId != null && !screen.isSending
        }
    }

    /**
     * Polls [condition] from the test (main) thread with a hard deadline; the failure message
     * carries only the service's screen facts (no real path ever appears in any of them).
     */
    private fun await(
        fixture: Fixture,
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        val screen = fixture.service.screen.value
        error(
            "timed out waiting for: $what (blockedReason=${screen.blockedReason}, " +
                "pendingDisclosure=${screen.pendingDisclosure != null}, " +
                "pendingAttachments=${screen.pendingAttachments.size}, isSending=${screen.isSending})",
        )
    }

    /**
     * Gives the service's turn jobs a beat to release their storage handles before the isolated
     * Room database closes (a late write would surface as a background exception on the IO pool
     * after the assertions already ran).
     */
    private fun settleAndClose(fixture: Fixture) {
        Thread.sleep(SETTLE_MILLIS)
        fixture.storage.close()
    }

    /** One isolated service stack per test: real Room + real files, fake SAF + fake wire. */
    private data class Fixture(
        val storage: HelixStorage,
        val service: ChatService,
        val providerSpec: ProviderConfigSpec,
        val workspaceRoot: File,
    )

    private fun newFixture(context: Context): Fixture {
        val suffix = UUID.randomUUID().toString()
        val storage =
            HelixStorage.open(context, "attach-retry-$suffix.db", File(context.filesDir, "attach-retry-$suffix"))
        val workspaceRoot = File(context.filesDir, "attach-retry-ws-$suffix").apply { mkdirs() }
        val staging = stagingFor(workspaceRoot)
        val lineStore = InMemoryLineStore()
        val statusStore = ProviderTestStatusStore(lineStore)
        val providerService = providerService(storage, lineStore, statusStore, suffix)
        val providerSpec = seedProvider(storage, statusStore)
        val app = context.applicationContext as HelixApplication
        val service =
            ChatService(
                storage = storage,
                providerService = providerService,
                profileStore = FixedStandardProfileStore,
                toolPipeline = app.appContainer.toolPipeline,
                idGenerator = { "id-${UUID.randomUUID()}" },
                attachmentStaging = staging,
            )
        return Fixture(storage, service, providerSpec, workspaceRoot)
    }

    /** The one-shot SAF import pipeline + workspace staging rooted at [workspaceRoot] (no real path). */
    private fun stagingFor(workspaceRoot: File): AttachmentStagingSupport =
        AttachmentStagingSupport(
            importer =
                AttachmentImporter(
                    SafImportPipeline(
                        scopeRoots = ScopeRootResolver { _ -> workspaceRoot.toPath() },
                        opener = SafSourceOpener { uri -> requireNotNull(sourceFiles[uri]).inputStream() },
                    ),
                ),
            workspaceScopeId = SCOPE_ID,
            sourceMetadata = { uri ->
                val file = requireNotNull(sourceFiles[uri])
                SafSourceMetadata(file.length(), "text/plain", file.name)
            },
            resolveWorkspacePath = { scopePath -> workspaceRoot.toPath().resolve(scopePath.relativePath) },
        )

    /** The keyless, offline provider service: the model never runs in these tests. */
    private fun providerService(
        storage: HelixStorage,
        lineStore: InMemoryLineStore,
        statusStore: ProviderTestStatusStore,
        suffix: String,
    ): ProviderService =
        ProviderService(
            storage = storage,
            factory =
                ProviderFactory(
                    credentials = CredentialLookup { ProviderFactory.NO_KEY_PLACEHOLDER },
                    wire =
                        object : WireClient {
                            override suspend fun open(request: WireRequest): WireResponse =
                                throw IOException("device test: the wire is disabled — no network")
                        },
                ),
            bindings = CleartextBindingStore(lineStore),
            testStatus = statusStore,
            idGenerator = { "prov-$suffix" },
        )

    /** Seeds the keyless HTTPS provider row and records a passed connection test for it. */
    private fun seedProvider(
        storage: HelixStorage,
        statusStore: ProviderTestStatusStore,
    ): ProviderConfigSpec {
        val spec =
            ProviderConfigSpec(
                id = PROVIDER_ID,
                displayName = "Retry Test Provider",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "https://one.invalid/v1",
                model = "model-x",
                headersJson = "{}",
                secretAlias = ProviderFactory.NO_KEY_ALIAS,
                capabilitySnapshot = "untested",
            )
        storage.providerConfigs.save(spec)
        statusStore.recordPassed(
            PROVIDER_ID,
            System.currentTimeMillis(),
            passedCapabilities(),
        )
        return spec
    }

    /** The PROBED capability snapshot a passed connection test records. */
    private fun passedCapabilities(): ProviderCapabilities =
        ProviderCapabilities(
            streaming = true,
            toolCalls = false,
            parallelToolCalls = false,
            vision = false,
            reasoning = false,
            jsonSchemaOutput = false,
            maxContextTokens = null,
            source = CapabilitySource.PROBED,
        )

    /** The service only observes the profile flow; the tests pin STANDARD. */
    private object FixedStandardProfileStore : SafetyProfileStore {
        override val profile: SafetyProfile = SafetyProfile.STANDARD
        override val flow: StateFlow<SafetyProfile> = MutableStateFlow(SafetyProfile.STANDARD)

        override fun switchTo(profile: SafetyProfile) = error("profile switching is not under test")
    }

    private companion object {
        const val SESSION_ID = "attach-retry-session"
        const val PROVIDER_ID = "prov-attach-retry"
        const val SCOPE_ID = "retry-scope"
        const val URI_KEY = "content://helix.test/attachment"
        const val AWAIT_TIMEOUT_MILLIS = 20_000L
        const val POLL_MILLIS = 50L
        const val SETTLE_MILLIS = 500L
        const val RETRY_PROBE_MILLIS = 500L
        const val MAX_RETRY_PROBES = 40
    }
}
