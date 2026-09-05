package com.helix.runtime.cli.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test fun permissionSurfaceIsNetworkOnly() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toSet().orEmpty()
        assertEquals(setOf(Manifest.permission.INTERNET), requested)
        assertFalse(requested.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE))
        assertFalse(requested.contains(Manifest.permission.BIND_ACCESSIBILITY_SERVICE))
    }
}
