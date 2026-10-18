package com.helix.app.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.chat.ConversationLaunchStore
import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated Room fixtures only. Never reads, clears or migrates the installed application's database. */
class BuiltInModelStorageDeviceTest {
    @Test fun freshRegistrationIsIdempotentAndDoesNotReplaceSubsequentSelection() {
        Fixture().use { f ->
            BuiltInModelRegistration.ensureRegistered(f.storage)
            BuiltInModelRegistration.ensureRegistered(f.storage)
            assertEquals(
                1,
                f.storage.providerConfigs
                    .list()
                    .size,
            )
            assertNull(f.launch.draftModel())
            assertNull(
                f.storage.providerConfigs
                    .resolve(BuiltInModel.PROVIDER_ID)
                    .secretAlias,
            )
            f.customProvider()
            f.launch.selectDraftModel("custom", "chosen")
            BuiltInModelRegistration.ensureRegistered(f.storage)
            assertEquals("custom" to "chosen", ConversationLaunchStore(f.preferences).draftModel())
        }
    }

    @Test fun existingProviderAndSessionStayUnchangedWithoutSeedingDefault() {
        Fixture().use { f ->
            f.customProvider()
            f.storage.sessions.create("existing", "Keep", "custom", "chosen", 1)
            val provider = f.storage.providerConfigs.resolve("custom")
            val session = f.storage.sessions.resolve("existing")
            BuiltInModelRegistration.ensureRegistered(f.storage)
            assertEquals(provider, f.storage.providerConfigs.resolve("custom"))
            assertEquals(session, f.storage.sessions.resolve("existing"))
            assertNull(f.launch.draftModel())
        }
    }

    @Test fun serviceAllowsAttemptWithoutFakingPassedAndRejectsMutations() =
        runBlocking {
            Fixture().use { f ->
                BuiltInModelRegistration.ensureRegistered(f.storage)
                val wire =
                    object : WireClient {
                        override suspend fun open(request: WireRequest): WireResponse = error("No network")
                    }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val service =
                        ProviderService(
                            f.storage,
                            ProviderFactory({ error("No secrets") }, wire, { error("No images") }, builtInWire = wire),
                            ProviderTestStatusStore(f.preferences),
                            idGenerator = { UUID.randomUUID().toString() },
                            scope = scope,
                        )
                    val id = BuiltInModel.PROVIDER_ID
                    assertTrue(service.chatSelectable(id))
                    assertEquals(ConnectionTestStatus.Untested, service.statusFor(id))
                    assertEquals(BuiltInModel.capabilities, service.capabilitiesFor(id, BuiltInModel.MODEL_ID))
                    assertEquals(false, service.capabilitiesFor(id, "other")?.toolCalls)
                    assertEquals(false, service.capabilitiesFor(id, "other")?.vision)
                    assertTrue(runCatching { service.delete(id) }.exceptionOrNull() is IllegalArgumentException)
                    assertTrue(runCatching { service.saveSelectedModels(id, listOf("other")) }.isFailure)
                    assertTrue(runCatching { service.declareVisionCapability(id, false) }.isFailure)
                    assertEquals(BuiltInModel.MODEL_ID, service.storedConfig(id).model)
                } finally {
                    scope.cancel()
                }
            }
        }

    private class Fixture : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "builtin-${UUID.randomUUID()}.db"
        private val directory = File(context.cacheDir, name).apply { mkdirs() }
        val storage = HelixStorage.open(context, name, File(directory, "content"))
        val preferences = InMemoryLineStore()
        val launch = ConversationLaunchStore(preferences)

        fun customProvider() {
            storage.providerConfigs.save(
                ProviderConfigSpec(
                    "custom",
                    "Own model",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    "https://fixture.invalid/v1",
                    "chosen",
                    "{}",
                    null,
                    "{}",
                    authKind = "NONE",
                ),
            )
        }

        override fun close() {
            storage.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }
}
