package com.helix.feature.conversation.presentation

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ReasoningEffort
import com.helix.feature.conversation.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ComposerModelGroupsDeviceTest : ConversationDeviceTestFixture() {
    @get:Rule val compose = createComposeRule()
    private val sources = mutableStateOf(listOf(modelSource("first", "Service A"), modelSource("second", "Service B")))
    private var saved: Triple<String, String, ReasoningEffort>? = null

    @Test fun sourceHeadingsReplacePerModelSubtitlesAndSelectionKeepsExactSource() {
        render()
        compose.onNodeWithTag("chat-model-menu").performClick()
        listOf("first", "second").forEach { id ->
            compose
                .onNodeWithTag("chat-model-source-$id")
                .performScrollTo()
                .assertIsDisplayed()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose.onNodeWithTag("chat-model-$id-shared").performScrollTo().assertIsDisplayed()
            val headingTop = compose.onNodeWithTag("chat-model-source-$id").getUnclippedBoundsInRoot().top
            val modelTop = compose.onNodeWithTag("chat-model-$id-shared").getUnclippedBoundsInRoot().top
            assertTrue(headingTop < modelTop)
        }
        compose.onAllNodesWithText("Service A").assertCountEquals(1)
        compose.onAllNodesWithText("Service B").assertCountEquals(1)
        compose.onNodeWithTag("chat-model-second-shared").performClick().assertIsSelected()
        compose
            .onNodeWithTag("chat-model-second-shared")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        val selectedModel = compose.onNodeWithTag("chat-model-second-shared").getUnclippedBoundsInRoot()
        val reasoning = compose.onNodeWithTag("chat-reasoning-menu").getUnclippedBoundsInRoot()
        assertTrue("Reasoning controls must sit below the model label", reasoning.top >= selectedModel.bottom)
        capturePickerIfRequested()
        compose.runOnIdle { assertEquals(null, saved) }
        compose.onNodeWithTag("chat-model-picker-save").performClick()
        compose.runOnIdle { assertEquals(Triple("second", "shared", ReasoningEffort.OFF), saved) }
        compose.onNodeWithTag("chat-model-list").assertDoesNotExist()
    }

    @Test fun sameNamedServicesRemainSeparateAndCancelDiscardsPendingSelection() {
        sources.value = sources.value.map { it.copy(displayName = "Same service") }
        render()
        repeat(2) {
            compose.onNodeWithTag("chat-model-menu").performClick()
            compose.onNodeWithTag("chat-model-first-shared").assertIsSelected()
            compose.onNodeWithTag("chat-model-source-first").assertIsDisplayed()
            compose.onNodeWithTag("chat-model-source-second").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("Same service").assertCountEquals(2)
            compose
                .onNodeWithTag("chat-model-second-shared")
                .performScrollTo()
                .performClick()
                .assertIsSelected()
            compose.onNodeWithTag("chat-model-picker-cancel").performClick()
            compose.onNodeWithTag("chat-model-list").assertDoesNotExist()
            compose.runOnIdle { assertEquals(null, saved) }
        }
    }

    @Test fun searchKeepsMatchingGroupsAndNeverIncludesUncheckedModels() {
        render()
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-search").performTextReplacement("service b")
        compose.onNodeWithTag("chat-model-source-first").assertDoesNotExist()
        compose.onNodeWithTag("chat-model-source-second").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-second-shared").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-search").performTextReplacement("Same model")
        compose.onNodeWithTag("chat-model-source-first").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-source-second").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chat-model-search").performScrollTo().performTextReplacement("hidden")
        compose.onNodeWithTag("chat-model-empty").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-source-first").assertDoesNotExist()
        compose.onNodeWithTag("chat-model-source-second").assertDoesNotExist()
        compose.onNodeWithTag("chat-model-first-hidden").assertDoesNotExist()
        compose.onNodeWithTag("chat-model-search").performTextReplacement("")
        compose.onNodeWithTag("chat-model-first-shared").assertIsSelected()
    }

    @Test fun sourceAndModelAvailabilityStillBlockSelectionAfterRefresh() {
        render()
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-second-shared").performScrollTo().performClick()
        compose.runOnIdle {
            sources.value =
                sources.value.map { source ->
                    if (source.id != "second") {
                        source
                    } else {
                        source.copy(
                            chatSelectable = false,
                            choices = source.choices.map { it.copy(selectable = false) },
                        )
                    }
                }
        }
        compose.onNodeWithTag("chat-model-second-shared").assertIsNotEnabled()
        compose.onNodeWithTag("chat-model-picker-save").assertIsNotEnabled()
        compose.onNodeWithTag("chat-model-pending-unavailable").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, saved) }
        compose.onNodeWithTag("chat-model-picker-cancel").performClick()
    }

    @Test fun sourceAndModelFailureReasonsRemainVisibleInTheirGroup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = sources.value[1]
        sources.value =
            listOf(
                sources.value[0],
                source.copy(
                    chatSelectable = false,
                    unavailable =
                        ConversationModelFailureUi(
                            null,
                            ConversationModelLabel(R.string.model_selection_provider_unavailable),
                        ),
                    choices =
                        source.choices.map {
                            it.copy(
                                selectable = false,
                                generationFailure =
                                    ConversationModelFailureUi(
                                        null,
                                        ConversationModelLabel(R.string.model_selection_failed),
                                    ),
                            )
                        },
                ),
            )
        render()
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose
            .onNodeWithText(context.getString(R.string.model_selection_provider_unavailable))
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithTag("chat-model-second-shared")
            .performScrollTo()
            .assertIsNotEnabled()
            .assertTextContains(context.getString(R.string.model_selection_failed))
    }

    private fun capturePickerIfRequested() {
        if (InstrumentationRegistry.getArguments().getString("providerUiScreenshots") != "true") return
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "provider-ui-redesign")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(directory, "conversation-picker.png").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun render() {
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(
                    providers = sources.value,
                    providerId = "first",
                    model = "shared",
                    enabled = true,
                    onSave = { source, model, reasoning ->
                        saved = Triple(source, model, reasoning)
                        SessionModelSelectionResult.APPLIED
                    },
                )
            }
        }
    }
}

private fun modelSource(
    id: String,
    name: String,
) = ConversationModelSourceUi(
    id = id,
    displayName = name,
    displayLabel = null,
    provisioning = ProviderProvisioningKind.USER_CONFIGURED,
    visibleInModelPicker = true,
    chatSelectable = true,
    setupStep = ConversationModelSetupStep.READY,
    setupLabel = null,
    knownModels = listOf("shared", "hidden"),
    choices =
        listOf("shared", "hidden").map { model ->
            ConversationModelChoiceUi(
                model,
                if (model == "shared") "Same model" else model,
                visible = model != "hidden",
                conversationEligible = true,
                selectable = true,
                reasoningEfforts = emptyList(),
                generationFailure = null,
                reasoningNotice = null,
            )
        },
    unavailable = null,
    catalogFeedback = null,
)
