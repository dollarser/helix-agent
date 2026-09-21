package com.helix.app.mcp.oauth

import android.app.Application
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.storage.AndroidKeystoreSecretStore
import com.helix.extensions.mcp.McpEndpointGate
import com.helix.extensions.mcp.oauth.McpOAuthClient
import com.helix.extensions.mcp.oauth.McpOAuthServerMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class McpOAuthDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val secrets get() = AndroidKeystoreSecretStore.create(context)

    private fun client() = McpOAuthClient(McpEndpointGate { error("No network expected in boundary fixture") })

    private fun metadata() =
        McpOAuthServerMetadata(
            "https://issuer.example",
            "https://issuer.example/authorize",
            "https://issuer.example/token",
        )

    @Test fun callbackRegistrationUsesInstalledApplicationId() {
        val uri = android.net.Uri.parse("${context.packageName}://oauth/mcp/callback?state=invalid")
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).setPackage(context.packageName)
        val flags = android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        val matches = context.packageManager.queryIntentActivities(intent, flags)
        assertEquals(1, matches.size)
        assertEquals(McpOAuthCallbackActivity::class.java.name, matches.single().activityInfo.name)
        assertTrue(matches.single().activityInfo.exported)
        val wrong = intent.setData(android.net.Uri.parse("${context.packageName}://oauth/mcp/callback/extra"))
        assertTrue(context.packageManager.queryIntentActivities(wrong, flags).isEmpty())
    }

    @Test fun callbackTraversalCannotDeleteOtherPrivateJson() =
        runBlocking {
            val dir = File(context.cacheDir, "oauth-${UUID.randomUUID()}").apply { mkdirs() }
            val victim = File(dir, "unrelated.json").apply { writeText("fixture") }
            val store = McpOAuthAttemptStore(File(dir, "attempts"), secrets)
            try {
                val coordinator = McpOAuthCoordinator(secrets, store, client())
                val result = coordinator.handleCallback("helix://oauth/mcp/callback?state=..%2Funrelated&code=fixture")
                assertTrue(result is McpOAuthResult.Failure)
                assertEquals("fixture", victim.readText())
            } finally {
                dir.deleteRecursively()
            }
        }

    @Test fun wrongRedirectIssuerAndRepeatedQueryDoNotConsumeAttempt() =
        runBlocking {
            val dir = File(context.cacheDir, "oauth-${UUID.randomUUID()}")
            val store = McpOAuthAttemptStore(dir, secrets)
            val coordinator = McpOAuthCoordinator(secrets, store, client())
            val id = "device-${UUID.randomUUID()}"
            val prepared = coordinator.prepareAuthorization(id, "client", metadata(), "read")
            try {
                val query = "?state=${prepared.state}&code=fixture"
                listOf(
                    "helix://oauth/callback$query",
                    "helix://oauth/mcp/callback$query&iss=wrong",
                    "helix://oauth/mcp/callback$query&state=other",
                ).forEach { callback ->
                    assertTrue(coordinator.handleCallback(callback) is McpOAuthResult.Failure)
                    assertTrue(store.peekAttempt(prepared.state) != null)
                }
                coordinator.cancel(id)
                assertNull(store.peekAttempt(prepared.state))
            } finally {
                coordinator.clearLocal(id)
                dir.deleteRecursively()
            }
        }

    @Test fun verifierAndOneTimeConsumptionSurviveRealProcessRestart() {
        val dir = File(context.noBackupFilesDir, "oauth-recovery")
        val stateMarker = File(context.noBackupFilesDir, "oauth-recovery-state")
        val pidMarker = File(context.noBackupFilesDir, "recovery-device-pid")
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        val id = "device-oauth-recovery"
        val store = McpOAuthAttemptStore(dir, secrets)
        val coordinator = McpOAuthCoordinator(secrets, store, client())
        if (phase != "verify") {
            coordinator.clearLocal(id)
            val prepared = coordinator.prepareAuthorization(id, "client", metadata(), "read")
            stateMarker.writeText(prepared.state)
            pidMarker.writeText(Process.myPid().toString())
            if (phase == "setup") Process.killProcess(Process.myPid())
        }
        if (phase == "setup") return
        try {
            if (phase == "verify") assertNotEquals(pidMarker.readText().toInt(), Process.myPid())
            val state = stateMarker.readText()
            val journal = File(dir, "$state.json").readText()
            assertFalse(journal.contains("codeVerifier"))
            val attempt = requireNotNull(store.consumeAttempt(state))
            assertTrue(attempt.codeVerifier.length >= 43)
            assertFalse(journal.contains(attempt.codeVerifier))
            assertNull(McpOAuthAttemptStore(dir, secrets).consumeAttempt(state))
        } finally {
            coordinator.clearLocal(id)
            dir.deleteRecursively()
            stateMarker.delete()
            pidMarker.delete()
        }
    }

    @Test fun expiredAndCancelledAttemptsClearVerifierWithoutNetwork() {
        val dir = File(context.cacheDir, "oauth-${UUID.randomUUID()}")
        val store = McpOAuthAttemptStore(dir, secrets)
        val coordinator = McpOAuthCoordinator(secrets, store, client())
        val id = "expiry-${UUID.randomUUID()}"
        try {
            val prepared = coordinator.prepareAuthorization(id, "client", metadata(), "read")
            val expiredAt = requireNotNull(store.peekAttempt(prepared.state)).expiresAtMs
            assertNull(store.consumeAttempt(prepared.state, expiredAt))
            assertTrue(
                secrets.aliases().none {
                    it.value.startsWith("oauth.attempt.") &&
                        it.value.endsWith(".${prepared.state}")
                },
            )
            val second = coordinator.prepareAuthorization(id, "client", metadata(), "read")
            coordinator.cancel(id)
            assertNull(store.consumeAttempt(second.state))
        } finally {
            coordinator.clearLocal(id)
            dir.deleteRecursively()
        }
    }
}
