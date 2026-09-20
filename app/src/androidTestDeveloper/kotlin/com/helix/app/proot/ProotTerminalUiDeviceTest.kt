package com.helix.app.proot

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore
import com.helix.app.terminal.ManualTerminalActivity
import com.helix.core.model.SafetyProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real production page, InputConnection, renderer and private PRoot; no synthetic terminal text. */
class ProotTerminalUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as HelixApplication).appContainer

    @Test
    @Suppress("LongMethod")
    fun chineseInputReplAndRotationPreserveTheOriginalShell() {
        ensureInstalledRuntime(context)
        val previous = container.profileStore.profile
        val previousLanguage = AppLanguageStore.stored(context)
        val locale = InstrumentationRegistry.getArguments().getString("terminalLocale", "en")
        require(locale in setOf("en", "zh"))
        val language = if (locale == "zh") AppLanguage.ZH_CN else AppLanguage.EN
        AppLanguageStore.applyChoice(context, language)
        val terminal = checkNotNull(container.manualTerminal)
        val relative = "terminal-ui-${UUID.randomUUID()}"
        val workspace = File(context.filesDir, "workspaces/app/$relative").apply { check(mkdirs()) }
        container.profileStore.switchTo(SafetyProfile.ADVANCED)
        val intent =
            Intent(
                context,
                ManualTerminalActivity::class.java,
            ).putExtra(ManualTerminalActivity.DIRECTORY, relative)
        try {
            ActivityScenario.launch<ManualTerminalActivity>(intent).use { scenario ->
                awaitNode("terminal-start")
                compose.onNodeWithTag("terminal-start").performClick()
                awaitNode("terminal-viewport")
                awaitPhase("RUNNING")
                compose.onNodeWithTag("terminal-workspace").assertTextContains(relative, substring = true)
                send(
                    scenario,
                    "export KEEP=preserved; printf '中文' > chinese.txt; printf '\\033[32mHELIX_中文_READY\\033[0m\\n'\n",
                )
                awaitFile(workspace, "chinese.txt", "中文")
                visibleGlyphs("terminal-shell.png")
                InstrumentationRegistry
                    .getInstrumentation()
                    .uiAutomation
                    .executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
                    .close()
                compose.onNodeWithTag("terminal-keyboard").performClick()
                compose.waitUntil(10_000) {
                    var visible = false
                    scenario.onActivity {
                        visible = ViewCompat
                            .getRootWindowInsets(it.window.decorView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                    visible
                }
                visibleGlyphs("terminal-keyboard.png")
                compose.onNodeWithTag("terminal-keyboard").performClick()
                send(scenario, "python3 -q\n")
                send(scenario, "value = 40\n")
                send(scenario, "open('repl.txt', 'w').write(str(value + 2))\n")
                awaitFile(workspace, "repl.txt", "42")
                compose.onNodeWithTag("terminal-key-4").performClick()
                send(scenario, "printf '\\033[32mREPL_COMPLETE\\033[0m\\n'\n")
                scenario.recreate()
                awaitNode("terminal-viewport")
                send(scenario, "printf \"%s\" \"\$KEEP\" > rotated.txt; printf '\\033[32mROTATED_保留\\033[0m\\n'\n")
                awaitFile(workspace, "rotated.txt", "preserved")
                visibleGlyphs("terminal-rotated.png")
                compose.onNodeWithTag("terminal-stop").performClick()
                runBlocking {
                    withTimeout(15_000) { while (!terminal.query().canSettle) delay(50) }
                }
                awaitPhase("STOPPED")
                compose.waitForIdle()
                compose.onNodeWithTag("terminal-settle").performClick()
                compose.waitUntil(10_000) { runBlocking { !terminal.hasSession() } }
            }
        } finally {
            runBlocking {
                if (terminal.hasSession()) {
                    terminal.stop()
                    withTimeout(15_000) { while (!terminal.query().canSettle) delay(50) }
                    terminal.settle()
                }
            }
            AppLanguageStore.applyChoice(context, previousLanguage)
            container.profileStore.switchTo(previous)
            workspace.deleteRecursively()
        }
    }

    private fun awaitPhase(phase: String) {
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(hasTestTag("terminal-state") and hasText(phase, substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun awaitNode(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun send(
        scenario: ActivityScenario<ManualTerminalActivity>,
        text: String,
    ) {
        compose.waitForIdle()
        scenario.onActivity { activity ->
            val view = checkNotNull(findInput(activity.window.decorView))
            val input = checkNotNull(view.onCreateInputConnection(EditorInfo()))
            assertTrue(input.commitText(text, 1))
        }
    }

    private fun findInput(view: View): View? =
        when {
            view.javaClass.simpleName == "ImeInputView" -> view
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findInput(view.getChildAt(it)) }
            else -> null
        }

    private fun awaitFile(
        directory: File,
        name: String,
        expected: String,
    ) {
        val file = File(directory, name)
        compose.waitUntil(15_000) { file.exists() && file.readText() == expected }
        assertEquals(expected, file.readText())
    }

    private fun visibleGlyphs(name: String) {
        compose.waitUntil(10_000) {
            val pixels = compose.onNodeWithTag("terminal-viewport").captureToImage().toPixelMap()
            var green = 0
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val color = pixels[x, y]
                    if (color.green > 0.3 && color.green > color.red * 1.5 && color.green > color.blue * 1.5) green++
                }
            }
            green > 40
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var bitmap = automation.takeScreenshot()
        var stableFrames = 0
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (stableFrames < 3 && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
            val next = automation.takeScreenshot()
            stableFrames = if (next.sameAs(bitmap)) stableFrames + 1 else 0
            bitmap.recycle()
            bitmap = next
        }
        assertTrue("Terminal screenshot did not stabilize", stableFrames >= 3)
        val target = File(context.cacheDir, "terminal-ui/$name").apply { parentFile!!.mkdirs() }
        target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
