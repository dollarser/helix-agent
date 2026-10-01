package com.helix.runtime.cli.app

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import com.helix.runtime.cli.client.CliReplayMaintenance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AntigravityReplayMaintenanceTest {
    @Test fun inventoryIsBoundedAndDoesNotEvictOldConversations() =
        directory { root ->
            val store = AntigravityReplayStore(root, CliReplayMaintenance.hash("session"))
            val maintenance = AntigravityReplayMaintenance(root)
            val messages = (0..128).map { message("agy_$it") }
            messages.forEach { store.save("model", "account", it, parts) }
            var after: String? = null
            val keys = mutableSetOf<String>()
            do {
                val page = maintenance.page(after)
                assertTrue(page.entries.size <= 32)
                assertTrue(page.entries.all { keys.add(it.key) })
                assertTrue(page.entries.all { it.owner == CliReplayMaintenance.hash("session") })
                assertFalse(CliReplayMaintenance.encode(page).contains("thoughtSignature"))
                after = page.nextAfter
            } while (after != null)
            assertEquals(129, keys.size)
            messages.forEach { assertNotNull(AntigravityReplayStore(root).read("model", "account", it)) }
        }

    @Test fun exactCandidateDeletionIsIdempotentAndDoesNotDeleteLaterRecords() =
        directory { root ->
            val store = AntigravityReplayStore(root, CliReplayMaintenance.EPHEMERAL)
            store.save("model", "account", message("agy_first"), parts)
            val maintenance = AntigravityReplayMaintenance(root)
            val candidates = maintenance.page(null).entries
            store.save("model", "account", message("agy_later"), parts)
            val result = maintenance.prune(candidates)
            assertEquals(1, result.deleted)
            assertEquals(candidates.single().bytes, result.deletedBytes)
            assertEquals(0, maintenance.prune(candidates).deleted)
            assertNotNull(store.read("model", "account", message("agy_later")))
        }

    @Test fun changedOrCorruptCandidateIsRetained() =
        directory { root ->
            val store = AntigravityReplayStore(root)
            store.save("model", "account", message("agy_changed"), parts)
            val maintenance = AntigravityReplayMaintenance(root)
            val candidates = maintenance.page(null).entries
            val file = File(root, "${candidates.single().key}.json")
            file.appendText(" ")
            assertEquals(1, maintenance.prune(candidates).retained)
            file.writeText("broken")
            assertEquals(1, maintenance.prune(candidates).retained)
            assertEquals(
                null,
                maintenance
                    .page(null)
                    .entries
                    .single()
                    .fingerprint,
            )
            assertTrue(file.exists())
        }

    @Test fun sameCallIdentityCannotOverwriteForeignEvidence() =
        directory { root ->
            val store = AntigravityReplayStore(root)
            val call = message("agy_collision")
            store.save("model", "account", call, parts)
            assertThrows(IllegalArgumentException::class.java) { store.save("other", "account", call, parts) }
            assertThrows(IllegalArgumentException::class.java) { store.save("model", "other", call, parts) }
            store.save("model", "account", call, parts)
            assertEquals(parts, store.read("model", "account", call))
        }

    @Test fun branchMayReadOriginalOwnerWithoutChangingOwnership() =
        directory { root ->
            val first = AntigravityReplayStore(root, CliReplayMaintenance.hash("parent"))
            first.save("model", "account", message("agy_fork"), parts)
            val branch = AntigravityReplayStore(root, CliReplayMaintenance.hash("branch"))
            assertEquals(parts, branch.read("model", "account", message("agy_fork")))
            branch.save("model", "account", message("agy_fork"), parts)
            assertEquals(
                CliReplayMaintenance.hash("parent"),
                AntigravityReplayMaintenance(root)
                    .page(null)
                    .entries
                    .single()
                    .owner,
            )
        }

    @Test fun linksAndOversizedFilesCannotBecomeDeletionCandidates() =
        directory { root ->
            val outside = Files.createTempFile("replay-outside", ".txt")
            try {
                Files.writeString(outside, "preserved")
                val linked = File(root, "${CliReplayMaintenance.hash("linked")}.json")
                Files.createSymbolicLink(linked.toPath(), outside)
                val oversized = File(root, "${CliReplayMaintenance.hash("large")}.json")
                java.io
                    .RandomAccessFile(
                        oversized,
                        "rw",
                    ).use { it.setLength(AntigravityReplayStore.MAX_BYTES.toLong() + 1) }
                val page = AntigravityReplayMaintenance(root).page(null)
                assertEquals(2, page.entries.size)
                assertTrue(page.entries.all { it.fingerprint == null })
                assertThrows(
                    IllegalArgumentException::class.java,
                ) { AntigravityReplayMaintenance(root).prune(page.entries) }
                assertEquals("preserved", Files.readString(outside))
                Files.delete(linked.toPath())
            } finally {
                Files.deleteIfExists(outside)
            }
        }

    @Test fun invalidCursorAndDuplicateCandidatesFailBeforeDeletion() =
        directory { root ->
            val store = AntigravityReplayStore(root)
            store.save("model", "account", message("agy_dup"), parts)
            val maintenance = AntigravityReplayMaintenance(root)
            assertThrows(IllegalArgumentException::class.java) { maintenance.page("../escape") }
            val entry = maintenance.page(null).entries.single()
            assertThrows(IllegalArgumentException::class.java) { maintenance.prune(listOf(entry, entry)) }
            assertNotNull(store.read("model", "account", message("agy_dup")))
        }

    private fun directory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("replay-maintenance").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun message(id: String) =
        ModelMessage(
            ModelRole.ASSISTANT,
            "",
            toolCalls = listOf(AssistantToolCall(ToolCallId(id), ToolName("read"), "{}")),
        )

    private val parts =
        Json.parseToJsonElement(
            """[{"thoughtSignature":"opaque","functionCall":{"name":"${AntigravityRequest.wireName(
                "read",
            )}","args":{}}}]""",
        ) as JsonArray
}
