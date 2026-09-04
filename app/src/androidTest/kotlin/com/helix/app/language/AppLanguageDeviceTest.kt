package com.helix.app.language

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.R
import com.helix.app.ui.dismissFirstLaunchIfNeeded
import com.helix.app.ui.navigateTo
import com.helix.core.model.ModelErrorCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-069 device gate: the app UI language (跟随系统 / 简体中文 / English) switches the app's
 * rendered locale on API 29 and API 36, survives activity recreation, two-way-syncs with the API
 * 33+ system per-app-language store, and — crucially — leaves the stable protocol/audit
 * identifiers (provider error codes, the persisted choice enum) untouched by the locale.
 *
 * The locale mechanism is deterministic (no network, no model), so the tests assert exact
 * localized resource values. `wrapForLocale` exercises the SAME `createConfigurationContext`
 * primitive that [com.helix.app.HelixApplication]/[com.helix.app.MainActivity] apply in
 * `attachBaseContext`, and the picker test drives the real user flow (applyChoice ->
 * activity.recreate()). Only the picker test needs the launched activity; the rest use the
 * application context directly.
 */
@RunWith(AndroidJUnit4::class)
class AppLanguageDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // The target app's application context — a valid base for explicit wrapForLocale calls.
    private val appContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @After
    fun restorePinnedLanguage() {
        // Restore the runner's deterministic pin (HelixAndroidJUnitRunner forces ZH_CN for the run)
        // so any test class running after this one sees the same language regardless of the device
        // system locale. On API 33+ this also re-pins the system per-app override to zh.
        AppLanguageStore.applyChoice(appContext, AppLanguage.ZH_CN)
    }

    @Test
    fun fixedChoicesMapToTheirBcp47Tags() {
        assertEquals("zh-CN", AppLanguageStore.localeListFor(AppLanguage.ZH_CN).toLanguageTags())
        assertEquals("en", AppLanguageStore.localeListFor(AppLanguage.EN).toLanguageTags())
    }

    @Test
    fun applyChoicePersistsAndTheStoredChoiceReflectsIt() {
        AppLanguageStore.applyChoice(appContext, AppLanguage.ZH_CN)
        assertEquals(AppLanguage.ZH_CN, AppLanguageStore.stored(appContext))
        AppLanguageStore.applyChoice(appContext, AppLanguage.EN)
        assertEquals(AppLanguage.EN, AppLanguageStore.stored(appContext))
    }

    @Test
    fun wrappedContextResolvesResourcesInTheChosenLanguage() {
        val zh = AppLanguageStore.wrapForLocale(appContext, AppLanguageStore.localeListFor(AppLanguage.ZH_CN))
        val en = AppLanguageStore.wrapForLocale(appContext, AppLanguageStore.localeListFor(AppLanguage.EN))
        assertEquals("设置", zh.getString(R.string.settings_title))
        assertEquals("Settings", en.getString(R.string.settings_title))
        assertNotEquals(zh.getString(R.string.chat_new_session), en.getString(R.string.chat_new_session))
    }

    @Test
    fun selectingEnglishInSettingsRecreatesTheActivityInEnglish() {
        composeRule.waitForIdle()
        composeRule.dismissFirstLaunchIfNeeded()
        composeRule.navigateTo("settings")
        composeRule.onNodeWithTag("settings-language-EN").performClick()
        // The picker persists EN and calls activity.recreate(); the recreated activity's context
        // is re-wrapped in English by attachBaseContext, so its lookups resolve English regardless
        // of the screen it lands on after the back-stack reset.
        composeRule.waitUntil(15_000) { composeRule.activity.getString(R.string.settings_title) == "Settings" }
    }

    @Test
    fun api33SyncsTheChoiceBothDirections() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val manager =
            checkNotNull(appContext.getSystemService(Context.LOCALE_SERVICE) as? LocaleManager) {
                "LocaleManager service is unavailable on API ${Build.VERSION.SDK_INT}"
            }
        // app -> system: applyChoice pushes the choice to the system per-app-locale store.
        AppLanguageStore.applyChoice(appContext, AppLanguage.ZH_CN)
        val systemAfter = manager.getApplicationLocales().toLanguageTags()
        assertTrue(
            "expected zh* after applyChoice(ZH_CN) but system store = [$systemAfter]",
            systemAfter.startsWith("zh"),
        )
        // system -> app: a system-side change is adopted back on the next effective-locale read.
        manager.setApplicationLocales(LocaleList.forLanguageTags("en"))
        val effectiveAfter = AppLanguageStore.effectiveLocaleList(appContext).toLanguageTags()
        assertEquals("en", effectiveAfter)
    }

    @Test
    fun stableAuditAndProviderIdentifiersAreLocaleIndependent() {
        // The provider/audit error codes and the persisted choice enum are fixed identifiers,
        // never locale text: switching the UI language must not change them.
        val expectedCodes = ModelErrorCode.entries.map { it.name }
        for (choice in AppLanguage.entries) {
            AppLanguageStore.applyChoice(appContext, choice)
            assertEquals(expectedCodes, ModelErrorCode.entries.map { it.name })
            assertEquals(listOf("SYSTEM", "ZH_CN", "EN"), AppLanguage.entries.map { it.name })
        }
    }
}
