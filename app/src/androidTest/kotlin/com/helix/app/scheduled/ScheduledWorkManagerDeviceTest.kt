package com.helix.app.scheduled

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.helix.app.HelixApplication
import com.helix.app.chat.ScriptedSseWire
import com.helix.app.chat.sseResponse
import com.helix.app.chat.textAnswerStream
import com.helix.app.provider.ProviderFactory
import com.helix.app.provider.ProviderModelSelection
import com.helix.app.provider.ProviderTestStatusStore
import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.core.storage.scheduled.ScheduledAgentRule
import com.helix.core.storage.scheduled.ScheduledAgentStore
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Real Android WorkManager wake and production ledger/runtime, with an offline synthetic provider. */
class ScheduledWorkManagerDeviceTest {
    @Test
    @Suppress("LongMethod") // Keep the real wake, immutable identity and result/no-replay assertions together.
    fun realDelayedWorkSettlesSyntheticTaskWithoutNotificationPermission(): Unit =
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
            require(app.packageName == "com.helix.agent.developer.asrvalidation")
            app.account.restore()
            check(!app.account.signedIn.value)
            val container = app.awaitAppContainer()
            app.runtimeRecoveryReady.await()
            val providers = container.providerService
            val factoryField = providers.javaClass.getDeclaredField("factory").apply { isAccessible = true }
            val factory = factoryField.get(providers) as ProviderFactory
            val wireField = factory.javaClass.getDeclaredField("wire").apply { isAccessible = true }
            val original = wireField.get(factory)
            val wire = ScriptedSseWire().apply { script(sseResponse(textAnswerStream("SYNTHETIC_CLOCK_RESULT"))) }
            val id = UUID.randomUUID().toString()
            val providerId = "clock-fixture-$id"
            val caps = ProviderCapabilities(true, true, false, false, false, false, null, CapabilitySource.PROBED)
            try {
                wireField.set(factory, wire.wireClient)
                withContext(Dispatchers.IO) {
                    container.storage.providerConfigs.save(
                        ProviderConfigSpec(
                            providerId,
                            "Synthetic clock fixture",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            "https://example.invalid/v1",
                            "synthetic",
                            "{}",
                            null,
                            ProviderCapabilities.toJsonString(caps),
                            authKind = "NONE",
                        ),
                    )
                }
                val status =
                    providers.javaClass
                        .getDeclaredField("testStatus")
                        .apply { isAccessible = true }
                        .get(providers) as ProviderTestStatusStore
                status.recordPassed(providerId, System.currentTimeMillis(), caps)
                providers.saveModelSelection(providerId, ProviderModelSelection().addCustom("synthetic"))
                providers.refresh()
                withTimeout(15_000) {
                    providers.rows.first { rows ->
                        rows.any {
                            it.id == providerId &&
                                it.modelSelectable("synthetic")
                        }
                    }
                }
                val session = container.chatService.createSession("Synthetic delayed work", providerId, "synthetic")
                val service = requireNotNull(container.scheduledAgents)
                val store =
                    service.javaClass
                        .getDeclaredField("store")
                        .apply {
                            isAccessible = true
                        }.get(service) as ScheduledAgentStore
                val draft =
                    ScheduledAgentRule(
                        id,
                        "Synthetic clock",
                        "Reply SYNTHETIC_CLOCK_RESULT only. No tools.",
                        "",
                        "",
                        session,
                        LocalDateTime.now(ZoneOffset.UTC).plusSeconds(3).toString(),
                        "UTC",
                        "ONCE",
                        enabled = true,
                    )
                service.save(service.prepare(draft))
                val rule = requireNotNull(store.dao.rule(id))
                val due = requireNotNull(rule.nextAt)
                val key = "scheduled-agent:$id:${rule.revision}:$due"
                val work = WorkManager.getInstance(app)
                withTimeout(120_000) {
                    while (withContext(Dispatchers.IO) {
                            work.getWorkInfosForUniqueWork(key).get().none { it.state.isFinished }
                        }
                    ) {
                        delay(100)
                    }
                }
                val workInfo = withContext(Dispatchers.IO) { work.getWorkInfosForUniqueWork(key).get().single() }
                assertEquals(WorkInfo.State.SUCCEEDED, workInfo.state)
                val occurrence = requireNotNull(store.dao.occurrence(id, rule.revision, due))
                val attempt = requireNotNull(store.dao.attempt(occurrence.lastAttemptId))
                assertEquals("COMPLETED", attempt.state)
                assertNotNull(attempt.completedAt)
                assertEquals(session, attempt.sessionId)
                assertEquals(1, wire.callCount)
                val fact = requireNotNull(service.notificationFact(id, rule.revision, due))
                val permission = app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                if (permission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    assertFalse(ScheduledResultNotification(app).post(fact))
                }
                service.fire(id, rule.revision, due)
                assertEquals(1, wire.callCount)
                val body =
                    withContext(Dispatchers.IO) {
                        container.storage.messages
                            .listBySession(session)
                            .filter { it.role == "ASSISTANT" }
                            .mapNotNull { container.storage.messages.readContent(it) }
                            .joinToString()
                    }
                org.junit.Assert.assertTrue(body.contains("SYNTHETIC_CLOCK_RESULT"))
                println("REAL_WORKMANAGER_WAKE_VERIFIED offlineProvider=true completed=true duplicateNoReplay=true")
            } finally {
                wireField.set(factory, original)
            }
        }
}
