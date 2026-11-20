package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.account.AccountCaptchaAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** User opens the challenge; this fixture neither solves it nor submits or records a proof. */
class AccountCaptchaLayoutHandoffDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun userCanInspectChallengeAndReturnWithoutSendingEmail() {
        check(InstrumentationRegistry.getArguments().getString("captchaLayoutHandoff") == "true")
        val visible = mutableStateOf(true)
        val closed = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    AccountCaptchaDialog(AccountCaptchaAction.PASSWORD_RESET, {
                        closed.incrementAndGet()
                        visible.value = false
                    }, { /* A layout-only handoff does not consume a proof. */ })
                } else {
                    Text("Closed")
                }
            }
        }
        compose.waitUntil(10 * 60_000L) { !visible.value }
        assertEquals(1, closed.get())
    }
}
