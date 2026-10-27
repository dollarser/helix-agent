package com.helix.app.proot

import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.terminal.ManualTerminal
import com.helix.app.terminal.ManualTerminalActivity
import com.helix.core.model.SafetyProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual PTYs and production page, exclusively in a new owner-authorized isolated package. */
class TerminalReclamationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as HelixApplication).appContainer

    @Test fun actualExitCloseCapacityAndReclamationPreserveWorkspace(): Unit =
        runBlocking {
            check(context.packageName.endsWith(".asrvalidation"))
            ensureInstalledRuntime(context)
            val terminal = checkNotNull(container.manualTerminal)
            check(terminal.sessions().isEmpty()) { "Fixture must not interrupt retained sessions" }
            container.profileStore.switchTo(SafetyProfile.ADVANCED)
            val relative = "reclaim-${UUID.randomUUID()}"
            val workspace = File(context.filesDir, "workspaces/app/terminal/$relative").apply { check(mkdirs()) }
            val created = mutableSetOf<String>()
            try {
                assertEquals(4, terminal.maxSessions)
                repeat(4) { created += terminal.start(relative, 120_000).sessionId }
                assertEquals(setOf(1L, 2L, 3L, 4L), terminal.sessions().map { it.displayNumber }.toSet())
                val first = terminal.sessions().single { it.displayNumber == 1L }
                val connection = terminal.attach(first.sessionId)
                try {
                    assertEquals("ACCEPTED", connection.write("printf RECLAIM_OK > keep.txt; exit\n".toByteArray()))
                    withTimeout(10_000) { while (!terminal.query(first.sessionId).canSettle) delay(25) }
                } finally {
                    connection.detach()
                }
                awaitReclaimed(terminal, first.sessionId)
                assertEquals("RECLAIM_OK", File(workspace, "keep.txt").readText())
                val replacement = terminal.start(relative, 120_000).also { created += it.sessionId }
                assertTrue(first.sessionId != replacement.sessionId)
                assertEquals(1L, terminal.sessions().single { it.sessionId == replacement.sessionId }.displayNumber)
                verifyCapacityPage(terminal, relative, created)
            } finally {
                terminal.sessions().filter { it.sessionId in created }.forEach {
                    terminal.stop(it.sessionId)
                    withTimeout(15_000) {
                        while (File(context.filesDir, "terminal-sessions/${it.sessionId}.pty").exists() &&
                            !terminal.query(it.sessionId).canSettle
                        ) {
                            delay(25)
                        }
                    }
                }
                created.forEach { awaitReclaimed(terminal, it) }
            }
            terminal.reclaim()
            assertTrue(terminal.sessions().isEmpty())
            assertTrue(File(context.filesDir, "execution-admission/terminals").listFiles().orEmpty().isEmpty())
            assertEquals("RECLAIM_OK", File(workspace, "keep.txt").readText())
            println(
                "MANUAL_RECLAIM slots=4 fifthRejectedOnOriginalPage=true exitReusedSlot=true workspacePreserved=true",
            )
            workspace.deleteRecursively()
        }

    @Test fun packageReplacementInterruptsOnlyOwnedShellsAndAllowsFreshWindows() =
        runBlocking {
            check(context.packageName.endsWith(".asrvalidation"))
            ensureInstalledRuntime(context)
            container.profileStore.switchTo(SafetyProfile.ADVANCED)
            val terminal = checkNotNull(container.manualTerminal)
            val state = File(context.cacheDir, "terminal-replacement-fixture")
            when (InstrumentationRegistry.getArguments().getString("reclaim.phase")) {
                "prepare" -> {
                    check(terminal.sessions().isEmpty())
                    val ids = List(2) { terminal.start(".", 120_000).sessionId }
                    ids.forEach { id ->
                        val connection = terminal.attach(id)
                        try {
                            assertEquals("ACCEPTED", connection.write("printf 'REPLACEMENT_READY\\n'\n".toByteArray()))
                            withTimeout(10_000) {
                                var cursor: String? = null
                                var output = ""
                                while (!output.contains("REPLACEMENT_READY")) {
                                    val chunk = connection.read(cursor)
                                    cursor = chunk.cursor
                                    output += chunk.bytes.toString(Charsets.UTF_8)
                                    delay(25)
                                }
                            }
                        } finally {
                            connection.detach()
                        }
                    }
                    state.writeText(ids.joinToString("\n"))
                    println("RECLAIM_REPLACEMENT_READY windows=2")
                    delay(120_000)
                    error("Expected host to replace this isolated package")
                }

                "recover" -> {
                    val ids = state.readLines()
                    assertEquals(2, ids.size)
                    assertTrue(terminal.sessions().none { it.sessionId in ids })
                    ids.forEach { awaitReclaimed(terminal, it) }
                    val fresh = terminal.start(".", 30_000)
                    assertTrue(fresh.sessionId !in ids)
                    assertEquals(1L, terminal.sessions().single().displayNumber)
                    terminal.stop(fresh.sessionId)
                    withTimeout(10_000) { while (!terminal.query(fresh.sessionId).canSettle) delay(25) }
                    awaitReclaimed(terminal, fresh.sessionId)
                    check(state.delete())
                    println("RECLAIM_REPLACEMENT_RECOVERED oldWindows=0 oldTmpAndJournal=0 freshSlot=1")
                }

                else -> {
                    error("Pass explicit reclaim.phase=prepare or recover")
                }
            }
        }

    private suspend fun verifyCapacityPage(
        terminal: ManualTerminal,
        relative: String,
        created: MutableSet<String>,
    ) {
        val intent =
            Intent(
                context,
                ManualTerminalActivity::class.java,
            ).putExtra(ManualTerminalActivity.DIRECTORY, relative)
        ActivityScenario.launch<ManualTerminalActivity>(intent).use {
            awaitNode("terminal-selector")
            val before = terminal.sessions().map { it.sessionId }.toSet()
            compose.onNodeWithTag("terminal-selector").performClick()
            compose.onNodeWithTag("terminal-new-session").performClick()
            awaitNode("terminal-capacity-notice")
            assertEquals(before, terminal.sessions().map { it.sessionId }.toSet())
            compose.onNodeWithTag("terminal-capacity-dismiss").performClick()
            compose.onNodeWithTag("terminal-settle").performClick()
            compose
                .onNode(
                    hasText(context.getString(com.helix.app.R.string.terminal_stop_close)) and hasClickAction(),
                ).performClick()
            withTimeout(15_000) { while (terminal.sessions().size != 3) delay(50) }
            awaitNode("terminal-selector")
            compose.onNodeWithTag("terminal-selector").performClick()
            compose.onNodeWithTag("terminal-new-session").performClick()
            withTimeout(15_000) { while (terminal.sessions().size != 4) delay(50) }
            created += terminal.sessions().map { it.sessionId }
            assertEquals(setOf(1L, 2L, 3L, 4L), terminal.sessions().map { it.displayNumber }.toSet())
        }
    }

    private suspend fun awaitReclaimed(
        terminal: ManualTerminal,
        id: String,
    ) = withTimeout(15_000) {
        val journal = File(context.filesDir, "terminal-sessions/$id.pty")
        terminal.reclaim()
        while (journal.exists() || File(journal.parentFile, "${journal.name}.reclaim").exists()) {
            terminal.reclaim()
            delay(50)
        }
        val runtime = File(context.filesDir, "runtime")
        runtime.listFiles().orEmpty().filter { it.name.startsWith("inst_") }.forEach {
            assertFalse(File(it, "tmp/pty-$id").exists())
        }
    }

    private fun awaitNode(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
