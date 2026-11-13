package com.helix.feature.conversation.presentation

import com.helix.core.model.ProviderProvisioningKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationModelGroupTest {
    @Test fun groupsKeepSourceIdentityAndOrderWhenNamesAndModelIdsMatch() {
        val first = source("first", "Same service")
        val second = source("second", "Same service")

        val groups = conversationModelGroups(listOf(first, second), "")

        assertEquals(listOf("first", "second"), groups.map { it.source.id })
        assertEquals(listOf(listOf("shared", "other"), listOf("shared", "other")), groups.map { it.models })
        assertSame(first, groups[0].source)
        assertSame(second, groups[1].source)
    }

    @Test fun searchMatchesSourceModelIdAndDisplayNameWithoutMixingGroups() {
        val sources = listOf(source("first", "Service A"), source("second", "Service B"))

        assertEquals(listOf("second"), conversationModelGroups(sources, "service b").map { it.source.id })
        assertEquals(listOf("shared", "other"), conversationModelGroups(sources, "SERVICE B").single().models)
        assertEquals(
            listOf(listOf("shared"), listOf("shared")),
            conversationModelGroups(sources, "SHAR").map { it.models },
        )
        assertEquals(
            listOf(listOf("other"), listOf("other")),
            conversationModelGroups(sources, "Friendly").map { it.models },
        )
        assertTrue(conversationModelGroups(sources, "missing").isEmpty())
    }

    @Test fun onlyCheckedModelsAppearAndEmptySourcesDoNotLeaveHeadings() {
        val first = source("first", "Service A")
        val empty = source("empty", "Empty service").copy(choices = first.choices.map { it.copy(visible = false) })
        val sources = listOf(first, empty)

        assertEquals(listOf("first"), conversationModelGroups(sources, "").map { it.source.id })
        assertTrue(conversationModelGroups(sources, "hidden").isEmpty())
        assertTrue(conversationModelGroups(sources, "Empty service").isEmpty())
    }

    @Test fun groupingDoesNotPromoteUnavailableModelsToSelectable() {
        val source = source("first", "Service A")
        val unavailable =
            source.copy(
                chatSelectable = false,
                choices = source.choices.map { it.copy(selectable = false) },
            )

        val group = conversationModelGroups(listOf(unavailable), "").single()

        assertSame(unavailable, group.source)
        assertEquals(listOf("shared", "other"), group.models)
        assertTrue(group.models.none(group.source::modelSelectable))
    }

    private fun source(
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
        knownModels = listOf("shared", "other", "hidden"),
        choices =
            listOf("shared", "other", "hidden").map { model ->
                ConversationModelChoiceUi(
                    model,
                    if (model == "other") "Friendly model" else model,
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
}
