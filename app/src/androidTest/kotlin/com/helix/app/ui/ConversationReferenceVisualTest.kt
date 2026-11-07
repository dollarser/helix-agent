package com.helix.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.ui.shared.HelixTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Picker-only fixture: no account screen, global setting reset, model call or user-draft mutation. */
class ConversationReferenceVisualTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val ids = mutableListOf<String>()
    private val selected = mutableStateOf<String?>(null)
    private val opened = mutableStateOf(true)
    private val enlargedDark = mutableStateOf(false)

    @Test fun pickerOnlySyntheticVisuals() =
        runBlocking {
            val token = "引用验收-${UUID.randomUUID().toString().take(8)}"
            try {
                val target = source("$token 当前会话", false)
                val first = source("$token 同名会话", true)
                source("$token 同名会话", true)
                val empty = source("$token 同名会话", false)
                val longRows = (1..24).map { source("$token 会话 ${it.toString().padStart(2, '0')}", true) }
                app.appContainer.chatService
                    .refreshReferenceSessions()
                    .await()
                selected.value = first
                showPicker(target)
                compose.onNodeWithTag("reference-search").performClick().performTextInput("$token 同名会话")
                compose.onNodeWithTag("reference-search").assertIsDisplayed()
                compose.onNodeWithTag("conversation-reference-$first").assertIsSelected()
                compose.onNodeWithTag("conversation-reference-$empty").assertIsNotEnabled()
                compose.onNodeWithTag("conversation-reference-$target").assertDoesNotExist()
                androidx.test.espresso.Espresso
                    .pressBack()
                compose.onNodeWithTag("conversation-reference-sheet").assertIsDisplayed()
                capture("reference-selected-light")
                compose.onNodeWithTag("reference-search").performTextReplacement(token)
                compose
                    .onNode(
                        hasScrollToIndexAction(),
                    ).performScrollToNode(hasTestTag("conversation-reference-${longRows.last()}"))
                compose.onNodeWithTag("reference-search").assertIsDisplayed()
                compose.onNodeWithTag("conversation-reference-${longRows.last()}").performClick().assertIsSelected()
                capture("reference-long-list")
                compose.runOnIdle { enlargedDark.value = true }
                compose.onNodeWithTag("reference-search").assertIsDisplayed()
                capture("reference-large-dark")
                compose.onNodeWithTag("conversation-reference-close").performClick()
                compose.onNodeWithTag("conversation-reference-sheet").assertDoesNotExist()
            } finally {
                compose.runOnIdle { opened.value = false }
                ids.forEach { app.appContainer.storage.deleteSessionPermanently(it) }
                app.appContainer.chatService
                    .refreshReferenceSessions()
                    .await()
            }
        }

    private fun source(
        title: String,
        readable: Boolean,
    ): String {
        val id = "reference-visual-${UUID.randomUUID()}"
        ids += id
        val storage = app.appContainer.storage
        storage.sessions.create(id, title, null, null, System.currentTimeMillis())
        if (readable) storage.messages.append("message-$id", id, null, "USER", "TEXT", "Synthetic UI evidence only")
        return id
    }

    private fun showPicker(target: String) {
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current)
            configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (enlargedDark.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, if (enlargedDark.value) 1.4f else 1f),
            ) {
                HelixTheme {
                    Surface {
                        if (opened.value) {
                            ConversationReferencePicker(
                                app.appContainer.chatService,
                                target,
                                onSelect = { selected.value = it.id },
                                onDismiss = { opened.value = false },
                                selectedSessionId = selected.value,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val image = stableChatLayoutScreenshot()
        try {
            val directory = File(app.cacheDir, "reference-visual-evidence").apply { mkdirs() }
            directory.resolve("$name.png").outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
    }
}
