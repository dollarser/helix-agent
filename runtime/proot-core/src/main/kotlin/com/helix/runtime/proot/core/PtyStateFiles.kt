package com.helix.runtime.proot.core

import java.io.File

/** Only a settled manual identity can authorize removal of its exact temporary namespace. */
object PtyStateFiles {
    fun reclaim(
        runtime: File,
        store: PtySessionStore,
        expected: PtySessionRecord,
        active: Boolean,
    ): Boolean {
        require(expected.reconciled && expected.stopProof != null && !active) { "Terminal still has live dependencies" }
        val current = store.read(expected.origin.sessionId)
        return when {
            current == null -> {
                true
            }

            current != expected -> {
                false
            }

            else -> {
                removeTemporary(runtime, expected.origin.sessionId)
                store.removeReconciled(expected)
            }
        }
    }

    fun removeTemporary(
        runtime: File,
        sessionId: String,
    ) {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        if (!runtime.exists()) return
        require(
            !java.nio.file.Files
                .isSymbolicLink(runtime.toPath()),
        )
        checkNotNull(runtime.listFiles()).filter { it.name.matches(Regex("inst_[A-Za-z0-9_-]+")) }.forEach {
            PtyStateNative.tree(runtime, "${it.name}/tmp/pty-$sessionId")
        }
    }

    fun removeMetadata(
        directory: File,
        name: String,
        expected: ByteArray,
    ): Boolean {
        require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name !in setOf(".", ".."))
        require(expected.size <= PtySessionRecordCodec.MAX_BYTES)
        return PtyStateNative.metadata(directory, name, expected)
    }
}
