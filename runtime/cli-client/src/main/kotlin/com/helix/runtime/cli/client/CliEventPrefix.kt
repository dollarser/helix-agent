package com.helix.runtime.cli.client

import com.helix.core.model.ModelEvent
import java.security.MessageDigest

/** Constant-memory prefix identity; event boundaries participate in the hash chain. */
internal class CliEventPrefix {
    var size: Int = 0
        private set
    private var digest = ByteArray(32)

    fun append(events: List<ModelEvent>) {
        events.forEach { event ->
            val hash = MessageDigest.getInstance("SHA-256")
            hash.update(digest)
            hash.update(CliModelEventCodec.encodeEvent(event).toString().encodeToByteArray())
            digest = hash.digest()
            size = Math.addExact(size, 1)
        }
    }

    fun matches(events: List<ModelEvent>): Boolean {
        if (events.size < size) return false
        val other = CliEventPrefix()
        other.append(events.subList(0, size))
        return MessageDigest.isEqual(digest, other.digest)
    }
}
