package com.helix.app

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore

/**
 * HXA-069: pins the app's UI language to a DETERMINISTIC value (简体中文, the primary/base
 * language) for the entire instrumented run, so the pre-existing UI device tests — which assert
 * the app's canonical Chinese-first copy — stay deterministic regardless of the emulator's system
 * locale. Before HXA-069 there was no `values-en`, so "跟随系统" (the default choice) fell back to
 * the base (Chinese) copy on every device; now an English-locale emulator resolves `values-en`,
 * which would break every test asserting the base copy. Pinning once here keeps those tests
 * meaningful (they verify the UX, not the locale) and independent of the device.
 *
 * The per-app language SWITCHING is exercised explicitly by AppLanguageDeviceTest, which sets its
 * own choice per test and restores [AppLanguage.ZH_CN] in its `@After` — preserving this pin for
 * any test class that runs afterwards. The pin is fail-closed: a write error never aborts the run.
 *
 * The pin PERSISTS ONLY the choice record ([AppLanguageStore.persistChoiceOnly]); it deliberately
 * does NOT perform [AppLanguageStore.applyChoice]'s API 33+ `LocaleManager.setApplicationLocales`
 * push. The app renders the persisted choice through the synchronous `wrapForLocale` in
 * [HelixApplication]/[MainActivity] `attachBaseContext`, so no system locale change is needed. The
 * push is async — the system delivers it as a configuration change at an UNPREDICTABLE moment, and
 * in this milestone it fired mid-test, reverting the app's locale to the device default and
 * destroying the activity under test (HXA-069 device flake). Persisting only the record keeps the UI
 * deterministic AND the activity stable for the whole run.
 */
class HelixAndroidJUnitRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        runCatching {
            // Persist-only on purpose (see class doc): the synchronous attachBaseContext wrap renders
            // the choice, so the async setApplicationLocales push is skipped to keep the activity
            // stable — that push fired mid-test and destroyed the activity under test (HXA-069 flake).
            AppLanguageStore.persistChoiceOnly(getTargetContext().applicationContext, AppLanguage.ZH_CN)
        }
    }
}
