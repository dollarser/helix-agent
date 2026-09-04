package com.helix.app.chat

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.ArtifactVisionImageSource
import com.helix.app.provider.CleartextBindingStore
import com.helix.app.provider.ProviderFactory
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTestStatusStore
import com.helix.core.model.ModelRole
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.TurnState
import com.helix.core.model.VisionLimits
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.feature.files.AttachmentImporter
import com.helix.feature.files.SafImportPipeline
import com.helix.feature.files.SafSourceMetadata
import com.helix.feature.files.SafSourceOpener
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ProviderCapabilities
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * HXA-056: the fixture-based attachment E2E (roadmap §9A: "固定 fixture 完成纯文本与图片附件
 * 消息的发送、流式回复、Tool Loop、历史恢复和诊断脱敏回归"). The model is a SCRIPTED OpenAI
 * Chat Completions SSE wire ([ScriptedSseWire]): the REAL production adapter parses it, so
 * every test drives the full production path — ChatService → ProviderService → protocol
 * adapter → wire — with a deterministic offline model. The real vision endpoint smoke is a
 * separate environment-documented step and never substitutes these fixtures.
 *
 * The image path runs the REAL [ArtifactVisionImageSource] (session-bound app-private
 * artifacts + the containment workspace): a staged image is normalized on-device and only
 * the normalized artifact's verified bytes may reach the recorded request.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentE2eDeviceTest {
    companion object {
        private const val SESSION_ID = "session-e2e"
        private const val PROVIDER_ID = "prov-e2e"
        private const val SCOPE_ID = "scope-ws"
        private const val URI_KEY = "content://fake/share/1"
        private const val AWAIT_TIMEOUT_MILLIS = 30_000L
        private const val POLL_MILLIS = 25L
        private const val SETTLE_MILLIS = 300L

        /** The stable unsupported-type refusal (HXA-049/056 closed boundary). */
        const val UNSUPPORTED_TEXT = "不是受支持的类型"
    }

    /** The fake SAF uri -> the local file it serves (the uri is never a real path). */
    private val sourceFiles = HashMap<String, File>()

    @Test
    fun textAttachmentSendsStreamsRepliesAndHistoryCarriesTheBlock() {
        val fixture = newFixture(vision = false)
        try {
            stageTextAttachment(fixture, "e2e text body one\n")
            fixture.wire.script(sseResponse(textAnswerStream("收到附件，共 2 行。")))

            sendToDisclosure(fixture)
            fixture.service.confirmSend()
            await(fixture, "the text turn completes") { turnIsTerminal(fixture) }

            assertEquals(1, fixture.wire.callCount)
            val body1 = fixture.wire.lastRequestBody
            val userMessage = fixture.userMessage()
            val binding =
                fixture.storage.messageAttachments
                    .listByMessage(userMessage.id)
                    .single()
            val rawSha = FileContentStore.sha256Hex("e2e text body one\n".toByteArray(Charsets.UTF_8))
            assertEquals(rawSha, binding.boundSha256)
            // Golden request: the labelled, hash-bound, UNTRUSTED attachment block rides the
            // user message; the reply is the streamed text.
            assertTrue("request must carry the attachment label, was: $body1", body1.contains("【附件 1/1"))
            assertTrue("request must carry the bound hash", body1.contains(rawSha.take(16)))
            assertTrue("request must carry the UNTRUSTED marker", body1.contains(AttachmentContext.UNTRUSTED_MARKER))
            assertEquals("收到附件，共 2 行。", fixture.assistantMessage())

            // History recovery through the REAL request path: a second (plain) send must
            // rebuild the previous user message WITH its attachment block from persisted
            // rows. A plain send (no staging) launches directly — the disclosure is an
            // ATTACHMENT egress gate, not a per-message one.
            fixture.wire.script(sseResponse(textAnswerStream("第二条回复。")))
            fixture.service.send("继续")
            await(fixture, "the second turn completes") { fixture.wire.callCount == 2 && turnIsTerminal(fixture) }
            assertTrue(
                "the history request must re-carry the attachment block",
                fixture.wire.lastRequestBody.contains("【附件 1/1"),
            )
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun imageAttachmentCarriesTheNormalizedPayloadAndHistoryReResolves() {
        val fixture = newFixture(vision = true)
        try {
            stageImageAttachment(fixture)
            fixture.wire.script(sseResponse(textAnswerStream("收到图片。")))

            sendToDisclosure(fixture)
            fixture.service.confirmSend()
            await(fixture, "the image turn completes") { turnIsTerminal(fixture) }

            // Golden request: the data URL is the NORMALIZED artifact's verified bytes
            // (PNG in, PNG out — the re-encode keeps the type for PNG).
            val artifacts = fixture.storage.artifacts.listBySession(SESSION_ID)
            assertEquals("raw + normalized artifacts", 2, artifacts.size)
            val normalized = artifacts.single { it.relativePath.contains("normalized.") }
            val normalizedBytes =
                fixture.workspaceRoot
                    .toPath()
                    .resolve(normalized.relativePath)
                    .toFile()
                    .readBytes()
            val expectedDataUrl =
                "data:${normalized.mediaType};base64," + Base64.getEncoder().encodeToString(normalizedBytes)
            val body1 = fixture.wire.lastRequestBody
            assertTrue(
                "the request must carry the normalized data URL (registered type ${normalized.mediaType})",
                body1.contains(expectedDataUrl),
            )
            // The egress binding is the NORMALIZED artifact (the bytes that leave).
            val userMessage = fixture.userMessage()
            val binding =
                fixture.storage.messageAttachments
                    .listByMessage(userMessage.id)
                    .single()
            assertEquals(normalized.id, binding.artifactId)
            assertEquals(FileContentStore.sha256Hex(normalizedBytes), binding.boundSha256)
            assertEquals("收到图片。", fixture.assistantMessage())

            // History: the second (plain) request RE-RESOLVES the image binding through
            // the production image source (bindSession + registry + containment + magic +
            // budget) — the data URL travels again without re-staging.
            fixture.wire.script(sseResponse(textAnswerStream("第二条。")))
            fixture.service.send("再看一遍")
            await(fixture, "the second image turn completes") { fixture.wire.callCount == 2 && turnIsTerminal(fixture) }
            assertTrue(
                "the history request must re-carry the image data URL",
                fixture.wire.lastRequestBody.contains(expectedDataUrl),
            )
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun imageSendIsBlockedWhenVisionIsNotConfirmed() {
        val fixture = newFixture(vision = false)
        try {
            stageImageAttachment(fixture)
            fixture.service.send("看看这张图")
            await(fixture, "the vision gate blocks before any disclosure") {
                fixture.service.screen.value.blockedReason != null
            }
            val blocked = fixture.service.screen.value.blockedReason
            assertTrue("actionable vision text, was: $blocked", blocked != null && blocked.contains("视觉能力"))
            assertNull(
                "no disclosure may be shown for a blocked image send",
                fixture.service.screen.value.pendingDisclosure,
            )
            assertEquals(
                "no turn may start",
                0,
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .size,
            )
            assertEquals("nothing may reach the wire", 0, fixture.wire.callCount)
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun capabilitySnapshotChangeRevokesVisionUntilReprobed() {
        val fixture = newFixture(vision = true)
        try {
            stageImageAttachment(fixture)
            fixture.wire.script(sseResponse(textAnswerStream("收到。")))
            sendToDisclosure(fixture)
            fixture.service.confirmSend()
            await(fixture, "the first image turn completes") { turnIsTerminal(fixture) }
            assertEquals(1, fixture.wire.callCount)

            // A re-run connection test FAILED the vision probe: the stored snapshot is
            // replaced (vision lost) — the same staged image must now be blocked.
            val revoked =
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
            fixture.storage.providerConfigs.overwrite(
                ProviderConfigSpec(
                    id = PROVIDER_ID,
                    displayName = "E2E Provider",
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    endpoint = "https://one.invalid/v1",
                    model = "model-e2e",
                    headersJson = "{}",
                    secretAlias = ProviderFactory.NO_KEY_ALIAS,
                    capabilitySnapshot = ProviderCapabilities.toJsonString(revoked),
                ),
            )

            // The same image re-enters through a fresh import (the staging was consumed by
            // the first send) — the vision gate then blocks the attachment egress.
            stageImageAttachment(fixture)
            fixture.service.send("再发一次")
            await(fixture, "the revoked vision blocks the second send") {
                fixture.service.screen.value.blockedReason != null
            }
            val blocked = fixture.service.screen.value.blockedReason
            assertTrue(
                "the block must name the vision gate, was: $blocked",
                blocked != null && blocked.contains("视觉能力"),
            )
            assertEquals(
                "the second send must not start a turn",
                1,
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .size,
            )
            assertEquals("the second send must not reach the wire", 1, fixture.wire.callCount)
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun imageRetryIsBlockedWhenTheNormalizedArtifactIsTampered() {
        val fixture = newFixture(vision = true)
        try {
            stageImageAttachment(fixture)
            fixture.wire.script(
                httpErrorResponse(500),
                sseResponse(textAnswerStream("不会到达。")),
            )
            sendToDisclosure(fixture)
            fixture.service.confirmSend()
            await(fixture, "the first turn fails at the wire") {
                fixture.service.screen.value.retryTargetTurnId != null && !fixture.service.screen.value.isSending
            }

            // Tamper the NORMALIZED artifact (the bound bytes): the retry must re-verify and
            // block fail-closed — no new turn, no wire call.
            val normalized =
                fixture.storage
                    .artifacts
                    .listBySession(SESSION_ID)
                    .single { it.relativePath.contains("normalized.") }
            fixture.workspaceRoot
                .toPath()
                .resolve(normalized.relativePath)
                .toFile()
                .appendBytes(byteArrayOf(0, 1, 2))

            val turnsBefore =
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .size
            val callsBefore = fixture.wire.callCount
            fixture.service.retry()
            await(fixture, "the tampered image retry is blocked") { fixture.service.screen.value.blockedReason != null }
            assertEquals(
                "no new turn may start",
                turnsBefore,
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .size,
            )
            assertEquals("nothing may reach the wire", callsBefore, fixture.wire.callCount)
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun toolLoopWithTextAttachmentRunsTheToolThenAnswers() {
        val fixture = newFixture(vision = false)
        try {
            stageTextAttachment(fixture, "e2e tool body\n")
            fixture.wire.script(
                sseResponse(toolCallStream("call-e2e", "\"time.now\"", "{}")),
                sseResponse(textAnswerStream("现在的时间已查到。")),
            )

            sendToDisclosure(fixture)
            fixture.service.confirmSend()
            await(fixture, "the tool loop completes both model calls") {
                fixture.wire.callCount == 2 && turnIsTerminal(fixture)
            }

            val turn =
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .single()
            assertEquals(TurnState.COMPLETED.name, turn.state)
            // The tool actually ran (durable timeline row) and the FINAL answer persisted.
            val toolCalls = fixture.storage.toolCalls.listByTurn(turn.id)
            assertEquals(1, toolCalls.size)
            assertEquals("time.now", toolCalls.single().name)
            assertEquals("现在的时间已查到。", fixture.assistantMessage())
            // The back-fill request carried BOTH the attachment block and the tool result.
            val body2 = fixture.wire.lastRequestBody
            assertTrue("back-fill must re-carry the attachment block", body2.contains("【附件 1/1"))
            assertTrue("back-fill must carry the tool result", body2.contains("tool"))
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun cancelMidStreamTerminalizesAndTheResendSucceeds() {
        val fixture = newFixture(vision = false)
        try {
            stageTextAttachment(fixture, "e2e cancel body\n")
            val partialDelta =
                "data: {\"id\":\"chatcmpl-e2e\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"半\"}," +
                    "\"finish_reason\":null}]}\n\n"
            fixture.wire.script(
                stalledResponse(partialDelta),
                sseResponse(textAnswerStream("重试成功。")),
            )

            sendToDisclosure(fixture)
            fixture.service.confirmSend()
            await(fixture, "the turn is streaming from the stalled wire") {
                val t =
                    fixture.storage.turns
                        .listBySession(SESSION_ID)
                        .lastOrNull()
                t != null && !TurnState.valueOf(t.state).isTerminal
            }

            fixture.service.stop()
            await(fixture, "the cancelled turn terminalizes") { turnIsTerminal(fixture) }
            assertEquals(
                TurnState.CANCELLED.name,
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .first()
                    .state,
            )

            // A user-initiated stop is NOT a retryable failure (the retry button targets the
            // newest FAILED turn): the user re-sends. The durable file is re-imported
            // (fresh staging) and the second scripted stream answers.
            stageTextAttachment(fixture, "e2e cancel body\n")
            val turnsBefore =
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .size
            fixture.service.send("重新发送")
            await(fixture, "the resend disclosure is shown") {
                fixture.service.screen.value.pendingDisclosure != null
            }
            fixture.service.confirmSend()
            await(fixture, "the resent turn completes") {
                val turns = fixture.storage.turns.listBySession(SESSION_ID)
                turns.size == turnsBefore + 1 &&
                    TurnState.valueOf(turns.last().state) == TurnState.COMPLETED
            }
            assertEquals("重试成功。", fixture.assistantMessage())
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun processRecyclingKeepsThePersistedAttachmentAndDropsTheStaging() {
        val fixture = newFixture(vision = true)
        try {
            stageImageAttachment(fixture)
            val artifactsBefore = fixture.storage.artifacts.listBySession(SESSION_ID)
            assertEquals("raw + normalized registered", 2, artifactsBefore.size)
            val normalizedFile =
                fixture.workspaceRoot
                    .toPath()
                    .resolve(artifactsBefore.single { it.relativePath.contains("normalized.") }.relativePath)
                    .toFile()
            assertTrue("the normalized file exists pre-death", normalizedFile.exists())

            // Simulated process death: the service scope dies (in-memory staging is gone)
            // and the storage connection closes — a killed process leaves committed state.
            fixture.serviceScope.cancel()
            fixture.storage.close()

            // A fresh process over the SAME database file.
            val storage2 =
                HelixStorage.open(
                    ApplicationProvider.getApplicationContext<Context>(),
                    fixture.dbName,
                    fixture.dataDir,
                )
            try {
                assertPersistedStateSurvived(fixture, storage2)
                assertFreshServiceStagingIsEmpty(fixture, storage2)
            } finally {
                storage2.close()
            }
        } finally {
            try {
                Thread.sleep(SETTLE_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    @Test
    fun oversizedImageIsBlockedAtStagingWithNoArtifact() {
        val fixture = newFixture(vision = true)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            // PNG magic + padding above the 10 MiB import cap.
            val source = File(context.cacheDir, "oversized-${UUID.randomUUID()}.png")
            val magic = pngMagic()
            source.outputStream().use { out ->
                out.write(magic)
                val padding = ByteArray(64 * 1024)
                var remaining = (VisionLimits.MAX_INPUT_BYTES + 1 - magic.size).toInt()
                while (remaining > 0) {
                    val n = minOf(padding.size, remaining)
                    out.write(padding, 0, n)
                    remaining -= n
                }
            }
            sourceFiles[URI_KEY] = source
            fixture.service.stageAttachment(URI_KEY)
            await(fixture, "the oversized image is blocked") { fixture.service.screen.value.blockedReason != null }
            val blocked = fixture.service.screen.value.blockedReason
            assertTrue("the 10 MiB cap text, was: $blocked", blocked != null && blocked.contains("10 MiB"))
            assertEquals(
                "no artifact may register",
                0,
                fixture.storage.artifacts
                    .listBySession(SESSION_ID)
                    .size,
            )
            assertTrue(
                fixture.service.screen.value.pendingAttachments
                    .isEmpty(),
            )
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun forgedImageHeaderFailsClosedAtNormalization() {
        val fixture = newFixture(vision = true)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            // A ~20 B file whose JPEG SOF claims 30000x30000 (the HXA-055 Phase 1 bomb):
            // the header is visible to the bounds probe but the bytes are tiny — the
            // normalization must fail closed (never trust the header for memory math).
            val source = File(context.cacheDir, "bomb-${UUID.randomUUID()}.jpg")
            source.writeBytes(makeForgedSofJpeg(30_000))
            sourceFiles[URI_KEY] = source
            fixture.service.stageAttachment(URI_KEY)
            await(fixture, "the forged-header image surfaces a block or a marked entry") {
                val s = fixture.service.screen.value
                s.blockedReason != null || s.pendingAttachments.isNotEmpty()
            }
            // In EVERY branch: no normalized artifact exists and nothing reaches the wire;
            // if the entry is marked (not blocked), a send is blocked fail-closed too.
            val normalized =
                fixture.storage
                    .artifacts
                    .listBySession(SESSION_ID)
                    .filter { it.relativePath.contains("normalized.") }
            assertEquals("no normalized artifact may exist for a forged header", 0, normalized.size)
            if (fixture.service.screen.value.pendingAttachments
                    .isNotEmpty()
            ) {
                fixture.wire.script(sseResponse(textAnswerStream("不可达。")))
                fixture.service.send("发送这张图")
                await(
                    fixture,
                    "the forged-header send is blocked",
                ) { fixture.service.screen.value.blockedReason != null }
            }
            assertEquals("nothing may reach the wire", 0, fixture.wire.callCount)
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun unsupportedTypesAreStablyRefusedWithoutSideEffects() {
        // The closed unsupported boundary (HXA-056): PDF / DOC-binary / audio / UTF-16 text
        // are REFUSED at the closed classifier — no parser, no OCR, no media decode, no
        // Provider upload, no base64 in any context, no derived artifacts.
        val cases =
            listOf(
                "doc.pdf" to pdfBytes(),
                "binary.doc" to ole2Bytes(),
                "clip.wav" to wavBytes(),
                "utf16.txt" to "utf-16 body".toByteArray(java.nio.charset.StandardCharsets.UTF_16LE),
            )
        for ((name, bytes) in cases) {
            val fixture = newFixture(vision = false)
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val source = File(context.cacheDir, "refused-${UUID.randomUUID()}-${name.substringAfterLast('.')}")
                source.writeBytes(bytes)
                sourceFiles[URI_KEY] = source
                fixture.service.stageAttachment(URI_KEY)
                await(fixture, "$name is refused") { fixture.service.screen.value.blockedReason != null }
                val blocked = fixture.service.screen.value.blockedReason
                assertTrue(
                    "$name must carry the stable unsupported refusal, was: $blocked",
                    blocked != null && blocked.contains(UNSUPPORTED_TEXT),
                )
                assertEquals(
                    "$name must not register an artifact",
                    0,
                    fixture.storage.artifacts
                        .listBySession(SESSION_ID)
                        .size,
                )
                assertTrue(
                    "$name must not stage",
                    fixture.service.screen.value.pendingAttachments
                        .isEmpty(),
                )
            } finally {
                settleAndClose(fixture)
            }
        }
    }

    @Test
    fun duplicateSendPersistsTwoIndependentTurns() {
        val fixture = newFixture(vision = false)
        try {
            stageTextAttachment(fixture, "e2e duplicate body\n")
            fixture.wire.script(
                sseResponse(textAnswerStream("第一条。")),
                sseResponse(textAnswerStream("第二条。")),
            )

            sendToDisclosure(fixture)
            fixture.service.confirmSend()
            await(fixture, "the first turn completes") { turnIsTerminal(fixture) }
            // The send consumed the staging; the duplicate send re-imports the SAME durable
            // file through a fresh picker import (the picker path is the only staging entry).
            stageTextAttachment(fixture, "e2e duplicate body\n")
            fixture.service.send("重复发送")
            await(fixture, "the second disclosure") { fixture.service.screen.value.pendingDisclosure != null }
            fixture.service.confirmSend()
            await(fixture, "the second turn completes") {
                fixture.storage.turns
                    .listBySession(SESSION_ID)
                    .size == 2 &&
                    fixture.storage
                        .turns
                        .listBySession(SESSION_ID)
                        .all { it.state == TurnState.COMPLETED.name }
            }
            assertEquals(2, fixture.wire.callCount)
            val replies =
                fixture.storage
                    .messages
                    .listBySession(SESSION_ID)
                    .filter { it.role == ModelRole.ASSISTANT.name }
                    .map { fixture.storage.messages.readContent(it) }
            assertEquals(listOf("第一条。", "第二条。"), replies)
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun noPathUriOrBase64LeaksIntoPersistedOrUserVisibleState() {
        val fixture = newFixture(vision = true)
        try {
            val source = stageImageAttachment(fixture)
            val realPath = source.absolutePath

            fixture.wire.script(sseResponse(textAnswerStream("收到。")))
            sendToDisclosure(fixture)
            fixture.service.confirmSend()
            await(fixture, "the turn completes") { turnIsTerminal(fixture) }

            // Scan every model-visible message body and every user-visible screen string
            // for the raw real path, the content URI and a base64 payload slice.
            val normalized =
                fixture.storage
                    .artifacts
                    .listBySession(SESSION_ID)
                    .single { it.relativePath.contains("normalized.") }
            val normalizedBytes =
                fixture.workspaceRoot
                    .toPath()
                    .resolve(normalized.relativePath)
                    .toFile()
                    .readBytes()
            val b64Slice = Base64.getEncoder().encodeToString(normalizedBytes).substring(0, 24)

            val persisted =
                fixture.storage
                    .messages
                    .listBySession(SESSION_ID)
                    .mapNotNull { fixture.storage.messages.readContent(it) }
                    .joinToString("\n")
            val userVisible =
                buildString {
                    val s = fixture.service.screen.value
                    s.blockedReason?.let { append(it).append('\n') }
                    s.pendingDisclosure?.let { append(it).append('\n') }
                    s.messages.forEach { append(it.content).append('\n') }
                }
            for ((text, label) in listOf(persisted to "persisted messages", userVisible to "user-visible state")) {
                assertTrue("$label must not leak the real path", !text.contains(realPath))
                assertTrue("$label must not leak the content URI", !text.contains(URI_KEY))
                assertTrue("$label must not leak the base64 payload", !text.contains(b64Slice))
            }
        } finally {
            settleAndClose(fixture)
        }
    }

    /** The durable half of the process-death contract: session + artifacts + files survive. */
    private fun assertPersistedStateSurvived(
        fixture: Fixture,
        storage: HelixStorage,
    ) {
        assertEquals("the session survived", 1, storage.sessions.list().size)
        val artifacts2 = storage.artifacts.listBySession(SESSION_ID)
        assertEquals("the raw + normalized artifacts are durable", 2, artifacts2.size)
        val norm2 = artifacts2.single { it.relativePath.contains("normalized.") }
        val file2 =
            fixture.workspaceRoot
                .toPath()
                .resolve(norm2.relativePath)
                .toFile()
        assertTrue("the normalized file survived", file2.exists())
        assertEquals("the file still matches its snapshot", norm2.size, file2.length())
        // Nothing was sent before the death: no messages, no turns, no bindings.
        assertEquals(0, storage.messages.listBySession(SESSION_ID).size)
        assertEquals(0, storage.turns.listBySession(SESSION_ID).size)
    }

    /** The volatile half: staging is process-local — nothing is pre-staged after a restart. */
    private fun assertFreshServiceStagingIsEmpty(
        fixture: Fixture,
        storage: HelixStorage,
    ) {
        val service2 =
            buildService(
                fixture.wire,
                fixture.workspaceRoot,
                fixture.suffix,
                fixture.visionFlag,
                storage,
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
        service2.openSession(SESSION_ID)
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline && service2.screen.value.openSessionId != SESSION_ID) {
            Thread.sleep(POLL_MILLIS)
        }
        assertEquals(SESSION_ID, service2.screen.value.openSessionId)
        assertTrue(
            "staging is process-local: nothing is pre-staged after a restart",
            service2.screen.value.pendingAttachments
                .isEmpty(),
        )
    }

    // --- fixtures ------------------------------------------------------------

    private data class Fixture(
        val storage: HelixStorage,
        val service: ChatService,
        val wire: ScriptedSseWire,
        val workspaceRoot: File,
        val dbName: String,
        val dataDir: File,
        val serviceScope: CoroutineScope,
        val suffix: String,
        val visionFlag: Boolean,
    )

    private fun newFixture(vision: Boolean): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val dataDir = File(context.filesDir, "attach-e2e-$suffix").apply { mkdirs() }
        val dbName = "attach-e2e-$suffix.db"
        val storage = HelixStorage.open(context, dbName, dataDir)
        val wire = ScriptedSseWire()
        val workspaceRoot = File(context.filesDir, "attach-e2e-ws-$suffix").apply { mkdirs() }
        // The handler turns a would-be silent coroutine death (a SupervisorJob child throwing
        // outside the guarded paths) into a visible logcat line instead of a 30 s await timeout.
        val serviceScope =
            CoroutineScope(
                SupervisorJob() +
                    Dispatchers.IO +
                    CoroutineExceptionHandler { _, e ->
                        android.util.Log.e("E2E-STAGE-EXC", "fixture scope exception", e)
                    },
            )
        val service = buildService(wire, workspaceRoot, suffix, vision, storage, serviceScope)
        // One provider-bound session, opened — staging requires an open session (ADR-0014 §4:
        // attachments are always session-scoped).
        storage.sessions.create(SESSION_ID, "e2e session", PROVIDER_ID, "model-e2e", System.currentTimeMillis())
        service.openSession(SESSION_ID)
        return Fixture(storage, service, wire, workspaceRoot, dbName, dataDir, serviceScope, suffix, vision)
    }

    /** Builds the production-shaped ChatService over [storage] (fresh in-memory state). */
    private fun buildService(
        wire: ScriptedSseWire,
        workspaceRoot: File,
        suffix: String,
        vision: Boolean,
        storage: HelixStorage,
        serviceScope: CoroutineScope,
    ): ChatService {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as HelixApplication
        val zh =
            AppLanguageStore.wrapForLocale(
                context.applicationContext,
                AppLanguageStore.localeListFor(AppLanguage.ZH_CN),
            )
        val lineStore = InMemoryLineStore()
        val statusStore = ProviderTestStatusStore(lineStore)
        val workspaceStore = WorkspaceArtifactStore(ScopeRootResolver { _ -> workspaceRoot.toPath() })
        val imageSource = ArtifactVisionImageSource(storage.artifacts, workspaceStore, SCOPE_ID)
        val providerService =
            ProviderService(
                storage = storage,
                factory =
                    ProviderFactory(
                        credentials = CredentialLookup { ProviderFactory.NO_KEY_PLACEHOLDER },
                        wire = wire.wireClient,
                        imageSource = { imageSource },
                    ),
                bindings = CleartextBindingStore(lineStore),
                testStatus = statusStore,
                idGenerator = { "prov-$suffix" },
            )
        seedProvider(storage, statusStore, vision)
        return ChatService(
            storage = storage,
            providerService = providerService,
            profileStore = FixedStandardProfileStore,
            toolPipeline = app.appContainer.toolPipeline,
            idGenerator = { "id-${UUID.randomUUID()}" },
            scope = serviceScope,
            attachmentStaging = stagingFor(workspaceRoot),
            visionSessionBinder = imageSource::bindSession,
            strings = { resId, args -> zh.getString(resId, *args) },
        )
    }

    /**
     * The keyless provider row + a PASSED connection test (the vision flag per fixture).
     * The probed snapshot lands in the DB `capability_snapshot` column (the vision gate
     * reads it) and the pass record in the test-status store; the row save is skipped when
     * the provider already exists (the fresh-process fixture reuses the durable row).
     */
    private fun seedProvider(
        storage: HelixStorage,
        statusStore: ProviderTestStatusStore,
        vision: Boolean,
    ) {
        val caps =
            ProviderCapabilities(
                streaming = true,
                toolCalls = true,
                parallelToolCalls = false,
                vision = vision,
                reasoning = false,
                jsonSchemaOutput = false,
                maxContextTokens = null,
                source = CapabilitySource.PROBED,
            )
        if (storage.providerConfigs.list().none { it.id == PROVIDER_ID }) {
            storage.providerConfigs.save(
                ProviderConfigSpec(
                    id = PROVIDER_ID,
                    displayName = "E2E Provider",
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    endpoint = "https://one.invalid/v1",
                    model = "model-e2e",
                    headersJson = "{}",
                    secretAlias = ProviderFactory.NO_KEY_ALIAS,
                    capabilitySnapshot = ProviderCapabilities.toJsonString(caps),
                ),
            )
        }
        statusStore.recordPassed(PROVIDER_ID, System.currentTimeMillis(), caps)
    }

    /** The fake-SAF import pipeline + workspace staging rooted at [workspaceRoot] (no real path). */
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
                val mime =
                    when {
                        file.name.endsWith(".png") -> "image/png"
                        file.name.endsWith(".jpg") -> "image/jpeg"
                        else -> "application/octet-stream"
                    }
                SafSourceMetadata(file.length(), mime, file.name)
            },
            resolveWorkspacePath = { scopePath -> workspaceRoot.toPath().resolve(scopePath.relativePath) },
        )

    /** The service only observes the profile flow; the tests pin STANDARD. */
    private object FixedStandardProfileStore : SafetyProfileStore {
        override val profile: SafetyProfile = SafetyProfile.STANDARD
        override val flow: StateFlow<SafetyProfile> = MutableStateFlow(SafetyProfile.STANDARD)

        override fun switchTo(profile: SafetyProfile) = error("profile switching is not under test")
    }

    private fun stageTextAttachment(
        fixture: Fixture,
        body: String,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "src-${UUID.randomUUID()}.txt")
        source.writeText(body)
        sourceFiles[URI_KEY] = source
        fixture.service.stageAttachment(URI_KEY)
        await(fixture, "the text attachment stages") { fixture.service.screen.value.pendingAttachments.size == 1 }
    }

    /** Stages one real PNG (64x64) and returns the source file (for the leak scan). */
    private fun stageImageAttachment(fixture: Fixture): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFFFF3333.toInt())
        val source = File(context.cacheDir, "img-${UUID.randomUUID()}.png")
        source.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        sourceFiles[URI_KEY] = source
        fixture.service.stageAttachment(URI_KEY)
        await(fixture, "the image attachment stages (normalized)") {
            val s = fixture.service.screen.value
            s.pendingAttachments.size == 1 && s.blockedReason == null
        }
        return source
    }

    private fun sendToDisclosure(fixture: Fixture) {
        fixture.service.send("处理这个附件")
        await(fixture, "the egress disclosure is shown") { fixture.service.screen.value.pendingDisclosure != null }
    }

    private fun turnIsTerminal(fixture: Fixture): Boolean =
        fixture.storage.turns
            .listBySession(SESSION_ID)
            .isNotEmpty() &&
            fixture.storage
                .turns
                .listBySession(SESSION_ID)
                .all { TurnState.valueOf(it.state).isTerminal }

    private fun Fixture.userMessage() =
        storage.messages.listBySession(SESSION_ID).single { it.role == ModelRole.USER.name }

    private fun Fixture.assistantMessage(): String =
        storage
            .messages
            .listBySession(SESSION_ID)
            .filter { it.role == ModelRole.ASSISTANT.name }
            .maxByOrNull { it.sequence }
            ?.let { storage.messages.readContent(it) }
            ?: error("no assistant message persisted")

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
        val s = fixture.service.screen.value
        error(
            "timed out waiting for: $what (blockedReason=${s.blockedReason}, " +
                "pendingDisclosure=${s.pendingDisclosure != null}, " +
                "pendingAttachments=${s.pendingAttachments.size}, " +
                "isSending=${s.isSending}, wireCalls=${fixture.wire.callCount})",
        )
    }

    private fun settleAndClose(fixture: Fixture) {
        try {
            Thread.sleep(SETTLE_MILLIS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            fixture.serviceScope.cancel()
            fixture.storage.close()
        } catch (_: Exception) {
            // settle is best-effort cleanup; the assertions already ran (test-fixture tolerance)
        }
    }

    // --- byte fixtures (closed-magic builders, no external assets) -----------

    /** The PNG signature + IHDR header start (the importer reads only the magic). */
    private fun pngMagic(): ByteArray =
        byteArrayOf(
            0x89.toByte(),
            0x50.toByte(),
            0x4E.toByte(),
            0x47.toByte(),
            0x0D.toByte(),
            0x0A.toByte(),
            0x1A.toByte(),
            0x0A.toByte(),
            0x00,
            0x00,
            0x00,
            0x0D.toByte(),
            0x49.toByte(),
            0x48.toByte(),
            0x44.toByte(),
            0x52.toByte(),
        )

    /** %PDF-1.4 header + harmless trailer bytes (magic only; the importer refuses before parsing). */
    private fun pdfBytes(): ByteArray =
        ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< >>\n%%EOF\n").encodeToByteArray()

    /** OLE2 compound document magic (DOC/PPT family) + padding. */
    private fun ole2Bytes(): ByteArray {
        val b = ByteArray(64)
        b[0] = 0xD0.toByte()
        b[1] = 0xCF.toByte()
        b[2] = 0x11.toByte()
        b[3] = 0xE0.toByte()
        b[4] = 0xA1.toByte()
        b[5] = 0xB1.toByte()
        b[6] = 0x1A.toByte()
        b[7] = 0xE1.toByte()
        return b
    }

    /** RIFF....WAVE header + padding (PCM audio magic). */
    private fun wavBytes(): ByteArray {
        val b = ByteArray(64)
        "RIFF".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        "WAVE".forEachIndexed { i, c -> b[8 + i] = c.code.toByte() }
        return b
    }

    /**
     * The HXA-055 Phase 1 bomb: a ~20 B file whose JPEG SOF claims [claimed]x[claimed].
     * The bounds probe trusts the header (documented platform fact); the normalization's
     * envelope math must fail closed before any decode at that size is attempted.
     */
    private fun makeForgedSofJpeg(claimed: Int): ByteArray =
        byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(), // SOI
            0xFF.toByte(),
            0xC0.toByte(), // SOF0
            0x11.toByte(),
            0x00, // length field (17)
            8, // precision
            ((claimed shr 8) and 0xFF).toByte(),
            (claimed and 0xFF).toByte(), // height
            ((claimed shr 8) and 0xFF).toByte(),
            (claimed and 0xFF).toByte(), // width
            1, // components
            1,
            0x11,
            0, // component 1
            0xFF.toByte(),
            0xD9.toByte(), // EOI
        )
}
