package com.helix.extensions.documents

import com.helix.tools.framework.ExecutableToolCall
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Snapshot identity is host-issued and tied to a session, never a bearer permission. */
data class DocumentSnapshot(
    val reference: String,
    val sourcePath: String,
    val sha256: String,
    val format: String,
)

interface DocumentSnapshots {
    fun create(
        call: ExecutableToolCall,
        path: String,
    ): DocumentSnapshot

    fun resolve(
        call: ExecutableToolCall,
        reference: String,
    ): DocumentSnapshot
}

fun interface DocumentParser {
    fun parse(
        call: ExecutableToolCall,
        snapshot: DocumentSnapshot,
        operation: String,
    ): DocumentResult
}

data class DocumentResult(
    val metadata: JsonObject,
    val image: ByteArray? = null,
)

class DocumentException(
    val code: String,
) : java.io.IOException(code)

object DocumentLimits {
    const val SOURCE_BYTES = 20L * 1024 * 1024
    const val TEXT = 20_000
    const val IMAGE_BYTES = 3 * 1024 * 1024
    const val BLOCKS = 32

    fun checkActive(call: ExecutableToolCall) {
        if (call.cancel.isCancelled()) throw DocumentException("CANCELLED")
        if (!Instant.now().isBefore(call.deadline)) throw DocumentException("TIMED_OUT")
    }
}
