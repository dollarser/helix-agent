package com.helix.runtime.cli.app

import com.helix.core.model.ModelEvent
import com.helix.runtime.cli.client.CliModelEventCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpoolingModelEventsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun resultExceedsFormerQuotaWithoutRetainingAllEventsInMemory() {
        val spool = SpoolingModelEvents(temp.root)
        val text = ModelEvent.TextDelta("x".repeat(30_000))
        repeat(100) { spool.append(listOf(text)) }
        spool.append(listOf(ModelEvent.Completed("stop")))
        MappedModelEvents(spool) { it }.use { mapped ->
            val bytes = CliModelEventCodec.encode(mapped)
            assertTrue(bytes.size > 2 * 1024 * 1024)
            val decoded = CliModelEventCodec.decode(bytes)
            assertEquals(101, decoded.size)
            assertEquals(text, decoded.first())
            assertEquals(ModelEvent.Completed("stop"), decoded.last())
            assertEquals(text, spool[50])
        }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }
}
