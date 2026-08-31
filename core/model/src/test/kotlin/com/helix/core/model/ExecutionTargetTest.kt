package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionTargetTest {
    private val descriptor =
        ExecutionTargetDescriptor(
            id = ExecutionTargetId("target-1"),
            type = ExecutionTargetType.LOCAL_PROOT,
            protocolVersion = 2,
            displayName = "PRoot Alpine runtime",
            attributes = linkedMapOf("zeta" to "z", "alpha" to "a1", "rootfs" to "sha-256:abcd"),
        )

    @Test
    fun rejectsInvalidProtocolVersionAndDisplayName() {
        assertThrows<IllegalArgumentException> {
            ExecutionTargetDescriptor(ExecutionTargetId("t"), ExecutionTargetType.LOCAL_ANDROID, 0, "x")
        }
        assertThrows<IllegalArgumentException> {
            ExecutionTargetDescriptor(ExecutionTargetId("t"), ExecutionTargetType.LOCAL_ANDROID, 1, "  ")
        }
        assertThrows<IllegalArgumentException> {
            ExecutionTargetDescriptor(ExecutionTargetId("t"), ExecutionTargetType.LOCAL_ANDROID, 1, "d".repeat(129))
        }
    }

    @Test
    fun rejectsInvalidAttributes() {
        fun withAttributes(attributes: Map<String, String>) {
            assertThrows<IllegalArgumentException> {
                ExecutionTargetDescriptor(ExecutionTargetId("t"), ExecutionTargetType.LOCAL_ANDROID, 1, "x", attributes)
            }
        }

        withAttributes(mapOf("a b" to "v"))
        withAttributes(mapOf("a".repeat(65) to "v"))
        withAttributes(mapOf("k" to "v".repeat(513)))
        withAttributes(mapOf("k" to "a" + '\n' + "b"))
        val tooMany = (1..17).associate { "k$it" to "v" }
        withAttributes(tooMany)
    }

    @Test
    fun storageEncodingSortsAttributesAndRoundTrips() {
        val encoded = descriptor.toStorageString()
        val expected =
            """{"id":"target-1","type":"LOCAL_PROOT","protocolVersion":2,"displayName":"PRoot Alpine """ +
                """runtime","attributes":{"alpha":"a1","rootfs":"sha-256:abcd","zeta":"z"}}"""
        assertEquals(expected, encoded)
        assertEquals(descriptor, ExecutionTargetDescriptor.parse(encoded))
    }

    @Test
    fun parseRejectsMalformedInput() {
        val valid = descriptor.toStorageString()
        assertThrows<IllegalArgumentException> { ExecutionTargetDescriptor.parse("") }
        assertThrows<IllegalArgumentException> {
            ExecutionTargetDescriptor.parse(valid.replace("\"LOCAL_PROOT\"", "\"LOCAL_NOPE\""))
        }
        assertThrows<IllegalArgumentException> {
            ExecutionTargetDescriptor.parse(valid.replace("\"protocolVersion\":2", "\"protocolVersion\":2.5"))
        }
        assertThrows<IllegalArgumentException> {
            ExecutionTargetDescriptor.parse(valid.dropLast(1) + ",\"extra\":1}")
        }
    }
}
