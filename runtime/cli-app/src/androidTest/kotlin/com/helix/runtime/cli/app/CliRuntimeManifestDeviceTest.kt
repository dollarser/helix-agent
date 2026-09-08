package com.helix.runtime.cli.app

import android.Manifest
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.system.Os
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.runtime.cli.client.CliRuntimeProtocol
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CliRuntimeManifestDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun codexTokenEndpointIsReachableWithoutCredentials() {
        val request =
            Request
                .Builder()
                .url(CodexOAuthProtocol.TOKEN_URL)
                .post(FormBody.Builder().add("grant_type", "invalid-probe").build())
                .build()
        OkHttpClient().newCall(request).execute().use { response ->
            assertTrue(response.code in 400..499)
        }
    }

    @Test fun copilotDeviceEndpointIssuesBoundedAnonymousAttempt() {
        val attempt = OkHttpCopilotDeviceTransport().use { it.requestDeviceCode() }
        assertTrue(attempt.userCode.isNotBlank())
        assertEquals("https://github.com/login/device", attempt.verificationUri)
        assertTrue(attempt.intervalMillis >= 5_000)
        assertTrue(attempt.expiresAtEpochMillis > System.currentTimeMillis())
    }

    @Test fun grokDeviceEndpointIssuesBoundedAnonymousAttempt() {
        val attempt = OkHttpGrokDeviceTransport().use { it.requestDeviceCode() }
        assertTrue(attempt.userCode.isNotBlank())
        assertTrue(attempt.verificationUri.startsWith("https://"))
        assertTrue(attempt.intervalMillis >= 5_000)
        assertTrue(attempt.expiresAtEpochMillis > System.currentTimeMillis())
    }

    @Test fun codexDeviceEndpointIssuesBoundedAnonymousAttempt() {
        val attempt = OkHttpCodexDeviceTransport().use { it.requestDeviceCode() }
        assertTrue(attempt.userCode.isNotBlank())
        assertEquals("https://auth.openai.com/codex/device", attempt.verificationUrl)
        assertTrue(attempt.intervalMillis in 1_000..60_000)
        assertTrue(attempt.expiresAtEpochMillis > System.currentTimeMillis())
    }

    @Test fun codexSubscriptionSmokeIsFixedToollessAndBounded() {
        val body = JSONObject(CodexSubscriptionSmoke.encodeRequest("device-test-model"))
        assertEquals("device-test-model", body.getString("model"))
        assertFalse(body.has("tools"))
        assertFalse(body.has("tool_choice"))
        assertFalse(body.has("reasoning"))
        assertFalse(body.has("max_output_tokens"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(64, CodexSubscriptionSmoke.MAX_TEXT_CHARS)
        assertEquals(256L * 1024L, CodexSubscriptionSmoke.MAX_STREAM_BYTES)
    }

    @Test fun deviceCodeClipboardCopiesOnlyTheSelectedValue() {
        ActivityScenario.launch(CodexLoginActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                DeviceCodeClipboard.copy(activity, "Device code", "ABCD-1234")
                assertEquals(
                    "ABCD-1234",
                    clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.text
                        ?.toString(),
                )
                DeviceCodeClipboard.copy(activity, "Verification URL", "https://auth.openai.com/codex/device")
                assertEquals(
                    "https://auth.openai.com/codex/device",
                    clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.text
                        ?.toString(),
                )
            }
        }
    }

    @Test fun embeddedLockIsStrictAndContainsNoBundledExecutable() {
        val lock = CliEmbeddedBaseline.lock(context)
        assertEquals(
            setOf(
                "node",
                "codex-app-server",
                "claude-code-npm",
                "claude-code-linux-arm64-musl",
                "github-copilot-sdk-npm",
                "github-copilot-sdk-linux-arm64",
                "github-copilot-sdk-linuxmusl-arm64",
            ),
            lock.artifacts.map { it.id }.toSet(),
        )
        assertTrue(lock.artifacts.none { it.bundled })
        assertEquals(64, CliRuntimeLockCodec.sha256(lock).length)
    }

    @Test fun runtimeExposesOnlyRedactedCredentialStateAndNoAgentBackend() {
        CliSubscriptionCredentialVault(context).apply {
            CliSubscriptionProvider.entries.forEach(::logout)
        }
        val status = JSONObject(CliEmbeddedBaseline.status(context))
        assertEquals("VAULT_READY_ADAPTERS_NOT_REGISTERED", status.getString("credentialState"))
        assertEquals("LOGGED_OUT", status.getString("codexLoginState"))
        assertEquals("LOGGED_OUT", status.getString("claudeLoginState"))
        assertEquals("LOGGED_OUT", status.getString("grokLoginState"))
        assertEquals("LOGGED_OUT", status.getString("copilotLoginState"))
        assertEquals("NOT_REGISTERED", status.getString("agentBackendState"))
    }

    @Test fun credentialVaultUsesRuntimePrivateKeystoreAndLogoutDeletesSession() {
        val vault = CliSubscriptionCredentialVault(context)
        val marker = "device-secret-${System.nanoTime()}"
        try {
            vault.save(
                CliSubscriptionProvider.CODEX,
                CliSubscriptionSession(marker, "refresh-$marker", null, System.currentTimeMillis() + 60_000),
            )
            vault.save(
                CliSubscriptionProvider.CODEX,
                CliSubscriptionSession(
                    "updated-$marker",
                    "updated-refresh-$marker",
                    null,
                    System.currentTimeMillis() + 120_000,
                ),
            )
            assertEquals("updated-$marker", vault.load(CliSubscriptionProvider.CODEX).accessToken)
            val credentialFile = credentialFile(CliSubscriptionProvider.CODEX)
            assertEquals(0x180, Os.stat(credentialFile.path).st_mode and 0x1ff)
            val status = CliEmbeddedBaseline.status(context)
            assertTrue(status.contains("\"codexLoginState\":\"LOGGED_IN\""))
            assertFalse(status.contains(marker))
        } finally {
            vault.logout(CliSubscriptionProvider.CODEX)
        }
        assertFalse(vault.contains(CliSubscriptionProvider.CODEX))
    }

    @Test fun credentialVaultRejectsTamperingAndReportsOnlyRedactedError() {
        val vault = CliSubscriptionCredentialVault(context)
        val marker = "tamper-${System.nanoTime()}"
        try {
            vault.save(
                CliSubscriptionProvider.CODEX,
                CliSubscriptionSession(marker, "refresh-$marker", null, System.currentTimeMillis() + 60_000),
            )
            val credentialFile = credentialFile(CliSubscriptionProvider.CODEX)
            val bytes = credentialFile.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            credentialFile.writeBytes(bytes)

            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                vault.load(CliSubscriptionProvider.CODEX)
            }
            val status = CliEmbeddedBaseline.status(context)
            assertTrue(status.contains("\"codexLoginState\":\"CREDENTIAL_ERROR\""))
            assertFalse(status.contains(marker))
        } finally {
            vault.logout(CliSubscriptionProvider.CODEX)
        }
    }

    @Test fun codexAccountIdStaysInsideEncryptedRuntimeVault() {
        val vault = CliSubscriptionCredentialVault(context)
        val marker = "account-${System.nanoTime()}"
        try {
            vault.save(
                CliSubscriptionProvider.CODEX,
                CliSubscriptionSession("access-$marker", "refresh-$marker", "id-$marker", 123_456, marker),
            )
            assertEquals(marker, vault.load(CliSubscriptionProvider.CODEX).accountId)
            assertFalse(CliEmbeddedBaseline.status(context).contains(marker))
            assertFalse(String(credentialFile(CliSubscriptionProvider.CODEX).readBytes()).contains(marker))
        } finally {
            vault.logout(CliSubscriptionProvider.CODEX)
        }
    }

    @Test fun loopbackAcceptsExactStateAndClosesAfterCode() {
        val server = CodexLoopbackServer.bind()
        val attempt = CodexOAuthProtocol.createAttempt(server.port)
        val latch = CountDownLatch(1)
        var result: CodexCallbackResult? = null
        server.await(attempt.state) {
            result = it
            latch.countDown()
        }
        Socket("127.0.0.1", server.port).use { socket ->
            socket.getOutputStream().write(
                "GET /auth/callback?code=device-code&state=${attempt.state} HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .encodeToByteArray(),
            )
            socket.getInputStream().readBytes()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(CodexCallbackResult.Code("device-code"), result)
    }

    @Test fun loopbackTimeoutSettlesWithoutCredential() {
        val server = CodexLoopbackServer.bind(timeoutMillis = 100)
        val latch = CountDownLatch(1)
        var result: CodexCallbackResult? = null
        server.await("unused-state") {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(CodexCallbackResult.Rejected("login timed out"), result)
        assertFalse(CliSubscriptionCredentialVault(context).contains(CliSubscriptionProvider.CODEX))
    }

    @Test fun claudeEphemeralLoopbackAcceptsExactCallbackAndState() {
        val server = CodexLoopbackServer.bindEphemeral()
        val attempt = ClaudeOAuthProtocol.createAttempt(server.port)
        val latch = CountDownLatch(1)
        var result: CodexCallbackResult? = null
        server.await(attempt.state, ClaudeOAuthProtocol::parseCallback) {
            result = it
            latch.countDown()
        }
        Socket("127.0.0.1", server.port).use { socket ->
            socket.getOutputStream().write(
                "GET /callback?code=claude-code&state=${attempt.state} HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .encodeToByteArray(),
            )
            socket.getInputStream().readBytes()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(CodexCallbackResult.Code("claude-code"), result)
    }

    @Test fun claudeFreeEligibilityNeverCreatesCredential() {
        val vault = CliSubscriptionCredentialVault(context)
        vault.logout(CliSubscriptionProvider.CLAUDE)
        val session =
            CliSubscriptionSession(
                "device-access",
                "device-refresh",
                null,
                System.currentTimeMillis() + 60_000,
            )
        val transport =
            object : ClaudeOAuthTransport {
                override fun exchange(
                    attempt: ClaudeOAuthAttempt,
                    code: String,
                ) = ClaudeExchangeResult(session, "free")

                override fun refresh(session: CliSubscriptionSession) = session
            }
        assertThrows(ClaudeEligibilityException::class.java) {
            ClaudeLoginController(vault, transport).complete(ClaudeOAuthProtocol.createAttempt(12345), "code")
        }
        assertFalse(vault.contains(CliSubscriptionProvider.CLAUDE))
        assertTrue(CliEmbeddedBaseline.status(context).contains("\"claudeLoginState\":\"LOGGED_OUT\""))
    }

    private fun credentialFile(provider: CliSubscriptionProvider) =
        File(context.filesDir, "subscription-secrets/subscription-${provider.wireId}.enc")

    @Test fun serviceIsExportedAndSignatureProtected() {
        val info =
            context.packageManager.getServiceInfo(
                ComponentName(context, CliRuntimeService::class.java),
                PackageManager.GET_META_DATA,
            )
        assertTrue(info.exported)
        assertEquals(CliRuntimeProtocol.PERMISSION, info.permission)
        assertNotNull(context.packageManager.getPermissionInfo(CliRuntimeProtocol.PERMISSION, 0))
    }

    @Test fun copilotLoginIsAnExplicitVisibleActivity() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, CopilotLoginActivity::class.java), 0)
        assertTrue(info.exported)
    }

    @Test fun claudeLoginIsAnExplicitVisibleActivity() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, ClaudeLoginActivity::class.java), 0)
        assertTrue(info.exported)
    }

    @Test fun grokLoginIsAnExplicitVisibleActivity() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, GrokLoginActivity::class.java), 0)
        assertTrue(info.exported)
    }

    @Test fun permissionSurfaceIsNetworkOnly() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toSet().orEmpty()
        assertEquals(setOf(Manifest.permission.INTERNET), requested)
        assertFalse(requested.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE))
        assertFalse(requested.contains(Manifest.permission.BIND_ACCESSIBILITY_SERVICE))
    }
}
