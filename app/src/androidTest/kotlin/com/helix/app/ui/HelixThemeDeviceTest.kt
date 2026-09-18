package com.helix.app.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.R
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.pow

/**
 * HXA-191 dark-theme gate — a NAMED device test for the unified, system-following light/dark
 * theme, its system bars, and the ACTUAL rendered Compose colors.
 *
 * Both sides of the theme must track the real system night mode, and each is pinned here:
 *  - THE COMPOSE SIDE: material3 1.4's `MaterialTheme` composable does NOT read the system night
 *    mode — a bare `MaterialTheme {}` resolves to the static `LocalColorScheme` default, which is
 *    `lightColorScheme()`, i.e. a FIXED light scheme (verified against the material3 1.4.0 bytes:
 *    no material3 class references `isSystemInDarkTheme` / `uiMode`). The app therefore wraps both
 *    composition roots (the first-launch notice and the main shell) in [HelixTheme], which selects
 *    the scheme EXPLICITLY from `isSystemInDarkTheme()`. `composeSurfaceColorMatchesCurrentSystemMode`
 *    proves this on-device by sampling the real rendered pixels: the full-bleed, opaque Compose
 *    Scaffold surface must be dark in night mode and light in day mode. Because that surface is
 *    opaque and covers the whole content area, the window background never shows through it — so
 *    this is a true Compose-color assertion, not a window read.
 *  - THE ANDROID WINDOW SIDE: the platform `Theme.Helix` (values) and its `values-night` variant
 *    make the status/navigation bars, the light-status-bar flag and the pre-Compose window
 *    background track the system night mode.
 *
 * The owning emulator script drives the REAL system night mode (`cmd uimode night yes/no`) and the
 * system font scale, then runs this class twice per mode with the two-phase restart protocol
 * (`recoveryPhase=setup` records the process identity and kills the process; `recoveryPhase=verify`
 * asserts a new process re-derives the theme). The window-side assertions read the RESOLVED theme
 * attributes of the running activity, so they are deterministic and independent of API-level
 * edge-to-edge enforcement — which on API 36 forces the composited status bar transparent and would
 * make a raw `statusBarColor` dumpsys read misleading. The owning script separately records the real
 * `dumpsys window` appearance as the on-device proof the window applied the flag.
 *
 * Facets: the mode contract (bars + flag + window background + WCAG contrast), the ACTUAL rendered
 * Compose surface color, activity rebuild (rotation equivalent on the portrait-locked AVD), a real
 * process restart, operability across the main / files / tasks / authorization / browser / settings
 * destinations, and stability across the three app languages.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions", "LongMethod") // one method per facet; LongMethod = resolved-attribute contract
class HelixThemeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val appContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @After
    fun restorePinnedLanguage() {
        // Method 5 switches the persisted language; restore the runner's deterministic ZH_CN pin
        // (record only — no async system push) so any later class sees the same language.
        AppLanguageStore.persistChoiceOnly(appContext, AppLanguage.ZH_CN)
    }

    // ---------- the mode contract ----------

    @Test
    fun systemBarsAndThemeFollowCurrentSystemMode() {
        assertThemeMatchesSystemMode()
    }

    @Test
    fun themeSurvivesActivityRebuild() {
        assertThemeMatchesSystemMode()
        // The rotation equivalent on the portrait-locked AVD: the activity rebuild path.
        rebuild()
        rebuild()
        assertThemeMatchesSystemMode()
    }

    @Test
    fun composeSurfaceColorMatchesCurrentSystemMode() {
        assumeTrue(recoveryPhase() != "setup")
        compose.resetDeterministicUiState()
        compose.waitForIdle()
        val night = currentSystemIsNight()
        // Two consecutive identical frames so we never assert on a mid-animation frame.
        val bitmap = stableScreenshot()
        try {
            // The central content band is the full-bleed, OPAQUE Compose Scaffold surface: the
            // window background never shows through it, so these pixels ARE the Compose colors.
            val median = medianLuminance(bitmap, x0 = 0.15f, x1 = 0.85f, y0 = 0.35f, y1 = 0.65f)
            if (night) {
                assertTrue(
                    "Compose surface must render DARK in night mode (median luminance $median)",
                    median < 0.4,
                )
            } else {
                assertTrue(
                    "Compose surface must render LIGHT in day mode (median luminance $median)",
                    median > 0.6,
                )
            }
            saveSurfaceEvidence(bitmap, if (night) "night" else "day")
        } finally {
            bitmap.recycle()
        }
    }

    // ---------- real process restart ----------

    @Test
    fun themeRestoredAfterRealProcessRestart() {
        val phase = recoveryPhase()
        if (phase == "verify") {
            val markerPid =
                appContext.noBackupFilesDir
                    .resolve(PID_MARKER)
                    .readText()
                    .toInt()
            assertNotEquals("the theme must be re-derived in a new process", markerPid, Process.myPid())
            // A fresh process re-reads the night theme from resources; it must not carry a stale
            // light theme (or vice versa) from the killed process.
            assertThemeMatchesSystemMode()
            return
        }
        appContext.noBackupFilesDir.resolve(PID_MARKER).writeText(Process.myPid().toString())
        if (phase == "setup") {
            Process.killProcess(Process.myPid())
            error("the theme recovery setup kill must end this process")
        }
        // Ordinary (no phase) run: only record the identity so a later verify could check it.
    }

    // ---------- operable across the required destinations ----------

    @Test
    fun shellDestinationsOperableInCurrentMode() {
        assumeTrue(recoveryPhase() != "setup")
        compose.resetDeterministicUiState()
        for (route in listOf("files", "tasks", "permissions", "browser", "settings")) {
            compose.navigateTo(route)
            assertPresent("screen-$route")
            // The mode contract still holds while the shell sits on each destination.
            assertThemeMatchesSystemMode()
        }
    }

    // ---------- three app languages ----------

    @Test
    fun themeStableAcrossAppLanguages() {
        assumeTrue(recoveryPhase() != "setup")
        compose.resetDeterministicUiState()
        // Two deterministic app languages: the recreated activity must render the new locale AND
        // keep the theme contract (the theme is orthogonal to locale).
        applyAndRecreate(AppLanguage.EN, "Settings")
        assertThemeMatchesSystemMode()
        applyAndRecreate(AppLanguage.ZH_CN, "设置")
        assertThemeMatchesSystemMode()
        // SYSTEM follows the emulator locale, so assert only the (locale-independent) theme
        // contract, not a fixed title. persistChoiceOnly avoids the async setApplicationLocales
        // push that flaked the HXA-069 picker mid-test.
        AppLanguageStore.persistChoiceOnly(appContext, AppLanguage.SYSTEM)
        compose.runOnUiThread { compose.activity.recreate() }
        compose.waitForIdle()
        assertThemeMatchesSystemMode()
    }

    // ---------- helpers ----------

    /** True when the running activity's configuration reports the real system night mode. */
    private fun currentSystemIsNight(): Boolean {
        val uiMode = compose.activity.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Captures the real composited display (`UiDevice.takeScreenshot`) until two consecutive frames
     * are identical, so a mid-transition / mid-animation frame is never asserted on.
     */
    private fun stableScreenshot(): Bitmap {
        var last =
            requireNotNull(
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(),
            )
        repeat(9) {
            val frame =
                requireNotNull(
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(),
                )
            if (frame.sameAs(last)) {
                last.recycle()
                return frame
            }
            last.recycle()
            last = frame
        }
        return last
    }

    /**
     * Perceived brightness (0 = black, 1 = white) of the pixels in the [x0,x1]×[y0,y1] fraction of
     * [bitmap], sampled on a fixed grid so the work stays bounded at any device resolution. Returns
     * the MEDIAN, which is robust to the minority of foreground text/icon pixels drawn on the
     * surface.
     */
    private fun medianLuminance(
        bitmap: Bitmap,
        x0: Float,
        x1: Float,
        y0: Float,
        y1: Float,
    ): Double {
        val left = (x0 * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val right = (x1 * bitmap.width).toInt().coerceIn(left + 1, bitmap.width - 1)
        val top = (y0 * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = (y1 * bitmap.height).toInt().coerceIn(top + 1, bitmap.height - 1)
        val cols = 24
        val rows = 32
        val values = ArrayList<Double>(cols * rows)
        for (row in 0 until rows) {
            val y = top + (bottom - top) * row / rows
            for (col in 0 until cols) {
                val x = left + (right - left) * col / cols
                values.add(luminance(bitmap.getPixel(x, y)))
            }
        }
        values.sort()
        return values[values.size / 2]
    }

    /** Persists the exact asserted frame so the on-device Compose color is inspectable outside. */
    private fun saveSurfaceEvidence(
        bitmap: Bitmap,
        mode: String,
    ) {
        File(compose.activity.cacheDir, "hxa191-theme")
            .apply { mkdirs() }
            .resolve("compose-surface-$mode.png")
            .outputStream()
            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertPresent(tag: String) {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun rebuild() {
        compose.runOnUiThread { compose.activity.recreate() }
        compose.waitForIdle()
    }

    private fun applyAndRecreate(
        choice: AppLanguage,
        expectedTitle: String,
    ) {
        AppLanguageStore.persistChoiceOnly(appContext, choice)
        compose.runOnUiThread { compose.activity.recreate() }
        compose.waitUntil(15_000) {
            runCatching {
                !compose.activity.isFinishing &&
                    compose.activity.getString(R.string.settings_title) == expectedTitle
            }.getOrDefault(false)
        }
    }

    private fun recoveryPhase(): String? = InstrumentationRegistry.getArguments().getString(RECOVERY_PHASE_KEY)

    /** The resolved platform-theme attributes must track the real system night mode. */
    private fun assertThemeMatchesSystemMode() {
        val ctx = compose.activity
        val night =
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val bars =
            ctx.theme.obtainStyledAttributes(
                intArrayOf(
                    android.R.attr.statusBarColor,
                    android.R.attr.navigationBarColor,
                    android.R.attr.windowBackground,
                ),
            )
        val lightTa = ctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowLightStatusBar))
        val textColorTa = ctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        try {
            val status = bars.getColor(0, 0)
            val nav = bars.getColor(1, 0)
            val windowBg = bars.getColor(2, 0)
            val lightStatus = lightTa.getBoolean(0, false)
            val textColor = textColorTa.getColor(0, 0)
            if (night) {
                assertTrue(
                    "status bar must be dark in night mode (0x${status.toString(16)})",
                    luminance(status) < 0.5,
                )
                assertTrue(
                    "nav bar must be dark in night mode (0x${nav.toString(16)})",
                    luminance(nav) < 0.5,
                )
                assertTrue(
                    "window background must be dark in night mode (0x${windowBg.toString(16)})",
                    luminance(windowBg) < 0.5,
                )
                assertFalse("windowLightStatusBar must be false (light icons) in night mode", lightStatus)
            } else {
                assertTrue(
                    "status bar must be light in day mode (0x${status.toString(16)})",
                    luminance(status) > 0.5,
                )
                assertTrue(
                    "nav bar must be light in day mode (0x${nav.toString(16)})",
                    luminance(nav) > 0.5,
                )
                assertTrue(
                    "window background must be light in day mode (0x${windowBg.toString(16)})",
                    luminance(windowBg) > 0.5,
                )
                assertTrue("windowLightStatusBar must be true (dark icons) in day mode", lightStatus)
            }
            // Contrast: the window background vs the primary text must stay legible (WCAG AA 4.5).
            assertTrue(
                "window/text contrast ${contrast(windowBg, textColor)} below AA 4.5",
                contrast(windowBg, textColor) >= 4.5,
            )
        } finally {
            bars.recycle()
            lightTa.recycle()
            textColorTa.recycle()
        }
    }

    /** Simple perceived brightness in 0..1, used to classify a resolved color as dark or light. */
    private fun luminance(color: Int): Double {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    }

    /** WCAG contrast ratio between two sRGB colors (range 1..21). */
    private fun contrast(
        a: Int,
        b: Int,
    ): Double {
        fun lum(c: Int): Double {
            fun chan(v: Int) =
                (v / 255.0).let {
                    if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
                }
            return 0.2126 * chan((c shr 16) and 0xFF) +
                0.7152 * chan((c shr 8) and 0xFF) +
                0.0722 * chan(c and 0xFF)
        }
        val hi = maxOf(lum(a), lum(b))
        val lo = minOf(lum(a), lum(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    companion object {
        private const val RECOVERY_PHASE_KEY = "recoveryPhase"
        private const val PID_MARKER = "theme-recovery-pid"
    }
}
