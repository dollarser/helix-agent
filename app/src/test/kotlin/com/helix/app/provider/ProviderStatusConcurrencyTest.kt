package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ModelErrorCode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProviderStatusConcurrencyTest {
    @Test fun independentProviderWritesAndNewProviderClearsCannotLoseStatuses() {
        val backing = InMemoryLineStore()
        val stores = listOf(ProviderTestStatusStore(backing), ProviderTestStatusStore(backing))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs =
                (0 until 32).map { i ->
                    pool.submit {
                        check(start.await(5, TimeUnit.SECONDS))
                        repeat(20) {
                            stores[i % 2].recordFailed("p$i", it.toLong(), 1, ModelErrorCode.PROTOCOL, false)
                            stores[(i + 1) % 2].clear("new-$i")
                        }
                    }
                }
            start.countDown()
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            repeat(32) { assertTrue(stores[0].statusFor("p$it") is ConnectionTestStatus.Failed) }
        } finally {
            pool.shutdownNow()
        }
    }
}
