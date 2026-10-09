package com.helix.app.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ForegroundRefreshDeviceTest {
    @Test fun refreshStopsInBackgroundAndRestartsImmediatelyOnResume() =
        runBlocking {
            val owner =
                object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry.createUnsafe(this)
                }
            val count = AtomicInteger()
            val refreshed = Channel<Unit>(Channel.UNLIMITED)
            val job =
                launch(Dispatchers.Main) {
                    repeatForegroundRefresh(owner.lifecycle) {
                        count.incrementAndGet()
                        refreshed.send(Unit)
                    }
                }
            try {
                withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.CREATED }
                assertEquals(0, count.get())
                withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
                withTimeout(2_000) { refreshed.receive() }
                withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.STARTED }
                val beforePause = count.get()
                delay(3_200)
                assertEquals(beforePause, count.get())
                withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
                withTimeout(2_000) { refreshed.receive() }
                assertEquals(beforePause + 1, count.get())
                withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
                delay(3_200)
                assertEquals(beforePause + 1, count.get())
            } finally {
                job.cancel()
                refreshed.close()
            }
        }
}
