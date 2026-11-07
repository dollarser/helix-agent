package com.helix.app.ui

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.core.model.input.InputSkillActivation
import com.helix.feature.conversation.input.ChatSubmission
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SkillDraftHandoffDeviceTest {
    @Test fun oncePreservesDraftDeduplicatesAndRejectsAnotherSession() =
        runBlocking {
            val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            val service = c.chatService
            val previous = service.screen.value.openSessionId
            val id = "skill-draft-${UUID.randomUUID()}"
            c.storage.sessions.create(id, "Skill draft validation", null, null, 0)
            try {
                service.openSession(id)
                val row = c.skillRepository.list(id).single { it.key.name == "helix-docs" }
                val original =
                    ChatSubmission(
                        id,
                        100,
                        UUID.randomUUID().toString(),
                        "保留原输入",
                        listOf("synthetic-attachment-reference"),
                    )
                assertTrue(service.saveComposerDraft(original))
                val prepared = c.skillRepository.prepareUserActivation(row.key, id, "参数一")
                val activation =
                    InputSkillActivation(
                        prepared.key.source.name,
                        prepared.key.name,
                        prepared.key.snapshotHash,
                        prepared.arguments,
                    )
                assertTrue(service.stageSkillActivation(id, activation, "默认提示").await())
                val first = requireNotNull(service.loadComposerDraft(id))
                assertEquals(original.text, first.text)
                assertEquals(original.attachmentIds, first.attachmentIds)
                assertEquals(1, first.skillActivations.size)
                assertEquals(row.key.snapshotHash, first.skillActivations.single().snapshotHash)
                assertTrue(first.revision > original.revision)
                assertTrue(service.stageSkillActivation(id, activation.copy(arguments = "参数二"), "默认提示").await())
                val next = requireNotNull(service.loadComposerDraft(id))
                assertEquals(1, next.skillActivations.size)
                assertEquals("参数二", next.skillActivations.single().arguments)
                assertFalse(service.stageSkillActivation("wrong-$id", activation, "ignored").await())
                assertEquals(next, service.loadComposerDraft(id))
                assertEquals(
                    row.enabled,
                    c.skillRepository
                        .list(id)
                        .single { it.key == row.key }
                        .enabled,
                )
                assertTrue(
                    c.storage.turns
                        .listBySession(id)
                        .isEmpty(),
                )
            } finally {
                if (previous == null) service.closeSession() else service.openSession(previous)
                c.storage.deleteSessionPermanently(id)
            }
        }
}
