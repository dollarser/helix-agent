package com.helix.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import java.io.File

/** Explicit layout runs capture actual activity pixels and verify the requested resource language. */
internal fun captureChatLayout(
    activity: MainActivity,
    stage: String,
) {
    val args = InstrumentationRegistry.getArguments()
    if (args.getString("helix.layout.capture") != "true") return
    val expected = requireNotNull(args.getString("helix.test.language")).substringBefore('-')
    check(
        activity.resources.configuration.locales[0]
            .language == expected,
    ) { "Activity language did not match fixture" }
    val bitmap = stableChatLayoutScreenshot()
    try {
        val directory = File(activity.cacheDir, "hxa147-layout").apply { mkdirs() }
        directory
            .resolve(
                "$stage.png",
            ).outputStream()
            .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally {
        bitmap.recycle()
    }
}

/** Exercise the production language picker, including API33+ system per-app locale synchronization. */
internal fun prepareChatLayoutLanguage(
    compose: AndroidComposeTestRule<*, *>,
    restore: Boolean = false,
) {
    val args = InstrumentationRegistry.getArguments()
    if (args.getString("helix.layout.capture") != "true") return
    val choice = if (restore) "zh-CN" else requireNotNull(args.getString("helix.test.language"))
    val language =
        when (choice) {
            "en" -> "EN"
            "zh-CN" -> "ZH_CN"
            else -> error("Unsupported layout language")
        }
    compose.navigateTo("settings")
    compose.onNodeWithTag("settings-language-$language").performScrollTo().performClick()
    compose.waitUntil(15_000) {
        compose.activity.resources.configuration.locales[0]
            .language == choice.substringBefore('-')
    }
}

internal fun verifyCompactChatSettings(compose: AndroidComposeTestRule<*, *>) {
    val config = compose.activity.resources.configuration
    if (config.screenHeightDp > 640 && config.fontScale < 1.3f) return
    compose.onNodeWithTag("chat-conversation-details").assertIsDisplayed().performClick()
    compose.onNodeWithTag("chat-budget-summary").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("chat-provider-summary").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("chat-conversation-details-close").assertIsDisplayed().performClick()
    compose.waitForIdle()
    compose.onNodeWithTag("chat-conversation-details-close").assertDoesNotExist()
}

/** Window-dismiss animations can outlive Compose semantics; capture only consecutive stable frames. */
private fun stableChatLayoutScreenshot(): Bitmap {
    var previous: Bitmap? = null
    repeat(10) {
        val current = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        if (previous?.sameAs(current) == true) {
            previous?.recycle()
            return current
        }
        previous?.recycle()
        previous = current
        Thread.sleep(100)
    }
    previous?.recycle()
    error("Layout screenshot did not settle")
}
