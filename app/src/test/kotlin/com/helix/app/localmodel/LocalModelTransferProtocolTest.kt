package com.helix.app.localmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelTransferProtocolTest {
    @Test fun fullResponseRestartsRatherThanAppendingToAPartialFile() {
        assertFalse(LocalModelTransferProtocol.resumes(200, null, 40, 100))
    }

    @Test fun partialResponseRequiresTheExactOffsetEndAndTotal() {
        assertTrue(LocalModelTransferProtocol.resumes(206, "bytes 40-99/100", 40, 100))
        for (header in listOf(null, "bytes 0-99/100", "bytes 40-98/100", "bytes 40-99/101")) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalModelTransferProtocol.resumes(206, header, 40, 100)
            }
        }
    }

    @Test fun redirectErrorAndOutOfBoundsOffsetCannotPublishAnAsset() {
        for (status in listOf(302, 401, 404, 416, 500)) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalModelTransferProtocol.resumes(status, null, 0, 100)
            }
        }
        for (offset in listOf(-1L, 100L, 101L)) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalModelTransferProtocol.resumes(206, "bytes $offset-99/100", offset, 100)
            }
        }
    }
}
