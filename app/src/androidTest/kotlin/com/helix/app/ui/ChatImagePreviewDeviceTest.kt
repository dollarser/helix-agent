package com.helix.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.chat.ChatImageRef
import com.helix.app.chat.ChatImageUi
import com.helix.app.chat.PendingAttachmentUi
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ChatImagePreviewDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun composerContainsThumbnailAndPreviewRemoveSendAndHistoryStaySeparate() {
        val image = PendingAttachmentUi("photo", "test-photo.png", 100, false, true)
        val pending = mutableStateOf(listOf(image))
        val history = mutableStateOf(emptyList<ChatImageUi>())
        val bytes = png()
        var sends = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    ChatMessageImages(history.value) { bytes }
                    ConversationComposer(
                        "Look at this image",
                        {},
                        false,
                        pending.value.isNotEmpty(),
                        ComposerActions(onFile = {}, onVoice = {}, onSend = {
                            sends++
                            history.value =
                                listOf(ChatImageUi(ChatImageRef("session", "message", "photo"), image.fileName))
                            pending.value = emptyList()
                        }, onStop = {}),
                        attachmentsContent = {
                            PendingAttachmentStrip(pending.value, { pending.value = emptyList() }, "session") { bytes }
                        },
                    )
                }
            }
        }
        awaitLoaded()
        val thumbnail = compose.onNodeWithTag("chat-image-photo").getUnclippedBoundsInRoot()
        val composer = compose.onNodeWithTag("chat-composer").getUnclippedBoundsInRoot()
        val input = compose.onNodeWithTag("chat-input").getUnclippedBoundsInRoot()
        assertTrue(thumbnail.left >= composer.left && thumbnail.right <= composer.right)
        assertTrue(thumbnail.top >= composer.top && thumbnail.bottom <= input.top)
        captureUiExperience("composer-image")
        compose.onNodeWithTag("chat-image-photo").performClick()
        compose.onNodeWithTag("chat-image-preview").assertIsDisplayed()
        assertEquals(0, sends)
        captureUiExperience("image-preview")
        compose.onNodeWithTag("chat-image-close").performClick()
        compose.onNodeWithTag("chat-pending-remove-photo").performClick()
        compose.onNodeWithTag("chat-image-photo").assertDoesNotExist()
        compose.runOnIdle { pending.value = listOf(image) }
        compose.onNodeWithTag("chat-send").performClick()
        compose.onNodeWithTag("chat-pending-attachments").assertDoesNotExist()
        compose.onNodeWithTag("chat-image-photo").performClick()
        compose.onNodeWithTag("chat-image-preview").assertIsDisplayed()
        assertEquals(1, sends)
        compose.onNodeWithTag("chat-image-close").performClick()
    }

    @Test fun switchingSessionCancelsOldPreviewAndRemovedImageClosesDialog() {
        val session = mutableStateOf("first")
        val pending = mutableStateOf(listOf(PendingAttachmentUi("same-id", "image.png", 100, false, true)))
        val oldLoad = CompletableDeferred<ByteArray?>()
        val bytes = png()
        compose.setContent {
            MaterialTheme {
                PendingAttachmentStrip(pending.value, {}, session.value) { ref ->
                    if (ref.sessionId == "first") oldLoad.await() else bytes
                }
            }
        }
        compose.onNodeWithTag("chat-image-same-id").performClick()
        compose.onNodeWithTag("chat-image-preview").assertIsDisplayed()
        compose.runOnIdle { session.value = "second" }
        compose.onNodeWithTag("chat-image-preview").assertDoesNotExist()
        awaitLoaded()
        oldLoad.complete(null)
        compose.onNodeWithTag("chat-image-same-id").performClick()
        compose.onNodeWithTag("chat-image-preview").assertIsDisplayed()
        compose.runOnIdle { pending.value = emptyList() }
        compose.onNodeWithTag("chat-image-preview").assertDoesNotExist()
    }

    @Test fun corruptAndUnavailableImagesAreHonestAndCanBeDismissed() {
        compose.setContent {
            MaterialTheme {
                ChatMessageImages(listOf(ChatImageUi(ChatImageRef("s", "m", "a"), "broken.png"))) {
                    byteArrayOf(1, 2, 3)
                }
            }
        }
        compose.waitUntil(
            5_000,
        ) {
            compose
                .onAllNodesWithTag(
                    "chat-image-unavailable",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("chat-image-a").performClick()
        compose.onNodeWithTag("chat-image-close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat-image-preview").assertDoesNotExist()
    }

    @Test fun localDecodeBoundsPixelsAndHonorsCameraOrientation() {
        assertNull(decodePreviewBitmap(byteArrayOf(1, 2, 3), 192))
        val large = requireNotNull(decodePreviewBitmap(png(2048, 1024), 192))
        assertEquals(192, large.width)
        assertEquals(96, large.height)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("hxa302-orientation", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            android.media.ExifInterface(file.path).apply {
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6")
                saveAttributes()
            }
            val decoded = requireNotNull(decodePreviewBitmap(file.readBytes(), 192))
            assertEquals(32, decoded.width)
            assertEquals(64, decoded.height)
        } finally {
            check(file.delete())
        }
    }

    private fun awaitLoaded() =
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("chat-image-loaded", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

    private fun png(
        width: Int = 96,
        height: Int = 64,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff378ad0.toInt())
        return ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            bitmap.recycle()
            out.toByteArray()
        }
    }
}

internal fun captureUiExperience(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val bitmap = stableChatLayoutScreenshot()
    try {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "hxa302-ui").apply { mkdirs() }
        directory.resolve("$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally {
        bitmap.recycle()
    }
}
