package com.helix.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic form fixtures only: no real account, credentials, Keystore writes, or external requests. */
class AccountLoginFormDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun form(
        loading: Boolean = false,
        submit: (String, String, Boolean) -> Unit = { _, _, _ -> },
    ) {
        compose.setContent {
            val localized =
                com.helix.app.language.AppLanguageStore.wrapForLocale(
                    LocalContext.current,
                    LocaleListCompat.forLanguageTags("zh-CN"),
                )
            CompositionLocalProvider(LocalContext provides localized) {
                MaterialTheme {
                    Surface {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .verticalScroll(rememberScrollState())
                                .padding(24.dp),
                        ) {
                            AccountLoginForm(loading, submit)
                        }
                    }
                }
            }
        }
    }

    @Test fun emptyAndInvalidEmailCannotSubmit() {
        form()
        compose.onNodeWithTag("account-submit").assertIsNotEnabled()
        compose.onNodeWithTag("account-email").performTextReplacement("invalid")
        compose.onNodeWithTag("account-password").performTextReplacement("synthetic-password")
        compose.onNodeWithTag("account-submit").assertIsNotEnabled()
        compose.onNodeWithTag("account-email").performTextReplacement("test@example.test")
        compose.onNodeWithTag("account-submit").assertIsEnabled()
    }

    @Test fun registrationRequiresMatchingConfirmationAndExplicitSubmit() {
        var submitted = false
        form { _, _, registration -> submitted = registration }
        compose.onNodeWithTag("account-switch-mode").performClick()
        compose.onNodeWithTag("account-confirm-password").assertExists()
        compose.onNodeWithTag("account-email").performTextReplacement("test@example.test")
        compose.onNodeWithTag("account-password").performTextReplacement("synthetic-password")
        compose.onNodeWithTag("account-confirm-password").performTextReplacement("different")
        compose.onNodeWithTag("account-submit").assertIsNotEnabled()
        compose.onNodeWithTag("account-confirm-password").performTextReplacement("synthetic-password")
        assertTrue(!submitted)
        compose.onNodeWithTag("account-submit").performScrollTo().performClick()
        assertTrue(submitted)
    }

    @Test fun pendingSubmissionDisablesAllAccountMutations() {
        val loading = mutableStateOf(false)
        var submissions = 0
        compose.setContent {
            MaterialTheme {
                AccountLoginForm(loading.value) { _, _, _ ->
                    submissions++
                    loading.value = true
                }
            }
        }
        compose.onNodeWithTag("account-email").performTextReplacement("test@example.test")
        compose.onNodeWithTag("account-password").performTextReplacement("synthetic-password")
        compose.onNodeWithTag("account-submit").performClick()
        compose.onNodeWithTag("account-submit").assertIsNotEnabled()
        compose.onNodeWithTag("account-switch-mode").assertIsNotEnabled()
        compose.onNodeWithTag("account-email").assertIsNotEnabled()
        compose.onNodeWithTag("account-password-visibility").assertIsNotEnabled()
        assertEquals(1, submissions)
    }

    @Test fun cleanLoginFormHasReadableLayoutAndNoUnsupportedModes() {
        form()
        compose.onNodeWithTag("account-password-visibility").performClick()
        compose.onNodeWithTag("account-submit").assertExists()
        compose.waitForIdle()
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.cacheDir, "account-login-form.png").outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            screenshot.recycle()
        }
    }
}
