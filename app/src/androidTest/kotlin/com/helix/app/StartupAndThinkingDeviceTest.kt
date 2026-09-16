package com.helix.app

import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.HelixStorage
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StartupAndThinkingDeviceTest {
    @Test fun firstUiReaderUsesBackgroundRoomAndActivityRecreationSharesContainer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "startup-thread-fixture.db"
        val content = File(context.cacheDir, "startup-thread-fixture")
        try {
            instrumentation.runOnMainSync {
                val initialization =
                    BackgroundInitialization("fixture-room-init") {
                        assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                        val storage = HelixStorage.open(context, name, content)
                        try {
                            // Synchronous query: Room's main-thread guard remains enabled.
                            storage.providerConfigs.list()
                        } finally {
                            storage.close()
                        }
                    }
                assertTrue(initialization.await().isEmpty())
            }
            val app = context.applicationContext as HelixApplication
            val original = app.appContainer
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { assertSame(original, (it.application as HelixApplication).appContainer) }
                scenario.recreate()
                scenario.onActivity { assertSame(original, (it.application as HelixApplication).appContainer) }
            }
        } finally {
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }

    @Test fun reasoningOnlyConnectionPassesRealWireDecoderAndStoredService() =
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
            val service = app.appContainer.providerService
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.reasoningOnlyResponses = true
                server.start()
                val id =
                    service.create(
                        ProviderDraft(
                            null,
                            "Thinking fixture",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                            "fixture-model-a",
                            "{}",
                            false,
                            CleartextAuthorization("127.0.0.1", server.port),
                            emptyList(),
                        ),
                        null,
                        cleartextConfirmed = true,
                    )
                try {
                    assertTrue(service.runConnectionTest(id) is ProbeOutcome.Ok)
                } finally {
                    service.delete(id)
                }
            }
        }
}
