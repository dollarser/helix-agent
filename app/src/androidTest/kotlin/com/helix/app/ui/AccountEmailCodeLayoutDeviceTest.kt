package com.helix.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.account.AccountPasswordRecoveryClient
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountTokenStorage
import com.helix.app.account.AccountTokens
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class AccountEmailCodeLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val wait = mutableStateOf(0L)

    private fun show(
        width: Int,
        scale: Float = 1f,
        english: Boolean = false,
    ) {
        compose.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply { if (english) setLocale(Locale.ENGLISH) }
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalResources provides base.createConfigurationContext(config).resources,
                LocalDensity provides Density(LocalDensity.current.density, scale),
            ) {
                MaterialTheme {
                    Column(Modifier.width(width.dp)) {
                        AccountEmailCodeInputs("012345", {}, true, wait.value == 0L, wait.value, {})
                    }
                }
            }
        }
    }

    @Test fun recoveryPageUsesAlignedControlsAndCapturesSyntheticPreview() {
        val account =
            AccountSession(
                object : AccountTokenStorage {
                    override fun load(): AccountTokens? = null

                    override fun save(tokens: AccountTokens) = error("No credentials in layout test")

                    override fun clear() = error("No account changes in layout test")
                },
                object : WireClient {
                    override suspend fun open(request: WireRequest): WireResponse = error("No network in layout test")
                },
            )
        compose.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalResources provides base.createConfigurationContext(config).resources,
                LocalDensity provides Density(LocalDensity.current.density, 1f),
            ) {
                MaterialTheme {
                    AccountPasswordRecoveryForm(
                        AccountPasswordRecoveryClient(account),
                        {},
                        onSuccess = {},
                    )
                }
            }
        }
        compose.onNodeWithText("发送验证码").assertExists()
        assertInline()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "account-code-layout.png")
        file.outputStream().use {
            val saved =
                compose
                    .onNodeWithTag("account-recovery")
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            assertTrue(saved)
        }
    }

    @Test fun normalWidthKeepsInputAndSendOnSameRowDuringCooldown() {
        show(360)
        assertInline()
        compose.runOnIdle { wait.value = 60 }
        compose.onNodeWithTag("account-send-code").assertIsNotEnabled()
        assertInline()
    }

    private fun assertInline() {
        val field = compose.onNodeWithTag("account-email-code").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("account-send-code").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("account-code-row").fetchSemanticsNode().boundsInRoot
        assertTrue(field.right < button.left)
        assertEquals(field.bottom, button.bottom, 1f)
        assertEquals(row.right, button.right, 1f)
        assertTrue(field.width > button.width)
        assertTrue(button.height >= 48 * compose.density.density)
    }

    @Test fun narrowWidthRetainsFullWidthControlsWithoutOverlap() {
        show(240)
        assertStacked()
    }

    @Test fun largeTextFallsBackWithoutSqueezingCodeField() {
        show(360, 2f)
        compose.runOnIdle { wait.value = 60 }
        assertStacked()
    }

    @Test fun englishMaximumCooldownRemainsReadableAtLargeFont() {
        wait.value = 86400
        show(240, 2f, english = true)
        assertStacked()
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText("Resend in 86400 seconds", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertTrue(layouts.none { it.hasVisualOverflow })
    }

    private fun assertStacked() {
        val field = compose.onNodeWithTag("account-email-code").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("account-send-code").fetchSemanticsNode().boundsInRoot
        assertTrue(button.top >= field.bottom)
        assertEquals(field.width, button.width, 1f)
    }
}
