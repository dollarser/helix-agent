package com.helix.app.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore

/**
 * HXA-069 (test-only, present in the `debug` build type only): a [ComponentActivity] that applies
 * the app's zh-CN UI language in [attachBaseContext] — the SAME wrap [HelixApplication] and
 * [MainActivity] apply ([AppLanguageStore.wrapForLocale]).
 *
 * Why a whole activity: a Material3 dialog (AlertDialog) renders its content in the HOST activity's
 * window, and a `CompositionLocalProvider(LocalContext provides ...)` override does NOT reach that
 * window — the dialog composes in the host context's locale. On API 33+ the runner's zh-CN pin is
 * pushed to the system per-app locale (so the host activity is already zh-CN), but API 29 has no
 * per-app locale, so a bare headless `createComposeRule()` activity stays en-US and the dialog
 * renders English. Giving a locale-sensitive dialog fixture its OWN activity that re-wraps in
 * attachBaseContext makes it render the pinned (zh-CN) copy on EVERY API level, independent of the
 * device locale.
 *
 * The composable under test is supplied by the test via [content] before the rule launches this
 * activity (the no-op default keeps the activity launchable on its own).
 */
class LocalizedComposeActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            AppLanguageStore.wrapForLocale(
                newBase,
                AppLanguageStore.localeListFor(AppLanguage.ZH_CN),
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            content()
        }
    }

    companion object {
        // Test-only seam: the fixture composable, set by the test before the rule launches this
        // activity. Public + mutable because androidTest reaches it across source sets; it is a
        // dev-only debug-class hook with no production surface.
        @Suppress("MutableVisibilityModifier")
        var content: @Composable () -> Unit = {}
    }
}
