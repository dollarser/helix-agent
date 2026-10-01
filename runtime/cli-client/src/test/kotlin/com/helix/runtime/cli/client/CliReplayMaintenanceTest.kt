package com.helix.runtime.cli.client

import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CliReplayMaintenanceTest {
    @Test fun wireByteCountsMustBeNumericAndCannotOverflowThePageTotal() {
        val entry = CliReplayEntry("a".repeat(64), "b".repeat(64), null, 12)
        val text = CliReplayMaintenance.encode(CliReplayPage(listOf(entry), null))
        assertThrows(IllegalArgumentException::class.java) {
            CliReplayMaintenance.decode(text.replace("\"bytes\":12", "\"bytes\":\"12\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CliReplayEntry("a".repeat(64), null, null, Long.MAX_VALUE)
        }
    }

    @Test fun ownerRoundTripsInExactRequestBytesAndChangesIdentity() {
        val request = ModelRequest("model", listOf(ModelMessage(ModelRole.USER, "hello")))
        val owner = CliReplayMaintenance.hash("session")
        val bytes = CliModelRequestCodec.encode(request, CliModelProvider.ANTIGRAVITY, replayOwner = owner)
        val decoded = CliModelRequestCodec.decodeEnvelope(bytes)
        assertEquals(request, decoded.request)
        assertEquals(owner, decoded.replayOwner)
        assertNotEquals(
            CliReplayMaintenance.hash(bytes),
            CliReplayMaintenance.hash(
                CliModelRequestCodec.encode(
                    request,
                    CliModelProvider.ANTIGRAVITY,
                    replayOwner = CliReplayMaintenance.EPHEMERAL,
                ),
            ),
        )
        assertEquals(null, CliModelRequestCodec.decodeEnvelope(CliModelRequestCodec.encode(request)).replayOwner)
    }

    @Test fun invalidOwnerAndWrongProviderAreRejected() {
        val request = ModelRequest("model", listOf(ModelMessage(ModelRole.USER, "hello")))
        assertThrows(IllegalArgumentException::class.java) {
            CliModelRequestCodec.encode(request, CliModelProvider.ANTIGRAVITY, replayOwner = "../session")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CliModelRequestCodec.encode(request, CliModelProvider.CODEX, replayOwner = CliReplayMaintenance.EPHEMERAL)
        }
    }

    @Test fun boundedInventoryRoundTripsWithoutPrivateData() {
        val entries =
            (1..32)
                .map {
                    CliReplayEntry(CliReplayMaintenance.hash("$it"), CliReplayMaintenance.hash("body$it"), null, 12)
                }.sortedBy { it.key }
        val page = CliReplayPage(entries, entries.last().key)
        assertEquals(page, CliReplayMaintenance.decode(CliReplayMaintenance.encode(page)))
        assertFalse(CliReplayMaintenance.encode(page).contains("parts"))
        assertThrows(IllegalArgumentException::class.java) { CliReplayPage(entries + entries.first(), null) }
        assertThrows(IllegalArgumentException::class.java) { CliReplayPage(entries.reversed(), null) }
        assertThrows(IllegalArgumentException::class.java) { CliReplayPage(entries, "wrong") }
    }
}
