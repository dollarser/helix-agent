package com.helix.app.provider

import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.entity.MessageEntity
import com.helix.runtime.cli.client.CliReplayEntry
import com.helix.runtime.cli.client.CliReplayMaintenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionReplayRetentionTest {
    @Test fun initialCursorIncludesEmptySessionAndMessageIds() {
        val live = Fixture()
        live.sessions += ""
        assertTrue(live.retention.unreferenced(listOf(entry("agy_live", ""))).isEmpty())
        val branch = Fixture()
        branch.sessions += "branch"
        branch.add("", "branch", "agy_empty_id")
        assertTrue(branch.retention.unreferenced(listOf(entry("agy_empty_id", "removed"))).isEmpty())
    }

    @Test fun liveSessionProtectsResponseBeforeItsHistoryPublication() {
        val fixture = Fixture()
        fixture.sessions += "live"
        assertEquals(emptyList<CliReplayEntry>(), fixture.retention.unreferenced(listOf(entry("agy_live", "live"))))
    }

    @Test fun deletedParentStillReferencedByBranchOrRetainedRevisionIsProtected() {
        val fixture = Fixture()
        fixture.sessions += "branch"
        fixture.add("m-1", "branch", "agy_parent", superseded = "later-edit")
        val shared = entry("agy_parent", "deleted-parent")
        assertEquals(emptyList<CliReplayEntry>(), fixture.retention.unreferenced(listOf(shared)))
        fixture.rows.clear()
        assertEquals(listOf(shared), fixture.retention.unreferenced(listOf(shared)))
    }

    @Test fun unknownLegacyOwnershipIsConservativeWhileAnySessionExists() {
        val fixture = Fixture()
        val legacy = entry("agy_legacy", null)
        fixture.sessions += "archived-session"
        assertEquals(emptyList<CliReplayEntry>(), fixture.retention.unreferenced(listOf(legacy)))
        fixture.sessions.clear()
        assertEquals(listOf(legacy), fixture.retention.unreferenced(listOf(legacy)))
    }

    @Test fun ephemeralProbeCanBeCollectedButCorruptMetadataCannot() {
        val fixture = Fixture()
        fixture.sessions += "unrelated"
        val probe = entry("agy_probe", null).copy(owner = CliReplayMaintenance.EPHEMERAL)
        val corrupt = entry("agy_corrupt", "removed").copy(fingerprint = null)
        assertEquals(listOf(probe), fixture.retention.unreferenced(listOf(probe, corrupt)))
    }

    @Test fun malformedRetainedHistoryAbortsTheDestructivePhase() {
        val fixture = Fixture()
        fixture.add("m-1", "branch", "agy_other", body = "broken")
        assertThrows(IllegalArgumentException::class.java) {
            fixture.retention.unreferenced(listOf(entry("agy_target", "removed")))
        }
    }

    @Test fun missingOrOversizedBodyAbortsBeforeReadingUnboundedData() {
        val fixture = Fixture()
        fixture.add("m-1", "branch", "agy_other")
        val row = fixture.rows.getValue("m-1")
        val hash = "a".repeat(64)
        fixture.rows["m-1"] = row.first.copy(
            contentRef = ContentRef(ContentRef.expectedPath(hash), 9L * 1024 * 1024, hash).toStorageString(),
        ) to row.second
        assertThrows(IllegalStateException::class.java) {
            fixture.retention.unreferenced(listOf(entry("agy_target", "removed")))
        }
        assertEquals(0, fixture.bodyReads)
    }

    @Test fun referenceBeyondFirstPageIsNotLost() {
        val fixture = Fixture()
        repeat(129) { fixture.add("m-${it.toString().padStart(3, '0')}", "branch", "agy_$it") }
        assertTrue(fixture.retention.unreferenced(listOf(entry("agy_128", "removed"))).isEmpty())
        assertEquals(129, fixture.bodyReads)
    }

    @Test fun cancellationStopsTheLocalScanWithoutReturningCandidates() {
        val fixture = Fixture()
        try {
            Thread.currentThread().interrupt()
            assertThrows(IllegalStateException::class.java) {
                fixture.retention.unreferenced(listOf(entry("agy_cancel", "removed")))
            }
        } finally {
            Thread.interrupted()
        }
    }

    private fun entry(
        callId: String,
        owner: String?,
    ) = CliReplayEntry(
        CliReplayMaintenance.hash(callId),
        "a".repeat(64),
        owner?.let(CliReplayMaintenance::hash),
        100,
    )

    private class Fixture {
        val sessions = mutableListOf<String>()
        val rows = linkedMapOf<String, Pair<MessageEntity, String>>()
        var bodyReads = 0
        private var inTransaction = false
        val retention =
            SubscriptionReplayRetention(
                transaction = { block ->
                    check(!inTransaction)
                    inTransaction = true
                    try {
                        block()
                    } finally {
                        inTransaction = false
                    }
                },
                sessionPage = { after, limit ->
                    check(inTransaction)
                    sessions.filter { after == null || it > after }.sorted().take(limit)
                },
                messagePage = { after, limit ->
                    check(inTransaction)
                    rows.values
                        .map { it.first }
                        .filter { after == null || it.id > after }
                        .sortedBy { it.id }
                        .take(limit)
                },
                readBody = { row, maxBytes ->
                    check(inTransaction)
                    val body = rows.getValue(row.id).second
                    check(body.toByteArray().size <= maxBytes)
                    bodyReads++
                    body
                },
            )

        fun add(
            id: String,
            sessionId: String,
            callId: String,
            superseded: String? = null,
            body: String? = null,
        ) {
            val text = body ?: """[{"id":"$callId","name":"read","arguments":"{}"}]"""
            val hash = CliReplayMaintenance.hash(text)
            val ref = ContentRef(ContentRef.expectedPath(hash), text.toByteArray().size.toLong(), hash)
            rows[id] =
                MessageEntity(
                    id,
                    sessionId,
                    null,
                    "ASSISTANT",
                    "TOOL_CALLS",
                    ref.toStorageString(),
                    rows.size.toLong(),
                    superseded,
                ) to
                text
        }
    }
}
