package com.helix.core.storage

import com.helix.core.model.CompactManifestCodec
import com.helix.core.model.MessageRefEntry
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestContextManifestDeviceTest {
    @Test
    fun boundedManifestEnforcesSingleLineLimitOnDevice() {
        val largeMessages = (1..512).map { MessageRefEntry("msg-$it", MessageRefEntry.ROLE_USER) }
        val largeInputs = (1..512).map { "input-uuid-$it" }
        val manifest =
            CompactManifestCodec.bounded(
                callId = "c-bench",
                timestamp = 1774300000000L,
                checkpoint = 100L,
                messages = largeMessages,
                inputIds = largeInputs,
            )
        val encoded = CompactManifestCodec.encodeCompact(manifest)
        val byteSize = encoded.toByteArray(Charsets.UTF_8).size
        assertTrue(
            "Manifest byte size $byteSize exceeds 256KiB line limit",
            byteSize <= com.helix.core.model.RequestContextManifest.MAX_SINGLE_LINE_BYTES,
        )
    }
}
