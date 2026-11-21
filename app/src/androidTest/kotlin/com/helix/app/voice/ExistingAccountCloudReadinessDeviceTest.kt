package com.helix.app.voice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.account.AccountRequestFailure
import com.helix.app.account.AccountUsageClient
import com.helix.app.account.AccountUsageUnit
import com.helix.app.account.summary
import com.helix.app.provider.BuiltInModel
import com.helix.app.ui.dismissFirstLaunchIfNeeded
import com.helix.core.model.AgentMode
import com.helix.core.model.TurnState
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Opt-in real-service checks with an existing emulator login. No credential bridge or logout.
 * Speech input is synthetic, AudioTrack is muted, and no microphone or listening-quality claim is made.
 * Usage checks cover the production transport/parser, not account-panel rendering.
 */
class ExistingAccountCloudReadinessDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as HelixApplication
    private val result by lazy {
        File(app.cacheDir, "existing-account-cloud-readiness/${UUID.randomUUID()}.txt").also {
            check(it.parentFile!!.mkdirs() || it.parentFile!!.isDirectory)
            check(it.createNewFile())
        }
    }

    private fun record(value: String) = result.appendText("$value\n")

    private fun authenticated(
        stage: String,
        action: () -> Unit,
    ) {
        val enabled = InstrumentationRegistry.getArguments().getString("existingAccountCloudReadiness")
        assumeTrue("Real services not requested; this is not a pass", enabled != null)
        require(enabled == "true")
        check(app.packageName == "com.helix.agent.developer" && android.os.Build.HARDWARE == "ranchu")
        record("$stage=started")
        try {
            runBlocking { app.account.restore() }
            if (!app.account.signedIn.value) {
                record("$stage=blocked_missing_existing_login;service_requests=0")
                error("Existing emulator login required")
            }
            action()
            check(app.account.signedIn.value)
            record("$stage=passed")
        } catch (failure: Exception) {
            // Remote text, account details and nested causes are deliberately excluded from JUnit output.
            record("$stage=failed:${failure.javaClass.simpleName}")
            failure.stackTrace
                .take(
                    6,
                ).forEach { record("$stage.frame=${it.className}.${it.methodName}:${it.lineNumber}") }
            when (failure) {
                is SpeechHttpFailure -> {
                    record("$stage.http=${failure.status};requestId=${failure.details.requestId}")
                }

                is AccountRequestFailure -> {
                    record(
                        "$stage.http=${failure.details.status};requestId=${failure.details.requestId}",
                    )
                }
            }
            throw AssertionError("$stage failed; inspect sanitized stage evidence")
        } catch (failure: AssertionError) {
            record("$stage=failed:${failure.javaClass.simpleName}")
            failure.stackTrace
                .take(
                    6,
                ).forEach { record("$stage.frame=${it.className}.${it.methodName}:${it.lineNumber}") }
            throw AssertionError("$stage assertion failed; inspect sanitized stage evidence")
        }
    }

    @Test fun existingLoginReadsProductionUsageSummary() =
        authenticated("usage") {
            val summary = runBlocking { AccountUsageClient(app.account).read().summary() }
            check(
                summary.quotas.map { it.unit } ==
                    listOf(AccountUsageUnit.TOKENS, AccountUsageUnit.MINUTES, AccountUsageUnit.CHARACTERS),
            )
            check(summary.records.size <= 20)
            record("usage.transport_and_parser=true;account_panel_rendering=not_tested")
        }

    @Test fun cloudSpeechRoundTripUsesSyntheticAudioAndMutedPlayback() =
        authenticated("speech") {
            val settings = SpeechServiceSettings(httpEnabled = true, useAccountLogin = true)
            val samples = synthesizeAndPlay(settings)
            val transcript =
                HttpFileAsr(settings.session(settings.asrEndpoint, app.account), settings.asrModel).use { asr ->
                    resample(samples).asList().chunked(1600).forEach { asr.send(it.toFloatArray()) }
                    asr.finish()
                }
            check(transcript.any { it.isLetterOrDigit() })
            val expected = SYNTHETIC_SPEECH_TEXT.filter(Char::isLetterOrDigit)
            val recognized = transcript.filter(Char::isLetterOrDigit)
            val matches = recognized == expected
            // Only a known synthetic sentence is compared. Never log remote transcript text.
            record(
                "speech.http_asr_nonempty=true;synthetic_roundtrip_exact_match=$matches;" +
                    "expected_characters=${expected.length};recognized_characters=${recognized.length}",
            )
            check(matches) { "Synthetic TTS/ASR round trip differs; this does not isolate ASR accuracy" }
            // Local close-before-finish exercise only; no claim about remote cancellation.
            HttpFileAsr(settings.session(settings.asrEndpoint, app.account), settings.asrModel).use { asr ->
                asr.send(FloatArray(1600))
            }
            record(
                "speech.local_asr_close_exercised=true;remote_cancel=not_tested;" +
                    "microphone=false;audible_quality=not_tested",
            )
        }

    @Test fun cloudAsrAcceptsIndependentSyntheticWav() =
        authenticated("asr_fixture") {
            val fixture = File(app.cacheDir, "cloud-readiness-synthetic-asr.wav")
            check(fixture.isFile && fixture.length() in 44..640000)
            val settings = SpeechServiceSettings(httpEnabled = true, useAccountLogin = true)
            val transcript =
                HttpFileAsr(settings.session(settings.asrEndpoint, app.account), settings.asrModel).use { asr ->
                    SpeechWav.consume(fixture.readBytes()) { samples, rate ->
                        check(rate == 16000)
                        asr.send(samples)
                    }
                    asr.finish()
                }
            check(transcript.any { it.isLetterOrDigit() })
            record("asr_fixture.nonempty=true;source=macos_say;microphone=false;accuracy=not_asserted")
        }

    @Test fun cloudAsrRecognizesKnownSyntheticSentence() =
        authenticated("asr_known_sentence") {
            val fixture = File(app.cacheDir, "cloud-readiness-known-sentence.wav")
            check(fixture.isFile && fixture.length() in 44..640000)
            val settings = SpeechServiceSettings(httpEnabled = true, useAccountLogin = true)
            val transcript =
                HttpFileAsr(settings.session(settings.asrEndpoint, app.account), settings.asrModel).use { asr ->
                    SpeechWav.consume(fixture.readBytes()) { samples, rate ->
                        check(rate == 16000)
                        asr.send(samples)
                    }
                    asr.finish()
                }
            val expected = SYNTHETIC_SPEECH_TEXT.filter(Char::isLetterOrDigit)
            val recognized = transcript.filter(Char::isLetterOrDigit)
            val matches = recognized == expected
            record(
                "asr_known_sentence.exact_match=$matches;source=macos_say;microphone=false;" +
                    "expected_characters=${expected.length};recognized_characters=${recognized.length}",
            )
            check(matches) { "Known synthetic sample differs; this is not a real microphone quality check" }
        }

    @Test fun cloudAsrRecognizesFrozenTransportSentence() =
        authenticated("asr_transport_baseline") {
            val wav =
                InstrumentationRegistry
                    .getInstrumentation()
                    .context.assets
                    .open(
                        "voice/transport-sentence.wav",
                    ).use {
                        it.readBytes()
                    }
            val settings = SpeechServiceSettings(httpEnabled = true, useAccountLogin = true)
            val transcript =
                HttpFileAsr(settings.session(settings.asrEndpoint, app.account), settings.asrModel).use { asr ->
                    SpeechWav.consume(wav) { samples, rate ->
                        check(rate == 16000)
                        asr.send(samples)
                    }
                    asr.finish()
                }
            val expected = "你好，这是语音传输测试。".filter(Char::isLetterOrDigit)
            val recognized = transcript.filter(Char::isLetterOrDigit)
            record(
                "asr_transport_baseline.exact_match=${recognized == expected};" +
                    "expected=${expected.length};recognized=${recognized.length}",
            )
            check(recognized == expected) { "Frozen transport sentence transcription differs" }
        }

    private fun synthesizeAndPlay(settings: SpeechServiceSettings): List<Float> {
        val audio = mutableListOf<Float>()
        SpeechAudio(app, volume = 0f).sink(24000).use { sink ->
            HttpCloudTts(settings.session(settings.ttsEndpoint, app.account), settings.ttsModel).use { tts ->
                tts.stream(SYNTHETIC_SPEECH_TEXT) { pcm, rate ->
                    check(rate == 24000 && audio.size + pcm.size <= 24000 * 20)
                    audio.addAll(pcm.toList())
                    sink.write(pcm) { true }
                }
            }
            sink.finish { true }
            check(audio.any { kotlin.math.abs(it) > 0.001f })
            sink.interrupt()
        }
        record("speech.http_tts_nonzero=true;muted_audiotrack_drained=true")
        return audio
    }

    private fun resample(audio: List<Float>): FloatArray =
        FloatArray(audio.size * 2 / 3) { index ->
            val position = index * 1.5
            val first = position.toInt().coerceAtMost(audio.lastIndex)
            val second = minOf(first + 1, audio.lastIndex)
            audio[first] + (audio[second] - audio[first]) * (position - first).toFloat()
        }

    @Test fun ordinaryLunaConversationCompletesAfterActivityRecreation() =
        authenticated("luna") {
            compose.dismissFirstLaunchIfNeeded()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("app-startup").fetchSemanticsNodes().isEmpty() }
            val container = app.appContainer
            val chat = container.chatService
            check(
                runBlocking {
                    container.memory
                        ?.list(
                            com.helix.core.workspace.memory.MemoryScope.Global,
                        ).isNullOrEmpty()
                },
            )
            // Opening another session clears an ephemeral draft; do not discard one for acceptance.
            check(!chat.screen.value.isDraft) { "Open a persisted session before this opt-in UI test" }
            val previous = chat.screen.value.openSessionId
            val previousControl = chat.runControl.value
            val session =
                runBlocking {
                    chat.createSession(
                        "Synthetic cloud readiness",
                        BuiltInModel.PROVIDER_ID,
                        BuiltInModel.MODEL_ID,
                    )
                }
            try {
                sendAndVerifyLunaAnswer(session)
            } finally {
                recordLunaState(session)
                container.storage.turns
                    .listBySession(session)
                    .filter {
                        !TurnState.valueOf(it.state).isTerminal
                    }.forEach { chat.stop(it.id) }
                compose.waitUntil(15_000) {
                    container.storage.turns
                        .listBySession(session)
                        .all { TurnState.valueOf(it.state).isTerminal }
                }
                runBlocking {
                    chat.closeSession()
                    container.storage.deleteSessionPermanently(session)
                    if (previous != null) chat.openSession(previous)
                }
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == previous }
                if (previous != null) {
                    compose.waitUntil(10_000) { chat.runControl.value == previousControl }
                }
            }
        }

    private fun sendAndVerifyLunaAnswer(session: String) {
        val title = hasText("Synthetic cloud readiness") and hasAnyAncestor(hasTestTag("chat-header"))
        val container = app.appContainer
        val chat = container.chatService
        val storage = container.storage
        runBlocking { container.providerService.refreshModelCatalog(BuiltInModel.PROVIDER_ID) }
        chat.openSession(session)
        compose.waitUntil(10_000) {
            chat.screen.value.openSessionId == session && chat.screen.value.modelBindingIssue == null &&
                chat.screen.value.selectedModel
                    ?.model == BuiltInModel.MODEL_ID &&
                compose.onAllNodes(title).fetchSemanticsNodes().size == 1
        }
        record("luna.ui_title_found=true;activity=${compose.activity.lifecycle.currentState}")
        compose.onNode(title).assertIsDisplayed()
        record("luna.ui_title_displayed=true")
        chat.setMode(AgentMode.CHAT)
        chat.setChatToolsEnabled(false)
        compose.waitUntil(10_000) {
            chat.screen.value.openSessionId == session &&
                chat.runControl.value.mode == AgentMode.CHAT && !chat.runControl.value.chatToolsEnabled
        }
        compose.onNodeWithTag("chat-input").performTextReplacement("Reply only HELIX_CLOUD_READY. Do not call tools.")
        compose.waitForIdle()
        record("luna.ui_text_replaced=true")
        compose.onNodeWithTag("chat-input").assertTextEquals("Reply only HELIX_CLOUD_READY. Do not call tools.")
        record("luna.ui_text_matches=true")
        compose.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        record("luna.ui_send_clicked=true")
        admitSyntheticDisclosure(session)
        compose.waitUntil(15_000) { storage.turns.listBySession(session).size == 1 }
        compose.runOnUiThread { compose.activity.recreate() }
        compose.waitUntil(120_000) {
            storage.turns
                .listBySession(session)
                .singleOrNull()
                ?.let { TurnState.valueOf(it.state).isTerminal } == true
        }
        val turn = storage.turns.listBySession(session).single()
        check(turn.state == "COMPLETED")
        check(storage.toolCalls.listByTurn(turn.id).isEmpty())
        check(
            storage.messages.listBySession(session).any {
                it.role == "ASSISTANT" && it.kind == "TEXT" &&
                    storage.messages.readContent(it)?.contains("HELIX_CLOUD_READY") == true
            },
        )
        record("luna.persisted_answer=true;activity_recreation=true;tools=false")
    }

    private fun admitSyntheticDisclosure(session: String) {
        val container = app.appContainer
        val chat = container.chatService
        compose.waitUntil(15_000) {
            container.storage.turns
                .listBySession(session)
                .isNotEmpty() ||
                chat.screen.value.pendingDisclosure != null || chat.screen.value.blockedReason != null
        }
        chat.screen.value.pendingDisclosure?.let { disclosure ->
            check(chat.screen.value.openSessionId == session)
            check(disclosure.providerId == BuiltInModel.PROVIDER_ID)
            val origin = java.net.URI(disclosure.origin)
            check(origin.scheme == "https" && origin.host == "api.hilex.me" && origin.port in setOf(-1, 443))
            check(origin.rawUserInfo == null && origin.rawQuery == null && origin.rawFragment == null)
            check(origin.path in setOf("", "/"))
            check(disclosure.attachments.isEmpty() && !disclosure.hasHighSensitive)
            record("luna.synthetic_egress_disclosure=confirmed")
            compose.onNodeWithTag("egress-confirm").assertIsEnabled().performClick()
        }
    }

    private fun recordLunaState(session: String) {
        val container = app.appContainer
        val screen = container.chatService.screen.value
        record("luna.fixture_visible=${screen.openSessionId == session};bindingIssue=${screen.modelBindingIssue}")
        record("luna.blocked=${screen.blockedReason != null};disclosure=${screen.pendingDisclosure != null}")
        val turns = container.storage.turns.listBySession(session)
        record("luna.turn_count=${turns.size};states=${turns.joinToString { it.state }}")
        // Added after live-luna-r8: that historical run did not retain these diagnostic fields.
        turns.forEachIndexed { index, turn ->
            val errorCode = turn.errorCode?.takeIf { Regex("[A-Z][A-Z0-9_]{0,99}").matches(it) }
            record("luna.turn[$index].state=${turn.state};errorCode=${errorCode ?: "unknown_or_absent"}")
        }
        turns.flatMap { container.storage.modelCalls.listByTurn(it.id) }.forEachIndexed { index, call ->
            val state = call.state.takeIf { Regex("[A-Z][A-Z0-9_]{0,79}").matches(it) }
            val requestId = call.requestId?.takeIf { Regex("[A-Za-z0-9._:-]{1,128}").matches(it) }
            record("luna.model_call[$index].state=${state ?: "unknown"};requestId=${requestId ?: "unknown_or_absent"}")
            recordModelFailureDiagnostics(index, call.id)
            val snapshot = org.json.JSONObject(call.providerSnapshot)
            record("luna.executed_provider=${snapshot.getString("providerId")};model=${snapshot.getString("model")}")
        }
    }

    private fun recordModelFailureDiagnostics(
        index: Int,
        callId: String,
    ) {
        // Room's exact correlated event is model.failure; never inspect other calls or archive bodies.
        val events =
            app.appContainer.storage.auditEvents
                .listByCorrelation(callId)
                .filter { it.type == com.helix.core.agent.ModelFailureDiagnostics.TYPE }
        events.forEach { event ->
            val payload =
                try {
                    org.json.JSONObject(event.redactedPayload)
                } catch (_: org.json.JSONException) {
                    record("luna.model_call[$index].diagnostic=malformed")
                    return@forEach
                }
            val safe = org.json.JSONObject()
            (payload.opt("httpStatus") as? Int)?.takeIf { it in 100..599 }?.let { safe.put("httpStatus", it) }
            diagnosticEnums().forEach { (key, allowed) ->
                (payload.opt(key) as? String)?.takeIf { it in allowed }?.let { safe.put(key, it) }
            }
            val patterns =
                mapOf(
                    "serverCode" to Regex("[A-Za-z0-9_.:-]{1,96}"),
                    "errorParameter" to Regex("[A-Za-z0-9_.\\[\\]-]{1,128}"),
                    "requestId" to Regex("[A-Za-z0-9._:-]{1,128}"),
                )
            patterns.forEach { (key, pattern) ->
                (payload.opt(key) as? String)?.takeIf(pattern::matches)?.let { safe.put(key, it) }
            }
            record("luna.model_call[$index].diagnostic=$safe")
        }
    }

    private fun diagnosticEnums(): Map<String, Set<String>> =
        mapOf(
            "stage" to
                com.helix.core.model.ModelFailureDiagnostic.Stage.entries
                    .map { it.name }
                    .toSet(),
            "reason" to
                com.helix.core.model.ModelFailureDiagnostic.Reason.entries
                    .map { it.name }
                    .toSet(),
            "recoveryDecision" to
                setOf(
                    "RETRY_SCHEDULED",
                    "PROVIDER_RETRY_DISABLED",
                    "NOT_RETRYABLE",
                    "PARTIAL_OR_TERMINAL_STREAM",
                    "TECHNICAL_RECOVERY_EXHAUSTED",
                ),
        )

    private companion object {
        const val SYNTHETIC_SPEECH_TEXT = "你好，这是云端语音验证。"
    }
}
