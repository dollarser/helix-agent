package com.helix.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.chat.ChatImageRef
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.workspace.FileScopePath
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit owner-authorized real provider, synthetic image only, no user account or conversation reads. */
class LiveImagePreviewDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod", "TooGenericExceptionCaught") // Owned journey; failure capture always rethrows.
    fun previewHistoryAndRealModelRecognitionAreVerifiedSeparately() =
        runBlocking {
            val (port, model, scale) = readProfile()
            val c = compose.container()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val chat = c.chatService
            val previousSession = chat.screen.value.openSessionId
            val provider =
                c.providerService.create(
                    ProviderDraft(
                        null,
                        "Image preview fixture",
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
            var session: String? = null
            val source = File(context.filesDir, "attachments/camera/hxa302-${UUID.randomUUID()}.png")
            try {
                check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                check(c.providerService.runCapabilityTest(provider, model) is ProbeOutcome.Ok)
                check(c.providerService.capabilitiesFor(provider, model)?.vision == true)
                val id = chat.createSession("Image preview fixture", provider, model)
                session = id
                chat.openSession(id)
                compose.navigateTo("sessions")
                chat.setMode(AgentMode.CHAT)
                chat.setChatToolsEnabled(false)
                chat.setReasoning(ReasoningEffort.OFF) // Omits effort; does not disable server-default thinking.
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == id }
                writeImage(source, scale)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
                chat.stageAttachment(uri.toString())
                compose.waitUntil(10_000) { chat.screen.value.pendingAttachments.size == 1 }
                val pending =
                    chat.screen.value.pendingAttachments
                        .single()
                val staged = requireNotNull(chat.previewImage(ChatImageRef(id, null, pending.id)))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(staged, 0, staged.size, bounds)
                assertEquals(640 * scale, bounds.outWidth)
                assertEquals(360 * scale, bounds.outHeight)
                println("LIVE_IMAGE_INPUT scale=$scale width=${bounds.outWidth} height=${bounds.outHeight}")
                val digest =
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(staged)
                        .joinToString("") { "%02x".format(it) }
                println("LIVE_IMAGE_INPUT_SHA256=$digest")
                compose.onNodeWithTag("chat-image-${pending.id}").performClick()
                compose.onNodeWithTag("chat-image-preview").assertIsDisplayed()
                assertTrue(
                    c.storage.turns
                        .listBySession(id)
                        .isEmpty(),
                )
                compose.onNodeWithTag("chat-image-close").performClick()
                compose.onNodeWithTag("chat-input").performTextInput(
                    "Read the three-digit code and the circle color in the image. " +
                        "Reply only CODE|COLOR in English.",
                )
                compose.onNodeWithTag("chat-send").performClick()
                androidx.test.espresso.Espresso
                    .closeSoftKeyboard()
                compose.waitUntil(10_000) {
                    chat.screen.value.pendingDisclosure != null || chat.screen.value.blockedReason != null
                }
                check(chat.screen.value.pendingDisclosure != null) {
                    chat.screen.value.blockedReason
                        .orEmpty()
                }
                chat.confirmSend()
                compose.waitUntil(180_000) {
                    val latest =
                        c.storage.turns
                            .listBySession(id)
                            .lastOrNull()
                    latest != null && TurnState.valueOf(latest.state).isTerminal
                }
                val turn =
                    c.storage.turns
                        .listBySession(id)
                        .single()
                assertEquals(turn.errorCode, "COMPLETED", turn.state)
                val reply =
                    c.storage.messages
                        .listBySession(id)
                        .filter { it.role == "ASSISTANT" }
                        .mapNotNull(c.storage.messages::readContent)
                        .joinToString("\n")
                println("LIVE_IMAGE_REPLY=${reply.replace('\n', ' ').take(500)}")
                val recognized = reply.contains("482") && reply.uppercase().contains("BLUE")
                println("LIVE_IMAGE_MODEL_ACCURACY=${if (recognized) "PASS" else "FAIL"}")
                chat.closeSession()
                chat.openSession(id)
                compose.waitUntil(10_000) {
                    chat.screen.value.messages
                        .any { it.images.isNotEmpty() }
                }
                val saved =
                    chat.screen.value.messages
                        .first { it.images.isNotEmpty() }
                        .images
                        .single()
                assertArrayEquals(staged, chat.previewImage(saved.reference))
                androidx.test.espresso.Espresso
                    .closeSoftKeyboard()
                compose.onNodeWithTag("chat-timeline").performTouchInput { swipeDown() }
                compose
                    .onNodeWithTag("chat-timeline")
                    .performScrollToNode(hasTestTag("chat-image-${saved.reference.attachmentId}"))
                compose
                    .onNodeWithTag("chat-image-${saved.reference.attachmentId}")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .performClick()
                compose.onNodeWithTag("chat-image-preview").assertIsDisplayed()
                compose.waitUntil(10_000) {
                    val previewImage =
                        hasTestTag("chat-image-loaded") and
                            hasAnyAncestor(hasTestTag("chat-image-preview"))
                    compose.onAllNodes(previewImage, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                captureUiExperience("real-model-image-history")
                compose.onNodeWithTag("chat-image-close").performClick()
                println("LIVE_IMAGE_PREVIEW_PATH=PASS model=$model turns=1")
                assertTrue(
                    "Image code/color was not recognized: $reply",
                    recognized,
                )
                println("LIVE_IMAGE_PREVIEW=PASS model=$model turns=1")
            } catch (failure: Throwable) {
                runCatching { captureUiExperience("real-model-image-failure") }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                throw failure
            } finally {
                session?.let { id ->
                    c.storage.turns.listBySession(id).lastOrNull()?.let {
                        chat.stopTurn(it.id)
                        withTimeout(20_000) { c.agentRuntime.observe(TurnId(it.id)).collect {} }
                    }
                    val artifacts = c.storage.artifacts.listBySession(id)
                    chat.closeSession()
                    c.storage.deleteSessionPermanently(id)
                    artifacts.forEach { artifact ->
                        val path = FileScopePath.fromModelReference(artifact.relativePath).relativePath
                        check(path.startsWith("input/attachments/"))
                        val file = File(context.filesDir, "workspaces/app").resolve(path)
                        check(!file.exists() || file.delete())
                        file.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
                    }
                }
                source.delete()
                c.providerService.delete(provider)
                previousSession?.let(chat::openSession)
            }
        }

    private data class Profile(
        val port: Int,
        val model: String,
        val scale: Int,
    )

    private fun readProfile(): Profile {
        val args = InstrumentationRegistry.getArguments()
        val rawPort = args.getString("imagePort")
        val rawModel = args.getString("imageModel")
        val rawScale = args.getString("imageScale")
        assumeTrue("Explicit model profile required", rawPort != null || rawModel != null || rawScale != null)
        val port = requireNotNull(rawPort).toInt().also { require(it in 1..65535) }
        val model = requireNotNull(rawModel).also { require(it.isNotBlank()) }
        val scale = (rawScale?.toInt() ?: 1).also { require(it in 1..2) }
        return Profile(port, model, scale)
    }

    private fun writeImage(
        file: File,
        scale: Int,
    ) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 100f
            }
        canvas.drawText("482", 50f, 160f, paint)
        paint.color = Color.BLUE
        canvas.drawCircle(470f, 170f, 85f, paint)
        // Keep the original fixture and assertion. Enlarging is an explicit diagnostic arm only.
        val output = if (scale == 1) bitmap else Bitmap.createScaledBitmap(bitmap, 640 * scale, 360 * scale, true)
        try {
            file.outputStream().use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            if (output !== bitmap) output.recycle()
            bitmap.recycle()
        }
    }
}
