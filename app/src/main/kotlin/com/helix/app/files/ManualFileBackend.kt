package com.helix.app.files

import java.io.InputStream
import java.io.OutputStream

/** User-operated storage only; never registered as an Agent capability or tool. */
internal interface ManualFileBackend {
    fun validateMutation(path: String)

    fun stat(path: String): ManualFileInfo?

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

internal data class ManualFileInfo(
    val directory: Boolean,
    val size: Long,
)
