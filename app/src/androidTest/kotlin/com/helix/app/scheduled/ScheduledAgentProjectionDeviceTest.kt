package com.helix.app.scheduled

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class ScheduledAgentProjectionDeviceTest {
    @Test fun realWorkManagerKeepsOneProjectionAndCancelsOnlyOwnedFuture(): Unit =
        runBlocking {
            ScheduledFixture().use { f ->
                val context = ApplicationProvider.getApplicationContext<Context>()
                val rule = f.save()
                val wake = AndroidScheduledAgentWake(context)
                val key = "scheduled-agent:${rule.id}:${rule.revision}:${requireNotNull(rule.nextAt)}"
                val manager = WorkManager.getInstance(context)
                try {
                    wake.schedule(rule)
                    wake.schedule(rule)
                    val works = manager.getWorkInfosForUniqueWork(key).get(10, TimeUnit.SECONDS)
                    assertEquals(1, works.size)
                    assertEquals(WorkInfo.State.ENQUEUED, works.single().state)
                } finally {
                    wake.cancelFuture(rule)
                }
                assertEquals(
                    WorkInfo.State.CANCELLED,
                    manager
                        .getWorkInfosForUniqueWork(key)
                        .get(10, TimeUnit.SECONDS)
                        .single()
                        .state,
                )
            }
        }
}
