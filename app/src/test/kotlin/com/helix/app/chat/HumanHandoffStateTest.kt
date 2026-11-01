package com.helix.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HumanHandoffStateTest {
    @Test fun malformedOrUnknownDecisionsNeverBecomeReturnAuthority() {
        assertNull(parseHumanHandoffState("broken"))
        assertNull(parseHumanHandoffState("[]"))
        assertNull(parseHumanHandoffState("{}"))
        assertNull(parseHumanHandoffState("{\"state\":\"unknown\"}"))
        assertEquals(HumanHandoffState.SUBMITTED, parseHumanHandoffState("{\"state\":\"SUBMITTED\"}"))
    }
}
