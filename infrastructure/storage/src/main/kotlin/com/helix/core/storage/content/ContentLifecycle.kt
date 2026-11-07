package com.helix.core.storage.content

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process coordination for the single storage-owning app process, not a cross-process lock.
 * Publication precedes Room locking; cleanup shares the same canonical root across store instances.
 */
internal object ContentLifecycle {
    // Only protects in-flight private files, never a durable content/reference authority.
    // Process exit drops leases, allowing the existing orphan collector to reclaim abandoned stages.
    private val staging = mutableSetOf<String>()

    fun lease(file: File) {
        synchronized(staging) { check(staging.add(file.canonicalPath)) }
    }

    fun release(file: File) {
        synchronized(staging) { check(staging.remove(file.canonicalPath)) }
    }

    fun isStaging(file: File): Boolean = synchronized(staging) { file.canonicalPath in staging }

    private val locks = ConcurrentHashMap<String, Any>()

    fun <T> coordinate(
        root: File,
        block: () -> T,
    ): T = synchronized(locks.computeIfAbsent(root.canonicalPath) { Any() }, block)
}
