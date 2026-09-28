package com.helix.app.localmodel

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in P3 install path: real Android HTTP + resume + local runtime probes + current-session selection. */
class LocalModelInstallDeviceTest : com.helix.app.test.ForegroundDeviceTestHost() {
    @Test
    @Suppress("LongMethod")
    fun httpCancelResumePublishProbeAndSelectOnlyCurrentSession() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val port = args.getString("p3ModelPort")?.toIntOrNull()
            assumeTrue("P3 controlled HTTP fixture not requested", port != null)
            val hash = requireNotNull(args.getString("p3ModelSha"))
            val size = requireNotNull(args.getString("p3ModelSize")).toLong()
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val container = app.appContainer
            val local = requireNotNull(container.providerService.localModels)
            val providerService = container.providerService
            val storage = container.storage
            val chat = container.chatService
            val url = "http://127.0.0.1:$port/model.gguf"
            if (storage.providerConfigs.list().any { it.id == hash }) {
                providerService.delete(hash)
            } else {
                local.delete(hash)
            }
            File(app.cacheDir, "model-transfers/$hash.part").delete()
            val reachedCancelPoint = CompletableDeferred<Unit>()
            val first =
                async {
                    providerService.installLocalModelForTest(url, hash, size, "P3 HTTP fixture") { progress ->
                        if (
                            progress.phase == LocalModelTransferPhase.DOWNLOADING &&
                            progress.downloadedBytes >= CANCEL_AFTER_BYTES
                        ) {
                            reachedCancelPoint.complete(Unit)
                        }
                    }
                }
            withTimeout(15_000) { reachedCancelPoint.await() }
            first.cancelAndJoin()
            assertTrue("caller cancellation must stop the install coroutine", first.isCancelled)

            val resumedProgress = mutableListOf<LocalModelTransferProgress>()
            val installed =
                providerService.installLocalModelForTest(url, hash, size, "P3 HTTP fixture") {
                    if (it.phase == LocalModelTransferPhase.DOWNLOADING) resumedProgress += it
                }
            val providerId = installed.providerId
            assertEquals(hash, providerId)
            assertTrue("resume must start after byte zero", resumedProgress.first().downloadedBytes > 0)
            assertTrue(
                "install connection probe must pass: ${installed.connection}",
                installed.connection is ProbeOutcome.Ok,
            )
            assertTrue(
                "install capability probe must pass: ${installed.capabilities}",
                installed.capabilities is ProbeOutcome.Ok,
            )
            val row = storage.providerConfigs.resolve(providerId)
            assertEquals("ON_DEVICE_ASSET", row.provisioningKind)
            assertEquals("ON_DEVICE_LOCAL", row.transportKind)
            assertEquals(providerId, row.model)

            val now = System.currentTimeMillis()
            val current = "p3-install-current-$now"
            val other = "p3-install-other-$now"
            storage.sessions.create(current, "P3 current", null, null, now)
            storage.sessions.create(other, "P3 other", null, null, now + 1)
            try {
                chat.openSession(current)
                withTimeout(10_000) { while (chat.screen.value.openSessionId != current) delay(20) }
                chat.selectSessionModel(providerId, providerId)
                withTimeout(10_000) {
                    while (storage.sessions.resolve(current).providerId != providerId) delay(20)
                }
                assertEquals(providerId, storage.sessions.resolve(current).providerId)
                assertEquals(providerId, storage.sessions.resolve(current).modelId)
                assertNull(
                    "selecting the local model must not rewrite another session",
                    storage.sessions.resolve(other).providerId,
                )
            } finally {
                chat.closeSession()
                storage.sessions.archive(current, System.currentTimeMillis())
                storage.sessions.archive(other, System.currentTimeMillis())
                providerService.delete(providerId)
            }
        }

    private companion object {
        const val CANCEL_AFTER_BYTES = 4L * 1024 * 1024
    }
}
