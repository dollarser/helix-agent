package com.helix.spike.termlib

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import org.connectbot.terminal.TerminalEmulatorFactory

class RendererProbeTest : InstrumentationTestCase() {
    fun testNativeParserSplitUtf8AndOscClipboardIgnored() {
        val activity =
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, RendererProbeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        try {
            await { activity.hasWindowFocus() }
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            instrumentation.runOnMainSync {
                clipboard.setPrimaryClip(ClipData.newPlainText("probe", "unchanged-terminal-probe"))
            }
            val terminal = TerminalEmulatorFactory.create()
            val sequence =
                "\u001b]133;A\u0007probe> \u001b]133;B\u0007echo\r\n" +
                    "\u001b]133;C\u0007结果中文42\r\n\u001b]133;D;0\u0007"
            sequence.toByteArray(Charsets.UTF_8).forEach { terminal.writeInput(byteArrayOf(it)) }
            await { terminal.getLastCommandOutput()?.contains("结果中文42") == true }
            terminal.writeInput("\u001b]52;c;Y2hhbmdlZA==\u0007".toByteArray())
            terminal.writeInput("\u001b]52;c;?\u0007".toByteArray())
            // Callback posts are enqueued by writeInput; drain the actual main queue.
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(
                    "unchanged-terminal-probe",
                    clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.text
                        ?.toString(),
                )
            }
            terminal.resize(37, 101)
            assertEquals(37, terminal.dimensions.rows)
            assertEquals(101, terminal.dimensions.columns)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
        // Candidate has no public emulator close API. Owned process teardown is NOT
        // a substitute for production session disposal; track this adoption blocker.
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!condition()) {
            assertTrue("Timed out awaiting terminal/focus state", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(20)
        }
    }
}
