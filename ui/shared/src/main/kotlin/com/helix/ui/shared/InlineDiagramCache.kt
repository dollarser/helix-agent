package com.helix.ui.shared

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.sync.Semaphore

/** A process-memory rendering cache, never a source store or permission. Keys contain no raw source. */
internal object InlineDiagramCache {
    val renderer = Semaphore(1)
    private val images =
        object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ) = value.allocationByteCount
        }

    val retainedBytes get() = images.size()

    fun get(key: String): Bitmap? = images.get(key)

    fun put(
        key: String,
        value: Bitmap,
    ) {
        images.put(key, value)
    }

    fun clear() {
        images.evictAll()
    }
}
