package com.helix.core.workspace

import java.io.InputStream
import java.io.OutputStream

/** Low-level file access, shared by user operations and already-authorized tool execution. */
interface WorkspaceFileBackend {
    fun validateMutation(path: String)

    fun stat(path: String): WorkspaceFileInfo?

    fun children(path: String): List<String>

    fun read(path: String): InputStream

    fun create(
        path: String,
        directory: Boolean,
    )

    fun write(path: String): OutputStream

    fun rename(
        path: String,
        destination: String,
    )

    fun delete(path: String)
}

data class WorkspaceFileInfo(
    val directory: Boolean,
    val size: Long,
)
