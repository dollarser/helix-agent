package com.helix.runtime.proot.app

import android.os.IBinder
import android.os.RemoteException

/** Per-job death links are released on terminal completion; query/unbind does not own these links. */
internal class ProotJobOwners {
    private val links = mutableMapOf<String, Pair<IBinder, IBinder.DeathRecipient>>()

    @Synchronized
    fun watch(
        job: String,
        owner: IBinder,
        onDeath: () -> Unit,
    ) {
        check(job !in links)
        val death =
            IBinder.DeathRecipient {
                try {
                    onDeath()
                } finally {
                    release(job)
                }
            }
        links[job] = owner to death
        try {
            owner.linkToDeath(death, 0)
        } catch (_: RemoteException) {
            links.remove(job)
            onDeath()
        }
    }

    @Synchronized
    fun release(job: String) {
        links.remove(job)?.let { (owner, death) -> owner.unlinkToDeath(death, 0) }
    }
}
