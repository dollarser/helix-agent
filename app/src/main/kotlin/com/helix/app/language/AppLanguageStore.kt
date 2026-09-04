package com.helix.app.language

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat

/**
 * The app's UI language choice (roadmap HXA-069). A closed three-value set: follow the system
 * default, Simplified Chinese, or English. Only the CHOICE is persisted (a stable,
 * locale-independent value in SharedPreferences); the actual locale is applied at context-attach
 * time via [androidx.core.content.ContextCompat.wrapForLocale] in [com.helix.app.HelixApplication]
 * and [com.helix.app.MainActivity] (see [effectiveLocaleList]).
 *
 * Source of truth:
 * - **API 29-32** — the app's own SharedPreferences is the only store (the system has no per-app
 *   language there), giving equivalent persistence across restart / process death / update.
 * - **API 33+** — the app's SharedPreferences remains the reliable reader, and the SYSTEM per-app
 *   language is kept in two-way sync through the `android.app.LocaleManager` system service —
 *   the PUBLIC per-app-locale API on API 33+, which works from any Context (unlike
 *   `AppCompatDelegate.setApplicationLocales`, which requires a live AppCompat delegate and so
 *   silently no-ops here where the activity is a plain `ComponentActivity`): [applyChoice] pushes
 *   the choice to the system (app -> system) and [effectiveLocaleList] adopts a system-side change
 *   back into the app record (system -> app).
 *
 * "Follow system" leaves NO stale override: the applied list is the adjusted device default and,
 * on API 33+, the system per-app override is cleared (empty list). Every read is fail-closed — any
 * error degrades to following the system default, so the app always starts in some valid language.
 */
enum class AppLanguage {
    SYSTEM,
    ZH_CN,
    EN,
}

/**
 * The persistence + API 33+ sync seam for the app UI language. A stateless object over a
 * [Context]; the pure mapping ([localeListFor]) and reconciliation logic are unit-testable.
 */
object AppLanguageStore {
    private const val PREFS_NAME = "com.helix.app.language"
    private const val KEY_CHOICE = "choice"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The last in-app choice (what the settings selector displays). On API 33+ [effectiveLocaleList]
     * has already adopted any externally-changed system language into this by the time any UI
     * renders.
     */
    fun stored(context: Context): AppLanguage = readRaw(context)

    private fun readRaw(context: Context): AppLanguage =
        runCatching {
            prefs(context)
                .getString(KEY_CHOICE, null)
                ?.let { name -> runCatching { AppLanguage.valueOf(name) }.getOrNull() }
                ?: AppLanguage.SYSTEM
        }.getOrDefault(AppLanguage.SYSTEM)

    /**
     * The locale list to apply at context-attach time. GENUINELY fail-closed: ANY error degrades
     * to following the system default ([LocaleListCompat.getAdjustedDefault]). This is load-bearing
     * — [com.helix.app.HelixApplication.attachBaseContext] runs in EVERY process, including the
     * isolated QuickJS execution process (ADR-0015), where SharedPreferences cannot be touched
     * (UserManager is unavailable there) and a bare read would otherwise crash the process before it
     * can even start. On API 33+ it first adopts a system-side language change.
     */
    fun effectiveLocaleList(base: Context): LocaleListCompat =
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) adoptSystemLanguage(base)
            localeListFor(readRaw(base))
        }.getOrDefault(LocaleListCompat.getAdjustedDefault())

    /**
     * Wraps [base] so resource lookups (XML string resources, Compose's `stringResource`,
     * notifications built from the application context, ...) resolve in [localeList]. Uses the
     * platform [Context.createConfigurationContext] (API 17+) with the configured [LocaleList]
     * (API 24+; minSdk 29) — the same primitive the AndroidX per-app-language layer applies
     * internally. Fail-closed: on any error the UNWRAPPED [base] is returned (the device default
     * locale), so a locale-wrap failure never prevents the app from starting.
     */
    fun wrapForLocale(
        base: Context,
        localeList: LocaleListCompat,
    ): Context =
        runCatching {
            val config = Configuration(base.resources.configuration)
            config.setLocales(localeList.unwrap() as LocaleList)
            base.createConfigurationContext(config)
        }.getOrDefault(base)

    /**
     * Persists an in-app choice. On API 33+ it ALSO pushes the choice to the system per-app
     * language store (the app -> system direction of the two-way sync, so the system "App
     * languages" page reflects it); a system-write failure is fail-closed — the app still honors
     * the choice from its own record on the next attach.
     */
    fun applyChoice(
        context: Context,
        choice: AppLanguage,
    ) {
        prefs(context).edit { putString(KEY_CHOICE, choice.name) }
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                val manager = context.getSystemService(Context.LOCALE_SERVICE) as? LocaleManager
                manager?.setApplicationLocales(systemLocaleFor(choice).unwrap() as LocaleList)
            }
        }
    }

    /**
     * Persists ONLY the in-app choice record (the SharedPreferences entry) WITHOUT pushing it to the
     * API 33+ system per-app locale. Used by the instrumented test runner to pin a deterministic UI
     * language: the app already renders the persisted choice through the SYNCHRONOUS [wrapForLocale]
     * in [com.helix.app.HelixApplication]/[com.helix.app.MainActivity] `attachBaseContext`, so the
     * async `LocaleManager.setApplicationLocales` push that [applyChoice] performs is unnecessary for
     * a pin — and it is exactly what destabilizes device tests, because the system delivers that push
     * as a configuration change at an UNPREDICTABLE moment (HXA-069: it fired mid-test, reverting the
     * app's locale to the device default and destroying the activity under test). The real user-facing
     * switch ([com.helix.app.ui.SettingsScreen]) and the two-way-sync test ([AppLanguageDeviceTest])
     * keep using [applyChoice], which does push.
     */
    fun persistChoiceOnly(
        context: Context,
        choice: AppLanguage,
    ) {
        prefs(context).edit { putString(KEY_CHOICE, choice.name) }
    }

    /**
     * The locale list for a fixed choice. SYSTEM = follow the device default (the adjusted
     * default list, which prefers the device's configured languages).
     */
    fun localeListFor(choice: AppLanguage): LocaleListCompat =
        when (choice) {
            AppLanguage.SYSTEM -> LocaleListCompat.getAdjustedDefault()
            AppLanguage.ZH_CN -> LocaleListCompat.forLanguageTags("zh-CN")
            AppLanguage.EN -> LocaleListCompat.forLanguageTags("en")
        }

    /**
     * The concrete locale list pushed to the system per-app store on API 33+. "Follow system" is
     * the EMPTY list — it clears any system per-app override, leaving no stale value.
     */
    private fun systemLocaleFor(choice: AppLanguage): LocaleListCompat =
        when (choice) {
            AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            AppLanguage.ZH_CN -> LocaleListCompat.forLanguageTags("zh-CN")
            AppLanguage.EN -> LocaleListCompat.forLanguageTags("en")
        }

    /**
     * API 33+ two-way sync (the system -> app direction): if the user set a per-app language in the
     * system "App languages" page it is adopted back into the app's record so the selector and the
     * rendered content agree. An empty system list (the system is set to "follow system") is left
     * as-is — the app's own record then decides. Any read error is fail-closed (no adoption).
     */
    @Suppress("ReturnCount") // fail-closed: each null/error guard degrades to following the system language
    private fun adoptSystemLanguage(base: Context) {
        // Lint (NewApi/InlinedApi) needs the guard INSIDE this function to see it: the caller
        // already gates on SDK_INT >= 33, but an early-return here makes the API 33 access provably
        // safe for Lint's static check (the runtime call is unchanged).
        if (Build.VERSION.SDK_INT < 33) return
        val manager =
            runCatching { base.getSystemService(Context.LOCALE_SERVICE) as? LocaleManager }
                .getOrNull() ?: return
        val system = runCatching { LocaleListCompat.wrap(manager.getApplicationLocales()) }.getOrNull() ?: return
        if (system.isEmpty) return
        val tag = system.get(0)?.toLanguageTag() ?: return
        val choice =
            when {
                tag.startsWith("zh", ignoreCase = true) -> AppLanguage.ZH_CN
                tag.startsWith("en", ignoreCase = true) -> AppLanguage.EN
                else -> return
            }
        prefs(base).edit { putString(KEY_CHOICE, choice.name) }
    }
}
