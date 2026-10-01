package com.helix.app.connector

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.plugin.PluginService
import com.helix.extensions.plugin.PluginPackageReader
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ConnectorSnapshotReferenceDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer

    @Test
    fun independentRemovalCannotDeletePreparedSnapshotBeforePublication() {
        val bundle = bundle()
        val original = c.pluginService.install(bundle)
        val key = original.skills.single()
        c.pluginService.catalog.claim(key, true)
        c.pluginService.remove(original)
        val removal = CompletableFuture<Throwable?>()
        val attempted = CountDownLatch(1)
        val service =
            PluginService(
                app,
                c.storage,
                c.mcpService,
                c.skillImportService,
                c.skillRepository,
                catalog = c.pluginService.catalog,
                installBoundary = { point ->
                    if (point == "before-commit") {
                        Thread {
                            attempted.countDown()
                            removal.complete(runCatching { c.skillRepository.remove(key) }.exceptionOrNull())
                        }.apply {
                            isDaemon = true
                            start()
                        }
                        assertTrue(attempted.await(5, TimeUnit.SECONDS))
                        assertThrows(TimeoutException::class.java) { removal.get(150, TimeUnit.MILLISECONDS) }
                    }
                },
            )
        try {
            service.install(bundle)
            assertTrue(removal.get(5, TimeUnit.SECONDS) is IllegalArgumentException)
            assertTrue(c.skillRepository.hasSnapshot(key))
        } finally {
            service.list().filter { key in it.skills }.forEach(service::remove)
            c.skillRepository.removePermanentlyForPrivacy(key)
        }
    }

    @Test
    fun cleanupRechecksAnIndependentReferenceAcquiredAfterItsInitialScan() {
        val record = c.pluginService.install(bundle())
        val key = record.skills.single()
        c.pluginService.catalog.remove(record) // commit removal while intentionally deferring cleanup
        val cleaned = CompletableFuture<Unit>()
        val attempted = CountDownLatch(1)
        try {
            c.skillRepository.withSnapshotReferences {
                Thread {
                    attempted.countDown()
                    runCatching { c.pluginService.cleanupRetired() }
                        .onSuccess { cleaned.complete(Unit) }
                        .onFailure { cleaned.completeExceptionally(it) }
                }.apply {
                    isDaemon = true
                    start()
                }
                assertTrue(attempted.await(5, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { cleaned.get(150, TimeUnit.MILLISECONDS) }
                c.pluginService.catalog.claim(key, true)
            }
            cleaned.get(5, TimeUnit.SECONDS)
            assertTrue(c.skillRepository.hasSnapshot(key))
        } finally {
            c.skillRepository.removePermanentlyForPrivacy(key)
        }
    }

    private fun bundle() =
        PluginPackageReader().parse(
            mapOf(
                "skills/reference/SKILL.md" to
                    "---\nname: reference\ndescription: ${UUID.randomUUID()}\n---\nRead a reference.".toByteArray(),
            ),
        )
}
