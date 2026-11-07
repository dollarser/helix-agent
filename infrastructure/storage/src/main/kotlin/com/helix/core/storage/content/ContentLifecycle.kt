package com.helix.core.storage.content

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Publication always precedes Room locking; cleanup shares the same root identity across store instances. */
internal object ContentLifecycle {
    private val locks = ConcurrentHashMap<String, Any>()

    fun <T> coordinate(
        root: File,
        block: () -> T,
    ): T = synchronized(locks.computeIfAbsent(root.canonicalPath) { Any() }, block)
}
