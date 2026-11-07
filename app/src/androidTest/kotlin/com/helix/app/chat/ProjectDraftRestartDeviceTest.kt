package com.helix.app.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.workspace.FileScopePath
import com.helix.feature.conversation.input.ChatSubmission
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Host invokes seed, stops only Helix, then invokes recover. Never resumes or sends input. */
class ProjectDraftRestartDeviceTest {
    @Suppress("LongMethod") // Explicit seed/recover handshake with cleanup of only its owned fixture.
    @Test
    fun cachedProjectConversationSurvivesHostProcessRestart() =
        runBlocking<Unit> {
            val phase = InstrumentationRegistry.getArguments().getString("projectDraftPhase")
            org.junit.Assume.assumeTrue("Requires explicit process restart orchestration", phase != null)
            require(phase in setOf("seed", "recover"))
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            app.runtimeRecoveryReady.await()
            val marker = File(app.filesDir, "project-draft-restart-fixture.json")
            if (phase == "seed") {
                check(!marker.exists()) { "Inspect unfinished owned restart fixture before rerunning" }
                check(
                    c.storage.turns
                        .listActive()
                        .isEmpty(),
                )
                val project = requireNotNull(c.projects).save(null, "Restart-${UUID.randomUUID()}", "", "", null)
                val session =
                    requireNotNull(c.chatService.newProjectDraft(project, c.chatService.screen.value.openSessionId))
                val file = File(app.filesDir, "workspaces/app/work/project-restart-${UUID.randomUUID()}.txt")
                file.parentFile?.mkdirs()
                file.writeText("owned restart attachment")
                val uri =
                    androidx.core.content.FileProvider
                        .getUriForFile(app, "${app.packageName}.fileprovider", file)
                c.chatService.stageAttachment(uri.toString(), session)
                kotlinx.coroutines.withTimeout(10_000) {
                    while (c.chatService.screen.value.pendingAttachments
                            .isEmpty()
                    ) {
                        kotlinx.coroutines.delay(50)
                    }
                }
                assertEquals(session, c.chatService.materializeDraftSession(session))
                val attachments = c.chatService.currentStagedAttachmentIds(session)
                assertEquals(1, attachments.size)
                val request =
                    ChatSubmission(session, 0, UUID.randomUUID().toString(), "Unsent project question", attachments)
                assertTrue(c.chatService.saveComposerDraft(request))
                assertEquals(
                    project,
                    c.storage.projects
                        .forSession(session)
                        ?.id,
                )
                marker.writeText(
                    JSONObject()
                        .put("project", project)
                        .put("session", session)
                        .put("file", file.name)
                        .put("attachment", attachments.single())
                        .put(
                            "request",
                            request.clientRequestId,
                        ).put(
                            "directory",
                            c.storage.sessions
                                .resolve(session)
                                .directoryRef,
                        ).toString(),
                )
            } else {
                val saved = JSONObject(marker.readText())
                val session = saved.getString("session")
                val project = saved.getString("project")
                try {
                    assertEquals(
                        project,
                        c.storage.projects
                            .forSession(session)
                            ?.id,
                    )
                    val directory = saved.getString("directory")
                    assertEquals(
                        directory,
                        c.storage.sessions
                            .resolve(session)
                            .directoryRef,
                    )
                    assertEquals(
                        requireNotNull(c.storage.projects.find(project)).workspaceId,
                        FileScopePath.fromModelReference(directory).scopeId,
                    )
                    val draft = requireNotNull(c.chatService.loadComposerDraft(session))
                    assertEquals("Unsent project question", draft.text)
                    assertEquals(saved.getString("request"), draft.clientRequestId)
                    assertEquals(listOf(saved.getString("attachment")), draft.attachmentIds)
                    c.chatService.openSession(session)
                    assertTrue(c.chatService.restoreDraftAttachments(session, draft.attachmentIds).isEmpty())
                    assertEquals(draft.attachmentIds, c.chatService.currentStagedAttachmentIds(session))
                    assertTrue(
                        c.storage.turns
                            .listBySession(session)
                            .isEmpty(),
                    )
                    assertTrue(
                        c.storage.messages
                            .listBySession(session)
                            .isEmpty(),
                    )
                } finally {
                    c.chatService.closeSession()
                    c.storage.deleteSessionPermanently(session)
                    c.storage.projects
                        .find(project)
                        ?.let { requireNotNull(c.projects).delete(it) }
                    File(app.filesDir, "workspaces/app/work/${saved.getString("file")}").delete()
                    marker.delete()
                }
            }
        }
}
