package com.helix.runtime.cli.client

import com.helix.core.model.ModelEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CliModelProgressCodecTest {
    @Test fun previewAllowsEmptyAndDeltasButCannotBeDecodedAsDurableResult() {
        assertEquals(emptyList<ModelEvent>(), CliModelProgressCodec.decode(CliModelProgressCodec.encode(emptyList())))
        val events = listOf(ModelEvent.TextDelta("first"), ModelEvent.ReasoningDelta("progress"))
        val bytes = CliModelProgressCodec.encode(events)
        assertEquals(events, CliModelProgressCodec.decode(bytes))
        assertThrows(IllegalArgumentException::class.java) { CliModelEventCodec.decode(bytes) }
    }

    @Test fun previewRejectsTerminalsAndOversizedSequences() {
        assertThrows(IllegalArgumentException::class.java) {
            CliModelProgressCodec.encode(listOf(ModelEvent.Completed("stop")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CliModelProgressCodec.decode(CliModelEventCodec.encode(listOf(ModelEvent.Completed("stop"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CliModelProgressCodec.encode(List(2049) { ModelEvent.TextDelta("x") })
        }
    }
}
