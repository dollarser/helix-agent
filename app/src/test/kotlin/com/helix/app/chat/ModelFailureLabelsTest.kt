package com.helix.app.chat

import com.helix.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFailureLabelsTest {
    private val rendered = mutableListOf<Int>()
    private val strings: (Int, Array<out Any>) -> String = { id, args ->
        rendered += id
        "$id[${args.joinToString()}]"
    }

    @Test fun exhaustedModelFailureHasStageAndFiveRetriesWithoutRawPayload() {
        val result =
            ModelFailureLabels.format(
                "PROTOCOL",
                "base",
                """{"reason":"MISSING_TERMINAL","attempt":6,"httpStatus":200,"message":"PRIVATE"}""",
                strings,
            )
        assertTrue(rendered.contains(R.string.model_request_failed))
        assertTrue(rendered.contains(R.string.model_failure_retry_count))
        assertTrue(result.contains("5"))
        assertFalse(result.contains("PRIVATE"))
    }

    @Test fun partialResponseIsDistinguishedAndNonModelErrorKeepsItsOwnLabel() {
        ModelFailureLabels.format(
            "TRANSPORT",
            "base",
            """{"recoveryDecision":"PARTIAL_OR_TERMINAL_STREAM"}""",
            strings,
        )
        assertTrue(rendered.contains(R.string.model_response_interrupted))
        assertEquals(
            "directory failed",
            ModelFailureLabels.format(
                "DIRECTORY_UNAVAILABLE",
                "directory failed",
                null,
                strings,
            ),
        )
    }

    @Test fun toolArgumentOverflowIsAModelResponseErrorNotAnExecutedToolFailure() {
        ModelFailureLabels.format(
            com.helix.core.agent.ModelStreamState.TOOL_ARGUMENTS_OVERFLOW,
            "arguments overflow",
            null,
            strings,
        )
        assertTrue(rendered.contains(R.string.model_request_failed))
    }
}
