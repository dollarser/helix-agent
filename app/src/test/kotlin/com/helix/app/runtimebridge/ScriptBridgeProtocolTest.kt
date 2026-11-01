package com.helix.app.runtimebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ScriptBridgeProtocolTest {
    @Test fun transportCredentialsCannotSelectIdentityOrBecomeToolArguments() {
        val text = """{"proof":"ephemeral-proof","id":"one","tool":"time.now","args":{}}"""
        assertEquals("time.now", ScriptBridgeProtocol.decode(text, "ephemeral-proof").tool)
        assertThrows(IllegalArgumentException::class.java) { ScriptBridgeProtocol.decode(text, "other-proof") }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptBridgeProtocol.decode(
                text.replace("\"args\":{}", "\"sessionId\":\"other\",\"args\":{}"),
                "ephemeral-proof",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptBridgeProtocol.decode(text.replace("{}", "{\"value\":\"ephemeral-proof\"}"), "ephemeral-proof")
        }
        assertFalse(ScriptBridgeProtocol.failure("BRIDGE_AUTH_REQUIRED").contains("ephemeral-proof"))
    }

    @Test fun malformedOrOversizedRequestsFailBeforeAnyDispatch() {
        assertThrows(IllegalArgumentException::class.java) {
            ScriptBridgeProtocol.decode("x".repeat(ScriptBridgeProtocol.MAX_BYTES + 1), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptBridgeProtocol.decode("""{"id":"../other","tool":"time.now","args":{}}""", null)
        }
    }
}
