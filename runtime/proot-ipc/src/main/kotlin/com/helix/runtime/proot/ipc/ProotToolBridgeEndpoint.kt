package com.helix.runtime.proot.ipc

/** Runtime-only value. A proof is never a log, durable record, fingerprint or display value. */
class ProotToolBridgeEndpoint(
    val socketName: String,
    val proof: String,
) {
    init {
        require(socketName.matches(Regex("helix-tools-[a-f0-9]{32}")))
        require(proof.matches(Regex("[a-f0-9]{64}")))
    }

    override fun toString(): String = "ProotToolBridgeEndpoint(redacted)"
}
