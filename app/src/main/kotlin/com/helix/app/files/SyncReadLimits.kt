package com.helix.app.files

import java.io.FilterInputStream
import java.io.InputStream

/** A source can grow after scanning. Bound every subsequent copy/verification read as well. */
internal fun boundedSyncBackend(delegate: ManualFileBackend): ManualFileBackend =
    object : ManualFileBackend by delegate {
        override fun stat(path: String): ManualFileInfo? =
            delegate.stat(path)?.also {
                require(!it.directory && it.size <= LIMIT) { "SYNC_FILE_CHANGED_OR_TOO_LARGE" }
            }

        override fun read(path: String): InputStream =
            object : FilterInputStream(delegate.read(path)) {
                private var bytes = 0L

                override fun read(): Int = `in`.read().also { if (it >= 0) account(1) }

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = `in`.read(buffer, offset, length).also { if (it > 0) account(it) }

                private fun account(count: Int) {
                    bytes += count
                    require(bytes <= LIMIT) { "SYNC_FILE_CHANGED_OR_TOO_LARGE" }
                }
            }
    }

private const val LIMIT = 32L * 1024 * 1024
