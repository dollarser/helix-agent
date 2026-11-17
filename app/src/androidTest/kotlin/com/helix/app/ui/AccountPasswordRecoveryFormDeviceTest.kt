package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import com.helix.app.account.AccountPasswordRecoveryClient
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountTokenStorage
import com.helix.app.account.AccountTokens
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic anonymous transport only. Never sends mail, resets a real account or changes installed credentials. */
class AccountPasswordRecoveryFormDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun validatesConfirmationAndSubmitsExactAnonymousFieldsThenClearsSecrets() {
        val calls = AtomicInteger()
        val success = AtomicInteger()
        val account = syntheticAccount(calls)
        compose.setContent {
            MaterialTheme {
                AccountPasswordRecoveryForm(AccountPasswordRecoveryClient(account), onBack = {}) {
                    success.incrementAndGet()
                }
            }
        }
        compose.onNodeWithTag("account-reset-email").performTextReplacement("OWNER@example.test")
        compose.onNodeWithTag("account-reset-password").performScrollTo().performTextReplacement(" secret ")
        compose.onNodeWithTag("account-reset-confirm").performScrollTo().performTextReplacement("different")
        compose.onNodeWithTag("account-email-code").performScrollTo().performTextReplacement("012345")
        compose.onNodeWithTag("account-reset-submit").performScrollTo().assertIsNotEnabled()
        assertEquals(0, calls.get())
        compose.onNodeWithTag("account-reset-confirm").performScrollTo().performTextReplacement(" secret ")
        compose
            .onNodeWithTag("account-reset-submit")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(5000) { success.get() == 1 }
        assertEquals(1, calls.get())
        assertFalse(account.signedIn.value)
        compose.onNodeWithTag("account-reset-submit").assertIsNotEnabled()
        // Empty editable values after success; labels are separate semantics content.
        compose
            .onNodeWithTag(
                "account-reset-password",
            ).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose
            .onNodeWithTag(
                "account-reset-confirm",
            ).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose
            .onNodeWithTag(
                "account-email-code",
            ).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    private fun syntheticAccount(calls: AtomicInteger): AccountSession =
        AccountSession(
            object : AccountTokenStorage {
                override fun load(): AccountTokens? = null

                override fun save(tokens: AccountTokens) = error("Must not login")

                override fun clear() = Unit
            },
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse {
                    calls.incrementAndGet()
                    assertEquals("https://accounts.hilex.me/auth/password-reset", request.url)
                    assertEquals(setOf("Content-Type"), request.headers.keys)
                    val body = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
                    assertEquals("owner@example.test", body.getValue("email").jsonPrimitive.content)
                    assertEquals("012345", body.getValue("code").jsonPrimitive.content)
                    assertEquals(" secret ", body.getValue("new_password").jsonPrimitive.content)
                    assertFalse(body.getValue("revoke_api_keys").jsonPrimitive.isString)
                    assertEquals("false", body.getValue("revoke_api_keys").jsonPrimitive.content)
                    return WireResponse(
                        200,
                        emptyMap(),
                        object : WireBody {
                            override suspend fun bytes() = """{"ok":true,"api_keys_revoked":false}""".toByteArray()

                            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                                onChunk(bytes())
                            }

                            override fun close() = Unit
                        },
                    )
                }
            },
        )
}
