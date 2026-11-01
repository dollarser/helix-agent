package com.helix.runtime.proot.app

/** Streaming exact proof filter; retains a possible suffix across reads and masks it at truncated EOF. */
internal class BridgeOutputRedactor(
    secret: String,
    private val emit: (ByteArray, Int) -> Unit,
) {
    private val needle = secret.toByteArray(Charsets.UTF_8)
    private val pending = ArrayList<Byte>()

    init {
        require(secret.isNotEmpty())
    }

    fun write(
        bytes: ByteArray,
        count: Int,
    ) {
        val output = java.io.ByteArrayOutputStream()
        repeat(count) { index ->
            pending.add(bytes[index])
            while (pending.isNotEmpty() && !prefix()) output.write(pending.removeAt(0).toInt())
            if (pending.size == needle.size) {
                output.write("[redacted]".toByteArray())
                pending.clear()
            }
        }
        val safe = output.toByteArray()
        if (safe.isNotEmpty()) emit(safe, safe.size)
    }

    fun finish(complete: Boolean = false) {
        if (pending.isNotEmpty()) {
            val safe = if (complete) pending.toByteArray() else "[redacted]".toByteArray()
            emit(safe, safe.size)
            pending.clear()
        }
    }

    private fun prefix(): Boolean = pending.size <= needle.size && pending.indices.all { pending[it] == needle[it] }
}
