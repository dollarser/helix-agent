package com.helix.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ProviderModelSelection
import com.helix.app.provider.ProviderRowUi
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ConversationComposerDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun singleLineStaysCompactAndMultilineGrowsWithContent() {
        val draft = mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    ConversationComposer(
                        draft.value,
                        { draft.value = it },
                        false,
                        false,
                        ComposerActions(onFile = {}, onVoice = {}, onSend = {}, onStop = {}),
                    )
                }
            }
        }
        val empty = inputHeight()
        compose.runOnIdle { draft.value = "Single line" }
        val single = inputHeight()
        assertEquals(empty, single)
        assertTrue("A single line must not reserve three lines", single <= 48.dp)
        compose.runOnIdle { draft.value = "First line\nSecond line\nThird line" }
        val multiline = inputHeight()
        assertTrue("Additional lines must grow the editor", multiline >= single + 24.dp)
        assertFits("chat-input", "chat-add", "chat-send", "chat-permission-menu")
    }

    private fun inputHeight() =
        compose.onNodeWithTag("chat-input").getUnclippedBoundsInRoot().let { it.bottom - it.top }

    @Suppress("LongMethod") // One end-to-end layout/state contract for narrow large-font composer.
    @Test
    fun narrowLargeFontKeepsEditingAndStopAccessible() {
        val input = mutableStateOf("")
        val sending = mutableStateOf(false)
        val attachments = mutableStateOf(false)
        var sends = 0
        var stops = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme {
                    Column(Modifier.width(320.dp)) {
                        ConversationComposer(
                            input.value,
                            { input.value = it },
                            sending.value,
                            attachments.value,
                            ComposerActions(onFile = {}, onVoice = {}, onSend = { sends++ }, onStop = { stops++ }),
                        )
                    }
                }
            }
        }
        assertFits("chat-input", "chat-add", "chat-voice", "chat-send")
        val field = compose.onNodeWithTag("chat-input").getUnclippedBoundsInRoot()
        assertTrue("Editing needs the available width", field.right - field.left >= 100.dp)
        val attach = compose.onNodeWithTag("chat-add").getUnclippedBoundsInRoot()
        val voice = compose.onNodeWithTag("chat-voice").getUnclippedBoundsInRoot()
        val send = compose.onNodeWithTag("chat-send").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("chat-mode-menu").assertDoesNotExist()
        val options = compose.onNodeWithTag("chat-composer-options").getUnclippedBoundsInRoot()
        listOf(attach, voice, send).forEach { bounds ->
            assertTrue("Actions stay below editing", bounds.top >= field.bottom)
        }
        listOf(options).forEach { bounds ->
            assertTrue("Options stay above editing", bounds.bottom <= field.top)
        }
        assertTrue(attach.right <= voice.left && voice.right <= send.left)
        compose.onNodeWithTag("chat-reasoning-menu").assertDoesNotExist()
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.runOnIdle { attachments.value = true }
        compose.onNodeWithTag("chat-send").assertIsEnabled()
        compose.runOnIdle { attachments.value = false }
        compose.onNodeWithTag("chat-input").performTextInput("Draft")
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle {
            assertEquals(1, sends)
            sending.value = true
        }
        assertFits("chat-stop", "chat-send", "chat-input")
        val stop = compose.onNodeWithTag("chat-stop").getUnclippedBoundsInRoot()
        val queuedSend = compose.onNodeWithTag("chat-send").getUnclippedBoundsInRoot()
        assertEquals("Stop and Queue/Steer send share one action row", stop.top, queuedSend.top)
        compose.onNodeWithTag("chat-input").assertIsEnabled()
        listOf("chat-add", "chat-voice").forEach {
            compose.onNodeWithTag(it).assertIsEnabled()
        }
        compose.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, sends) }
        compose.onNodeWithTag("chat-stop").performClick()
        compose.runOnIdle {
            assertEquals(1, stops)
            assertEquals("Draft", input.value)
        }
    }

    @Test fun englishGoalActionsHaveRoomAtDoubleFontSize() {
        compose.setContent {
            val context = LocalContext.current
            val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.ENGLISH) }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(config),
                LocalDensity provides Density(density.density, 2f),
            ) {
                MaterialTheme {
                    Column(Modifier.width(320.dp)) {
                        ConversationComposer(
                            "A goal to execute",
                            {},
                            false,
                            false,
                            ComposerActions(onFile = {}, onVoice = {}, onSend = {}, onStop = {}),
                            goalMode = true,
                        )
                    }
                }
            }
        }
        assertFits("chat-input", "chat-add", "chat-voice", "chat-send")
        val attach = compose.onNodeWithTag("chat-add").getUnclippedBoundsInRoot()
        assertTrue("Attachment label should fit one line", attach.bottom - attach.top < 72.dp)
        compose.onNodeWithTag("chat-send").assertIsEnabled()
    }

    @Test fun attachmentOnlySupplementIsEnabledDuringGoalButNotForNewObjective() {
        val sending = mutableStateOf(false)
        var sends = 0
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    "",
                    {},
                    sending.value,
                    true,
                    ComposerActions(onFile = {}, onVoice = {}, onSend = { sends++ }, onStop = {}),
                    goalMode = true,
                )
            }
        }
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.runOnIdle { sending.value = true }
        compose.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, sends) }
        compose.onNodeWithTag("chat-stop").assertIsEnabled()
    }

    @Test
    fun conversationReferenceChipEnablesSendAndCanBeRemoved() {
        val reference = mutableStateOf<String?>("Source conversation")
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    input = "",
                    onInput = {},
                    isSending = false,
                    hasAttachments = false,
                    actions = ComposerActions(onFile = {}, onVoice = {}, onSend = {}, onStop = {}),
                    referenceLabel = reference.value,
                    onRemoveReference = { reference.value = null },
                )
            }
        }

        compose.onNodeWithTag("composer-reference-chip").assertIsDisplayed()
        compose.onNodeWithTag("chat-send").assertIsEnabled()
        compose.onNodeWithTag("composer-reference-remove").performClick()
        compose.onNodeWithTag("composer-reference-chip").assertDoesNotExist()
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
    }

    @Test fun reasoningSelectionIsExplicitAndLockedDuringGeneration() {
        val provider = reasoningFixtureProvider()
        val reasoning = mutableStateOf(ReasoningEffort.OFF)
        val sending = mutableStateOf(false)
        var sends = 0
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    "draft",
                    {},
                    sending.value,
                    false,
                    ComposerActions(onFile = {}, onVoice = {}, onSend = { sends++ }, onStop = {}),
                    modelSelector = {
                        ComposerModelMenu(
                            listOf(provider),
                            provider.id,
                            "selected-model",
                            !sending.value,
                            {
                                _,
                                _,
                                effort,
                                ->
                                reasoning.value = effort
                                com.helix.app.chat.SessionModelSelectionResult.APPLIED
                            },
                            reasoning = reasoning.value,
                        )
                    },
                )
            }
        }
        compose.onNodeWithTag("chat-reasoning-menu").assertDoesNotExist()
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-reasoning-menu").performClick()
        compose.onNodeWithTag("chat-reasoning-medium").performClick()
        compose.runOnIdle { assertEquals(ReasoningEffort.OFF, reasoning.value) }
        compose.onNodeWithTag("chat-model-picker-save").performClick()
        compose.runOnIdle {
            assertEquals(ReasoningEffort.MEDIUM, reasoning.value)
            assertEquals(0, sends)
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-reasoning-menu").performClick()
        compose.runOnIdle { sending.value = true }
        compose.onNodeWithTag("chat-model-menu").assertIsNotEnabled()
        compose.onNodeWithTag("chat-reasoning-menu").assertDoesNotExist()
        compose.onNodeWithTag("chat-reasoning-medium").assertDoesNotExist()
        compose.onNodeWithTag("chat-mode-menu").assertDoesNotExist()
    }

    private fun reasoningFixtureProvider(): ProviderRowUi {
        val caps = ProviderCapabilities(true, true, false, false, true, false, 8192, CapabilitySource.PROBED)
        return ProviderRowUi(
            "fixture",
            "Fixture",
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            "https://example.test",
            ProviderResidence.PUBLIC_CLOUD,
            "selected-model",
            false,
            false,
            ConnectionTestStatus.Passed(1, caps, listOf("selected-model")),
            caps,
            listOf("selected-model"),
            modelSelection = ProviderModelSelection(models = listOf("selected-model"), configured = true),
        )
    }

    @Test fun modelStaysBelowEditingAndReasoningLivesInsidePicker() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme {
                    Column(Modifier.width(320.dp)) {
                        ConversationComposer(
                            "Draft",
                            {},
                            false,
                            false,
                            ComposerActions(onFile = {}, onVoice = {}, onSend = {}, onStop = {}),
                            modelSelector = {
                                ComposerModelMenu(
                                    emptyList(),
                                    null,
                                    "A long model name",
                                    true,
                                    { _, _, _ -> com.helix.app.chat.SessionModelSelectionResult.APPLIED },
                                )
                            },
                            contextUsage =
                                com.helix.app.agent
                                    .ChatContextUsage(8000, 10000),
                        )
                    }
                }
            }
        }
        assertFits("chat-input", "chat-add", "chat-send")
        val voice = compose.onNodeWithTag("chat-voice").getUnclippedBoundsInRoot()
        val add = compose.onNodeWithTag("chat-add").getUnclippedBoundsInRoot()
        val send = compose.onNodeWithTag("chat-send").getUnclippedBoundsInRoot()
        val input = compose.onNodeWithTag("chat-input").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("chat-mode-menu").assertDoesNotExist()
        val model = compose.onNodeWithTag("chat-model-menu").getUnclippedBoundsInRoot()
        assertTrue(add.right <= model.left && model.right <= voice.left && voice.right <= send.left)
        assertTrue(voice.top >= input.bottom)
        assertTrue(model.top >= input.bottom)
        compose.onNodeWithTag("chat-reasoning-menu").assertDoesNotExist()
        compose.onNodeWithTag("chat-context-window").assertIsDisplayed()
        compose.onNodeWithTag("chat-copy-input").assertDoesNotExist()
    }

    @Test fun serverDefinedEffortsUpdateWithoutACompiledOptionList() {
        val future = ReasoningEffort.fromWire("adaptive_next")
        val options = mutableStateOf(listOf(ReasoningEffort.OFF, future))
        var selected = ReasoningEffort.OFF
        compose.setContent {
            MaterialTheme {
                ComposerReasoningMenu(selected, true, { selected = it }, efforts = options.value)
            }
        }
        compose.onNodeWithTag("chat-reasoning-menu").performClick()
        compose.onNodeWithTag("chat-reasoning-low").assertDoesNotExist()
        compose.onNodeWithTag("chat-reasoning-adaptive_next").performClick()
        compose.runOnIdle { assertEquals(future, selected) }
        compose.runOnIdle { options.value = listOf(ReasoningEffort.OFF, ReasoningEffort.LOW) }
        compose.onNodeWithTag("chat-reasoning-menu").performClick()
        compose.onNodeWithTag("chat-reasoning-adaptive_next").assertDoesNotExist()
        compose.onNodeWithTag("chat-reasoning-low").performClick()
        compose.runOnIdle { assertEquals(ReasoningEffort.LOW, selected) }
    }

    @Test fun commonPhoneWidthsKeepHeaderAndEditingOnTheirOwnRows() {
        val width = mutableStateOf(320)
        var imeBottom = 0
        compose.setContent {
            val density = LocalDensity.current
            val keyboard = WindowInsets.ime.getBottom(density)
            SideEffect { imeBottom = keyboard }
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme {
                    Column(Modifier.width(width.value.dp)) {
                        ConversationComposer(
                            "第一行\n第二行\n第三行\n第四行",
                            {},
                            false,
                            false,
                            ComposerActions(onFile = {}, onVoice = {}, onSend = {}, onStop = {}),
                            permissionMode = com.helix.core.model.SessionPermissionMode.FULL_ACCESS,
                            headerStatus = { androidx.compose.material3.Text("Queued message") },
                            contextUsage =
                                com.helix.app.agent
                                    .ChatContextUsage(8000, 10000),
                        )
                    }
                }
            }
        }
        for (size in listOf(320, 360, 412)) {
            compose.runOnIdle { width.value = size }
            assertFits(
                "chat-input",
                "chat-voice",
                "chat-add",
                "chat-send",
                "chat-context-window",
                "chat-composer-options",
                "chat-permission-menu",
            )
            val input = compose.onNodeWithTag("chat-input").getUnclippedBoundsInRoot()
            val voice = compose.onNodeWithTag("chat-voice").getUnclippedBoundsInRoot()
            val add = compose.onNodeWithTag("chat-add").getUnclippedBoundsInRoot()
            val header = compose.onNodeWithTag("chat-composer-header").getUnclippedBoundsInRoot()
            assertTrue(add.right <= voice.left && voice.top >= input.bottom)
            assertTrue(header.bottom <= input.top)
            val permission = compose.onNodeWithTag("chat-permission-menu").getUnclippedBoundsInRoot()
            assertTrue(permission.top >= header.top && permission.bottom <= header.bottom)
            assertTrue(permission.bottom - permission.top >= 48.dp)
            compose.onNodeWithTag("chat-composer-footer").assertDoesNotExist()
            compose.onNodeWithTag("chat-current-mode").assertIsDisplayed()
            captureComposer("width-$size")
        }
        compose.runOnIdle { width.value = 320 }
        compose.onNodeWithTag("chat-input").performClick()
        compose.waitUntil(10_000) { imeBottom > 0 }
        assertFits("chat-input", "chat-permission-menu", "chat-send", "chat-composer-options")
        captureComposer("keyboard-multiline")
    }

    private fun captureComposer(stage: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("composerCapture") != "true") return
        compose.waitForIdle()
        // This is visual evidence, not a pixel-equality test: the focused caret and IME may animate.
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory =
                instrumentation.targetContext.cacheDir
                    .resolve("composer-layout")
                    .apply { mkdirs() }
            directory.resolve("$stage.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertFits(vararg tags: String) {
        val area = compose.onNodeWithTag("chat-composer").getUnclippedBoundsInRoot()
        tags.forEach { tag ->
            val node = compose.onNodeWithTag(tag)
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$tag must be contained", bounds.left >= area.left && bounds.right <= area.right)
            assertTrue("$tag must fit vertically", bounds.top >= area.top && bounds.bottom <= area.bottom)
        }
    }
}
