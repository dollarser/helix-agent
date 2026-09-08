package com.helix.runtime.proot.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProotJobOwnersDeviceTest {
    @Test
    fun concurrentDeathAndCompletionUnlinkExactlyOnce() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { index ->
                val owners = ProotJobOwners()
                val owner = ControllableOwnerFixture()
                val deaths = AtomicInteger()
                val job = "job-$index"
                owners.watch(job, owner.binder) { deaths.incrementAndGet() }
                val pendingCallback = requireNotNull(owner.recipient.get())
                val start = CountDownLatch(1)
                val death =
                    pool.submit {
                        start.await()
                        pendingCallback.binderDied()
                    }
                val completion =
                    pool.submit {
                        start.await()
                        owners.release(job)
                    }
                start.countDown()
                death.get(5, TimeUnit.SECONDS)
                completion.get(5, TimeUnit.SECONDS)
                owners.release(job)
                assertEquals(1, owner.links.get())
                assertEquals(1, owner.unlinks.get())
                assertEquals(1, deaths.get())
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
