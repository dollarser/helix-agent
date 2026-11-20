package com.helix.app.account

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit, one-shot test login. Never logs credentials or calls logout, including on failure. */
class ExistingAccountLoginDeviceTest {
    @Test fun loginOnceAndKeepSession() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val enabled = args.getString("existingAccountLoginApproved")
            assumeTrue("Explicit login authorization is absent", enabled != null)
            require(enabled == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "com.helix.agent.developer" && android.os.Build.HARDWARE == "ranchu")
            check(OfficialServices.ACCOUNT_BASE_URL == "https://accounts.hilex.me")
            val bridge = args.getString("accountCredentialBridge")
            require(bridge == "http://127.0.0.1:19091/one-shot-credentials")
            val result = File(context.cacheDir, "existing-account-login-result.txt")
            result.writeText("login=started;logout_requested=false\n")
            val account = (context.applicationContext as HelixApplication).account
            try {
                account.restore()
                check(account.status.value == AccountStatus.SIGNED_OUT) { "Existing login must not be replaced" }
                val credentials = credentials(requireNotNull(bridge))
                account.login(
                    credentials.getValue("email").jsonPrimitive.content,
                    credentials.getValue("password").jsonPrimitive.content,
                )
                check(account.status.value == AccountStatus.SIGNED_IN && account.signedIn.value)
                result.appendText("login=passed;session_retained=true\n")
            } catch (failure: Exception) {
                result.appendText("login=failed:${failure.javaClass.simpleName};logout_requested=false\n")
                throw AssertionError("Login failed; see sanitized login status, do not retry automatically")
            }
        }

    private suspend fun credentials(bridge: String): kotlinx.serialization.json.JsonObject {
        val response =
            OkHttpWireClient(followRedirects = false, maxBodyBytes = 8192)
                .open(WireRequest("GET", bridge, emptyMap(), null))
        val credentials =
            try {
                check(response.status == 200)
                Json.parseToJsonElement(response.body.bytes().toString(Charsets.UTF_8)).jsonObject
            } finally {
                response.body.close()
            }
        check(credentials.keys == setOf("email", "password"))
        return credentials
    }
}
