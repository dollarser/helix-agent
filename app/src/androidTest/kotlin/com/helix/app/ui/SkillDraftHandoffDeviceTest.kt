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
    @Test
    @Suppress("LongMethod") // One session lifecycle verifies durable handoff and cleanup together.
    fun oncePreservesDraftDeduplicatesAndRejectsAnotherSession() =
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
                        prepared.key.ownerId,
                    )
                assertTrue(service.stageSkillActivation(id, activation).await())
                val first = requireNotNull(service.loadComposerDraft(id))
                assertEquals(original.text, first.text)
                assertEquals(original.attachmentIds, first.attachmentIds)
                assertEquals(1, first.skillActivations.size)
                assertEquals(row.key.snapshotHash, first.skillActivations.single().snapshotHash)
                assertEquals(row.key.ownerId, first.skillActivations.single().ownerId)
                assertTrue(first.revision > original.revision)
                assertTrue(service.stageSkillActivation(id, activation.copy(arguments = "参数二")).await())
                val next = requireNotNull(service.loadComposerDraft(id))
                assertEquals(1, next.skillActivations.size)
                assertEquals("参数二", next.skillActivations.single().arguments)
                assertFalse(service.stageSkillActivation("wrong-$id", activation).await())
                assertEquals(next, service.loadComposerDraft(id))
                // A cached older snapshot is still a distinct chip, not a name-only replacement target.
                val older = activation.copy(snapshotHash = "b".repeat(64))
                val blank =
                    next.copy(
                        text = "",
                        revision = next.revision + 1,
                        clientRequestId = UUID.randomUUID().toString(),
                        skillActivations = listOf(older),
                    )
                assertTrue(service.saveComposerDraft(blank))
                assertTrue(service.stageSkillActivation(id, activation).await())
                val stagedBlank = requireNotNull(service.loadComposerDraft(id))
                assertEquals("", stagedBlank.text)
                assertEquals(original.attachmentIds, stagedBlank.attachmentIds)
                assertEquals(listOf(older, activation), stagedBlank.skillActivations)
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
