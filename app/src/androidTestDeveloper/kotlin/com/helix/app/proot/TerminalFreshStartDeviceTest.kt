package com.helix.app.proot

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.core.model.SafetyProfile
import com.helix.runtime.proot.core.PtySessionOrigin
import com.helix.runtime.proot.core.PtySessionRecord
import com.helix.runtime.proot.core.PtySessionStore
import com.helix.runtime.proot.core.RuntimeExecutionCapacity
import com.helix.tools.framework.ExecutionOwnership
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Synthetic never-launched history plus a real new PTY. Never touches a daily package. */
class TerminalFreshStartDeviceTest {
    @Test fun moreUnknownRecordsThanPhysicalSlotsCannotBlockANewInteractiveShell() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName.endsWith(".asrvalidation"))
            ensureInstalledRuntime(context)
            ActivityScenario.launch(MainActivity::class.java).use {
                val container = (context.applicationContext as HelixApplication).appContainer
                val terminal = checkNotNull(container.manualTerminal)
                val previous = container.profileStore.profile
                val root = File(context.filesDir, "terminal-sessions")
                val store = PtySessionStore(root)
                val bindings = File(context.filesDir, "execution-admission/terminals")
                val synthetic = seedHistory(context.filesDir, store, bindings)
                var sessionId: String? = null
                try {
                    container.profileStore.switchTo(SafetyProfile.ADVANCED)
                    val started = terminal.start(".", 30_000)
                    sessionId = started.sessionId
                    assertEquals("RUNNING", started.phase)
                    verifyInteractiveShell(terminal, started.sessionId)
                    synthetic.forEach { (_, binding, record) ->
                        assertEquals(record, store.read(record.origin.sessionId))
                        assertTrue(binding.exists())
                    }
                } finally {
                    sessionId?.let { id ->
                        terminal.stop(id)
                        withTimeout(10_000) { while (!terminal.query(id).canSettle) delay(25) }
                        terminal.settle(id)
                    }
                    // Remove only this test's never-executed synthetic journal/binding files.
                    synthetic.forEach { (_, binding, record) ->
                        check(binding.delete())
                        check(File(root, "${record.origin.sessionId}.pty").delete())
                    }
                    container.profileStore.switchTo(previous)
                }
            }
        }

    private fun seedHistory(
        root: File,
        store: PtySessionStore,
        bindings: File,
    ): List<Triple<ExecutionOwnership.Owner, File, PtySessionRecord>> =
        (0..RuntimeExecutionCapacity.MAX_TERMINALS).map {
            val id = "fixture-${UUID.randomUUID()}"
            val owner = ExecutionOwnership.Owner(id, "fixture")
            val binding = File(bindings, "$id.owner")
            check(ExecutionOwnershipStore(binding).compareAndSet(null, owner))
            val initial =
                PtySessionRecord(
                    PtySessionOrigin(
                        id,
                        "fixture",
                        id,
                        root.path,
                        "fixture",
                        null,
                        1,
                        1,
                        60_001,
                    ),
                )
            val record = initial.runtimeLost()
            // Fixture-only seed: no process was launched for these exact identities.
            check(store.compareAndSet(null, initial))
            check(store.compareAndSet(initial, record))
            Triple(owner, binding, record)
        }

    private suspend fun verifyInteractiveShell(
        terminal: com.helix.app.terminal.ManualTerminal,
        id: String,
    ) {
        val connection = terminal.attach(id)
        try {
            assertTrue(
                connection
                    .read(null)
                    .bytes
                    .toString(Charsets.UTF_8)
                    .contains("helix> "),
            )
            assertEquals("ACCEPTED", connection.write("printf 'FRESH_%s\\n' SHELL_OK\n".toByteArray()))
            withTimeout(10_000) {
                var cursor: String? = null
                var text = ""
                while (!text.contains("FRESH_SHELL_OK")) {
                    val page = connection.read(cursor)
                    text += page.bytes.toString(Charsets.UTF_8)
                    cursor = page.cursor
                    delay(25)
                }
            }
        } finally {
            connection.detach()
        }
    }
}
