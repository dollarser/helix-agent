package com.helix.app.account

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.provider.BuiltInModel
import com.helix.app.voice.CloudSpeechDeviceTest
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in official account acceptance. Credentials arrive once from an explicitly supplied local bridge. */
class AccountServiceDeviceTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private val application: HelixApplication
        get() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            return context.applicationContext as HelixApplication
        }
    private val account get() = application.account
    private val evidence get() = File(application.cacheDir, "account-service-device-result.txt")
    private val pidFile get() = File(application.noBackupFilesDir, "recovery-device-pid")
    private val request get() =
        ModelRequest(
            BuiltInModel.MODEL_ID,
            listOf(ModelMessage(ModelRole.USER, "Reply with OK only.")),
            maxOutputTokens = 256,
            reasoning = ReasoningEffort.LOW,
        )

    @Test fun loginRestartRefreshAndLogout() =
        runBlocking {
            assumeTrue("Account profile not supplied", args.getString("accountAcceptance") == "true")
            val container = application.awaitAppContainer()
            if (args.getString("recoveryPhase") == "setup") setup(container) else verify(container)
        }

    private suspend fun setup(container: AppContainer) {
        val bridge = requireNotNull(args.getString("accountCredentialBridge"))
        require(bridge == "http://127.0.0.1:19091/one-shot-credentials")
        val response =
            OkHttpWireClient(followRedirects = false, maxBodyBytes = 4096)
                .open(WireRequest("GET", bridge, emptyMap(), null))
        val credentials =
            try {
                check(response.status == 200) { "Local test credential bridge unavailable" }
                Json.parseToJsonElement(response.body.bytes().toString(Charsets.UTF_8)).jsonObject
            } finally {
                response.body.close()
            }
        try {
            account.login(
                credentials.getValue("email").jsonPrimitive.content,
                credentials.getValue("password").jsonPrimitive.content,
            )
            assertTrue(account.signedIn.value)
            assertTrue(container.providerService.chatSelectable(BuiltInModel.PROVIDER_ID))
            val provider = container.providerService.modelProviderFor(BuiltInModel.PROVIDER_ID)
            val catalog = provider.listModels() as? ModelCatalogResult.Listed
            assertNotNull("Official model directory must succeed", catalog)
            assertTrue(requireNotNull(catalog).models.contains(BuiltInModel.MODEL_ID))
            container.providerService.builtInModels.refresh(true)
            assertTrue(
                container.providerService.builtInModels
                    .current()
                    .models
                    .contains(BuiltInModel.MODEL_ID),
            )
            withTimeout(10000) {
                container.providerService.rows.first { rows ->
                    rows.any { it.id == BuiltInModel.PROVIDER_ID && BuiltInModel.MODEL_ID in it.conversationModels }
                }
            }
            val events = provider.stream(request).toList()
            assertFalse("Model returned an error", events.any { it is ModelEvent.Error })
            assertTrue(events.any { it is ModelEvent.Completed })
            val visible = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }.trim()
            assertEquals("OK", visible)
            evidence.writeText("login=passed\ncatalog=passed\nvisibleReply=OK\ncompletion=passed\n")
            pidFile.writeText(Process.myPid().toString())
            Process.killProcess(Process.myPid())
            error("Process kill did not terminate setup")
        } finally {
            account.logout()
        }
    }

    private suspend fun verify(container: AppContainer) {
        var refreshed: AccountTokens? = null
        val provider = container.providerService.modelProviderFor(BuiltInModel.PROVIDER_ID)
        try {
            assertTrue("Owned recovery must use a new process", pidFile.readText().toInt() != Process.myPid())
            assertTrue("Encrypted account must restore before container admission", account.signedIn.value)
            assertTrue(
                container.providerService.builtInModels
                    .current()
                    .models
                    .contains(BuiltInModel.MODEL_ID),
            )
            val before = requireNotNull(account.authorization(expectedRevision = account.revision))
            val after = requireNotNull(account.authorization(before.access, account.revision))
            refreshed = after
            assertTrue("Rotating refresh must replace access token", before.access != after.access)
            assertTrue(provider.listModels() is ModelCatalogResult.Listed)
            evidence.appendText("processRestartRestore=passed\nrefresh=passed\npostRefreshCatalog=passed\n")
            if (args.getString("accountSpeechAcceptance") == "true") {
                CloudSpeechDeviceTest().explicitHttpProfileSynthesizesAndUploadsGeneratedAudio()
            }
            if (args.getString("accountWsAcceptance") == "true") {
                com.helix.app.voice.WebSocketSpeechDeviceCheck.run(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    account,
                )
            }
        } finally {
            account.logout()
        }
        assertFalse(account.signedIn.value)
        assertFalse(container.providerService.chatSelectable(BuiltInModel.PROVIDER_ID))
        assertTrue(
            container.providerService.builtInModels
                .current()
                .models
                .isEmpty(),
        )
        assertTrue(provider.listModels() is ModelCatalogResult.Failed)
        val late = provider.stream(request).toList()
        assertFalse(late.any { it is ModelEvent.TextDelta || it is ModelEvent.Completed })
        assertTrue(late.any { it is ModelEvent.Error })
        verifyRevoked(requireNotNull(refreshed).refresh)
        evidence.appendText(
            "logout=passed\ncachedDirectoryHidden=passed\nmodelPickerRowsHidden=passed\nboundRequestRejected=passed\n",
        )
    }

    private suspend fun verifyRevoked(refresh: String) {
        val response =
            OkHttpWireClient(followRedirects = false).open(
                WireRequest(
                    "POST",
                    "${OfficialServices.ACCOUNT_BASE_URL}/auth/refresh",
                    mapOf("Content-Type" to "application/json", "X-Client-Id" to BuiltInModel.CLIENT_ID),
                    buildJsonObject { put("refresh_token", refresh) }.toString().toByteArray(),
                ),
            )
        try {
            assertEquals(401, response.status)
        } finally {
            response.body.close()
        }
        evidence.appendText("postLogoutRefresh401=passed\n")
    }
}
