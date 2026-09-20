package com.helix.app.proot

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore
import com.helix.core.model.SafetyProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Prepares files only; the host starts the real terminal after instrumentation has exited. */
class TerminalHostJourneyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun prepareOrdinaryProcessJourney() =
        runBlocking {
            assumeTrue(
                "Requires the exclusive ordinary-process host runner",
                InstrumentationRegistry.getArguments().getString("terminalHostPhase") == "prepare",
            )
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val container = (context.applicationContext as HelixApplication).appContainer
            ensureInstalledRuntime(context)
            assertFalse(checkNotNull(container.manualTerminal).hasSession())
            container.firstLaunch.markSeen()
            container.profileStore.switchTo(SafetyProfile.ADVANCED)
            AppLanguageStore.applyChoice(context, AppLanguage.EN)
            val directory = File(context.filesDir, "workspaces/app/terminal-recovery")
            check(directory.mkdirs())
            File(directory, "recover.sh").writeText(
                """
                export RECOVERY_VALUE=original
                printf 1 >> starts
                echo ${'$'}${'$'} > shell.pid
                printf running > phase
                sleep 12
                printf done > phase
                """.trimIndent() + "\n",
            )
        }
}
