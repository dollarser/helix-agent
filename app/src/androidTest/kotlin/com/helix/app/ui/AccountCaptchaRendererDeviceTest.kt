package com.helix.app.ui

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.account.AccountCaptchaAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic renderer callbacks only: no navigation, real challenge, or renderer termination. */
class AccountCaptchaRendererDeviceTest {
    @Test fun rendererExitRevokesProofAndDetachesDestroyedView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (crashed in listOf(true, false)) {
                val policy = AccountCaptchaPolicy(AccountCaptchaAction.PASSWORD_RESET)
                val events = mutableListOf<String>()
                val view =
                    object : WebView(instrumentation.targetContext) {
                        override fun destroy() {
                            events += "destroy"
                            super.destroy()
                        }
                    }
                val parent = FrameLayout(instrumentation.targetContext).apply { addView(view) }
                val message = """{"type":"captcha-completed","action":"password_reset_code","token":"synthetic"}"""
                val client =
                    captchaWebClient(
                        policy,
                        {
                            events += "failure"
                            assertNull(policy.receive(AccountCaptchaPolicy.ORIGIN, true, policy.url, message))
                        },
                        { events += "release" },
                    )
                val detail =
                    object : RenderProcessGoneDetail() {
                        override fun didCrash() = crashed

                        override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_BOUND
                    }
                assertTrue(client.onRenderProcessGone(view, detail))
                assertEquals(listOf("failure", "release", "destroy"), events)
                assertEquals(0, parent.childCount)
                assertNull(policy.receive(AccountCaptchaPolicy.ORIGIN, true, policy.url, message))
            }
        }
    }
}
