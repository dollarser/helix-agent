package com.helix.app.eval

import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.UUID

/** Explicit opt-in; a generated text file is the only user content sent to the configured model. */
class AttachmentRealModelDeviceTest {
    @Suppress("LongMethod", "NestedBlockDepth") // One bounded import/send/model/read/cleanup journey.
    @Test
    fun configuredModelReadsTheImportedAttachmentAndReturnsItsUniqueContent() =
        runBlocking {
            require(InstrumentationRegistry.getArguments().getString("helixRealModel") == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            ActivityScenario.launch(MainActivity::class.java).use {
                val c = app.appContainer
                check(
                    c.storage.turns
                        .listActive()
                        .isEmpty(),
                ) { "OTHER_ACTIVE_TURN" }
                val provider =
                    c.providerService.rows.value.firstOrNull {
                        it.chatSelectable &&
                            it.conversationModels.isNotEmpty()
                    }
                check(provider != null) { "CONFIGURED_REAL_MODEL_UNAVAILABLE" }
                val previous = c.chatService.screen.value.openSessionId
                val session =
                    c.chatService.createSession(
                        "Synthetic attachment reading",
                        provider.id,
                        provider.conversationModels.first(),
                    )
                val marker = "附件回归标记：${UUID.randomUUID()}"
                val source = app.cacheDir.resolve("device-share/attachment-${UUID.randomUUID()}.txt")
                source.parentFile!!.mkdirs()
                source.writeText(marker)
                try {
                    configureOwnedModelSession(c.storage, c.chatService, session, AgentMode.PLAN)
                    val config = requireNotNull(c.storage.sessionPermissionConfigs.forSession(session))
                    c.storage.sessionPermissionConfigs.setForSession(
                        session,
                        config.copy(
                            mode = SessionPermissionMode.CUSTOM,
                            rules = config.rules + (OperationEffect.FILE_READ_EXTERNAL to OperationRule.ALLOW),
                        ),
                        System.currentTimeMillis(),
                    )
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", source)
                    c.chatService.stageAttachment(uri.toString())
                    withTimeout(30_000) { while (c.chatService.screen.value.pendingAttachments.size != 1) delay(50) }
                    c.chatService.sendTestMessage("请用读取工具读取所附文本，只回复文件中的完整标记。不要修改任何文件或设置。")
                    withTimeout(30_000) { while (c.chatService.screen.value.pendingDisclosure == null) delay(50) }
                    c.chatService.confirmSend()
                    withTimeout(180_000) {
                        while (c.storage.turns
                                .listBySession(
                                    session,
                                ).lastOrNull()
                                ?.let { TurnState.valueOf(it.state).isTerminal } !=
                            true
                        ) {
                            delay(100)
                        }
                    }
                    val turn =
                        c.storage.turns
                            .listBySession(session)
                            .last()
                    val reads =
                        c.storage.toolCalls.listByTurn(turn.id).filter {
                            it.name in setOf("read", "files_read") &&
                                it.state == "COMPLETED"
                        }
                    val answer =
                        c.storage.messages
                            .listBySession(session)
                            .filter {
                                it.role == "ASSISTANT" &&
                                    it.kind == "TEXT"
                            }.mapNotNull { c.storage.messages.readContent(it) }
                            .joinToString("\n")
                    check(turn.state == "COMPLETED" && reads.isNotEmpty() && marker in answer) {
                        "MODEL_DID_NOT_VERIFY_ATTACHMENT_READ: ${turn.state}"
                    }
                    check(source.readText() == marker)
                } finally {
                    finishOwnedCase(c.storage, c.chatService, session, {}, {}, { stopped ->
                        if (stopped) {
                            if (previous == null) c.chatService.closeSession() else c.chatService.openSession(previous)
                            c.storage.deleteSessionPermanently(session)
                            source.delete()
                        }
                    })
                }
            }
        }
}
