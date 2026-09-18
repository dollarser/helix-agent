package com.helix.runtime.proot.client

import android.os.IBinder

/** Bounded transport references, not job state or authorization. No ServiceConnection is held.
 * Main-process or Runtime death loses previews; it must never cause observation to cold-start
 * a service. The server still verifies calling UID, job binding and cursor generation.
 */
internal object ProotLogConnections {
    private val entries = linkedMapOf<String, Pair<String, IBinder>>()

    @Synchronized
    fun remember(
        jobId: String,
        hash: String,
        binder: IBinder,
    ) {
        entries[jobId] = hash to binder
        while (entries.size > 4) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun find(
        jobId: String,
        hash: String,
    ): IBinder? {
        val entry = entries[jobId] ?: return null
        val alive = entry.second.isBinderAlive
        if (!alive) entries.remove(jobId)
        return entry.second.takeIf { alive && entry.first == hash }
    }
}
