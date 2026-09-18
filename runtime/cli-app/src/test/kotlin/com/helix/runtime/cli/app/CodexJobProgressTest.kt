package com.helix.runtime.cli.app

import com.helix.core.model.ModelEvent
import com.helix.runtime.cli.client.CliModelProgressCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CodexJobProgressTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun slowReaderGetsCompletePrefixAcrossBoundedBatchesAndCanRetry() {
        val spool = CodexJobProgress(File(temp.root, "preview"))
        val event = ModelEvent.TextDelta("x".repeat(30_000))
        repeat(100) { spool.append(listOf(event)) }
        var offset = 0
        while (offset < 100) {
            val batch = spool.read(offset)
            assertTrue(batch.isNotEmpty())
            assertTrue(CliModelProgressCodec.encode(batch).size <= CliModelProgressCodec.MAX_BATCH_BYTES)
            assertEquals(batch, spool.read(offset))
            offset += batch.size
        }
        assertEquals(100, offset)
        assertTrue(spool.read(offset).isEmpty())
        spool.clear()
        assertTrue(spool.read(0).isEmpty())
    }

    @Test fun oversizedEventStopsPreviewWithoutSkippingToLaterEvents() {
        val spool = CodexJobProgress(File(temp.root, "preview"))
        val first = ModelEvent.TextDelta("first")
        spool.append(listOf(first, ModelEvent.TextDelta("界".repeat(65_536))))
        spool.append(listOf(ModelEvent.TextDelta("later")))
        assertEquals(listOf(first), spool.read(0))
        assertTrue(spool.read(1).isEmpty())
    }
}
