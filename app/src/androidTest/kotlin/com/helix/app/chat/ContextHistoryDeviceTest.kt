package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.ContextCompaction
import com.helix.app.agent.ContextHistory
import com.helix.core.agent.ContextCapacityException
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ContextHistoryDeviceTest {
    @Test fun staleCheckpointCannotPublishSummary() =
        withStorage { storage ->
            storage.messages.append("original", "s", null, "USER", "TEXT", "原始问题")
            val retained =
                com.helix.core.agent.TurnContextRequest(
                    "fixture",
                    listOf(
                        com.helix.core.model
                            .ModelMessage(com.helix.core.model.ModelRole.USER, "原始问题"),
                    ),
                    emptyList(),
                    512,
                    com.helix.core.model.ReasoningEffort.OFF,
                    historySnapshot =
                        com.helix.core.agent
                            .ContextSourceSnapshot(emptyList(), null, "turn", null, 0),
                )
            val plan =
                com.helix.core.agent
                    .ContextCompactionPlan(0, retained.modelRequest(), retained)
            storage.messages.append(
                "new-checkpoint",
                "s",
                null,
                "ASSISTANT",
                ContextCompaction.KIND,
                """{"coveredThrough":0,"summary":"newer notes"}""",
            )
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    storage.withTransaction {
                        ContextCompaction.persist(storage, "s", "turn", "stale-summary", plan, "old notes", "call")
                    }
                }
            assertEquals("CONTEXT_CHECKPOINT_CHANGED", failure.message)
            assertEquals("newer notes", ContextHistory.checkpoint(storage, "s")?.summary)
            assertEquals(2, storage.messages.listBySession("s").size)
        }

    @Test fun boundReferenceIsSharedByReplayAndSummaryMapping() =
        withStorage { storage ->
            storage.messages.append("quoted", "s", null, "USER", "TEXT", "对照引用")
            val snapshotBefore = ContextHistory.load(storage, "s")
            storage.withTransaction {
                storage.messageReferenceSnapshots.bind(
                    "quoted",
                    listOf(
                        com.helix.core.storage.repository.ConversationReferenceSnapshotInput(
                            "source",
                            "引用来源",
                            com.helix.core.storage.repository.ConversationReferenceKind.RECENT_MESSAGES,
                            emptyList(),
                            "保留这条原始约束",
                        ),
                    ),
                    1000,
                )
            }
            val snapshot = ContextHistory.load(storage, "s")
            assertEquals("对照引用", snapshotBefore.contents["quoted"])
            val mapped =
                com.helix.app.agent.ContextHistoryMapping.rows(
                    storage,
                    snapshot.rows,
                ) { snapshot.contents[it.id] }
            assertTrue(mapped.single().content!!.contains("保留这条原始约束"))
            assertEquals(
                mapped.single().content,
                mapped
                    .single()
                    .messages
                    .single()
                    .text,
            )
        }

    @Test fun expandedReferenceCountsAgainstMaterializationLimit() =
        withStorage { storage ->
            storage.messages.append(
                "quoted",
                "s",
                null,
                "USER",
                "TEXT",
                "a".repeat(ContextHistory.MAX_BODY_BYTES - 100),
            )
            storage.withTransaction {
                storage.messageReferenceSnapshots.bind(
                    "quoted",
                    listOf(
                        com.helix.core.storage.repository.ConversationReferenceSnapshotInput(
                            "source",
                            "引用来源",
                            com.helix.core.storage.repository.ConversationReferenceKind.SUMMARY,
                            emptyList(),
                            "b".repeat(1024),
                        ),
                    ),
                    1000,
                )
            }
            val failure = assertThrows(ContextCapacityException::class.java) { ContextHistory.load(storage, "s") }
            assertEquals("CONTEXT_MATERIALIZATION_LIMIT", failure.code)
            assertEquals(1, storage.messages.listBySession("s").size)
        }

    @Test fun pagedHistoryPreservesPinnedRowsAndSystemMessagesAcrossCheckpoint() =
        withStorage { storage ->
            repeat(600) { index ->
                val role = if (index == 0) "SYSTEM" else "USER"
                storage.messages.append("m-$index", "s", null, role, "TEXT", "text-$index")
            }
            val checkpointJson = """{"coveredThrough":550,"summary":"notes","preservedMessageIds":["m-100"]}"""
            storage.messages.append("cp", "s", null, "ASSISTANT", ContextCompaction.KIND, checkpointJson)
            val snapshot = ContextHistory.load(storage, "s")
            assertEquals(listOf("m-0", "m-100") + (551..599).map { "m-$it" }, snapshot.rows.map { it.id })
            assertEquals(601, storage.messages.listBySession("s").size)
            assertEquals("notes", snapshot.checkpoint?.summary)
            assertEquals(
                snapshot.rows,
                ContextCompaction.retained(storage.messages.listBySession("s"), snapshot.checkpoint),
            )
        }

    @Test fun oversizedRetainedContentFailsBeforeReadingItsBodyAndKeepsHistory() =
        withStorage { storage ->
            storage.messages.append("large", "s", null, "USER", "TEXT", "a".repeat(ContextHistory.MAX_BODY_BYTES + 1))
            val failure = assertThrows(ContextCapacityException::class.java) { ContextHistory.load(storage, "s") }
            assertEquals("CONTEXT_MATERIALIZATION_LIMIT", failure.code)
            assertEquals(1, storage.messages.listBySession("s").size)
            assertTrue(storage.messages.resolve("large").contentRef != null)
        }

    @Test fun coveredLargeBodyDoesNotConsumeTheActiveHistoryBudget() =
        withStorage { storage ->
            storage.messages.append("large", "s", null, "USER", "TEXT", "a".repeat(ContextHistory.MAX_BODY_BYTES + 1))
            storage.messages.append(
                "cp",
                "s",
                null,
                "ASSISTANT",
                ContextCompaction.KIND,
                """{"coveredThrough":0,"summary":"archived"}""",
            )
            storage.messages.append("new", "s", null, "USER", "TEXT", "continue")
            assertEquals(listOf("new"), ContextHistory.load(storage, "s").rows.map { it.id })
        }

    private fun withStorage(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "context-history-${UUID.randomUUID()}.db"
        val directory = File(context.filesDir, name)
        val storage = HelixStorage.open(context, name, directory)
        try {
            storage.sessions.create("s", "History fixture", null, null, 1000)
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }
}
